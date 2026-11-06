package dev.screencopy.sender

/** The size button authorizes exactly one restart, conditional on acknowledged owned cleanup. */
internal class SizeSwitchAuthorization {
    @Volatile var requested = false
        private set
    @Volatile var acknowledged = false
        private set
    @Volatile var receiverClosed = false
        private set
    @Synchronized fun request(codec: String, playing: Boolean, failed: Boolean): Boolean {
        if (requested || codec !in setOf("h264", "h265", "av1") || !playing || failed) return false
        requested = true
        return true
    }
    @Synchronized fun cancel() { requested = false }
    @Synchronized fun acknowledge() { if (requested) acknowledged = true }
    @Synchronized fun peerClosed() { if (requested && acknowledged) receiverClosed = true }
    fun permitsRestart(cleanupSucceeded: Boolean) = requested && acknowledged && receiverClosed && cleanupSucceeded
}
