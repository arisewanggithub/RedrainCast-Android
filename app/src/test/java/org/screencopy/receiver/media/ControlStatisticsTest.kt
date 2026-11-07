package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class ControlStatisticsTest {
    @Test fun onlyAMatchingLocallySentNonceMeasuresRttAndDuplicatePongCannotResample() {
        val control = ControlStatistics(100)
        assertNull(control.snapshot(200).rttMs)
        assertEquals(100L, control.snapshot(200).lastValidControlAgeMs)
        control.validControl(200)
        control.pong(123, 201)
        assertNull(control.snapshot(201).rttMs)
        control.sentPing(41, 300)
        control.pong(42, 350)
        assertNull(control.snapshot(350).rttMs)
        control.pong(41, 375)
        assertEquals(75L, control.snapshot(400).rttMs)
        control.pong(41, 500)
        assertEquals(75L, control.snapshot(500).rttMs)
        control.sentPing(43, 600)
        control.pong(41, 650)
        control.pong(43, 675)
        assertEquals(75L, control.snapshot(700).rttMs)
    }
}
