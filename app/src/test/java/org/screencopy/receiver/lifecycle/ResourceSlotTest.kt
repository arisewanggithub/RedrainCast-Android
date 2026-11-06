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
