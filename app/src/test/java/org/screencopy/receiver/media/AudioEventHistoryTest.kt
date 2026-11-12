package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioEventHistoryTest {
    @Test fun boundedHistoryIncludesOrderedPacketAndPreRefillEvidence() {
        val history = AudioEventHistory()
        history.start(3)
        repeat(600) { history.record(it * 1000L, "receive", it.toLong(), 3840) }
        history.record(600000, "dequeue", 599, 0)
        history.refill(601000, 0, 960)
        val lines = history.dump(602000, 4)
        assertTrue(lines.first().contains("native_delta=1"))
        assertTrue(lines.first().contains("entries=512"))
        assertTrue(lines.first().contains("overwritten=90"))
        assertTrue(lines.last().contains("-2,dequeue,599,0;-1,refill,0,960"))
        assertEquals(65, lines.size)
        assertTrue(lines.all { it.length < 2000 })
    }

    @Test fun throttledUnderrunsAreRetainedAndNoEventProducesNoLog() {
        val history = AudioEventHistory()
        history.start(0)
        assertTrue(history.dump(0, 0).isEmpty())
        assertTrue(history.dump(1000, 1).first().contains("native_delta=1"))
        assertTrue(history.dump(500000000, 3).isEmpty())
        assertTrue(history.dump(1000001000, 3).first().contains("native_delta=2"))
        assertTrue(history.dump(2000001000, 3).isEmpty())
    }
}
