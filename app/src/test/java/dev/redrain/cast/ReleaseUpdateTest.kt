package dev.redrain.cast

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ReleaseUpdateTest {
    @Test fun installedPrereleaseCanUpgradeToTheFinalStableVersionWithTheSameBase() {
        for (installed in listOf("0.5.0-alpha.1", "0.5.0-beta.2", "0.5.0-rc.1", "0.5.0-rc.99999999999999999999")) {
            assertEquals("0.5.0", parse(installed, metadata("0.5.0"))?.version)
        }
    }

    @Test fun installedPrereleaseNeverDowngradesToAnOlderStableBase() {
        assertNull(parse("0.5.0-rc.1", metadata("0.4.99")))
        assertEquals("0.5.1", parse("0.5.0-rc.1", metadata("0.5.1"))?.version)
        assertNull(parse("0.5.0", metadata("0.5.0")))
    }

    @Test fun rcTagsNeverBecomeStableUpdatesEvenWhenApiFlagIsWrong() {
        assertNull(parse("0.4.10", metadata("0.5.0-rc.2", true)))
        assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", metadata("0.5.0-rc.2")) }
    }

    @Test fun installedPrereleaseVersionsRequireCanonicalChannelAndPositiveCounter() {
        for (installed in listOf("0.5.0_pre-release", "0.5.0-rc", "0.5.0-rc.0",
            "0.5.0-rc.01", "0.5.0-RC.1", "0.5.0+build", "00.5.0-rc.1")) {
            assertThrows(IllegalArgumentException::class.java) { parse(installed, metadata("0.5.1")) }
        }
    }

    @After fun resetCooldown() {
        GitHubUpdateSource::class.java.getDeclaredField("retryAfter").apply { isAccessible = true }.setLong(null, 0)
    }

    private fun metadata(version: String = "0.5.0", preview: Boolean = false): JSONObject {
        val name = "RedrainCast-$version.apk"
        return JSONObject().put("draft", false).put("prerelease", preview).put("tag_name", "v$version")
            .put("html_url", "$RELEASES/tag/v$version")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("state", "uploaded")
                .put("size", 1000).put("browser_download_url", "$RELEASES/download/v$version/$name")))
    }

    private fun parse(installed: String = "0.4.10", release: JSONObject = metadata()) =
        GitHubUpdateSource.parse(release.toString(), installed)

    @Test fun numericVersionsDoNotCompareLexicallyAndStablePageIsValidated() {
        assertEquals("0.10.0", parse("0.9.9", metadata("0.10.0"))?.version)
        val stable = checkNotNull(parse("0.4.5", metadata("0.4.6")))
        assertEquals("$RELEASES/tag/v0.4.6", stable.page)
    }

    @Test fun publishedPreviewDoesNotPromptEvenWhenItsVersionIsNewer() {
        assertNull(parse("0.4.5", metadata("0.4.6", true)))
        assertNull(parse("0.4.10", metadata("0.5.0", true)))
    }

    @Test fun sameOlderAndDraftReleasesNeverPrompt() {
        for (release in listOf(metadata("0.4.10"), metadata("0.4.6"), metadata("0.5.0").put("draft", true)))
            assertNull(parse("0.4.10", release))
    }

    @Test fun hugeVersionComponentsCannotOverflowAndMalformedVersionsFailExplicitly() {
        assertNotNull(parse("0.4.10", metadata("999999999999999999999.0.0")))
        for (tag in listOf("0.5.0", "v../wrong", "v0.5", "v0.5.0?redirect=1", "v" + "1".repeat(80) + ".0.0")) {
            assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", metadata().put("tag_name", tag)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse("not-a-version", metadata()) }
    }

    @Test fun releaseAndApkLinksMustExactlyMatchThePublicDownloadsRepository() {
        for (page in listOf("https://example.com", "$RELEASES/tag/v0.5.0?x=1",
            "http://github.com/arisewanggithub/RedrainCast-Releases/releases/tag/v0.5.0")) {
            assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", metadata().put("html_url", page)) }
        }
        for (url in listOf("https://example.com/RedrainCast-0.5.0.apk",
            "$RELEASES/download/v0.5.0/RedrainCast-0.5.0.apk?redirect=1",
            "https://github.com/other/repo/releases/download/v0.5.0/RedrainCast-0.5.0.apk")) {
            val release = metadata()
            release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", release) }
        }
    }

    @Test fun missingIncompleteAndDuplicateApksCannotProduceAnUpdate() {
        assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", metadata().put("assets", JSONArray())) }
        val duplicate = metadata()
        duplicate.getJSONArray("assets").put(metadata().getJSONArray("assets").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", duplicate) }
        for (size in listOf<Any>(0, -1, 0.5, "1000", JSONObject.NULL)) {
            val release = metadata()
            release.getJSONArray("assets").getJSONObject(0).put("size", size)
            assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", release) }
        }
        val incomplete = metadata()
        incomplete.getJSONArray("assets").getJSONObject(0).put("state", "new")
        assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", incomplete) }
    }

    @Test fun malformedFlagsAndUnboundedMetadataAreRejected() {
        for (field in listOf("draft", "prerelease")) {
            assertThrows(IllegalArgumentException::class.java) { parse("0.4.10", metadata().put(field, "false")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubUpdateSource.parse(" ".repeat(GitHubUpdateSource.METADATA_LIMIT + 1), "0.4.10")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("0.4.10", metadata().put("assets", JSONArray(List(65) {
                JSONObject().put("name", "extra-$it")
            })))
        }
    }

    @Test fun networkRequestIsBoundedAnonymousAndAlwaysDisconnected() {
        val connection = Connection(metadata().toString().toByteArray())
        assertNotNull(GitHubUpdateSource { connection }.check("0.4.10"))
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5000, connection.connectTimeout)
        assertEquals(5000, connection.readTimeout)
        assertEquals("RedrainCast-Update-Check/1", connection.getRequestProperty("User-Agent"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(GitHubUpdateSource.ENDPOINT.contains("RedrainCast-Releases/releases/"))
        assertTrue(GitHubUpdateSource.ENDPOINT.endsWith("/latest"))
    }

    @Test fun unauthorizedMissingRedirectAndServerFailuresDoNotBecomeSuccessfulChecks() {
        for (status in listOf(401, 404, 302, 500)) {
            val connection = Connection(ByteArray(0), status)
            assertThrows(IOException::class.java) { GitHubUpdateSource { connection }.check("0.4.10") }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun declaredChunkedAndIncompleteBodiesRemainBounded() {
        for (length in listOf((GitHubUpdateSource.METADATA_LIMIT + 1).toLong(), -1L)) {
            val connection = Connection(ByteArray(GitHubUpdateSource.METADATA_LIMIT + 1), length = length)
            assertThrows(IllegalArgumentException::class.java) { GitHubUpdateSource { connection }.check("0.4.10") }
            assertTrue(connection.disconnected)
        }
        val body = metadata().toString().toByteArray()
        val truncated = Connection(body, length = body.size + 1L)
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdateSource { truncated }.check("0.4.10") }
        assertTrue(truncated.disconnected)
    }

    @Test fun cancellationBeforeConnectionDoesNotOpenAnySocket() {
        val source = GitHubUpdateSource { error("Cancelled check must not open") }
        source.cancel()
        assertThrows(IllegalStateException::class.java) { source.check("0.4.10") }
    }

    @Test fun rateLimitCooldownPreventsRepeatedLaunchRequests() {
        val limited = Connection(ByteArray(0), 429)
        assertThrows(IOException::class.java) { GitHubUpdateSource { limited }.check("0.4.10") }
        assertTrue(limited.disconnected)
        assertThrows(IOException::class.java) {
            GitHubUpdateSource { error("Cooldown must prevent connection") }.check("0.4.10")
        }
    }

    private class Connection(private val body: ByteArray, private val status: Int = 200,
        private val length: Long = body.size.toLong()) : HttpURLConnection(URL(GitHubUpdateSource.ENDPOINT)) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = length
    }

    companion object { private const val RELEASES = "https://github.com/arisewanggithub/RedrainCast-Releases/releases" }
}
