package org.screencopy.receiver.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionPoolTest {
    @Test fun acceptedButUnregisteredSocketIsClosedAfterShutdownWithoutStartingHandshake() {
        val pool = ConnectionPool<Socket>({ it.close() })
        val acceptor = Executors.newSingleThreadExecutor()
        val accepted = CountDownLatch(1)
        val register = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            try {
                val admission = acceptor.submit<Boolean> {
                    val socket = listener.accept()
                    accepted.countDown()
                    check(register.await(3, TimeUnit.SECONDS))
                    pool.submit(socket) { ran.set(true) }
                }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { peer ->
                    peer.soTimeout = 1000
                    assertTrue(accepted.await(3, TimeUnit.SECONDS))
                    pool.close()
                    register.countDown()
                    assertFalse(admission.get(3, TimeUnit.SECONDS))
                    assertFalse(ran.get())
                    assertEquals(-1, peer.getInputStream().read())
                    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
                }
            } finally {
                register.countDown()
                pool.close()
                acceptor.shutdownNow()
                assertTrue(acceptor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun shutdownClosesAdmittedBlockedReadsRatherThanWaitingForTheirSocketTimeout() {
        val pool = ConnectionPool<Socket>({ it.close() })
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use {
                val socket = listener.accept().apply { soTimeout = 10_000 }
                try {
                    assertTrue(pool.submit(socket) {
                        try {
                            entered.countDown()
                            socket.getInputStream().read()
                        } catch (_: java.net.SocketException) {
                            assertTrue(socket.isClosed)
                        } finally {
                            socket.close()
                            finished.countDown()
                        }
                    })
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    pool.close()
                    assertTrue(finished.await(1, TimeUnit.SECONDS))
                    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
                    assertTrue(pool.snapshot().isEmpty())
                } finally { pool.close() }
            }
        }
    }

    @Test fun registrationAndSubmissionAreAtomicWithExecutorShutdown() {
        val executing = CountDownLatch(1)
        val allowSubmission = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val executor = object : ThreadPoolExecutor(0, 1, 0, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun execute(command: Runnable) {
                executing.countDown()
                check(allowSubmission.await(3, TimeUnit.SECONDS))
                super.execute(command)
            }
        }
        val pool = ConnectionPool<Unit>({ closed.countDown() }, executor)
        val racers = Executors.newFixedThreadPool(2)
        try {
            val submit = racers.submit<Boolean> {
                pool.submit(Unit) { check(closed.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(executing.await(3, TimeUnit.SECONDS))
            val shutdown = racers.submit { pool.close() }
            allowSubmission.countDown()
            assertTrue(submit.get(3, TimeUnit.SECONDS))
            shutdown.get(3, TimeUnit.SECONDS)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
        } finally {
            allowSubmission.countDown()
            pool.close()
            racers.shutdownNow()
            assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
