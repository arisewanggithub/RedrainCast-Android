package org.screencopy.receiver.protocol

enum class ReceiverOrientation(val wireValue: String) {
    AUTO("auto"), LANDSCAPE("landscape"), PORTRAIT("portrait");

    companion object {
        fun parse(value: Any?): ReceiverOrientation =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("UNSUPPORTED_CONFIG", "Invalid receiverOrientation")
    }
}
