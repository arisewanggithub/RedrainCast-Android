package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class SessionOutcomeTest {
    @Test fun setupCauseUsesTypedLocalReasonsWithoutPromotingPrivateTextToPeerEvidence() {
        val cases = listOf(
            EOFException("private") to "eof",
            java.net.ConnectException("private") to "connect",
            java.net.SocketTimeoutException("private") to "timeout",
            IOException("TIMEOUT: private") to "timeout",
            IOException("PROTOCOL: private") to "setup"
        )
        for ((error, reason) in cases) {
            val failure = setupFailure(DiagnosticProxySetupException("lane", error), false).json()
            assertEquals("sender_local", failure.getString("provenance"))
            assertEquals(reason, failure.getString("reason"))
            assertFalse(failure.toString().contains("private"))
        }
    }

    @Test fun receiverErrorSupersedesOnlyProvisionalMediaDisconnect() {
        val outcome = SessionOutcome()
        assertTrue(outcome.record(MediaConnectionFailure(EOFException()), SessionOutcome.Origin.RECEIVER_READ))
        val peer = ReceiverReportedFailure("DECODER_FAILED", "Video output failed")
        assertTrue(outcome.record(peer, SessionOutcome.Origin.RECEIVER_READ, closing = true))
        assertSame(peer, outcome.failure)
        assertEquals("DECODER_FAILED", outcome.terminal(7, false).json().getString("code"))
    }

    @Test fun earlierCaptureFailureIsNotReplacedByALaterReceiverError() {
        val outcome = SessionOutcome()
        val capture = IOException("CAPTURE_FAILED: fixture encoder failure")
        outcome.record(capture, SessionOutcome.Origin.CAPTURE)
        assertFalse(outcome.record(ReceiverReportedFailure("DECODER_FAILED", "Peer cleanup failed"),
            SessionOutcome.Origin.RECEIVER_READ, closing = true))
        assertSame(capture, outcome.failure)
    }

    @Test fun mediaCancellationAfterUserStopDoesNotTurnStopIntoAnError() {
        val outcome = SessionOutcome()
        outcome.requestStop()
        assertFalse(outcome.record(MediaConnectionFailure(EOFException()), SessionOutcome.Origin.RECEIVER_READ))
        assertNull(outcome.failure)
        assertEquals(Wire.STOP, outcome.terminal(7, false).type)
    }

    @Test fun terminalCommitIsPublishedOnlyForAcceptedTransitions() {
        val outcome = SessionOutcome()
        var commits = 0
        assertTrue(outcome.requestStop { commits++ })
        assertFalse(outcome.requestStop { commits++ })
        assertFalse(outcome.record(EOFException(), SessionOutcome.Origin.RECEIVER_READ,
            commit = { commits++ }))
        assertEquals(1, commits)
        val failed = SessionOutcome()
        assertTrue(failed.record(IOException("failed"), SessionOutcome.Origin.CAPTURE, commit = { commits++ }))
        assertFalse(failed.record(IOException("later"), SessionOutcome.Origin.CAPTURE, commit = { commits++ }))
        assertEquals(2, commits)
    }

    @Test fun peerErrorCodeIsValidatedAndDiagnosticSummaryDoesNotExposePeerText() {
        assertThrows(IOException::class.java) { ReceiverReportedFailure("NOT_A_WIRE_ERROR", "invalid") }
        val peer = ReceiverReportedFailure("AUDIO_FAILED", "private peer text")
        val safe = SenderFailureDiagnostics.sanitize(peer)
        assertTrue(safe.message!!.startsWith("AUDIO_FAILED:"))
        assertFalse(safe.message!!.contains("private peer text"))
        assertEquals("RECEIVER_ERROR", SenderFailureDiagnostics.type(peer))
    }
}
