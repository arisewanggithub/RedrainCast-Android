package dev.screencopy.sender

/** Name is a label, never peer identity. Endpoint selection is in-memory only. */
data class ReceiverTarget(val host: String, val port: Int, val name: String = "Receiver") {
    init {
        Settings.require(host.isNotBlank() && host.length <= 253 && host == host.trim(), "Enter receiver IP/address")
        Settings.range(port, 1, 65535, "Receiver port")
        Settings.require(name.isNotEmpty() && name.length <= 80, "Invalid receiver name")
    }
}
