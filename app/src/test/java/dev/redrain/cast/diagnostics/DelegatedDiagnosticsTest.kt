package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DelegatedDiagnosticsTest {
    private class Fixture {
        var now = 1_000_000_000L
        var allowed = true
        var local = false
        var localReady = true
        var consent = 0L
        var owner = true
        var fail = false
        val networkValid = AtomicBoolean(true)
        var opens = 0
        var lastListener: DiagnosticListener? = null
        val recorder = DiagnosticRecorder { now }
        val reports = mutableListOf<String>()
        val unavailable = CountDownLatch(1)
        val manager = DelegatedDiagnostics(recorder, Executor { it.run() }, { allowed }, { local },
            { permitted, maintenance ->
                opens++
                if (fail) throw java.io.IOException("synthetic startup failure")
                DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress(), isValid = networkValid::get),
                    accessAllowed = permitted, accessRevoked = maintenance,
                    discoveryPort = 0).also { lastListener = it }
            }, { now }, localReady = { localReady }, localGeneration = { consent })
        fun admit(session: Long = 77, enabled: Boolean = true) {
            recorder.sessionStarted("receiver", session)
            manager.admitted(session, enabled, { owner }, {
                assertFalse(Thread.holdsLock(manager))
                reports.add(it)
                if (it == "unavailable") unavailable.countDown()
            })
        }
        fun start(session: Long = 77) = recorder.start("receiver", session, setOf("session"), 10000, 100)
        fun rejects(code: String, action: () -> Unit) {
            try { action(); fail("Expected $code") } catch (error: DiagnosticError) { assertEquals(code, error.code) }
        }
    }

    @Test fun admittedDelegationNeedsNoLocalStatisticsPreferenceAndDoesNotCollectUntilPcStart() {
        val f = Fixture()
        try {
            assertFalse(DiagnosticAccess.enabled)
            f.admit()
            assertEquals(listOf("enabled"), f.reports)
            assertNotNull(f.manager.discovery)
            assertFalse(f.recorder.collecting)
            assertFalse(DiagnosticAccess.enabled)
            f.start()
            assertTrue(f.recorder.collecting)
        } finally { f.manager.reset() }
    }

    @Test fun scopeRejectsSenderOtherSessionAndNextSessionAndFiltersStatusAndHello() {
        val f = Fixture()
        try {
            f.recorder.sessionStarted("sender", 55)
            f.admit()
            f.rejects("SCOPE_MISMATCH") { f.recorder.start("sender", 55, setOf("session"), 1000, 100) }
            f.rejects("SCOPE_MISMATCH") { f.start(78) }
            f.rejects("SCOPE_MISMATCH") { f.recorder.start("receiver", null, setOf("session"), 1000, 100) }
            val status = f.recorder.status()
            assertEquals(1, status.getJSONArray("sessions").length())
            assertEquals("77", status.getJSONArray("sessions").getJSONObject(0).getString("session"))
            val hello = DiagnosticProtocol(f.recorder).handle(
                """{"v":1,"id":"hello","op":"HELLO","args":{}}""".toByteArray()).getJSONObject("result")
            assertEquals(0, hello.getJSONObject("capabilities").getJSONArray("sender").length())
            assertEquals("77", hello.getJSONObject("scope").getString("session"))
        } finally { f.manager.reset() }
    }

    @Test fun teardownImmediatelyFreezesAndGraceClosesAt120SecondsWithoutRenewal() {
        val f = Fixture()
        try {
            f.admit()
            val id = f.start().getString("captureId")
            f.manager.ended(77)
            assertFalse(f.recorder.collecting)
            assertEquals("frozen", f.recorder.status().getString("state"))
            assertNotNull(f.manager.discovery)
            f.recorder.fetch(id, 0, 32)
            f.rejects("SESSION_ENDED") { f.start() }
            f.now += 119_999_999_999L
            f.manager.ended(77)
            assertNotNull(f.manager.discovery)
            f.now++
            f.manager.tick()
            assertNull(f.manager.discovery)
            assertEquals("idle", f.recorder.status().getString("state"))
        } finally { f.manager.reset() }
    }

    @Test fun endpointBootstrapFollowsSessionGraceReplacementAndOwnerRevocation() {
        val f = Fixture()
        try {
            f.admit()
            val first = checkNotNull(f.lastListener)
            assertNotNull(first.endpointDiscoveryPort)
            f.manager.ended(77)
            assertNotNull(first.endpointDiscoveryPort)
            f.now += 120_000_000_000L
            f.manager.tick()
            assertNull(first.endpointDiscoveryPort)
            f.admit(88)
            val second = checkNotNull(f.lastListener)
            assertNotNull(second.endpointDiscoveryPort)
            f.admit(99, false)
            assertNull(second.endpointDiscoveryPort)
            assertNull(f.manager.discovery)
        } finally { f.manager.reset() }
    }

    @Test fun flagOffFreezesOnceAndNeverRebindsToOtherCast() {
        val f = Fixture()
        try {
            f.admit()
            f.start()
            f.manager.revoked(76)
            assertTrue(f.recorder.collecting)
            f.manager.revoked(77)
            assertFalse(f.recorder.collecting)
            f.recorder.sessionStarted("receiver", 88)
            f.rejects("SCOPE_MISMATCH") { f.start(88) }
            f.rejects("SESSION_ENDED") { f.start() }
            assertEquals("77", f.recorder.scope()?.getString("session"))
        } finally { f.manager.reset() }
    }

    @Test fun ownerLivenessImmediatelyGatesMediaBeforeMaintenance() {
        val f = Fixture()
        try {
            f.admit()
            f.start()
            f.owner = false
            assertFalse(f.recorder.collecting)
            assertFalse(f.recorder.enabled("receiver", 77, DiagnosticKind.SESSION))
            f.rejects("SESSION_ENDED") { f.start() }
            f.manager.tick()
            assertEquals("frozen", f.recorder.status().getString("state"))
        } finally { f.manager.reset() }
    }

    @Test fun newOwnerWithoutFlagRevokesOldGraceAndWithFlagStartsFreshScope() {
        val f = Fixture()
        try {
            f.admit()
            val id = f.start().getString("captureId")
            f.manager.ended(77)
            f.admit(88, false)
            assertNull(f.manager.discovery)
            f.rejects("CAPTURE_NOT_FOUND") { f.recorder.fetch(id, 0, 32) }
            f.admit(99)
            assertEquals("99", f.recorder.scope()?.getString("session"))
            assertFalse(f.recorder.collecting)
            f.start(99)
            assertTrue(f.recorder.collecting)
            f.manager.ended(77)
            assertTrue(f.recorder.collecting)
        } finally { f.manager.reset() }
    }

    @Test fun permissionAndBindFailuresReportUnavailableWithoutStartingOrCollecting() {
        for (permission in listOf(false, true)) {
            val f = Fixture()
            try {
                f.allowed = permission
                f.fail = true
                f.admit()
                assertEquals(listOf("unavailable"), f.reports)
                assertNull(f.manager.discovery)
                assertFalse(f.recorder.collecting)
                assertEquals(if (permission) 1 else 0, f.opens)
            } finally { f.manager.reset() }
        }
    }

    @Test fun permissionLossClosesAndClearsWithoutGrace() {
        val f = Fixture()
        try {
            f.admit()
            f.start()
            f.allowed = false
            f.manager.tick()
            assertNull(f.manager.discovery)
            assertFalse(f.recorder.collecting)
            assertEquals("idle", f.recorder.status().getString("state"))
            assertEquals("unavailable", f.reports.last())
        } finally { f.manager.reset() }
    }

    @Test fun unexpectedListenerLossIsReportedAndClearsScopeRatherThanClaimingEnabled() {
        val f = Fixture()
        try {
            f.admit()
            f.start()
            checkNotNull(f.lastListener).revokeNetwork()
            f.manager.tick()
            assertNull(f.manager.discovery)
            assertNull(f.recorder.scope())
            assertEquals("unavailable", f.reports.last())
        } finally { f.manager.reset() }
    }

    @Test fun networkChangeRevokesDelegationAndReportsUnavailableWithoutGrace() {
        val f = Fixture()
        try {
            f.admit()
            f.start()
            f.networkValid.set(false)
            f.manager.tick()
            assertTrue(f.unavailable.await(3, TimeUnit.SECONDS))
            assertNull(f.manager.discovery)
            assertFalse(f.recorder.collecting)
            assertNull(f.recorder.scope())
            assertEquals("unavailable", f.reports.last())
        } finally { f.manager.reset() }
    }

    @Test fun explicitLocalModeIsIndependentOfOwnerTeardown() {
        val f = Fixture()
        try {
            f.local = true
            f.recorder.sessionStarted("sender", 55)
            f.admit()
            assertEquals(listOf("local"), f.reports)
            assertEquals(0, f.opens)
            assertNull(f.recorder.scope())
            f.recorder.start("sender", 55, setOf("session"), 10000, 100)
            f.manager.ended(77)
            f.manager.admitted(88, false, { true }, {})
            assertTrue(f.recorder.collecting)
        } finally { f.recorder.revoke(); f.manager.reset() }
    }

    @Test fun staleAsyncStartupCannotReopenAfterNewOwnerOrLocalTakeover() {
        val recorder = DiagnosticRecorder()
        val work = mutableListOf<Runnable>()
        var opened = false
        val manager = DelegatedDiagnostics(recorder, Executor { work.add(it) }, { true }, { false },
            { _, _ -> opened = true; error("Stale startup executed") })
        try {
            manager.admitted(77, true, { true }, {})
            manager.admitted(88, false, { true }, {})
            work.single().run()
            assertFalse(opened)
            assertNull(manager.discovery)
            assertNull(recorder.scope())
        } finally { manager.reset() }
    }

    @Test fun localStartupDefersEndpointReportUntilReadyAndNeverCallsOwnerUnderLock() {
        val f = Fixture()
        try {
            f.local = true
            f.localReady = false
            f.manager.admitted(77, true, { f.owner }) {
                assertFalse(Thread.holdsLock(f.manager))
                f.reports.add(it)
            }
            assertTrue(f.reports.isEmpty())
            f.localReady = true
            f.manager.localListenerChanged()
            f.manager.localListenerChanged()
            assertEquals(listOf("local"), f.reports)
            assertNull(f.recorder.scope())
        } finally { f.manager.reset() }
    }

    @Test fun pendingLocalReportCannotSurviveRetirementOwnerReplacementOrConsentChange() {
        for (race in listOf("ended", "revoked", "replaced", "owner_dead", "disabled", "new_consent", "reset")) {
            val f = Fixture()
            try {
                f.local = true
                f.localReady = false
                f.admit()
                when (race) {
                    "ended" -> f.manager.ended(77)
                    "revoked" -> f.manager.revoked(77)
                    "replaced" -> f.manager.admitted(88, false, { true }, { fail("Unexpected replacement report") })
                    "owner_dead" -> f.owner = false
                    "disabled" -> f.local = false
                    "new_consent" -> f.consent++
                    "reset" -> f.manager.reset()
                }
                f.manager.localListenerChanged()
                f.localReady = true
                f.manager.localListenerChanged()
                assertFalse(race, "local" in f.reports)
                assertEquals(race, if (race in setOf("disabled", "new_consent"))
                    listOf("unavailable") else emptyList<String>(), f.reports)
            } finally { f.manager.reset() }
        }
    }
}
