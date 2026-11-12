package org.screencopy.receiver.media

import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AudioOutputPerformanceTest {
    @Test fun observesActualModeWithoutRequestingADifferentOutputPathOrChangingPcmPolicy() {
        for (mode in listOf(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, AudioTrack.PERFORMANCE_MODE_NONE)) {
            val track = Mockito.mock(AudioTrack::class.java)
            Mockito.`when`(track.state).thenReturn(AudioTrack.STATE_INITIALIZED)
            Mockito.`when`(track.sampleRate).thenReturn(48000)
            Mockito.`when`(track.channelCount).thenReturn(2)
            Mockito.`when`(track.audioFormat).thenReturn(PlatformAudioFormat.ENCODING_PCM_16BIT)
            Mockito.`when`(track.bufferSizeInFrames).thenReturn(240)
            Mockito.`when`(track.bufferCapacityInFrames).thenReturn(240)
            Mockito.`when`(track.playState).thenReturn(AudioTrack.PLAYSTATE_STOPPED)
            Mockito.`when`(track.performanceMode).thenReturn(mode)
            Mockito.`when`(track.playbackParams).thenReturn(PlaybackParams().setSpeed(1f).setPitch(1f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL))
            Mockito.mockStatic(AudioTrack::class.java).use { platform ->
                platform.`when`<Int> {
                    AudioTrack.getMinBufferSize(48000, PlatformAudioFormat.CHANNEL_OUT_STEREO,
                        PlatformAudioFormat.ENCODING_PCM_16BIT)
                }.thenReturn(512)
                Mockito.mockConstruction(AudioTrack.Builder::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF),
                    { builder, _ -> Mockito.`when`(builder.build()).thenReturn(track) }).use { builders ->
                    PlatformAudioOutput(AudioFormat("pcm", 48000, 2, byteArrayOf()),
                        { code, detail -> fail("$code: $detail") }).use { output ->
                        assertEquals(240, output.bufferFrames)
                        assertEquals(1f, output.preparedSpeed, 0f)
                        assertFalse(output.isPlaying)
                    }
                    val builder = builders.constructed().single()
                    val selectedFormat = ArgumentCaptor.forClass(PlatformAudioFormat::class.java)
                    Mockito.verify(builder).setAudioFormat(selectedFormat.capture())
                    assertEquals(48000, selectedFormat.value.sampleRate)
                    assertEquals(PlatformAudioFormat.CHANNEL_OUT_STEREO, selectedFormat.value.channelMask)
                    assertEquals(PlatformAudioFormat.ENCODING_PCM_16BIT, selectedFormat.value.encoding)
                    Mockito.verify(builder).setTransferMode(AudioTrack.MODE_STREAM)
                    Mockito.verify(builder).setBufferSizeInBytes(960)
                    Mockito.verify(builder, Mockito.never()).setPerformanceMode(Mockito.anyInt())
                    Mockito.verify(track).release()
                    Mockito.verify(track, Mockito.never()).play()
                    Mockito.verify(track, Mockito.never()).setPlaybackRate(Mockito.anyInt())
                }
            }
            assertTrue(ShadowLog.getLogsForTag("ScreenCopyReceiver").any {
                it.msg == "event=AUDIO_OUTPUT_PERFORMANCE policy=unchanged actual_mode=$mode" +
                    " sample_rate=48000 buffer_frames=240 capacity_frames=240"
            })
        }
    }
}
