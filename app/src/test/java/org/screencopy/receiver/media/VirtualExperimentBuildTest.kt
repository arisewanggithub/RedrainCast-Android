package org.screencopy.receiver.media

import dev.redrain.cast.BuildConfig
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import dev.redrain.cast.diagnostics.DiagnosticError
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import dev.redrain.cast.diagnostics.SessionTelemetryProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.Wire
import org.screencopy.receiver.protocol.ReceiverOptionCapabilities
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VirtualExperimentBuildTest {
    @Test fun ordinaryDebugUsesNormalClockByDefault() {
        assumeTrue(BuildConfig.DEBUG && !BuildConfig.HYBRID_AUDIO_EXPERIMENT)
        assertFalse(PlaybackClock().hybridVideoSync)
    }

    @Test fun hybridUsesPhysicalOutput() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.HYBRID_AUDIO_EXPERIMENT)
        val track = Mockito.mock(AudioTrack::class.java)
        Mockito.`when`(track.state).thenReturn(AudioTrack.STATE_INITIALIZED)
        Mockito.`when`(track.sampleRate).thenReturn(48000)
        Mockito.`when`(track.channelCount).thenReturn(2)
        Mockito.`when`(track.audioFormat).thenReturn(PlatformAudioFormat.ENCODING_PCM_16BIT)
        Mockito.`when`(track.bufferSizeInFrames).thenReturn(240)
        Mockito.`when`(track.bufferCapacityInFrames).thenReturn(240)
        Mockito.`when`(track.playState).thenReturn(AudioTrack.PLAYSTATE_STOPPED)
        Mockito.`when`(track.playbackParams).thenReturn(PlaybackParams().setSpeed(1f).setPitch(1f)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL))
        Mockito.mockStatic(AudioTrack::class.java).use { platform ->
            platform.`when`<Int> {
                AudioTrack.getMinBufferSize(48000, PlatformAudioFormat.CHANNEL_OUT_STEREO,
                    PlatformAudioFormat.ENCODING_PCM_16BIT)
            }.thenReturn(512)
            Mockito.mockConstruction(AudioTrack.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF),
                { builder, _ -> Mockito.`when`(builder.build()).thenReturn(track) }).use {
                val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), PlaybackClock(),
                    { _, _ -> }, resamplerFactory = { _, channels -> IdentityPcmResampler(channels) }, prepareThread = {})
                try {
                    val field = AudioPlayer::class.java.getDeclaredField("output").apply { isAccessible = true }
                    assertTrue(field.get(player) is PlatformAudioOutput)
                    assertFalse(player.decoderDescription.contains("virtual discard"))
                } finally {
                    player.close()
                }
                Mockito.verify(track).release()
            }
        }
    }

    @Test fun experimentUsesVirtualFactoryAndRejectsReceiverCapture() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.VIRTUAL_AUDIO_EXPERIMENT)
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val player = AudioPlayer(format, PlaybackClock(), { _, _ -> },
            resamplerFactory = { _, channels -> IdentityPcmResampler(channels) }, prepareThread = {})
        try {
            val field = AudioPlayer::class.java.getDeclaredField("output").apply { isAccessible = true }
            assertTrue(field.get(player) is VirtualDiscardAudioOutput)
            assertTrue(player.decoderDescription.contains("virtual discard"))
        } finally {
            player.close()
        }
        val recorder = DiagnosticRecorder()
        val error = assertThrows(DiagnosticError::class.java) {
            recorder.start("receiver", null, setOf("session"), 10000, 1000)
        }
        assertEquals("UNSUPPORTED_FLAG", error.code)
        val admittedError = assertThrows(DiagnosticError::class.java) {
            recorder.startAdmittedReceiver(1, setOf("session"), System.nanoTime() + 1_000_000_000,
                1000, AtomicBoolean(true))
        }
        assertEquals("UNSUPPORTED_FLAG", admittedError.code)
    }

    @Test fun receiverDoesNotAdvertisePhysicalTelemetryOrProxy() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.VIRTUAL_AUDIO_EXPERIMENT)
        ReceiverServer.forProtocolTests("Receiver", supportsPlaybackStatistics = true).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { socket ->
                socket.soTimeout = 3000
                val hello = JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                    .put("name", "test").put("sessionTelemetry", SessionTelemetryProtocol.capability())
                    .put("diagProxy", JSONObject().put("version", 2))
                    .put("receiverOptions", ReceiverOptionCapabilities(true).wireValue())
                    .put("streamRecovery", true).put("streamRecoveryVersion", 2)
                Wire.write(socket.outputStream, Message.json(Type.HELLO, hello))
                val response = Wire.read(socket.inputStream).json()
                assertFalse(response.has("sessionTelemetry"))
                assertFalse(response.has("diagProxy"))
                assertFalse(response.has("receiverOptions"))
                assertTrue(response.optBoolean("streamRecovery"))
            }
        }
    }

    @Test fun armedReceiverProxyIsRejectedRatherThanSilentlyDowngraded() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.VIRTUAL_AUDIO_EXPERIMENT)
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { socket ->
                socket.soTimeout = 3000
                val offer = JSONObject().put("session", "41").put("pairId", "test")
                    .put("armGeneration", "1").put("receiverFlags", JSONArray().put("session"))
                    .put("durationMs", 10000).put("samplePeriodMs", 1000)
                val hello = JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                    .put("name", "test").put("diagProxy", JSONObject().put("version", 2).put("offer", offer))
                Wire.write(socket.outputStream, Message.json(Type.HELLO, hello))
                val result = Wire.read(socket.inputStream)
                assertEquals(Type.ERROR, result.type)
                assertEquals("UNSUPPORTED_CONFIG", result.json().getString("code"))
                assertTrue(result.json().getString("message").contains("virtual audio"))
            }
        }
    }
}
