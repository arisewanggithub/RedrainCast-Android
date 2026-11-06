package dev.redrain.cast

import org.junit.Assert.*
import org.junit.Test

class RoleOwnerTest {
    @Test fun rolesAreExclusiveAndRetiredLeasesCannotReleaseNewOwners() {
        val roles = RoleOwner()
        val sender = roles.acquire(RoleOwner.Role.SEND)
        assertThrows(IllegalStateException::class.java) { roles.acquire(RoleOwner.Role.RECEIVE) }
        roles.release(sender)
        val receiver = roles.acquire(RoleOwner.Role.RECEIVE)
        roles.release(sender)
        assertTrue(roles.owns(receiver))
        assertFalse(roles.owns(sender))
        roles.release(receiver)
        assertNull(roles.role())
    }
}
