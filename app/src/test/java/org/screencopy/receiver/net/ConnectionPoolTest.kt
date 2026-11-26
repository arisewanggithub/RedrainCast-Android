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
    @Test fun overflowRemainsEligibleAndDiagnosticReservationSurvivesReaderRetirement() {
        val readerExited = CountDownLatch(1)
        val general = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun afterExecute(runnable: Runnable, error: Throwable?) {
                super.afterExecute(runnable, error)
                readerExited.countDown()
            }
        }
        val aborted = mutableListOf<Int>()
        val pool = ConnectionPool<Int>({ synchronized(aborted) { aborted.add(it) } }, general)
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val overflowEntered = CountDownLatch(1)
        val releaseOverflow = CountDownLatch(1)
        try {
            assertTrue(pool.submit(1) { readerEntered.countDown(); releaseReader.await() })
            assertTrue(readerEntered.await(3, TimeUnit.SECONDS))
            val reservation = checkNotNull(pool.reclassifyDiagnostic(1))
            assertEquals(0, pool.ordinaryCount())
            assertTrue(pool.submit(2) { overflowEntered.countDown(); releaseOverflow.await() })
            assertTrue(overflowEntered.await(3, TimeUnit.SECONDS))
            assertEquals(1, pool.ordinaryCount())
            assertNull(pool.reclassifyDiagnostic(2))
            assertFalse(pool.trySubmit(3) {})
            assertTrue(synchronized(aborted) { aborted.isEmpty() })
            releaseReader.countDown()
            assertTrue(readerExited.await(3, TimeUnit.SECONDS))
            assertNull(pool.reclassifyDiagnostic(2))
            assertTrue(pool.snapshot().contains(1))
            reservation.writerExited()
            assertFalse(pool.snapshot().contains(1))
            val next = checkNotNull(pool.reclassifyDiagnostic(2))
            reservation.writerExited()
            assertEquals(0, pool.ordinaryCount())
            next.writerExited()
            pool.close()
            releaseOverflow.countDown()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(0, pool.ordinaryCount())
        } finally {
            releaseReader.countDown()
            releaseOverflow.countDown()
            pool.close()
        }
    }

    @Test fun overflowWithoutDiagnosticsIsEligibleWhenGeneralDispatchIsFull() {
        val pool = ConnectionPool<Int>({}, ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue()))
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            assertTrue(pool.submit(1) { entered.countDown(); release.await() })
            assertTrue(pool.submit(2) { entered.countDown(); release.await() })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(2, pool.ordinaryCount())
            assertFalse(pool.trySubmit(3) {})
        } finally {
            release.countDown()
            pool.close()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun writerExitDoesNotReleaseAStillRunningDiagnosticReader() {
        val readerExited = CountDownLatch(1)
        val general = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun afterExecute(runnable: Runnable, error: Throwable?) {
                super.afterExecute(runnable, error)
                readerExited.countDown()
            }
        }
        val pool = ConnectionPool<Int>({}, general)
        val entered = CountDownLatch(2)
        val reader = CountDownLatch(1)
        val overflow = CountDownLatch(1)
        try {
            assertTrue(pool.submit(1) { entered.countDown(); reader.await() })
            assertTrue(pool.submit(2) { entered.countDown(); overflow.await() })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val reservation = checkNotNull(pool.reclassifyDiagnostic(1))
            reservation.writerExited()
            assertNull(pool.reclassifyDiagnostic(2))
            assertTrue(pool.snapshot().contains(1))
            reader.countDown()
            assertTrue(readerExited.await(3, TimeUnit.SECONDS))
            assertFalse(pool.snapshot().contains(1))
            checkNotNull(pool.reclassifyDiagnostic(2)).writerExited()
        } finally {
            reader.countDown()
            overflow.countDown()
            pool.close()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun shutdownAbortsDiagnosticAfterReaderExitWhileWriterIsActive() {
        val readerExited = CountDownLatch(1)
        val general = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue()) {
            override fun afterExecute(runnable: Runnable, error: Throwable?) {
                super.afterExecute(runnable, error)
                readerExited.countDown()
            }
        }
        val closed = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val socket = object : Socket() {
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) {
                    writerEntered.countDown()
                    closed.await()
                    throw java.net.SocketException("socket closed")
                }
            }
            override fun close() {
                try { super.close() } finally { closed.countDown() }
            }
        }
        val aborted = CountDownLatch(1)
        val pool = ConnectionPool<Socket>({
            it.close()
            aborted.countDown()
        }, general)
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writers = Executors.newSingleThreadExecutor()
        try {
            assertTrue(pool.submit(socket) { readerEntered.countDown(); releaseReader.await() })
            assertTrue(readerEntered.await(3, TimeUnit.SECONDS))
            val reservation = checkNotNull(pool.reclassifyDiagnostic(socket))
            val writer = writers.submit {
                try {
                    assertThrows(java.net.SocketException::class.java) { socket.outputStream.write(0) }
                } finally {
                    reservation.writerExited()
                }
            }
            assertTrue(writerEntered.await(3, TimeUnit.SECONDS))
            releaseReader.countDown()
            assertTrue(readerExited.await(3, TimeUnit.SECONDS))
            assertEquals(0, pool.ordinaryCount())
            assertTrue(pool.snapshot().contains(socket))
            assertFalse(pool.trySubmit(socket) {})
            pool.close()
            assertTrue(aborted.await(3, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            writer.get(3, TimeUnit.SECONDS)
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(pool.snapshot().isEmpty())
        } finally {
            releaseReader.countDown()
            pool.close()
            socket.close()
            writers.shutdownNow()
            assertTrue(writers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun diagnosticReclassificationStillLimitsOrdinaryCreditsTo32() {
        val pool = ConnectionPool<Int>({})
        val entered = CountDownLatch(32)
        val overflowEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            repeat(32) { id -> assertTrue(pool.submit(id) { entered.countDown(); release.await() }) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(32, pool.ordinaryCount())
            assertFalse(pool.trySubmit(32) {})
            val reservation = checkNotNull(pool.reclassifyDiagnostic(0))
            assertTrue(pool.submit(32) { overflowEntered.countDown(); release.await() })
            assertTrue(overflowEntered.await(3, TimeUnit.SECONDS))
            assertEquals(32, pool.ordinaryCount())
            assertEquals(33, pool.snapshot().size)
            assertFalse(pool.trySubmit(33) {})
            reservation.writerExited()
        } finally {
            release.countDown()
            pool.close()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertEquals(0, pool.ordinaryCount())
    }

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
