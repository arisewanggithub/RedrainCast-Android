package org.screencopy.receiver.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Type
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ControlWriterEvidenceTest {
    @Test fun reservedTerminalCancelsFullQueueBeforePreparationWithoutHoldingWriterMonitor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val cancelled = CopyOnWriteArrayList<Message>()
        val writes = CopyOnWriteArrayList<Type>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it.type)
            if (it.type == Type.STOP) stopped.countDown()
        }, { fail(it) }, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, observed = { message, boundary, _, _, _ ->
            if (boundary == ControlWriter.Boundary.CANCELLED) cancelled.add(message)
        })
        try {
            writer.offer(Message(Type.PING, payload = byteArrayOf()))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(64) { writer.offer(Message(Type.STREAM_READY, track = 1, generation = 1, payload = byteArrayOf())) }
            writer.offerTelemetry(Message(Type.STATUS, payload = byteArrayOf()))
            assertTrue(writer.offerTerminalTransaction(Type.STOP, null,
                System.nanoTime() / 1_000_000 + 5000, { Message(Type.STOP, payload = byteArrayOf()) },
                prepareFinal = { discarded ->
                    assertTrue(discarded)
                    assertEquals(64, cancelled.count { it.type == Type.STREAM_READY })
                    val admission = java.util.concurrent.CompletableFuture.supplyAsync {
                        writer.offer(Message(Type.PONG, payload = byteArrayOf()))
                        writer.offerTelemetry(Message(Type.STATUS, payload = byteArrayOf()))
                    }.get(1, TimeUnit.SECONDS)
                    assertEquals(ControlWriter.TelemetryAdmission.REJECTED, admission)
                    Message(Type.STATUS, payload = byteArrayOf())
                }))
            release.countDown()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(Type.PING, Type.STATUS, Type.STOP), writes.toList())
        } finally { release.countDown(); writer.close(); socket.close() }
    }

    @Test fun fatalSupersedesReservedTerminalBeforeFinalPreparationCompletes() {
        val fatal = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it.type)
            if (it.type == Type.ERROR) fatal.countDown()
        }, { fail(it) }, writeFrame = { _, _ -> })
        try {
            assertFalse(writer.offerTerminalTransaction(Type.STOP, null,
                System.nanoTime() / 1_000_000 + 5000, { Message(Type.STOP, payload = byteArrayOf()) },
                prepareFinal = {
                    writer.offer(Message.json(Type.ERROR, JSONObject().put("fatal", true)))
                    Message(Type.STATUS, payload = byteArrayOf())
                }))
            assertTrue(fatal.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(Type.ERROR), writes.toList())
        } finally { writer.close(); socket.close() }
    }

    @Test fun fatalFinalWriteTimeoutFailsClosedWithoutAppendingErrorToPartialFrame() {
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val closeCallback = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val writes = CopyOnWriteArrayList<Type>()
        val socket = object : Socket() {
            override fun close() { super.close(); closed.countDown() }
        }
        val writer = ControlWriter(socket, { writes.add(it.type) }, { failed.countDown() },
            writeFrame = { _, message ->
                if (message.type == Type.STATUS) {
                    check(closed.await(3, TimeUnit.SECONDS))
                    throw java.io.IOException("partial final write interrupted")
                }
            }, closeTransport = {
                closeCallback.countDown()
                socket.close()
            })
        try {
            assertTrue(writer.offerTerminalTransaction(Type.ERROR, Message(Type.STATUS, payload = byteArrayOf()),
                System.nanoTime() / 1_000_000 + 1000,
                { Message.json(Type.ERROR, JSONObject().put("code", "AUDIO_FAILED")
                    .put("message", "original failure").put("fatal", true), 7) }, results::add, bounded = true))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(0L, closeCallback.count)
            assertEquals(listOf(false), results.toList())
            assertTrue(writes.isEmpty())
            assertTrue(socket.isClosed)
        } finally { socket.close(); writer.close() }
    }

    @Test fun finalTransactionCarriesTelemetryAndMatchingTerminalOverWire() {
        val collector = dev.redrain.cast.diagnostics.SessionTelemetryCollector { 123 }
        collector.event(dev.redrain.cast.diagnostics.TelemetryRow(1, dev.redrain.cast.diagnostics.DiagnosticStage.PRESSURE.ordinal, 1, 1, 123, 42, listOf(0, 0, 0, 0, 1)),
            pressure = true)
        val output = java.io.ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val socket = Socket()
        val writer = ControlWriter(socket, { if (it.type == Type.SESSION_END) done.countDown() }, { fail(it) },
            writeFrame = { _, message -> org.screencopy.receiver.protocol.Wire.write(output, message) })
        try {
            val final = Message(Type.STATUS, session = 7, payload = collector.publish(final = true))
            assertTrue(writer.offerTerminalTransaction(Type.SESSION_END, final,
                System.nanoTime() / 1_000_000 + 5000,
                { delivered -> Message.json(Type.SESSION_END, JSONObject().put("reason", "stop")
                    .put("finalSeq", if (delivered) "1" else JSONObject.NULL), 7) }))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val input = java.io.ByteArrayInputStream(output.toByteArray())
            val status = dev.screencopy.sender.Wire.read(input)
            val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(status.payload)
            assertTrue(batch.final)
            assertEquals(42, batch.firstPressure!!.value)
            val terminal = dev.screencopy.sender.Wire.read(input)
            assertEquals(dev.screencopy.sender.Wire.SESSION_END, terminal.type)
            assertEquals(batch.sequence.toString(), terminal.json().getString("finalSeq"))
            assertEquals(-1, input.read())
        } finally { writer.close(); socket.close() }
    }

    @Test fun terminalTransactionWritesFinalImmediatelyBeforeEitherTerminal() {
        for (type in listOf(Type.STOP, Type.SESSION_END, Type.ERROR)) {
            val sent = CountDownLatch(1)
            val writes = CopyOnWriteArrayList<Type>()
            val results = CopyOnWriteArrayList<Boolean>()
            val socket = Socket()
            val writer = ControlWriter(socket, {
                writes.add(it.type)
                if (it.type == type) sent.countDown()
            }, { fail(it) }, writeFrame = { _, _ -> })
            try {
                assertTrue(writer.offerTerminalTransaction(type, Message(Type.STATUS, payload = byteArrayOf(1)),
                    System.nanoTime() / 1_000_000 + 5000,
                    { delivered -> Message(type, payload = byteArrayOf(if (delivered) 1 else 0)) }, results::add))
                assertTrue(sent.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(Type.STATUS, type), writes.toList())
                assertEquals(listOf(true), results.toList())
            } finally { writer.close(); socket.close() }
        }
    }

    @Test fun expiredUnstartedFinalIsSkippedButTerminalStillWrites() {
        val sent = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val results = CopyOnWriteArrayList<Boolean>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it.type)
            if (it.type == Type.STOP) sent.countDown()
        }, { fail(it) }, writeFrame = { _, _ -> }, clock = { 10 })
        try {
            assertTrue(writer.offerTerminalTransaction(Type.STOP, Message(Type.STATUS, payload = byteArrayOf()),
                10, { delivered -> Message(Type.STOP, payload = byteArrayOf(if (delivered) 1 else 0)) }, results::add))
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Type.STOP), writes.toList())
            assertEquals(listOf(false), results.toList())
        } finally { writer.close(); socket.close() }
    }

    @Test fun startedFinalTimeoutClosesSocketWithoutWritingTerminal() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = object : Socket() {
            override fun close() { closed.countDown(); super.close() }
        }
        val writer = ControlWriter(socket, { writes.add(it.type) }, { failed.countDown() }, writeFrame = { _, message ->
            if (message.type == Type.STATUS) {
                started.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                throw java.io.IOException("socket closed during frame")
            }
        })
        try {
            assertTrue(writer.offerTerminalTransaction(Type.STOP, Message(Type.STATUS, payload = byteArrayOf()),
                System.nanoTime() / 1_000_000 + 5000,
                { Message(Type.STOP, payload = byteArrayOf()) }))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(writes.isEmpty())
        } finally { socket.close(); writer.close() }
    }

    @Test fun boundedTerminalTimeoutClosesSocketWithoutReportingSuccess() {
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = object : Socket() {
            override fun close() { closed.countDown(); super.close() }
        }
        val writer = ControlWriter(socket, { writes.add(it.type) }, { failed.countDown() }, writeFrame = { _, message ->
            if (message.type == Type.STOP) {
                check(closed.await(3, TimeUnit.SECONDS))
                throw java.io.IOException("terminal interrupted")
            }
        })
        try {
            assertTrue(writer.offerTerminalTransaction(Type.STOP, null,
                System.nanoTime() / 1_000_000 + 1000, { Message(Type.STOP, payload = byteArrayOf()) },
                bounded = true))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertTrue(writes.isEmpty())
        } finally { socket.close(); writer.close() }
    }

    @Test fun boundedTransactionClosesAControlWriteAlreadyInFlight() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = object : Socket() {
            override fun close() { closed.countDown(); super.close() }
        }
        val writer = ControlWriter(socket, { writes.add(it.type) }, { failed.countDown() }, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(closed.await(3, TimeUnit.SECONDS))
                throw java.io.IOException("control interrupted")
            }
        })
        try {
            writer.offer(Message(Type.PING, payload = byteArrayOf()))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(writer.offerTerminalTransaction(Type.STOP, null,
                System.nanoTime() / 1_000_000 + 1000, { Message(Type.STOP, payload = byteArrayOf()) },
                bounded = true))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertTrue(writes.isEmpty())
        } finally { socket.close(); writer.close() }
    }

    @Test fun stoppingDuringStartedPeriodicTelemetryClosesOnDeadlineWithoutTerminal() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val writes = CopyOnWriteArrayList<Type>()
        val socket = object : Socket() {
            override fun close() { closed.countDown(); super.close() }
        }
        val writer = ControlWriter(socket, { writes.add(it.type) }, { failed.countDown() }, writeFrame = { _, message ->
            if (message.type == Type.STATUS) {
                started.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                throw java.io.IOException("socket closed during frame")
            }
        })
        try {
            assertEquals(ControlWriter.TelemetryAdmission.ACCEPTED,
                writer.offerTelemetry(Message(Type.STATUS, payload = byteArrayOf())))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(writer.offerTerminalTransaction(Type.STOP, Message(Type.STATUS, payload = byteArrayOf()),
                System.nanoTime() / 1_000_000 + 5000,
                { Message(Type.STOP, payload = byteArrayOf()) }, results::add))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(false), results.toList())
            assertTrue(writes.isEmpty())
        } finally { socket.close(); writer.close() }
    }

    @Test fun fatalErrorSupersedesTerminalAfterStartedFinalCompletes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fatalSent = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it.type)
            if (it.type == Type.ERROR) fatalSent.countDown()
        }, { fail(it) }, writeFrame = { _, message ->
            if (message.type == Type.STATUS) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        try {
            assertTrue(writer.offerTerminalTransaction(Type.STOP, Message(Type.STATUS, payload = byteArrayOf()),
                System.nanoTime() / 1_000_000 + 5000,
                { Message(Type.STOP, payload = byteArrayOf()) }))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            writer.offer(Message.json(Type.ERROR, JSONObject().put("fatal", true)))
            release.countDown()
            assertTrue(fatalSent.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Type.STATUS, Type.ERROR), writes.toList())
        } finally { release.countDown(); writer.close(); socket.close() }
    }

    @Test fun telemetryIsReplaceableAndDoesNotConsumeOrdinaryQueueCapacity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(65)
        val writes = CopyOnWriteArrayList<Message>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it)
            if (it.type != Type.PING || it.payload.isNotEmpty()) completed.countDown()
        }, { fail(it) }, writeFrame = { _, message ->
            if (message.type == Type.PING && message.payload.isEmpty()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        try {
            writer.offer(Message(Type.PING, payload = byteArrayOf()))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(ControlWriter.TelemetryAdmission.ACCEPTED,
                writer.offerTelemetry(Message(Type.STATUS, payload = ByteArray(8192) { 1 })))
            val payload = byteArrayOf(2)
            assertEquals(ControlWriter.TelemetryAdmission.REPLACED,
                writer.offerTelemetry(Message(Type.STATUS, payload = payload)))
            payload[0] = 3
            repeat(64) { writer.offer(Message(Type.PONG, payload = byteArrayOf(1))) }
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(List(64) { Type.PONG } + Type.STATUS, writes.drop(1).map { it.type })
            assertArrayEquals(byteArrayOf(2), writes.last().payload)
        } finally { release.countDown(); writer.close(); socket.close() }
    }

    @Test fun terminalCancelsPendingTelemetryAndRejectsFutureBatches() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Type>()
        val socket = Socket()
        val writer = ControlWriter(socket, {
            writes.add(it.type)
            if (it.type == Type.STOP) stopped.countDown()
        }, { fail(it) }, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        try {
            writer.offer(Message(Type.PING, payload = byteArrayOf()))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(ControlWriter.TelemetryAdmission.ACCEPTED,
                writer.offerTelemetry(Message(Type.STATUS, payload = byteArrayOf(1))))
            writer.offer(Message(Type.STOP, payload = byteArrayOf()))
            assertEquals(ControlWriter.TelemetryAdmission.REJECTED,
                writer.offerTelemetry(Message(Type.STATUS, payload = byteArrayOf(2))))
            release.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Type.PING, Type.STOP), writes.toList())
        } finally { release.countDown(); writer.close(); socket.close() }
    }

    @Test fun telemetryAdmissionEnforcesTypeAndByteLimit() {
        val socket = Socket()
        val writer = ControlWriter(socket, {}, { fail(it) }, writeFrame = { _, _ -> })
        try {
            assertThrows(IllegalArgumentException::class.java) {
                writer.offerTelemetry(Message(Type.STATUS, payload = ByteArray(8193)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                writer.offerTelemetry(Message(Type.PONG, payload = byteArrayOf()))
            }
        } finally { writer.close(); socket.close() }
    }

    @Test fun stopDrainsInitialReadyThroughActualEvidenceCallbackWithoutSchedulerCancellation() {
        val capture = dev.redrain.cast.diagnostics.ReceiverTelemetrySession({ true })
        dev.redrain.cast.diagnostics.TelemetryRegistry.beginReceiver(992, capture)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = Socket()
        val writer = ControlWriter(socket, {}, { fail(it) }, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, observed = { message, boundary, _, _, _ -> ControlWriter.observeReadyBoundary(message, boundary) })
        try {
            writer.offer(Message.json(Type.PING, JSONObject().put("nonce", 1), 992))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.offer(Message.json(Type.STREAM_READY, JSONObject().put("ok", true), 992, 1, 1))
            writer.offer(Message.json(Type.STOP, JSONObject().put("reason", "user"), 992))
            val final = checkNotNull(capture.requestFinal(true, System.nanoTime() + 1_000_000_000L))
            val stages = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(final.payload).events
                .filter { it.kind == dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND }.map { it.stage }
            assertTrue(dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_CANCELLED.ordinal in stages)
            assertFalse(dev.redrain.cast.diagnostics.IncidentEvidence.Stage.RECOVERY_CANCELLED.ordinal in stages)
            assertFalse(dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_WRITTEN.ordinal in stages)
        } finally {
            release.countDown()
            writer.close()
            socket.close()
            dev.redrain.cast.diagnostics.TelemetryRegistry.end("receiver", capture)
            capture.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun failedReadyWriteHasNoSuccessfulWrittenBoundary() {
        val failed = CountDownLatch(1)
        val boundaries = CopyOnWriteArrayList<ControlWriter.Boundary>()
        val socket = Socket()
        val writer = ControlWriter(socket, { fail("Unexpected successful write") }, { failed.countDown() },
            writeFrame = { _, _ -> throw java.io.IOException() },
            observed = { _, boundary, _, _, _ -> boundaries.add(boundary) })
        try {
            writer.offer(Message.json(Type.STREAM_READY, JSONObject().put("ok", true), 1, 2, 12))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(ControlWriter.Boundary.ENQUEUED, ControlWriter.Boundary.WRITING,
                ControlWriter.Boundary.FAILED), boundaries.toList())
        } finally { writer.close(); socket.close() }
    }

    @Test fun queuedReadyIsNotReportedWrittenWhilePreviousControlWriteIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readyWritten = CountDownLatch(1)
        val boundaries = CopyOnWriteArrayList<ControlWriter.Boundary>()
        val failures = CopyOnWriteArrayList<String>()
        val socket = Socket()
        val writer = ControlWriter(socket, {}, failures::add, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, observed = { message, boundary, _, _, _ ->
            if (message.type == Type.STREAM_READY) {
                boundaries.add(boundary)
                if (boundary == ControlWriter.Boundary.WRITTEN) readyWritten.countDown()
            }
        })
        try {
            writer.offer(Message.json(Type.PING, JSONObject().put("nonce", 1), 1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.offer(Message.json(Type.STREAM_READY, JSONObject().put("ok", true), 1, 2, 12))
            assertEquals(listOf(ControlWriter.Boundary.ENQUEUED), boundaries.toList())
            release.countDown()
            assertTrue(readyWritten.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(ControlWriter.Boundary.ENQUEUED, ControlWriter.Boundary.WRITING,
                ControlWriter.Boundary.WRITTEN), boundaries.toList())
            assertTrue(failures.isEmpty())
        } finally { release.countDown(); writer.close(); socket.close() }
    }
}
