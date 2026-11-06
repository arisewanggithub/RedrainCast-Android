package dev.screencopy.sender

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SenderFailureDiagnosticsTest {
    @Test fun sanitizationRetainsCodeAndCauseButNeverPrivateMessagesOrStackFilenames() {
        val privateText = "tcp://private-peer.invalid/media"
        val root = SocketTimeoutException(privateText).apply {
            stackTrace = arrayOf(
                StackTraceElement("dev.screencopy.sender.ReceiverLink", "send", privateText, 108),
                StackTraceElement("private.peer.Secret", "send", privateText, 9))
        }
        val original = IOException("CAPTURE_FAILED: $privateText", root)
        val safe = SenderFailureDiagnostics.sanitize(original)
        assertEquals("CAPTURE_FAILED: Sender failure stage=SESSION root=TIMEOUT category=TIMEOUT", safe.message)
        assertSame(original, safe.cause)
        val chain = SenderFailureDiagnostics.chain(original)
        assertEquals(listOf("sender_failure_cause depth=0 type=IO", "sender_failure_cause depth=1 type=TIMEOUT"),
            SenderFailureDiagnostics.causes(chain))
        val stack = SenderFailureDiagnostics.stack(chain).joinToString()
        assertTrue(stack.contains("ReceiverLink.send line=108"))
        assertFalse(stack.contains(privateText))
        assertFalse(stack.contains("private.peer.Secret"))
    }

    @Test fun cancellationIdentityAndBoundedCyclicCauseChainsRemainSafe() {
        val cancel = SessionCancelledException()
        assertSame(cancel, SenderFailureDiagnostics.sanitize(cancel))
        val first = IOException("CAPTURE_FAILED: first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(2, SenderFailureDiagnostics.chain(first).size)
        assertTrue(SenderFailureDiagnostics.sanitize(first).message!!.contains("chain_truncated=true"))
        var deep = IOException("private")
        repeat(20) { deep = IOException("private", deep) }
        val chain = SenderFailureDiagnostics.chain(deep)
        assertEquals(8, chain.size)
        assertTrue(SenderFailureDiagnostics.stack(chain).size <= 12)
        assertTrue(SenderFailureDiagnostics.sanitize(deep).message!!.contains("chain_truncated=true"))
    }
}
