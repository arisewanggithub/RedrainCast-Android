package dev.screencopy.sender

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaybackMuteTest {
    @Test fun recoveryAndUnusedInstanceCloseCannotRestoreAnActiveOwnersMute() {
        val fixture = Fixture()
        val owner = fixture.instance()
        val recovery = fixture.instance()
        try {
            owner.mute()
            recovery.restore()
            recovery.close()
            assertTrue("Activity recovery must leave the live cast muted", fixture.muted.get())
            assertTrue(fixture.marker())
            owner.mute()
            Mockito.verify(fixture.audio, Mockito.times(1))
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            owner.close()
            assertFalse(fixture.muted.get())
            assertFalse(fixture.marker())
        } finally {
            recovery.close()
            owner.close()
        }
    }

    @Test fun aSecondProjectionCannotTakeTheActiveMuteOwnership() {
        val fixture = Fixture()
        val owner = fixture.instance()
        val other = fixture.instance()
        try {
            owner.mute()
            assertThrows(IOException::class.java) { other.mute() }
            other.close()
            assertTrue(fixture.muted.get())
            assertTrue(fixture.marker())
        } finally {
            other.close()
            owner.close()
        }
    }

    @Test fun abandonedMarkerIsRecoveredWithoutChangingTheVolumeIndex() {
        val fixture = Fixture(initiallyMuted = true)
        fixture.preferences.edit().putBoolean("restore-media-mute", true).commit()
        fixture.instance().use { recovery -> recovery.restore() }
        assertFalse(fixture.muted.get())
        assertFalse(fixture.marker())
        Mockito.verify(fixture.audio, Mockito.never())
            .setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
    }

    @Test fun existingUserMuteAndZeroVolumeArePreserved() {
        for ((muted, volume) in listOf(true to 7, false to 0)) {
            val fixture = Fixture(muted, volume)
            fixture.instance().use { owner ->
                owner.mute()
                fixture.instance().use { recovery -> recovery.restore() }
                assertEquals(muted, fixture.muted.get())
                assertFalse(fixture.marker())
            }
            assertEquals(muted, fixture.muted.get())
            Mockito.verify(fixture.audio, Mockito.never())
                .adjustStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
        }
    }

    @Test fun failedOwnerCleanupLeavesTheMarkerAvailableToLaterRecovery() {
        val fixture = Fixture()
        val owner = fixture.instance()
        try {
            owner.mute()
            fixture.denyNextUnmute = true
            assertThrows(IOException::class.java) { owner.close() }
            assertTrue(fixture.marker())
            fixture.instance().use { recovery -> recovery.restore() }
            assertFalse(fixture.muted.get())
            assertFalse(fixture.marker())
        } finally { owner.close() }
    }

    private class Fixture(initiallyMuted: Boolean = false, volume: Int = 7) {
        val audio = Mockito.mock(AudioManager::class.java)
        val muted = AtomicBoolean(initiallyMuted)
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("mute-test", Context.MODE_PRIVATE)
        var denyNextUnmute = false

        init {
            preferences.edit().clear().commit()
            Mockito.`when`(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenAnswer { muted.get() }
            Mockito.`when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(volume)
            Mockito.doAnswer { invocation ->
                when (invocation.getArgument<Int>(1)) {
                    AudioManager.ADJUST_MUTE -> muted.set(true)
                    AudioManager.ADJUST_UNMUTE -> {
                        if (denyNextUnmute) {
                            denyNextUnmute = false
                            throw SecurityException("Test restoration denial")
                        }
                        muted.set(false)
                    }
                    else -> throw IllegalArgumentException("Unexpected volume adjustment")
                }
                null
            }.`when`(audio).adjustStreamVolume(Mockito.eq(AudioManager.STREAM_MUSIC), Mockito.anyInt(), Mockito.anyInt())
        }

        fun instance() = PlaybackMute(audio, preferences)
        fun marker() = preferences.getBoolean("restore-media-mute", false)
    }
}
