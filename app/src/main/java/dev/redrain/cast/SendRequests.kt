package dev.redrain.cast

import dev.screencopy.sender.ReceiverTarget

/** Reserves the outgoing role before consent; the service adopts exactly that reservation. */
internal class SendRequests(private val roles: RoleOwner) {
    data class Request(val id: Long, val settings: String, val target: ReceiverTarget, val adopted: Boolean,
        val consentStarted: Boolean = false)
    private var nextId = 0L
    private var request: Request? = null
    private var lease: RoleOwner.Lease? = null

    @Synchronized fun reserve(settings: String, target: ReceiverTarget): Request {
        check(request == null) { "A sharing request is already in progress." }
        lease = roles.acquire(RoleOwner.Role.SEND)
        return Request(++nextId, settings, target, false).also { request = it }
    }

    private fun pending(id: Long): Request {
        val current = checkNotNull(request) { "Sharing request expired; approve screen sharing again." }
        check(current.id == id && !current.adopted && lease?.let(roles::owns) == true) {
            "Sharing request is stale or already running."
        }
        return current
    }

    @Synchronized fun beginConsent(id: Long) {
        request = pending(id).copy(consentStarted = true)
    }

    @Synchronized fun adopt(id: Long): Request {
        val current = pending(id)
        return current.copy(adopted = true).also { request = it }
    }

    @Synchronized fun snapshot(): Request? = request

    @Synchronized fun cancel(id: Long): Boolean {
        if (request?.id != id || request?.adopted != false) return false
        return finish(id)
    }

    @Synchronized fun finish(id: Long): Boolean {
        if (request?.id != id) return false
        lease?.let(roles::release)
        lease = null
        request = null
        return true
    }
}
