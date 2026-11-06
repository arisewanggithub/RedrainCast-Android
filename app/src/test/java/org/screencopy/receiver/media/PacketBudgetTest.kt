package org.screencopy.receiver.media

import org.junit.Assert.assertThrows
import org.junit.Test

class PacketBudgetTest {
    @Test fun bytesAreBoundedBeforeQueueing() {
        val budget = PacketBudget(10, 250_000)
        budget.add(AccessUnit(0, ByteArray(10)))
        assertThrows(PlaybackException::class.java) { budget.add(AccessUnit(1, ByteArray(1))) }
        budget.remove()
        budget.add(AccessUnit(2, ByteArray(10)))
    }

    @Test fun durationIsBoundedAtExactThreshold() {
        val budget = PacketBudget(100, 250_000)
        budget.add(AccessUnit(100, ByteArray(1)))
        budget.add(AccessUnit(250_100, ByteArray(1)))
        assertThrows(PlaybackException::class.java) { budget.add(AccessUnit(250_101, ByteArray(1))) }
    }

    @Test fun negativeTimestampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PacketBudget(10, 10).add(AccessUnit(-1, byteArrayOf(1)))
        }

    }

    @Test fun presentationReorderingDoesNotBreakDecodeOrder() {
        val budget = PacketBudget(10, 250_000)
        budget.add(AccessUnit(0, byteArrayOf(1)))
        budget.add(AccessUnit(66_666, byteArrayOf(2)))
        budget.add(AccessUnit(33_333, byteArrayOf(3)))
        repeat(3) { budget.remove() }
    }

    @Test fun actualVideoBudgetIsEightMiBAnd250Milliseconds() {
        val budget = PacketBudget(8 * 1024 * 1024, 250_000)
        budget.add(AccessUnit(0, ByteArray(4 * 1024 * 1024)))
        budget.add(AccessUnit(250_000, ByteArray(4 * 1024 * 1024)))
        assertThrows(PlaybackException::class.java) { budget.add(AccessUnit(250_000, byteArrayOf(1))) }
        budget.remove()
        budget.add(AccessUnit(500_000, byteArrayOf(1)))
        assertThrows(PlaybackException::class.java) { budget.add(AccessUnit(500_001, byteArrayOf(1))) }
    }
}
