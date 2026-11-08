package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class CaptureFramePacingTest {
    @Test fun uncappedAdmitsEveryNewTimestampWithoutDivisionOrASixtyFpsThrottle() {
        val start = 1_000_000_000L
        assertTrue(ProjectionRenderer.frameDue(start, 0, 0))
        for (delta in listOf(1L, 4_166_666L, 8_333_333L, 16_666_666L)) {
            assertTrue(ProjectionRenderer.frameDue(start + delta, start, 0))
        }
        assertFalse(ProjectionRenderer.frameDue(0, 0, 0))
        assertFalse(ProjectionRenderer.frameDue(start, start, 0))
        assertFalse(ProjectionRenderer.frameDue(start - 1, start, 0))
    }

    @Test fun explicitCapsEnforceTheirExactTimestampThresholds() {
        val start = 1_000_000_000L
        for (fps in listOf(15, 24, 25, 30, 47, 48, 50, 60, 90, 120, 240)) {
            val interval = 1_000_000_000L / fps
            assertTrue(ProjectionRenderer.frameDue(start, 0, fps))
            assertFalse(ProjectionRenderer.frameDue(start + interval - 1, start, fps))
            assertTrue(ProjectionRenderer.frameDue(start + interval, start, fps))
            assertFalse(ProjectionRenderer.frameDue(start, start, fps))
            assertFalse(ProjectionRenderer.frameDue(start - 1, start, fps))
        }
    }
}
