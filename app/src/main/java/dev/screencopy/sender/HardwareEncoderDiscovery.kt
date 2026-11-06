package dev.screencopy.sender

import android.media.MediaCodecInfo
import android.media.MediaCodecList

internal object HardwareEncoderDiscovery {
    fun metadata(info: MediaCodecInfo) = EncoderMetadata(
        info.name, info.canonicalName, info.supportedTypes.toList(), info.isEncoder,
        info.isHardwareAccelerated, info.isSoftwareOnly, info.isAlias
    )
    fun read() = HardwareEncoders(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.map(::metadata))
    fun codecs(codec: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter {
            HardwareEncoderPolicy.eligible(metadata(it), codec)
        }
}
