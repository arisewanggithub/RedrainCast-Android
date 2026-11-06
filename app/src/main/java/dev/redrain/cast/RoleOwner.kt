package dev.redrain.cast

internal class RoleOwner {
    enum class Role { SEND, RECEIVE }
    class Lease internal constructor(val role: Role)
    private var active: Lease? = null

    @Synchronized fun acquire(role: Role): Lease {
        check(active == null) { "Stop the active role before switching Send/Receive." }
        return Lease(role).also { active = it }
    }

    @Synchronized fun tryAcquire(role: Role): Lease? =
        if (active == null) Lease(role).also { active = it } else null

    @Synchronized fun owns(lease: Lease) = active === lease

    @Synchronized fun release(lease: Lease) {
        if (active === lease) active = null
    }

    @Synchronized fun role(): Role? = active?.role
}

internal object CastState {
    val roles = RoleOwner()
    val requests = SendRequests(roles)
    val instanceId: String = java.util.UUID.randomUUID().toString().replace("-", "")
    @Volatile var message = ""
    @Volatile var failure: String? = null
    val sending: Boolean get() = requests.snapshot()?.adopted == true
}
