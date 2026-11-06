package org.screencopy.receiver.lifecycle

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ReceiverWorkTest {
    @Test fun rapidActivityRecreationRebindsOnlyAfterPreviousListenerTeardown() {
        val first = Owner()
        first.start(0).get(3, TimeUnit.SECONDS)
        val port = checkNotNull(first.listener).localPort
        val busy = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val pending = ReceiverWork.executor.submit {
            busy.countDown()
            check(releaseWork.await(3, TimeUnit.SECONDS))
        }
        val owners = mutableListOf(first)
        val operations = mutableListOf<Future<*>>()
        try {
            assertTrue(busy.await(3, TimeUnit.SECONDS))
            var old = first
            repeat(50) {
                operations.add(old.stop())
                val recreated = Owner()
                owners.add(recreated)
                operations.add(recreated.start(port))
                old = recreated
            }
            operations.add(old.stop())
            releaseWork.countDown()
            operations.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(owners.all { it.listener == null })
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
        } finally {
            releaseWork.countDown()
            pending.get(3, TimeUnit.SECONDS)
            owners.forEach { it.stop().get(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun delayedOldCleanupCompletesBeforeFreshActivityStarts() {
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val cleanup = ReceiverWork.executor.submit {
            cleanupEntered.countDown()
            check(allowCleanup.await(3, TimeUnit.SECONDS))
        }
        try {
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            val restart = ReceiverWork.executor.submit { nextStarted.countDown() }
            assertFalse(restart.isDone)
            assertEquals(1L, nextStarted.count)
            allowCleanup.countDown()
            cleanup.get(3, TimeUnit.SECONDS)
            restart.get(3, TimeUnit.SECONDS)
            assertEquals(0L, nextStarted.count)
        } finally { allowCleanup.countDown() }
    }

    private class Owner {
        @Volatile var listener: ServerSocket? = null
        fun start(port: Int): Future<*> = ReceiverWork.executor.submit {
            listener = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
        }
        fun stop(): Future<*> = ReceiverWork.executor.submit {
            listener?.close()
            listener = null
        }
    }
}
