package dev.redrain.cast

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

internal data class ReleaseUpdate(val version: String, val page: String)

internal interface UpdateSource {
    fun check(installedVersion: String): ReleaseUpdate?
    fun cancel()
}

/** Anonymous metadata only. The browser owns any user-requested APK download. */
internal class GitHubUpdateSource(
    private val open: () -> HttpURLConnection = {
        URL(ENDPOINT).openConnection() as? HttpURLConnection
            ?: throw IOException("Invalid release metadata connection")
    }
) : UpdateSource {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    override fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    override fun check(installedVersion: String): ReleaseUpdate? {
        check(!cancelled) { "Update check cancelled" }
        if (System.currentTimeMillis() < retryAfter) throw IOException("GitHub update checks are cooling down")
        val current = open()
        connection = current
        try {
            check(!cancelled) { "Update check cancelled" }
            current.instanceFollowRedirects = false
            current.connectTimeout = 5000
            current.readTimeout = 5000
            current.setRequestProperty("Accept", "application/vnd.github+json")
            current.setRequestProperty("User-Agent", "RedrainCast-Update-Check/1")
            current.setRequestProperty("Accept-Encoding", "identity")
            val status = current.responseCode
            if (status == 403 || status == 429) {
                val seconds = current.getHeaderField("Retry-After")?.toLongOrNull()
                    ?: current.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                        ?.minus(System.currentTimeMillis() / 1000) ?: 3600L
                retryAfter = System.currentTimeMillis() + seconds.coerceIn(60, 86400) * 1000
                throw IOException("GitHub update request denied or rate limited (HTTP $status)")
            }
            if (status != 200) throw IOException("Public release metadata unavailable (HTTP $status)")
            val expected = current.contentLengthLong
            require(expected <= METADATA_LIMIT) { "Release metadata too large" }
            val deadline = System.nanoTime() + 15_000_000_000L
            val output = ByteArrayOutputStream()
            current.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    check(!cancelled && System.nanoTime() < deadline) { "Update check cancelled or timed out" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= METADATA_LIMIT) { "Release metadata too large" }
                    output.write(buffer, 0, count)
                }
            }
            check(!cancelled) { "Update check cancelled" }
            require(expected < 0 || output.size().toLong() == expected) { "Incomplete release metadata" }
            return parse(output.toString("UTF-8"), installedVersion)
        } finally {
            current.disconnect()
            connection = null
        }
    }

    companion object {
        const val ENDPOINT = "https://api.github.com/repos/arisewanggithub/RedrainCast-Releases/releases/latest"
        const val METADATA_LIMIT = 256 * 1024
        private const val RELEASES = "https://github.com/arisewanggithub/RedrainCast-Releases/releases"
        private val versionPattern = Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.([1-9][0-9]*))?")
        private data class Version(val parts: List<BigInteger>, val prerelease: Boolean)
        @Volatile private var retryAfter = 0L

        private fun version(value: String): Version {
            require(value.length <= 79) { "Release version too long" }
            val match = requireNotNull(versionPattern.matchEntire(value)) { "Invalid release version" }
            return Version((1..3).map { BigInteger(match.groupValues[it]) }, match.groupValues[4].isNotEmpty())
        }

        private fun stableIsNewer(available: Version, installed: Version): Boolean {
            require(!available.prerelease) { "Stable release has a prerelease version" }
            for (index in 0..2) {
                val compared = available.parts[index].compareTo(installed.parts[index])
                if (compared != 0) return compared > 0
            }
            return installed.prerelease
        }

        private fun boolean(json: JSONObject, field: String): Boolean {
            val value = json.get(field)
            require(value is Boolean) { "Invalid release flag" }
            return value
        }

        fun parse(text: String, installedVersion: String): ReleaseUpdate? {
            require(text.toByteArray(Charsets.UTF_8).size <= METADATA_LIMIT) { "Release metadata too large" }
            val release = JSONObject(text)
            if (boolean(release, "draft") || boolean(release, "prerelease")) return null
            val installed = version(installedVersion)
            val tag = release.getString("tag_name")
            require(tag.startsWith("v")) { "Invalid release tag" }
            val name = tag.substring(1)
            val available = version(name)
            if (!stableIsNewer(available, installed)) return null
            val page = "$RELEASES/tag/$tag"
            require(release.getString("html_url") == page) { "Release page is outside the configured repository" }
            val assets = release.getJSONArray("assets")
            require(assets.length() <= 64) { "Too many release assets" }
            val assetName = "RedrainCast-$name.apk"
            var found = false
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.getString("name") != assetName) continue
                require(!found && asset.opt("state") == "uploaded" &&
                    asset.opt("size").let { it is Number && it.toLong() > 0 && it.toDouble() == it.toLong().toDouble() }) {
                    "Missing or ambiguous completed release APK"
                }
                require(asset.getString("browser_download_url") == "$RELEASES/download/$tag/$assetName") {
                    "Release APK is outside the configured repository"
                }
                found = true
            }
            require(found) { "No completed RedrainCast APK in release" }
            return ReleaseUpdate(name, page)
        }
    }
}
