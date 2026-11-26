package dev.redrain.cast.diagnostics

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.protocolRequire
import org.screencopy.receiver.protocol.string
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private app files only; callers never supply paths or filenames. */
internal class IncidentStore(
    context: Context,
    private val readBytes: (File) -> ByteArray = { it.readBytes() },
    private val deleteFile: (File) -> Boolean = { it.delete() },
    private val executor: Executor = io,
    private val afterStartWrite: () -> Unit = {},
    private val atomicFile: (File) -> AtomicFile = ::AtomicFile,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    data class Report(val name: String, val timestampMs: Long)
    sealed interface ReadResult {
        data class Success(val content: String) : ReadResult
        object Missing : ReadResult
        data class Failure(val error: Exception) : ReadResult
    }
    companion object {
        private const val MAX_FILES = 8
        private const val MAX_BYTES = TelemetryReport.MAX_BYTES
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val namePattern = Regex("incident-[0-9a-f]{32}\\.json")
        private val stagingPattern = Regex("incident-[0-9a-f]{32}\\.json\\.(new|bak)")
        private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            { r -> Thread(r, "incident-storage").apply { isDaemon = true } })
        private val storageLocks = ConcurrentHashMap<File, Any>()
    }

    private fun submit(
        onRejected: ((RejectedExecutionException) -> Unit)? = null,
        action: () -> Unit
    ): Boolean = try {
        executor.execute {
            try { action() } catch (e: Exception) {
                Log.w("PressureIncident", "storage_failure=${e.javaClass.simpleName}")
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        Log.w("PressureIncident", "storage_failure=queue_full", e)
        onRejected?.invoke(e)
        false
    }

    private val directory = File(context.applicationContext.filesDir, "pressure-incidents")
    private val storageLock = storageLocks.computeIfAbsent(directory.canonicalFile) { Any() }
    fun write(localKey: String, value: JSONObject) {
        require(localKey.matches(Regex("[0-9a-f]{32}")))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 64 * 1024) {
            Log.w("PressureIncident", "storage_failure=oversized")
            return
        }
        submit { synchronized(storageLock) { persist(localKey, bytes) } }
    }

    /** Called on the serial telemetry report worker, never on a media or control thread. */
    fun writeTelemetry(localKey: String, bytes: ByteArray) {
        require(localKey.matches(Regex("[0-9a-f]{32}")))
        synchronized(storageLock) {
            require(bytes.size <= MAX_BYTES && validBundle(bytes))
            persist(localKey, bytes)
        }
    }

    private fun persist(localKey: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        prune()
        val file = File(directory, "incident-$localKey.json")
        val atomic = atomicFile(file)
        val stream = atomic.startWrite()
        try {
            afterStartWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        check(file.setLastModified(clockMs())) { "Incident timestamp could not be updated" }
        prune(protected = file)
    }

    fun list(callback: (List<Report>?) -> Unit) {
        if (!submit {
                val value = synchronized(storageLock) {
                    try {
                        prune()
                        files().sortedByDescending { it.lastModified() }.map { Report(it.name, it.lastModified()) }
                    } catch (e: Exception) {
                        Log.w("PressureIncident", "storage_failure=${e.javaClass.simpleName}")
                        null
                    }
                }
                callback(value)
            }) callback(null)
    }

    fun read(name: String, callback: (ReadResult) -> Unit) {
        require(namePattern.matches(name))
        submit(onRejected = { callback(ReadResult.Failure(it)) }) {
            val value = synchronized(storageLock) {
                try {
                    prune()
                    val file = File(directory, name)
                    if (file.isFile && file.length() <= MAX_BYTES)
                        ReadResult.Success(readBytes(file).toString(Charsets.UTF_8))
                    else ReadResult.Missing
                } catch (e: IOException) {
                    readFailure(e)
                } catch (e: IllegalStateException) {
                    readFailure(e)
                } catch (e: SecurityException) {
                    readFailure(e)
                }
            }
            callback(value)
        }
    }

    private fun readFailure(error: Exception): ReadResult.Failure {
        Log.w("PressureIncident", "storage_failure=${error.javaClass.simpleName}", error)
        return ReadResult.Failure(error)
    }

    fun clear(callback: (Boolean) -> Unit) {
        if (!submit {
                val success = synchronized(storageLock) {
                    try {
                        files().plus(staging()).forEach { check(it.delete() || !it.exists()) }
                        true
                    } catch (e: Exception) {
                        Log.w("PressureIncident", "storage_failure=${e.javaClass.simpleName}")
                        false
                    }
                }
                callback(success)
            }) callback(false)
    }

    private fun files(): List<File> = listOwnedFiles(namePattern)

    private fun staging(): List<File> = listOwnedFiles(stagingPattern)

    private fun listOwnedFiles(pattern: Regex): List<File> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Incident storage path is not a directory" }
        val entries = directory.listFiles()
            ?: throw java.io.IOException("Incident storage directory could not be enumerated")
        return entries.filter { it.isFile && pattern.matches(it.name) }
    }

    private fun validBundle(bytes: ByteArray): Boolean = try {
        val bundle = StrictJson.decode(bytes, trackSizes = false)
        if (bundle.opt("version") == 2) {
            TelemetryReport.validate(bundle)
        } else {
            validLegacyBundle(bundle, bytes.size)
        }
    } catch (_: ProtocolException) {
        false
    }

    private fun validLegacyBundle(bundle: JSONObject, size: Int): Boolean {
        protocolRequire(size <= 64 * 1024, "Legacy incident report exceeds limit")
        protocolRequire(bundle.opt("version") == 1, "Invalid incident report version")
        val incident = bundle.string("incident")
        protocolRequire(IncidentProtocol.validId(incident), "Invalid incident report ID")
        val local = bundle.optJSONObject("local")
        val role = local?.string("role")
        protocolRequire(role == "sender" || role == "receiver", "Invalid incident report role")
        IncidentProtocol.parse(IncidentProtocol.snapshot(incident, checkNotNull(local)), checkNotNull(role), evidence = true)
        val peer = bundle.opt("peer")
        protocolRequire(peer == JSONObject.NULL || peer is JSONObject, "Invalid incident report peer")
        if (peer is JSONObject) IncidentProtocol.parse(
            IncidentProtocol.snapshot(incident, peer), if (role == "sender") "receiver" else "sender", evidence = true)
        val peerState = bundle.string("peerState")
        protocolRequire(peerState in setOf("unsupported", "awaiting_snapshot", "partial",
            "available", "session_ended_before_peer_snapshot", "ambiguous", "uncorrelated",
            "provenance_unknown", "not_collected_on_receiver"), "Invalid incident report peer state")
        protocolRequire(peerState != "available" || peer is JSONObject,
            "Available incident report peer is missing")
        // Legacy and teardown-fallback bundles predate peerProvenance; fallback counts are unknown.
        if (bundle.has("peerProvenance")) protocolRequire(bundle.opt("peerProvenance") == JSONObject.NULL ||
            bundle.opt("peerProvenance") in setOf("episode", "uncorrelated", "unknown"),
            "Invalid incident report provenance")
        for (field in listOf("coalesced", "suppressedPressure", "inboundDropped", "sendDropped")) {
            val count = bundle.opt(field)
            protocolRequire(count == JSONObject.NULL || count is String &&
                count.matches(Regex("0|[1-9][0-9]{0,18}")) && count.toLongOrNull() != null,
                "Invalid incident report count")
        }
        bundle.string("clockCorrelation")
        if (bundle.has("captureOrigin") || bundle.has("correlation")) protocolRequire(
            bundle.opt("captureOrigin") == "remote_fetch_after_teardown" &&
                bundle.opt("correlation") == "uncorrelated_remote_fetch",
            "Invalid incident report fallback origin")
        return true
    }

    private fun prune(protected: File? = null) {
        val staged = staging()
        staged.filter { it.name.endsWith(".new") }.forEach {
            check(it.delete() || !it.exists()) { "Incident staging cleanup failed" }
            Log.w("PressureIncident", "storage_incomplete_write_discarded")
        }
        staged.filter { it.name.endsWith(".bak") }.forEach { backup ->
            val base = File(directory, backup.name.removeSuffix(".bak"))
            // Validate before replacing a usable base: a corrupt backup is not a rollback.
            if (backup.length() <= MAX_BYTES && validBundle(readBytes(backup))) {
                Files.move(backup.toPath(), base.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                check(deleteFile(backup) || !backup.exists()) { "Incident backup cleanup failed" }
                Log.w("PressureIncident", "storage_corrupt_backup_discarded")
            }
        }
        val now = clockMs()
        val valid = files().filter { file ->
            if (file.length() > MAX_BYTES || file.lastModified() <= now - MAX_AGE_MS) {
                check(file.delete() || !file.exists())
                false
            } else if (!validBundle(readBytes(file))) {
                check(deleteFile(file) || !file.exists()) {
                    Log.w("PressureIncident", "storage_failure=corrupt_report_cleanup_failed")
                    "Incident corrupt report cleanup failed"
                }
                Log.w("PressureIncident", "storage_corrupt_report_discarded")
                false
            } else {
                if (file.lastModified() > now)
                    check(file.setLastModified(now)) { "Incident timestamp could not be corrected" }
                true
            }
        }
        valid.sortedWith(compareByDescending<File> { it == protected }
            .thenByDescending { it.lastModified() }.thenBy { it.name })
            .drop(MAX_FILES).forEach { check(it.delete() || !it.exists()) }
    }
}
