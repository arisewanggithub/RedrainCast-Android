package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiagnosticAudioTimingTest {
    @Test fun ownerIntervalsPreserveReentrantIdentityAndCaptureBoundaries() {
        val lock = Any()
        val first = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true, ownerEnabled = true)
        val second = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true, ownerEnabled = true)
        val tid = Thread.currentThread().id
        synchronized(lock) {
            first.ownerInterval(3) {
                synchronized(lock) {
                    first.ownerInterval(3) { assertTrue(Thread.holdsLock(lock)) }
                }
                first.close()
                second.ownerInterval(4) { assertTrue(Thread.holdsLock(lock)) }
            }
        }
        val oldEvents = first.drain(8)
        assertEquals(listOf(30, 30, 31), oldEvents.map { it.type })
        assertTrue(oldEvents.all { it.value1 == tid })
        assertEquals(oldEvents[1].value2, oldEvents[2].value2)
        assertNotEquals(oldEvents[0].value2, oldEvents[1].value2)
        assertEquals(1L, first.droppedBeforeSequence())
        val newEvents = second.drain(8)
        assertEquals(listOf(30, 31), newEvents.map { it.type })
        assertEquals(newEvents[0].value2, newEvents[1].value2)
        assertTrue(newEvents.all { it.generation == 4 && it.value1 == tid })
    }

    @Test fun fullBufferReportsOwnerBoundaryLossRatherThanGuessing() {
        val timing = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true, ownerEnabled = true)
        repeat(DiagnosticAudioTiming.CAPACITY) { timing.record(1, 1, 0, 1, 0, 0) }
        val span = timing.ownerBegin(1)
        timing.ownerEnd(1, span)
        assertEquals(2L, timing.dropped())
        assertTrue(timing.drain(DiagnosticAudioTiming.CAPACITY).none { it.type >= 30 })
    }

    @Test fun ownerEvidenceIsDisabledWithoutExplicitSelection() {
        val timing = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true)
        val span = timing.ownerBegin(1)
        timing.ownerEnd(1, span)
        assertEquals(0L, timing.attempted())
    }

    @Test fun ownerIntervalOverlapsCallbackWaitWithoutInferringItsCause() {
        val timing = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true, ownerEnabled = true)
        val lock = Any()
        val waiting = CountDownLatch(1)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val holder = Thread.currentThread().id
        synchronized(lock) {
            timing.ownerInterval(5) {
                Thread {
                    try {
                        timing.callbackLock(lock, 5, {
                            val now = System.nanoTime()
                            waiting.countDown()
                            now
                        }, { 0L }, around = { action -> timing.ownerInterval(5, action) }) {
                            assertTrue(Thread.holdsLock(lock))
                        }
                    } catch (failure: Throwable) {
                        error.set(failure)
                    } finally {
                        done.countDown()
                    }
                }.start()
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        error.get()?.let { throw it }
        val events = timing.drain(16)
        val held = events.filter { it.type == DiagnosticAudioTiming.OWNER_BEGIN && it.value1 == holder }.single()
        val released = events.single { it.type == DiagnosticAudioTiming.OWNER_END && it.value2 == held.value2 }
        val waitStart = events.first { it.type == DiagnosticAudioTiming.WORK_BEGIN &&
            it.value1 == DiagnosticAudioTiming.CALLBACK.toLong() }
        assertTrue(waitStart.monoNs in held.monoNs..released.monoNs)
        assertTrue(events.any { it.type == DiagnosticAudioTiming.OWNER_BEGIN && it.value1 != holder })
    }

    @Test fun retirementDuringOtherThreadsHoldLeavesUnmatchedBegin() {
        val timing = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true, ownerEnabled = true)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        Thread {
            try {
                synchronized(Any()) {
                    timing.ownerInterval(2) {
                        acquired.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(acquired.await(5, TimeUnit.SECONDS))
        timing.close()
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        error.get()?.let { throw it }
        assertEquals(listOf(DiagnosticAudioTiming.OWNER_BEGIN), timing.drain(8).map { it.type })
        assertEquals(1L, timing.droppedBeforeSequence())
    }

    @Test fun detailRequiresSelectionAndRetainsPrePressureEvents() {
        val legacy = DiagnosticAudioTiming(workEnabled = true)
        legacy.record(1, 1, 1, DiagnosticAudioTiming.WORK_BEGIN, DiagnosticAudioTiming.DECODE.toLong(), 1)
        legacy.record(2, 1, 1, DiagnosticAudioTiming.QUEUE_DETAIL, 2, 3)
        assertEquals(0L, legacy.attempted())
        val detail = DiagnosticAudioTiming(workEnabled = true, detailEnabled = true)
        detail.record(1, 1, 1, DiagnosticAudioTiming.QUEUE_DETAIL, 100, 50)
        detail.pressure(2, 1, 1, 0)
        detail.record(3, 1, 1, DiagnosticAudioTiming.OUTPUT_DETAIL, 4800, 0)
        assertEquals(listOf(1L, 3L), detail.drain(8).map { it.monoNs })
        assertEquals(2L, detail.firstPressure()?.monoNs)
        assertEquals(0L, detail.dropped())
    }

    @Test fun concurrentCallbackWaitIsRecordedBeforeAcquisitionAndKeepsEpochIdentity() {
        val timing = DiagnosticAudioTiming(workEnabled = true)
        val lock = Any()
        val requested = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val wall = java.util.concurrent.atomic.AtomicLong()
        val failure = AtomicReference<Throwable?>()
        synchronized(lock) {
            Thread {
                try {
                    timing.callbackLock(lock, 8, {
                        if (wall.incrementAndGet() == 1L) requested.countDown()
                        wall.get()
                    }, { 0L }) { assertTrue(Thread.holdsLock(lock)) }
                } catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }.start()
            assertTrue(requested.await(5, TimeUnit.SECONDS))
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        val events = timing.drain(8)
        assertEquals(listOf(8L, 8L, 8L, 8L), events.map { it.value1 })
        assertTrue(events.all { it.generation == 8 && it.value2 == 0L })
    }

    @Test fun callbackLockSplitsAcquisitionAndCriticalSectionWithoutReadingClocksWhenDisabled() {
        val lock = Any()
        val disabled = DiagnosticAudioTiming()
        assertEquals(7, disabled.callbackLock(lock, 2, { error("wall read") }, { error("CPU read") }) {
            assertTrue(Thread.holdsLock(lock))
            7
        })
        val timing = DiagnosticAudioTiming(workEnabled = true)
        var wall = 0L
        var cpu = 0L
        timing.callbackLock(lock, 3, { wall += 10; wall }, { ++cpu }) {
            assertTrue(Thread.holdsLock(lock))
        }
        val events = timing.drain(8)
        assertEquals(listOf(22, 23, 22, 23), events.map { it.type })
        assertEquals(listOf(8L, 8L, 8L, 8L), events.map { it.value1 })
        assertEquals(listOf(10L, 20L, 30L, 40L), events.map { it.monoNs })
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.value2 })
        assertTrue(events.all { it.generation == 3 && it.packetId == 0L })
        assertThrows(IllegalStateException::class.java) {
            timing.callbackLock(lock, 4, { ++wall }, { ++cpu }) { error("critical failure") }
        }
        assertEquals(DiagnosticAudioTiming.WORK_FAILED, timing.drain(8).last().type)
    }

    @Test fun legacyBufferIgnoresUnselectedWorkWithoutConsumingSequencesOrLossBudget() {
        val timing = DiagnosticAudioTiming()
        timing.record(1, 1, 7, DiagnosticAudioTiming.RECEIVED, 100, 4)
        timing.record(2, 1, 7, DiagnosticAudioTiming.WORK_BEGIN, DiagnosticAudioTiming.START_GATE.toLong(), 0)
        timing.record(3, 1, 7, DiagnosticAudioTiming.WORK_END, DiagnosticAudioTiming.START_GATE.toLong(), 1)
        timing.record(4, 1, 0, DiagnosticAudioTiming.OUTPUT_PROGRESS, 10, 5)
        val events = timing.drain(8)
        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals(listOf(DiagnosticAudioTiming.RECEIVED, DiagnosticAudioTiming.OUTPUT_PROGRESS),
            events.map { it.type })
        assertEquals(2L, timing.attempted())
        assertEquals(0L, timing.dropped())
        assertEquals(0L, timing.droppedBeforeSequence())
    }

    @Test fun boundedBufferCountsDroppedAttemptsAndPreservesEvents() {
        val timing = DiagnosticAudioTiming()
        repeat(DiagnosticAudioTiming.CAPACITY + 3) {
            timing.record(it.toLong(), 2, it.toLong(), 1, 4, 5)
        }
        assertEquals(3L, timing.dropped())
        assertEquals((DiagnosticAudioTiming.CAPACITY + 3).toLong(), timing.attempted())
        val events = timing.drain(DiagnosticAudioTiming.CAPACITY)
        assertEquals(DiagnosticAudioTiming.CAPACITY, events.size)
        assertEquals((1L..DiagnosticAudioTiming.CAPACITY.toLong()).toList(), events.map { it.sequence })
        timing.record(7, 3, 9, 2, 10, 11)
        assertEquals(DiagnosticAudioTiming.CAPACITY + 4L, timing.drain(1).single().sequence)
    }

    @Test fun pressureSurvivesFullBufferAndIsClaimedOnlyOnce() {
        val timing = DiagnosticAudioTiming()
        repeat(DiagnosticAudioTiming.CAPACITY) { timing.record(1, 1, 1, 1, 1, 1) }
        timing.pressure(2, 11, 3, 4)
        timing.pressure(3, 11, 4, 5)
        assertEquals(DiagnosticAudioTiming.Pressure(2, 11, 3, 4), timing.firstPressure())
    }

    @Test fun packetIdentifiersRemainDistinctAcrossGenerationsAndDroppedEvents() {
        val timing = DiagnosticAudioTiming()
        val first = timing.packetId()
        repeat(DiagnosticAudioTiming.CAPACITY + 1) { timing.record(1, 1, first, 1, 0, 0) }
        val second = timing.packetId()
        assertTrue(second > first)
        assertEquals(1L, timing.dropped())
    }

    @Test fun limitedDrainsMakeProgressPastFrequentlyReusedSlots() {
        val timing = DiagnosticAudioTiming()
        repeat(DiagnosticAudioTiming.CAPACITY) { timing.record(1, 1, 1, 1, 0, 0) }
        assertEquals(1L, timing.drain(1).single().sequence)
        timing.record(2, 1, 1, 1, 0, 0)
        assertEquals(2L, timing.drain(1).single().sequence)
    }

    @Test fun closingFencesNewEventsAndReportsUnsequencedDrops() {
        val timing = DiagnosticAudioTiming()
        timing.record(1, 1, 1, 1, 0, 0)
        timing.close()
        timing.record(2, 1, 1, 1, 0, 0)
        assertEquals(0L, timing.packetId())
        assertEquals(0L, timing.pendingProducers())
        assertEquals(2L, timing.droppedBeforeSequence())
        assertEquals(1L, timing.attempted())
        assertEquals(1L, timing.drain(1).single().sequence)
    }

    @Test fun shortenedLeaseRejectsLateEventsAndPressureWithoutAllocatingSequence() {
        val timing = DiagnosticAudioTiming(100)
        timing.shortenDeadline(50)
        timing.record(49, 1, 1, 1, 0, 0)
        timing.record(50, 1, 1, 1, 0, 0)
        timing.pressure(50, 1, 1, 1)
        assertEquals(1L, timing.attempted())
        assertEquals(2L, timing.droppedBeforeSequence())
        assertNull(timing.firstPressure())
    }

    @Test fun concurrentCloseAccountsForAllProducerAttempts() {
        val timing = DiagnosticAudioTiming()
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { generation ->
            Thread {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(400) { timing.record(it.toLong(), generation + 1, 1, 1, 0, 0) }
                } finally { done.countDown() }
            }.start()
        }
        start.countDown()
        timing.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(0L, timing.pendingProducers())
        assertEquals(1600L, timing.attempted() + timing.droppedBeforeSequence())
        assertEquals(timing.attempted(), timing.drain(DiagnosticAudioTiming.CAPACITY).size + timing.dropped())
    }

    @Test fun concurrentProducersKeepSuccessfulRecordsAndLossAccounted() {
        val timing = DiagnosticAudioTiming()
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val failure = AtomicReference<Throwable?>()
        repeat(4) { producer ->
            Thread {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(400) { timing.record(producer.toLong(), producer, it.toLong(), 1, 2, 3) }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        val events = timing.drain(DiagnosticAudioTiming.CAPACITY)
        assertEquals(1600L, events.size + timing.dropped())
        assertEquals(events.size, events.map { it.sequence }.toSet().size)
        assertTrue(events.all { it.monoNs == it.generation.toLong() })
    }
}
