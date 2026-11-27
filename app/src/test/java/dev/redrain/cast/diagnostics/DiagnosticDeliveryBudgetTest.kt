package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticDeliveryBudgetTest {
    @Test fun rollingLimitsIncludeBytesAndDoNotBlock() {
        val budget = DiagnosticDeliveryBudget()
        repeat(256) { assertTrue(budget.tryAcquire(0, 1, 4)) }
        assertFalse(budget.tryAcquire(999_999_999, 1, 4))
        assertTrue(budget.tryAcquire(1_000_000_000, 1, 128 * 1024))
        assertFalse(budget.tryAcquire(1_000_000_000, 0, 1))
        assertTrue(budget.tryAcquire(2_000_000_000, 32, 1))
    }

    @Test fun zeroRecordControlFramesCannotGrowTheWindowWithoutBound() {
        val budget = DiagnosticDeliveryBudget()
        repeat(256) { assertTrue(budget.tryAcquire(0, 0, 1)) }
        assertFalse(budget.tryAcquire(0, 0, 1))
        assertTrue(budget.tryAcquire(1_000_000_000, 0, 1))
    }

    @Test fun reservedTerminalFitsAfterRoutineTrafficSaturatesSlotsOrBytes() {
        val reserve = DiagnosticProxyWire.FRAME_BYTES + 4
        val slots = DiagnosticDeliveryBudget()
        repeat(255) { assertTrue(slots.tryAcquire(0, 1, 4, reserve, 1)) }
        assertFalse(slots.tryAcquire(0, 1, 4, reserve, 1))
        assertTrue(slots.tryAcquire(0, 0, reserve))

        val bytes = DiagnosticDeliveryBudget()
        assertTrue(bytes.tryAcquire(0, 1, DiagnosticDeliveryBudget.BYTES_PER_SECOND - reserve, reserve, 1))
        assertFalse(bytes.tryAcquire(0, 1, 1, reserve, 1))
        assertTrue(bytes.tryAcquire(0, 0, reserve))
    }

    @Test fun routineSaturationLeavesOneCriticalRecordAndTerminal() {
        val budget = DiagnosticDeliveryBudget()
        val terminal = DiagnosticProxyWire.FRAME_BYTES + 4
        repeat(254) {
            assertTrue(budget.tryAcquire(0, 1, 4, terminal + 4096, 2, 1))
        }
        assertFalse(budget.tryAcquire(0, 1, 4, terminal + 4096, 2, 1))
        assertTrue(budget.tryAcquire(0, 1, 4096, terminal, 1))
        assertTrue(budget.tryAcquire(0, 0, terminal))
    }
}
