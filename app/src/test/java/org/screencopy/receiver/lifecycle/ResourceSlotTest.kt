package org.screencopy.receiver.lifecycle

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.TrackWorker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ResourceSlotTest {
    @Test fun surfaceCallbackRetiresRendererEvenWhenTheActivityWorkQueueIsBlocked() {
        val blocked = CountDownLatch(1)
        val allowQueuedCleanup = CountDownLatch(1)
        val background = ReceiverWork.executor.submit {
            blocked.countDown()
            check(allowQueuedCleanup.await(3, TimeUnit.SECONDS))
        }
        val slot = ResourceSlot<Renderer>()
        try {
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            val renderer = slot.replace { Renderer().also { it.start() } }
            assertTrue(renderer.drawing.await(3, TimeUnit.SECONDS))
            slot.close()
            assertEquals(1, renderer.released.get())
            assertTrue(slot.isClosed)
            assertFalse(background.isDone)
            slot.release(renderer)
            slot.close()
            assertEquals(1, renderer.released.get())
        } finally {
            allowQueuedCleanup.countDown()
            slot.close()
            background.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun surfaceDestructionWaitsForInFlightDecoderConstructionAndReleasesItExactlyOnce() {
        val slot = ResourceSlot<Renderer>()
        val constructing = CountDownLatch(1)
        val allowConstruction = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val configured = threads.submit<Renderer> {
                slot.replace {
                    constructing.countDown()
                    check(allowConstruction.await(3, TimeUnit.SECONDS))
                    Renderer().also { it.start() }
                }
            }
            assertTrue(constructing.await(3, TimeUnit.SECONDS))
            val destruction = threads.submit {
                closing.countDown()
                slot.close()
            }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            allowConstruction.countDown()
            destruction.get(3, TimeUnit.SECONDS)
            val renderer = configured.get(3, TimeUnit.SECONDS)
            assertEquals(1, renderer.released.get())
            slot.release(renderer)
            assertEquals(1, renderer.released.get())
        } finally {
            allowConstruction.countDown()
            slot.close()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun staleSurfaceCannotConfigureAnotherDecoder() {
        val slot = ResourceSlot<Renderer>()
        val constructed = AtomicBoolean(false)
        slot.close()
        assertThrows(IllegalStateException::class.java) {
            slot.replace { constructed.set(true); Renderer() }
        }
        assertFalse(constructed.get())
    }

    @Test fun closedSurfaceRejectsOwnershipCommits() {
        val slot = ResourceSlot<Renderer>()
        val commits = AtomicInteger()
        assertTrue(slot.whileOpen { commits.incrementAndGet() })
        slot.close()
        assertFalse(slot.whileOpen { commits.incrementAndGet() })
        assertEquals(1, commits.get())
    }

    @Test fun closureCannotPublishInsideAnAdmittedOwnershipCommit() {
        val slot = ResourceSlot<Renderer>()
        val committing = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = Executors.newFixedThreadPool(2)
        try {
            val commit = threads.submit {
                assertTrue(slot.whileOpen {
                    committing.countDown()
                    check(finishCommit.await(3, TimeUnit.SECONDS))
                    assertFalse(slot.isClosed)
                    order.add("READY")
                })
            }
            assertTrue(committing.await(2, TimeUnit.SECONDS))
            val close = threads.submit {
                closing.countDown()
                slot.close()
                order.add("CLOSED")
            }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertFalse(slot.isClosed)
            finishCommit.countDown()
            commit.get(3, TimeUnit.SECONDS)
            close.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("READY", "CLOSED"), order.toList())
            assertFalse(slot.whileOpen { fail("Closed lifetime admitted a commit") })
        } finally {
            finishCommit.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS))
            slot.close()
        }
    }

    @Test fun closedConstructionAdmissionDoesNotInvokeFactoryOrHideFactoryFailures() {
        val closed = ResourceSlot<Renderer>()
        closed.close()
        assertNull(closed.replaceIfOpen { fail("Closed lifetime invoked codec factory"); Renderer() })
        val open = ResourceSlot<Renderer>()
        val failure = IllegalStateException("Codec failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            open.replaceIfOpen { throw failure }
        })
        open.close()
    }

    private class Renderer : TrackWorker("test-renderer", 1024, { code, detail -> throw AssertionError("$code: $detail") }) {
        val drawing = CountDownLatch(1)
        val released = AtomicInteger()
        private val stopDrawing = CountDownLatch(1)
        override fun step() {
            drawing.countDown()
            stopDrawing.await()
        }
        override fun unblock() { stopDrawing.countDown() }
        override fun release() { released.incrementAndGet() }
    }
}
