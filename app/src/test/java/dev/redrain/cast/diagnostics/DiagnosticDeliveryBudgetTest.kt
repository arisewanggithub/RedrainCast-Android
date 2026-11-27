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
}
