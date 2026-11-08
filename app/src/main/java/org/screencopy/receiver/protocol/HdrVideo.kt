package org.screencopy.receiver.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HdrStaticMetadata(
    val masteringPrimaries: List<Int>,
    val maxMasteringNits: Int,
    val minMasteringTenThousandths: Int,
    val maxContentLightNits: Int,
    val maxFrameAverageLightNits: Int
) {
    fun json(): JSONObject = JSONObject().put("masteringPrimaries", JSONArray(masteringPrimaries))
        .put("maxMasteringNits", maxMasteringNits).put("minMasteringTenThousandths", minMasteringTenThousandths)
        .put("maxContentLightNits", maxContentLightNits).put("maxFrameAverageLightNits", maxFrameAverageLightNits)

    fun descriptor(): ByteBuffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
        protocolRequire(maxContentLightNits == 0 || maxFrameAverageLightNits <= maxContentLightNits,
            "Invalid HDR luminance metadata")
        protocolRequire(minMasteringTenThousandths <= 65535,
            "HDR minimum luminance cannot be represented by CTA-861.3 metadata", "UNSUPPORTED_CONFIG")
        put(0)
        masteringPrimaries.forEach { putShort(it.toShort()) }
        putShort(maxMasteringNits.toShort())
        // CTA-861.3 uses 0.0001 nit units for minimum luminance, unlike maximum luminance.
        putShort(minMasteringTenThousandths.toShort())
        putShort(maxContentLightNits.toShort())
        putShort(maxFrameAverageLightNits.toShort())
        flip()
    }

    companion object {
        fun parse(json: JSONObject): HdrStaticMetadata {
            val primaries = json.array("masteringPrimaries")
            protocolRequire(primaries.length() == 8, "HDR masteringPrimaries must contain eight integers")
            val values = (0 until 8).map {
                val value = primaries.opt(it)
                protocolRequire(value is Int && value in 0..50000, "Invalid HDR mastering primary")
                value as Int
            }
            protocolRequire(values.chunked(2).all { it[0] + it[1] <= 50000 },
                "HDR mastering xy pairs must be normalized")
            val maximum = json.integer("maxMasteringNits")
            val minimum = json.integer("minMasteringTenThousandths")
            val content = json.integer("maxContentLightNits")
            val average = json.integer("maxFrameAverageLightNits")
            protocolRequire(maximum in 1..10000 && minimum in 0..maximum * 10000 &&
                content in 0..10000 && average in 0..10000 &&
                (content == 0 || average <= content), "Invalid HDR luminance metadata")
            protocolRequire(minimum <= 65535,
                "HDR minimum luminance cannot be represented by CTA-861.3 metadata", "UNSUPPORTED_CONFIG")
            return HdrStaticMetadata(values, maximum, minimum, content, average)
        }
    }
}

data class HdrVideoColor(val staticMetadata: HdrStaticMetadata? = null) {
    fun json(): JSONObject = mode().put("version", 1).apply {
        staticMetadata?.let { put("staticMetadata", it.json()) }
    }

    companion object {
        fun mode(): JSONObject = JSONObject().put("codec", "h265").put("profile", "main10")
            .put("dynamicRange", "hdr10").put("colorPrimaries", "bt2020").put("transfer", "pq")
            .put("matrix", "bt2020nc").put("range", "limited").put("bitDepth", 10)

        fun validateMode(json: JSONObject) {
            val expected = mode()
            expected.keys().forEach { key ->
                protocolRequire(if (key == "bitDepth") json.integer(key) == 10 else json.string(key, 32) == expected.get(key),
                    "Unsupported HDR video mode: $key", "UNSUPPORTED_CONFIG")
            }
        }

        fun parse(json: JSONObject): HdrVideoColor {
            protocolRequire(StrictJson.encodedSize(json) <= 2048, "HDR videoColor exceeds 2048 bytes")
            protocolRequire(json.integer("version") == 1, "Unsupported HDR videoColor version", "UNSUPPORTED_CONFIG")
            validateMode(json)
            return HdrVideoColor(if (json.has("staticMetadata")) HdrStaticMetadata.parse(json.obj("staticMetadata")) else null)
        }
    }
}

data class ReceiveCapabilities(val hdr10: Boolean = false, val nativeHdrAvailable: Boolean? = null,
    val hdrToSdr: Boolean = false) {
    val acceptsHdr: Boolean get() = hdr10 && (nativeHdrAvailable == true || hdrToSdr)
    fun json(): JSONObject = JSONObject().put("version", 1)
        .put("videoModes", JSONArray().apply { if (acceptsHdr) put(HdrVideoColor.mode()) })
        .put("nativeHdrAvailable", nativeHdrAvailable ?: JSONObject.NULL).put("hdrToSdr", hdrToSdr)

    companion object {
        fun parse(json: JSONObject): ReceiveCapabilities? {
            protocolRequire(StrictJson.encodedSize(json) <= 2048, "receiveCapabilities exceeds 2048 bytes")
            val version = json.integer("version")
            protocolRequire(version > 0, "Invalid receiveCapabilities version")
            if (version != 1) return null
            val native = json.opt("nativeHdrAvailable")
            protocolRequire(native is Boolean || native === JSONObject.NULL, "Invalid nativeHdrAvailable")
            val toneMap = json.boolean("hdrToSdr")
            val modes = json.array("videoModes")
            protocolRequire(modes.length() <= 16, "Too many receiveCapabilities videoModes")
            var hdr = false
            repeat(modes.length()) {
                val mode = modes.opt(it)
                protocolRequire(mode is JSONObject, "Invalid receiveCapabilities videoMode")
                if (mode is JSONObject && mode.opt("codec") == "h265" && mode.opt("dynamicRange") == "hdr10") {
                    HdrVideoColor.validateMode(mode)
                    hdr = true
                }
            }
            return ReceiveCapabilities(hdr, native as? Boolean, toneMap)
        }
    }
}

internal object HdrPreparationDeadline {
    fun milliseconds(version: Int?): Int = if (version == 1) 45000 else 10000
    fun expired(version: Int?, readAgeMs: Long, preparationAgeMs: Long): Boolean =
        readAgeMs >= milliseconds(version) || preparationAgeMs >= milliseconds(version)
}
