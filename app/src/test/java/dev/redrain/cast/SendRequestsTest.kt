package dev.redrain.cast

import dev.screencopy.sender.ReceiverTarget
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SendRequestsTest {
    private val target = ReceiverTarget("192.0.2.1", 49152, "Receiver")

    @Test fun consentStageBelongsToTheLiveReservationAndSurvivesAdoption() {
        val roles = RoleOwner()
        val requests = SendRequests(roles)
        val pending = requests.reserve("settings", target)
        assertFalse(pending.consentStarted)
        requests.beginConsent(pending.id)
        requests.beginConsent(pending.id)
        assertTrue(checkNotNull(requests.snapshot()).consentStarted)
        assertTrue(requests.adopt(pending.id).consentStarted)
        assertThrows(IllegalStateException::class.java) { requests.beginConsent(pending.id) }
        requests.finish(pending.id)
    }

    @Test fun staleConsentContinuationCannotAdvanceAReplacementReservation() {
        val roles = RoleOwner()
        val requests = SendRequests(roles)
        val first = requests.reserve("first", target)
        requests.cancel(first.id)
        val next = requests.reserve("next", target)
        assertThrows(IllegalStateException::class.java) { requests.beginConsent(first.id) }
        assertEquals(next, requests.snapshot())
        assertFalse(checkNotNull(requests.snapshot()).consentStarted)
        requests.finish(next.id)
    }

    @Test fun reservationBlocksIncomingBeforeConsentAndDenialReleasesIt() {
        val roles = RoleOwner()
        val requests = SendRequests(roles)
        val pending = requests.reserve("settings", target)
        assertEquals(RoleOwner.Role.SEND, roles.role())
        assertNull(roles.tryAcquire(RoleOwner.Role.RECEIVE))
        assertFalse(pending.adopted)
        assertTrue(requests.cancel(pending.id))
        assertNull(requests.snapshot())
        assertNotNull(roles.tryAcquire(RoleOwner.Role.RECEIVE))
    }

    @Test fun adoptionIsSingleUseAndActivityCannotCancelServiceOwnership() {
        val roles = RoleOwner()
        val requests = SendRequests(roles)
        val pending = requests.reserve("settings", target)
        assertThrows(IllegalStateException::class.java) { requests.adopt(pending.id + 1) }
        val adopted = requests.adopt(pending.id)
        assertTrue(adopted.adopted)
        assertEquals(target, adopted.target)
        assertThrows(IllegalStateException::class.java) { requests.adopt(pending.id) }
        assertFalse(requests.cancel(pending.id))
        assertEquals(RoleOwner.Role.SEND, roles.role())
        assertTrue(requests.finish(pending.id))
        assertNull(roles.role())
    }

    @Test fun staleServiceCompletionCannotReleaseNewReceiveOrSend() {
        val roles = RoleOwner()
        val requests = SendRequests(roles)
        val first = requests.reserve("settings", target)
        requests.adopt(first.id)
        requests.finish(first.id)
        val receive = checkNotNull(roles.tryAcquire(RoleOwner.Role.RECEIVE))
        assertFalse(requests.finish(first.id))
        assertTrue(roles.owns(receive))
        assertThrows(IllegalStateException::class.java) { requests.reserve("settings", target) }
        roles.release(receive)
        val second = requests.reserve("settings", target)
        assertFalse(requests.finish(first.id))
        assertEquals(second, requests.snapshot())
        requests.finish(second.id)
        assertNull(roles.role())
    }

    @Test fun incomingAndOutgoingAdmissionHaveExactlyOneWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val roles = RoleOwner()
                val requests = SendRequests(roles)
                val start = CountDownLatch(1)
                val incoming = executor.submit<RoleOwner.Lease?> {
                    start.await(); roles.tryAcquire(RoleOwner.Role.RECEIVE)
                }
                val outgoing = executor.submit<SendRequests.Request?> {
                    start.await()
                    try { requests.reserve("settings", target) } catch (_: IllegalStateException) { null }
                }
                start.countDown()
                val receive = incoming.get(2, TimeUnit.SECONDS)
                val send = outgoing.get(2, TimeUnit.SECONDS)
                assertTrue((receive == null) != (send == null))
                receive?.let(roles::release)
                send?.let { requests.cancel(it.id) }
                assertNull(roles.role())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
