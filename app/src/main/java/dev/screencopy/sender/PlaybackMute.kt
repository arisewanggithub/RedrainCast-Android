package dev.screencopy.sender

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import java.io.Closeable
import java.io.IOException

/** One projection owns the mute, including size-switch restarts. Never rewrites volume indexes. */
class PlaybackMute(
    private val audio: AudioManager,
    private val preferences: SharedPreferences
) : Closeable {
    private var engaged = false
    private var closed = false

    fun mute() = synchronized(ownershipLock) {
        if (closed) throw IOException("AUDIO_FAILED: local audio ownership has ended")
        if (engaged) return@synchronized
        if (owner != null && owner !== this)
            throw IOException("AUDIO_FAILED: local media mute is owned by another projection")
        restoreRecordedState()
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            owner = this
            engaged = true
            return@synchronized
        }
        if (audio.isVolumeFixed) throw IOException("AUDIO_FAILED: Android does not allow this app to mute local media on this device.")
        if (!preferences.edit().putBoolean(RESTORE, true).commit())
            throw IOException("AUDIO_FAILED: cannot record local audio restoration state; phone volume was not changed")
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            if (!audio.isStreamMute(AudioManager.STREAM_MUSIC))
                throw IOException("AUDIO_FAILED: Android did not mute local media. Lower the phone's media volume manually.")
            owner = this
            engaged = true
        } catch (e: SecurityException) {
            throw IOException("AUDIO_FAILED: Android denied local media mute", e)
        }
    }

    /** Recover an abandoned process only when no live projection owns the mute. */
    fun restore() = synchronized(ownershipLock) {
        if (owner == null) restoreRecordedState()
    }

    private fun restoreRecordedState() {
        if (!preferences.getBoolean(RESTORE, false)) return
        try {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                if (audio.isStreamMute(AudioManager.STREAM_MUSIC))
                    throw IOException("Cannot restore local media audio. Unmute the phone manually and reopen the app.")
            }
        } catch (e: SecurityException) { throw IOException("Android denied local media audio restoration", e) }
        if (!preferences.edit().remove(RESTORE).commit())
            throw IOException("Cannot clear local audio restoration state")
    }

    override fun close() = synchronized(ownershipLock) {
        if (closed) return@synchronized
        try {
            if (owner == null || owner === this) restoreRecordedState()
            closed = true
        } finally {
            if (owner === this) owner = null
            engaged = false
        }
    }

    companion object {
        private val ownershipLock = Any()
        private var owner: PlaybackMute? = null
        private const val RESTORE = "restore-media-mute"
        fun create(context: Context) = PlaybackMute(context.getSystemService(AudioManager::class.java),
            context.getSharedPreferences("playback-mute", Context.MODE_PRIVATE))
        fun applies(settings: Settings) = settings.audio && settings.audioSource in setOf("playback", "output")
    }
}
