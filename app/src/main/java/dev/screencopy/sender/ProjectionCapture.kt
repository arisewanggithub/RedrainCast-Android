package dev.screencopy.sender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import dev.redrain.cast.diagnostics.*
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ProjectionCapture(
    private val context: Context, private val owner: ProjectionOwner,
    private val fail: (Exception) -> Unit
) : Closeable {
    private val closed = AtomicBoolean()
    private val workers = mutableListOf<Thread>()
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var input: Surface? = null
    private var renderer: ProjectionRenderer? = null
    private var videoStarted = false
    private var audioStarted = false
    private var videoSource: CaptureDisplay? = null
    private var videoGeometry: VideoGeometry? = null
    private val pendingSource = AtomicReference<CaptureDisplay?>()
    private var displayListener: DisplayManager.DisplayListener? = null
    private val rotations = VideoRotationTimeline()
    private val keyFrameRequested = AtomicBoolean()

    fun validate(settings: Settings) = ProjectionInventory.validate(context, settings)

    fun requestKeyFrame() {
        if (closed.get()) throw IOException("CAPTURE_FAILED: video encoder is not running")
        keyFrameRequested.set(true)
    }

    @Synchronized fun start(settings: Settings, session: Long, sink: ScrcpyAdapter.Sink) {
        if (closed.get()) throw SessionCancelledException()
        configureVideo(settings, ProjectionInventory.displays(context).displays.single())
        val videoOutput = EncodedOutput(1, settings.videoCodec, session, sink, rotations::at)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) report(IOException("CAPTURE_FAILED: capture display was removed"))
            }
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) refreshSource()
            }
        }
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
        if (settings.audio) startAudio(settings, session, sink)
        attachVideo()
        worker("projection-video") {
            while (!closed.get()) {
                pendingSource.getAndSet(null)?.let { source ->
                    val geometry = VideoEncoderPreflight.validate(settings, DisplayInventory(listOf(source)))
                    val latest = if (geometry.output != videoGeometry?.output) {
                        sink.awaitVideoDrain()
                        pendingSource.getAndSet(null) ?: source
                    } else source
                    synchronized(this@ProjectionCapture) {
                        if (!closed.get()) updateVideo(settings, latest, videoOutput)
                    }
                }
                if (!closed.get()) {
                    if (keyFrameRequested.getAndSet(false))
                        checkNotNull(video).setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    videoOutput.drain(checkNotNull(video), 10_000)
                }
            }
        }
    }

    private fun configureVideo(settings: Settings, source: CaptureDisplay) {
        val geometry = VideoEncoderPreflight.validate(settings, DisplayInventory(listOf(source)))
        val encoder = MediaCodec.createByCodecName(settings.videoEncoder).also { video = it }
        val format = videoEncoderFormat(settings, geometry.output)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface().also { input = it }
        encoder.start(); videoStarted = true
        videoSource = source
        videoGeometry = geometry
        android.util.Log.i("ScreenCopySender", "event=PROJECTION_VIDEO_GEOMETRY source_width=${source.width} source_height=${source.height}" +
            " source_rotation=${source.rotation} width=${geometry.output.width} height=${geometry.output.height}")
        renderer = ProjectionRenderer(context, surface, geometry.output, settings, source,
            onFrame = rotations::offer, fail = ::report)
    }

    private fun attachVideo() {
        val source = checkNotNull(videoSource)
        val resize: (Int, Int) -> Unit = { width, height -> refreshSource(width, height) }
        val captureSurface = checkNotNull(renderer).start()
        owner.attach(captureSurface, source.width, source.height, resize)
    }

    private fun refreshSource(width: Int? = null, height: Int? = null) {
        if (closed.get()) return
        try {
            val metadata = ProjectionInventory.displays(context).displays.single()
            if (width != null && (metadata.width != width || metadata.height != height))
                android.util.Log.i("ScreenCopySender", "event=PROJECTION_RESIZE_SUPERSEDED callback_width=$width callback_height=$height" +
                    " source_width=${metadata.width} source_height=${metadata.height} source_rotation=${metadata.rotation}")
            // A queued callback can precede another rotation; never combine its old size with a newer rotation.
            pendingSource.set(metadata)
        } catch (e: Exception) { report(IOException("CAPTURE_FAILED: capture content size update failed", e)) }
    }

    private fun updateVideo(settings: Settings, source: CaptureDisplay, output: EncodedOutput) {
        if (source == videoSource) return
        val geometry = VideoEncoderPreflight.validate(settings, DisplayInventory(listOf(source)))
        if (geometry.output == videoGeometry?.output) {
            renderer?.let {
                it.resize(source)
                owner.resize(source.width, source.height)
            }
            videoSource = source
            return
        }
        owner.detach()
        renderer?.close(); renderer = null
        video?.stop(); videoStarted = false
        video?.release(); video = null
        input?.release(); input = null
        output.restart()
        configureVideo(settings, source)
        attachVideo()
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(settings: Settings, session: Long, sink: ScrcpyAdapter.Sink) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw IOException("AUDIO_FAILED: Allow audio recording or explicitly disable audio in Settings.")
        val audioFormat = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("AUDIO_FAILED: stereo 48 kHz PCM capture is unavailable")
        val builder = AudioRecord.Builder().setAudioFormat(audioFormat).setBufferSizeInBytes(maxOf(minimum, 3840 * 8))
        if (settings.audioSource == "mic") builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        else builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(owner.projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
        val record = builder.build().also { recorder = it }
        if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("AUDIO_FAILED: audio capture initialization failed")
        val output = EncodedOutput(2, settings.audioCodec, session, sink)
        val codec = if (settings.audioCodec == "raw") null else {
            MediaCodec.createByCodecName(settings.audioEncoder).also { encoder ->
                audio = encoder
                val format = MediaFormat.createAudioFormat(checkNotNull(HardwareEncoderPolicy.mimeTypes[settings.audioCodec]), 48000, 2).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 3840)
                    if (settings.audioCodec == "aac") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    ProjectionPackets.options(this, settings.audioOptions)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start(); audioStarted = true
            }
        }
        if (codec == null) output.configure(byteArrayOf())
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("AUDIO_FAILED: audio recording did not start")
        owner.mutePlayback(settings)
        val start = System.nanoTime() / 1000
        worker("projection-audio") {
            var frames = 0L
            var origin: Long? = null
            var rawPackets: RawPcmPackets? = null
            var diagnosticTimestampAt = 0L
            var diagnosticCaptureId: String? = null
            val bytes = ByteArray(3840)
            while (!closed.get()) {
                val operation = RemoteDiagnostics.recorder.operation("sender", session, DiagnosticKind.AUDIO_CAPTURE)
                RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_CAPTURE,
                    DiagnosticStage.BEGIN, 2, output.generation, operation = operation, count = frames, point = DiagnosticPoint.READ)
                val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_NON_BLOCKING)
                RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_CAPTURE,
                    if (count < 0) DiagnosticStage.FAILED else DiagnosticStage.COMPLETE,
                    2, output.generation, operation = operation, pts = origin?.let { it + frames * 1_000_000L / 48000 } ?: -1,
                    count = if (count >= 0) count.toLong() / 4 else -1, bytes = count.toLong(), point = DiagnosticPoint.READ)
                if (RemoteDiagnostics.recorder.enabled("sender", session, DiagnosticKind.AUDIO_CAPTURE)) {
                    if (diagnosticCaptureId != RemoteDiagnostics.recorder.captureIdentity) {
                        diagnosticCaptureId = RemoteDiagnostics.recorder.captureIdentity
                        diagnosticTimestampAt = 0
                    }
                    val diagnosticNow = System.nanoTime()
                    if (diagnosticNow >= diagnosticTimestampAt) {
                        diagnosticTimestampAt = diagnosticNow + RemoteDiagnostics.recorder.samplePeriodNs
                        val time = AudioTimestamp()
                        val available = try {
                            record.getTimestamp(time, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                        } catch (_: IllegalStateException) { false }
                        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_CAPTURE,
                            DiagnosticStage.SAMPLE, 2, output.generation,
                            metadata = DiagnosticCaptureTimestamp(available, time.framePosition, time.nanoTime))
                    }
                }
                if (count < 0) throw IOException("AUDIO_FAILED: AudioRecord read failed ($count)")
                if (count == 0) {
                    if (codec != null) output.drain(codec, 0)
                    Thread.sleep(2); continue
                }
                if (count % 4 != 0) throw IOException("AUDIO_FAILED: unaligned stereo PCM")
                if (origin == null) {
                    val time = AudioTimestamp()
                    val available = record.getTimestamp(time, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    origin = if (available)
                        time.nanoTime / 1000 - time.framePosition * 1_000_000L / 48000 else start
                    if (RemoteDiagnostics.recorder.enabled("sender", session, DiagnosticKind.AUDIO_CAPTURE))
                        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_CAPTURE,
                            DiagnosticStage.CONFIG, 2, pts = checkNotNull(origin),
                            metadata = DiagnosticCaptureTimestamp(available, time.framePosition, time.nanoTime))
                }
                if (codec == null) {
                    if (rawPackets == null) rawPackets = RawPcmPackets(checkNotNull(origin)) { data, pts -> output.packet(data, pts, false) }
                    checkNotNull(rawPackets).append(bytes, count)
                } else {
                    var offset = 0
                    while (offset < count && !closed.get()) {
                        val slot = codec.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(slot)).apply { clear() }
                            val length = minOf(count - offset, buffer.remaining()) / 4 * 4
                            if (length == 0) throw IOException("AUDIO_FAILED: encoder PCM buffer is too small")
                            buffer.put(bytes, offset, length)
                            codec.queueInputBuffer(slot, 0, length,
                                checkNotNull(origin) + (frames + offset / 4) * 1_000_000 / 48000, 0)
                            offset += length
                        }
                        output.drain(codec, 0)
                    }
                }
                frames += count / 4
            }
        }
    }

    companion object {
        internal fun videoEncoderFormat(settings: Settings, size: VideoSize): MediaFormat {
            val mime = checkNotNull(HardwareEncoderPolicy.mimeTypes[settings.videoCodec])
            return MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, settings.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                if (settings.maxFps > 0) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, settings.maxFps.toFloat())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                ProjectionPackets.options(this, settings.videoOptions)
            }
        }

    }

    private fun worker(name: String, action: () -> Unit) {
        workers += Thread({
            try { action() } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                if (!closed.get()) report(if (SessionOutcome.errorCode(e) != "INTERNAL" ||
                    e is SessionCancelledException || e is SenderProtocolException) e
                    else IOException("${if (name.endsWith("audio")) "AUDIO_FAILED" else "CAPTURE_FAILED"}: Android capture worker failed", e))
            }
        }, name).apply { start() }
    }
    private fun report(error: Exception) { if (!closed.get()) fail(error) }

    override fun close() {
        val threads = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            workers.toList()
        }
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) {
                if (failure == null) failure = e else failure?.addSuppressed(e)
            }
        }
        cleanup { displayListener?.let { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } }
        displayListener = null
        cleanup { owner.detach() }
        cleanup { renderer?.close() }
        cleanup { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
        threads.forEach(Thread::interrupt)
        threads.forEach { thread -> if (thread !== Thread.currentThread()) cleanup {
            thread.join(2000)
            if (thread.isAlive) throw IOException("Capture worker did not stop")
        } }
        cleanup { recorder?.release() }
        cleanup { video?.let { if (videoStarted) it.stop() } }
        cleanup { video?.release() }
        cleanup { audio?.let { if (audioStarted) it.stop() } }
        cleanup { audio?.release() }
        cleanup { input?.release() }
        failure?.let { throw IOException("Capture cleanup failed", it) }
    }
}

internal class EncodedOutput(
    val track: Int, private val codec: String, private val session: Long, private val sink: ScrcpyAdapter.Sink,
    private val rotation: (Long) -> Int = { 0 }
) {
    private var previous: ByteArray? = null
    var generation = 0
        private set
    fun restart() { previous = null }
    fun drain(encoder: MediaCodec, timeout: Long) {
        val info = MediaCodec.BufferInfo()
        val index = encoder.dequeueOutputBuffer(info, timeout)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val format = encoder.outputFormat
            val keys = if (track == 1) listOf("csd-0", "csd-1") else listOf("csd-0")
            val bytes = keys.fold(byteArrayOf()) { data, key ->
                val buffer = format.getByteBuffer(key)?.duplicate()
                data + if (buffer == null) byteArrayOf() else ByteArray(buffer.remaining()).also(buffer::get)
            }
            configure(bytes)
        } else if (index >= 0) {
            try {
                if (info.size > 0) {
                    val buffer = checkNotNull(encoder.getOutputBuffer(index)).duplicate()
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also(buffer::get)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) configure(bytes)
                    else packet(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                    throw IOException("CAPTURE_FAILED: Android encoder ended unexpectedly")
            } finally { encoder.releaseOutputBuffer(index, false) }
        }
    }
    fun configure(raw: ByteArray) {
        val bytes = if (codec == "opus") ProjectionPackets.opusHead(raw) else raw
        if (previous?.contentEquals(bytes) == true) return
        val configuration = if (track == 1) CodecConfig.video(codec, bytes) else CodecConfig.audio(codec, bytes)
        sink.config(track, ++generation, configuration)
        previous = bytes
    }
    fun packet(bytes: ByteArray, pts: Long, key: Boolean) {
        if (generation == 0) throw IOException("CAPTURE_FAILED: encoder output preceded initialization")
        if (pts < 0 || bytes.isEmpty() || bytes.size > if (track == 1) Wire.MAX_VIDEO else Wire.MAX_AUDIO)
            throw IOException("CAPTURE_FAILED: invalid encoded packet")
        if (track == 1 && codec == "av1") Av1Configuration.obus(bytes)
        if (track == 1 && codec != "av1" && (bytes.size < 4 || bytes[0] != 0.toByte() || bytes[1] != 0.toByte() ||
                !(bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())))
            throw IOException("CAPTURE_FAILED: encoder did not produce Annex B access units")
        if (codec == "raw" && bytes.size % 4 != 0) throw IOException("AUDIO_FAILED: unaligned PCM packet")
        if (codec == "aac") AacConfiguration.accessUnit(bytes)
        val degrees = if (track == 1) rotation(pts) else 0
        if (degrees !in listOf(0, 90, 180, 270)) throw IOException("CAPTURE_FAILED: invalid video rotation")
        val flags = (if (track == 1 && key) 1 else 0) or (degrees / 90 shl 1)
        if (track == 1) RemoteDiagnostics.recorder.video("sender", session, generation,
            DiagnosticVideoStage.ENCODED, pts, bytes.size.toLong(), keyFrame = key)
        sink.packet(Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET,
            flags, session, track, generation, pts, bytes))
    }
}
