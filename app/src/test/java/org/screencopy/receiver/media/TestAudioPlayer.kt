package org.screencopy.receiver.media

internal class IdentityPcmResampler(private val channels: Int) : PcmResampler {
    val compensations = mutableListOf<AudioCompensation>()
    override val delayFrames = 0
    override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int) =
        samples.copyOfRange(offsetFrames * channels, (offsetFrames + frames) * channels)
    override fun compensate(deltaFrames: Int, distanceFrames: Int) {
        compensations.add(AudioCompensation(deltaFrames, distanceFrames))
    }
    override fun close() {}
}

internal fun testAudioPlayer(
    format: AudioFormat,
    clock: PlaybackClock,
    failure: (String, String) -> Unit,
    outputFactory: () -> AudioOutput,
    onClockCorrection: (AudioClockCorrection) -> Unit = {},
    onOutputTiming: (AudioOutputTiming) -> Unit = {},
    onUnderrunRecovery: (AudioUnderrunDiagnostic) -> Unit = {},
    opusFactory: (AudioFormat) -> OpusPacketDecoder = { OpusDecoder(it.channels, it.outputGainQ8) },
    onRegulation: (AudioRegulationDiagnostic) -> Unit = {},
    resamplerFactory: (Int, Int) -> PcmResampler = { _, channels -> IdentityPcmResampler(channels) }
) = AudioPlayer(format, clock, failure, outputFactory, onClockCorrection, onOutputTiming,
    onUnderrunRecovery, opusFactory, onRegulation, resamplerFactory)
