package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import dev.redrain.cast.diagnostics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioPipelineTest {
    @Test fun recoveryStartReportsActualDecodedBytesWhileDecodeReservationIsActive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = PlaybackClock({ 0L }) {
            entered.countDown()
            release.await()
        }.apply { anchorVideo(1_000_000); beginAudioRecovery() }
        val diagnostics = RemoteDiagnostics.recorder
        val telemetry = ReceiverTelemetrySession({ true })
        val errors = ConcurrentLinkedQueue<String>()
        diagnostics.grantConsent()
        diagnostics.sessionStarted("receiver", 9801)
        TelemetryRegistry.beginReceiver(9801, telemetry)
        val id = diagnostics.start("receiver", 9801, setOf("audio.output"), 10000, 1000).getString("captureId")
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { BoundedOutput(AtomicLong()) },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray) = ShortArray(1920)
                override fun close() {}
            } }).apply { recoveryCutoffUs = 4_000_000; diagnosticSession = 9801; diagnosticGeneration = 2 }
        try {
            player.start()
            player.offer(AccessUnit(4_000_000, byteArrayOf(1)))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            diagnostics.end(id)
            val records = diagnostics.fetch(id, 0, 32).getJSONArray("records")
            val decisions = (0 until records.length()).mapNotNull { index ->
                records.getJSONObject(index).getJSONObject("data").optJSONObject("metrics")
                    ?.takeIf { it.optString("schema") == "audio_start_decision" }
            }
            assertTrue(decisions.isNotEmpty())
            assertEquals(3840, decisions.first().getInt("retainedDecodedBytes"))
            assertEquals(1, decisions.first().getInt("retainedDecodedPackets"))
            TelemetryRegistry.end("receiver", telemetry)
            val batch = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            val row = batch.state.single { it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.detail.size >= 7 && it.detail[0] == DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong() &&
                it.detail[3] == 3840L }
            assertEquals(listOf(3840L, 1L, 0L, 0L), row.detail.subList(3, 7))
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            release.countDown()
            player.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
            diagnostics.revoke()
            diagnostics.sessionEnded("receiver", 9801)
        }
    }

    @Test fun rejectionDuringDecodeIncludesReservationInCapacityButNotDecodedBytes() {
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val telemetry = ReceiverTelemetrySession({ true })
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock({ 0L }) { Thread.yield() }.apply {
            anchorVideo(1_000_000); beginAudioRecovery()
        }
        TelemetryRegistry.beginReceiver(9802, telemetry)
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { BoundedOutput(AtomicLong()) },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray): ShortArray {
                    if (packet[0].toInt() == 1) {
                        decoding.countDown()
                        release.await()
                    }
                    return ShortArray(1920)
                }
                override fun close() {}
            } }).apply { recoveryCutoffUs = 4_000_000; diagnosticSession = 9802; diagnosticGeneration = 2 }
        try {
            player.start()
            player.offer(AccessUnit(4_000_000, byteArrayOf(0)))
            player.offer(AccessUnit(4_020_000, byteArrayOf(1)))
            assertTrue(decoding.await(3, TimeUnit.SECONDS))
            val encodedCapacity = 512 * 1024 - 3840 - 23040
            assertNull(player.tryOffer(AccessUnit(4_040_000, ByteArray(encodedCapacity))))
            val pressure = checkNotNull(player.tryOffer(AccessUnit(4_040_001, byteArrayOf(2))))
            assertEquals(QueueLimit.BYTES, pressure.limit)
            assertEquals(encodedCapacity, pressure.encodedBytes)
            assertEquals(3840, pressure.retainedDecodedBytes)
            assertEquals(512 * 1024, pressure.queuedBytes)
            assertEquals(512 * 1024 + 1L, pressure.attemptedBytes)
            TelemetryRegistry.end("receiver", telemetry)
            val batch = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            val row = batch.state.single { it.kind == DiagnosticKind.AUDIO_QUEUE.ordinal + 1 &&
                it.stage == DiagnosticStage.REJECTED.ordinal }
            assertEquals(listOf(encodedCapacity.toLong(), 3840L, 1L, 524289L, 1L,
                QueueLimit.BYTES.ordinal.toLong()), row.detail)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            player.halt()
            release.countDown()
            player.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun fullyTrimmedRecoveryAndCancelledDecodeDoNotRetainPhantomPcm() {
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock({ 0L }) { Thread.yield() }.apply {
            anchorVideo(1_000_000); beginAudioRecovery()
        }
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { BoundedOutput(AtomicLong()) },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray): ShortArray {
                    if (packet[0].toInt() == 1) {
                        decoding.countDown()
                        release.await()
                    }
                    return ShortArray(1920)
                }
                override fun close() {}
            } }).apply { recoveryCutoffUs = 4_000_000 }
        try {
            player.start()
            player.offer(AccessUnit(3_900_000, byteArrayOf(0)))
            player.offer(AccessUnit(4_000_000, byteArrayOf(1)))
            assertTrue(decoding.await(3, TimeUnit.SECONDS))
            val encodedCapacity = 512 * 1024 - 23040
            assertNull(player.tryOffer(AccessUnit(4_020_000, ByteArray(encodedCapacity))))
            val pressure = checkNotNull(player.tryOffer(AccessUnit(4_020_001, byteArrayOf(2))))
            assertEquals(0, pressure.retainedDecodedBytes)
            assertEquals(encodedCapacity, pressure.encodedBytes)
            assertEquals(512 * 1024, pressure.queuedBytes)
            assertEquals(524289L, pressure.attemptedBytes)
            player.halt()
            release.countDown()
            player.close()
            val retained = TrackWorker::class.java.getDeclaredField("retainedInputBytes").apply { isAccessible = true }
            assertEquals(0, retained.getInt(player))
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            release.countDown()
            player.close()
        }
    }

    @Test fun failedRecoveryDecodeReleasesItsReservation() {
        val failed = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock({ 0L }) { Thread.yield() }.apply {
            anchorVideo(1_000_000); beginAudioRecovery()
        }
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail"); failed.countDown() }, { BoundedOutput(AtomicLong()) },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray): ShortArray = throw IllegalStateException("decode failed")
                override fun close() {}
            } }).apply { recoveryCutoffUs = 4_000_000 }
        try {
            player.start()
            player.offer(AccessUnit(4_000_000, byteArrayOf(0)))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertTrue(errors.single().contains("decode failed"))
            assertNull(player.tryOffer(AccessUnit(4_020_000, ByteArray(512 * 1024))))
            val pressure = checkNotNull(player.tryOffer(AccessUnit(4_020_001, byteArrayOf(1))))
            assertEquals(0, pressure.retainedDecodedBytes)
            assertEquals(512 * 1024, pressure.encodedBytes)
            assertEquals(524289L, pressure.attemptedBytes)
        } finally { player.close() }
    }

    @Test(timeout = 15_000) fun opusRecoveryExitsWithRealisticFilterLookahead() = lookaheadRecoveryExit("opus")

    @Test(timeout = 15_000) fun pcmRecoveryExitsWithRealisticFilterLookahead() = lookaheadRecoveryExit("pcm")

    @Test(timeout = 15_000) fun continuousNewAudioDoesNotKeepRecoveryPreservationActive() =
        lookaheadRecoveryExit("opus", continuous = true)

    private fun lookaheadRecoveryExit(codec: String, continuous: Boolean = false) {
        val time = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = PlaybackClock(time::get) {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }.apply { anchorVideo(1_000_000); beginAudioRecovery() }
        val errors = ConcurrentLinkedQueue<String>()
        val written = ConcurrentLinkedQueue<Short>()
        val output = BoundedOutput(time) { samples, offset, count ->
            repeat(count) { written.add(samples[offset + it]) }
        }
        fun source(index: Int): ShortArray {
            val first = when (index) {
                0 -> 0; 1 -> 960; 2 -> 6720; 3 -> if (continuous) 12480 else 7680
                else -> (if (continuous) 18240 else 13440) + (index - 4) * 960
            }
            val frames = if (index == 1 || index == 3 || continuous && index == 2) 5760 else 960
            return ShortArray(frames * 2) { (first + it / 2 + 1).toShort() }
        }
        val player = testAudioPlayer(AudioFormat(codec, 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray) = source(packet[0].toInt())
                override fun close() {}
            } },
            resamplerFactory = { _, _ -> object : PcmResampler {
                private val held = ArrayDeque<Short>()
                override val delayFrames get() = held.size / 2
                override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray {
                    for (i in offsetFrames * 2 until (offsetFrames + frames) * 2) held.addLast(samples[i])
                    return ShortArray((held.size - 34).coerceAtLeast(0)) { held.removeFirst() }
                }
                override fun compensate(deltaFrames: Int, distanceFrames: Int) {}
                override fun close() {}
            } }).apply { recoveryCutoffUs = 4_000_000 }
        fun offer(index: Int, pts: Long) {
            val samples = source(index)
            val bytes = if (codec == "opus") byteArrayOf(index.toByte()) else
                ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    samples.forEach { putShort(it) }
                }.array()
            assertNull(player.tryOffer(AccessUnit(pts, bytes)))
        }
        val regulator = AudioPlayer::class.java.getDeclaredField("regulator").apply { isAccessible = true }
        val catchup = AudioPlayer::class.java.getDeclaredField("startupCatchup").apply { isAccessible = true }
        try {
            player.start()
            offer(0, 4_000_000)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            offer(1, 4_020_000)
            time.set(3_000_000_000)
            release.countDown()
            await { player.recoverySourceBoundary == 6720L || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(17, player.retainedSourceFrames)
            offer(2, 4_140_000)
            if (continuous) {
                await { player.processedSourceFrames >= 12480 || errors.isNotEmpty() }
                time.addAndGet(50_000_000)
                await { player.sourceSubmittedFrames >= 8160 || errors.isNotEmpty() }
            } else repeat(2) { time.addAndGet(20_000_000); await {
                player.sourceSubmittedFrames >= minOf(5760 + (it + 1) * 960L, 7663L) || errors.isNotEmpty()
            } }
            await { !player.preservingRecovery || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            if (continuous) assertTrue((regulator.get(player) as AudioRegulator).bufferedFrames > 0)
            assertEquals(17, player.retainedSourceFrames)
            val sourceSamples = written.filter { it != 0.toShort() }
            assertTrue(sourceSamples.size >= 6720 * 2)
            sourceSamples.take(6720 * 2).forEachIndexed { index, sample ->
                assertEquals("recovery sample $index", (index / 2 + 1).toShort(), sample)
            }
            if (!continuous) time.addAndGet(20_000_000)
            await { !catchup.getBoolean(player) || errors.isNotEmpty() }
            offer(3, if (continuous) 4_260_000 else 4_160_000)
            await { (regulator.get(player) as AudioRegulator).discardedFrames > 0 || errors.isNotEmpty() }
            repeat(14) { index ->
                offer(index + 4, (if (continuous) 4_380_000 else 4_280_000) + index * 20_000L)
                await { player.processedSourceFrames >= (if (continuous) 18240 else 13440) +
                    (index + 1) * 960L || errors.isNotEmpty() }
            }
            assertEquals(0, player.queuedPackets())
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { release.countDown(); player.close() }
    }

    @Test(timeout = 15_000) fun encodedRecoveryBacklogRemainsPreservedAfterFirstPacketDrains() {
        val time = AtomicLong()
        val atStartWait = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val firstSubmitted = CountDownLatch(1)
        val ringFilled = CountDownLatch(1)
        val allSubmitted = CountDownLatch(1)
        val clock = PlaybackClock(time::get) {
            atStartWait.countDown()
            check(releaseStart.await(3, TimeUnit.SECONDS))
        }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val errors = ConcurrentLinkedQueue<String>()
        val written = ConcurrentLinkedQueue<Short>()
        val writtenCount = AtomicInteger()
        val output = BoundedOutput(time) { samples, offset, count ->
            repeat(count) { written.add(samples[offset + it]) }
            val total = writtenCount.addAndGet(count)
            if (total >= 11520) ringFilled.countDown()
            if (total >= 13440) allSubmitted.countDown()
        }
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail"); allSubmitted.countDown() }, { output }, opusFactory = {
                object : OpusPacketDecoder {
                    override fun decode(packet: ByteArray): ShortArray {
                        val first = if (packet[0].toInt() == 0) 0 else 960
                        val frames = if (first == 0) 960 else 5760
                        return ShortArray(frames * 2) { (first + it / 2 + 1).toShort() }
                    }
                    override fun close() {}
                }
            }).apply {
                recoveryCutoffUs = 4_000_000
                onFirstSubmitted = { firstSubmitted.countDown() }
            }
        try {
            player.start()
            assertNull(player.tryOffer(AccessUnit(4_000_000, byteArrayOf(0))))
            assertTrue(atStartWait.await(3, TimeUnit.SECONDS))
            assertNull(player.tryOffer(AccessUnit(4_020_000, byteArrayOf(1))))
            assertEquals(1, player.queuedPackets())
            time.set(3_000_000_000)
            releaseStart.countDown()
            assertTrue(firstSubmitted.await(3, TimeUnit.SECONDS))
            assertTrue(ringFilled.await(3, TimeUnit.SECONDS))
            assertEquals(961.toShort(), written.elementAt(1920))
            time.addAndGet(20_000_000)
            assertTrue(allSubmitted.await(3, TimeUnit.SECONDS))
            player.close()
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(6720L, player.sourceSubmittedFrames)
            val samples = written.toList()
            assertTrue(samples.size >= 13440)
            samples.take(13440).forEachIndexed { index, sample ->
                assertEquals("sample $index", (index / 2 + 1).toShort(), sample)
            }
            assertTrue(samples.drop(13440).all { it == 0.toShort() })
        } finally {
            releaseStart.countDown()
            player.close()
        }
    }

    @Test(timeout = 15_000) fun maximumDurationOpusRecoveryPacketsReachOutputWithoutGapsOrReordering() =
        maximumDurationRecoveryPackets("opus")

    @Test(timeout = 15_000) fun maximumDurationRawRecoveryPacketsReachOutputWithoutGapsOrReordering() =
        maximumDurationRecoveryPackets("pcm")

    @Test(timeout = 15_000) fun followingMaximumDurationRecoveryPacketDoesNotLoseItsBeginning() =
        maximumDurationRecoveryPackets("opus", firstFrames = 960)

    private fun maximumDurationRecoveryPackets(codec: String, firstFrames: Int = 5760) {
        fun firstFrame(index: Int) = if (index == 0) 0 else firstFrames + (index - 1) * 5760
        fun frames(index: Int) = if (index == 0) firstFrames else 5760
        val totalFrames = firstFrames + 2 * 5760
        val time = AtomicLong()
        val clock = PlaybackClock(time::get) { Thread.yield() }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val errors = ConcurrentLinkedQueue<String>()
        val decoded = AtomicInteger()
        val proofs = ConcurrentLinkedQueue<Long>()
        val written = ConcurrentLinkedQueue<Short>()
        val output = BoundedOutput(time) { samples, offset, count ->
            repeat(count) { written.add(samples[offset + it]) }
        }
        val player = testAudioPlayer(AudioFormat(codec, 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output }, opusFactory = {
                object : OpusPacketDecoder {
                    override fun decode(packet: ByteArray): ShortArray {
                        val index = packet[0].toInt() and 255
                        val first = firstFrame(index)
                        decoded.incrementAndGet()
                        return ShortArray(frames(index) * 2) { (first + it / 2 + 1).toShort() }
                    }
                    override fun close() {}
                }
            }).apply {
                recoveryCutoffUs = 4_000_000
                onFirstSubmitted = proofs::add
            }
        val retained = TrackWorker::class.java.getDeclaredField("retainedInputBytes").apply { isAccessible = true }
        val pending = AudioPlayer::class.java.getDeclaredField("pending").apply { isAccessible = true }
        val following = AudioPlayer::class.java.getDeclaredField("recoveryAudio").apply { isAccessible = true }
        try {
            player.start()
            repeat(3) { index ->
                val bytes = if (codec == "opus") byteArrayOf(index.toByte())
                else ByteBuffer.allocate(frames(index) * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(frames(index) * 2) { putShort((firstFrame(index) + it / 2 + 1).toShort()) }
                }.array()
                assertNull(player.tryOffer(AccessUnit(4_000_000 + firstFrame(index) * 1_000_000L / 48000, bytes)))
                await {
                    (if (codec == "opus") decoded.get() > index
                     else (if (pending.get(player) == null) 0 else 1) +
                         (following.get(player) as Collection<*>).size > index) || errors.isNotEmpty()
                }
            }
            await { retained.getInt(player) >= totalFrames * 4 || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(retained.getInt(player) <= 512 * 1024)
            assertEquals(0, output.samples.get())
            time.set(3_000_000_000)
            await { proofs.isNotEmpty() || errors.isNotEmpty() }
            assertEquals(listOf(4_000_000L), proofs.toList())
            assertEquals("First recovery sample must not be discarded", 1.toShort(), written.first())
            if (firstFrames == 960) {
                await { written.size >= 1922 || errors.isNotEmpty() }
                assertEquals("Following packet must not lose its beginning", 961.toShort(), written.elementAt(1920))
            }
            repeat((totalFrames - 5760 + 959) / 960) {
                time.addAndGet(20_000_000)
                await { player.sourceSubmittedFrames >= minOf(5760 + (it + 1) * 960L, totalFrames.toLong()) ||
                    errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            player.close()
            assertEquals(totalFrames.toLong(), player.sourceSubmittedFrames)
            val samples = written.toList()
            assertTrue(samples.size >= totalFrames * 2)
            samples.take(totalFrames * 2).forEachIndexed { index, sample ->
                assertEquals("sample $index", (index / 2 + 1).toShort(), sample)
            }
            assertTrue(samples.drop(totalFrames * 2).all { it == 0.toShort() })
            assertEquals(0, retained.getInt(player))
        } finally { player.close() }
    }

    @Test fun recoveryReservesForMaximumOpusOutputAndStillRejectsOversizedDecodedPackets() {
        val clock = PlaybackClock({ 0L }) { Thread.yield() }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val errors = ConcurrentLinkedQueue<String>()
        val decoded = AtomicInteger()
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { BoundedOutput(AtomicLong()) }, opusFactory = {
                object : OpusPacketDecoder {
                    override fun decode(packet: ByteArray): ShortArray {
                        decoded.incrementAndGet()
                        return ShortArray(if (packet[0].toInt() == 0) 11520 else 11522)
                    }
                    override fun close() {}
                }
            }).apply { recoveryCutoffUs = 4_000_000 }
        val retained = TrackWorker::class.java.getDeclaredField("retainedInputBytes").apply { isAccessible = true }
        try {
            player.start()
            player.offer(AccessUnit(4_000_000, byteArrayOf(0)))
            await { retained.getInt(player) >= 23040 && decoded.get() == 1 || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            player.offer(AccessUnit(4_120_000, byteArrayOf(1)))
            await { errors.isNotEmpty() }
            assertEquals(2, decoded.get())
            assertTrue(errors.single(), errors.single().contains("exceeds 120 ms budget"))
            assertTrue(retained.getInt(player) <= 512 * 1024)
        } finally { player.close() }
    }

    @Test fun fullEncodedQueueWithoutAPendingDeadlineRequestsRecoveryInsteadOfStarving() {
        val clock = PlaybackClock({ 0L }) { Thread.yield() }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val errors = ConcurrentLinkedQueue<String>()
        val pressures = ConcurrentLinkedQueue<QueuePressure>()
        val reported = CountDownLatch(1)
        val calls = AtomicInteger()
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { BoundedOutput(AtomicLong()) }, opusFactory = {
                object : OpusPacketDecoder {
                    override fun decode(packet: ByteArray): ShortArray {
                        calls.incrementAndGet()
                        return ShortArray(1920)
                    }
                    override fun close() {}
                }
            }).apply {
                recoveryCutoffUs = 4_000_000
                onRecoveryPressure = { pressures.add(it); reported.countDown() }
            }
        try {
            assertNull(player.tryOffer(AccessUnit(4_000_000, ByteArray(505_000))))
            player.start()
            assertTrue(reported.await(3, TimeUnit.SECONDS))
            val pressure = pressures.single()
            assertEquals(QueueLimit.BYTES, pressure.limit)
            assertEquals(505_000, pressure.encodedBytes)
            assertEquals(0, pressure.retainedDecodedBytes)
            assertTrue(pressure.attemptedBytes > 512 * 1024)
            assertEquals(1, player.queuedPackets())
            assertEquals(0, calls.get())
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { player.close() }
    }

    @Test(timeout = 15_000) fun futureRecoveryBackpressuresEncodedDependenciesAndResumesWithoutLosingSamples() {
        val time = AtomicLong()
        val clock = PlaybackClock(time::get) { Thread.yield() }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val decoded = ConcurrentLinkedQueue<Int>()
        val written = ConcurrentLinkedQueue<Short>()
        val errors = ConcurrentLinkedQueue<String>()
        val proofs = ConcurrentLinkedQueue<Long>()
        val output = BoundedOutput(time) { samples, offset, count ->
            repeat(count) { written.add(samples[offset + it]) }
        }
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output }, opusFactory = {
                object : OpusPacketDecoder {
                    override fun decode(packet: ByteArray): ShortArray {
                        val index = packet[0].toInt() and 255
                        decoded.add(index)
                        return ShortArray(1920) { (index + 1).toShort() }
                    }
                    override fun close() {}
                }
            }).apply {
                recoveryCutoffUs = 4_000_000
                onFirstSubmitted = proofs::add
            }
        val retained = TrackWorker::class.java.getDeclaredField("retainedInputBytes").apply { isAccessible = true }
        fun unit(index: Int) = AccessUnit(4_000_000 + index * 20_000L, byteArrayOf(index.toByte()))
        try {
            player.start()
            repeat(131) { index ->
                assertNull(player.tryOffer(unit(index)))
                await { decoded.size > index || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            repeat(13) { assertNull(player.tryOffer(unit(131 + it))) }
            val pressure = checkNotNull(player.tryOffer(unit(144)))
            assertEquals(QueueLimit.PTS, pressure.limit)
            assertEquals(2, pressure.track)
            assertEquals(131 * 3840, pressure.retainedDecodedBytes)
            assertTrue(pressure.queuedBytes <= 512 * 1024)
            assertEquals(13, player.queuedPackets())
            assertEquals((0 until 131).toList(), decoded.toList())
            assertEquals(0, output.samples.get())
            assertTrue(proofs.isEmpty())

            time.set(3_000_000_000)
            await { proofs.isNotEmpty() || errors.isNotEmpty() }
            assertEquals(listOf(4_000_000L), proofs.toList())
            repeat(143) {
                time.addAndGet(20_000_000)
                await { player.sourceSubmittedFrames >= minOf((it + 2) * 960L, 144 * 960L) || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            player.close()
            assertEquals((0 until 144).toList(), decoded.toList())
            assertEquals(144 * 960L, player.sourceSubmittedFrames)
            val samples = written.toList()
            assertTrue(samples.size >= 144 * 1920)
            samples.take(144 * 1920).forEachIndexed { index, sample ->
                assertEquals("sample $index", (index / 1920 + 1).toShort(), sample)
            }
            assertTrue(samples.drop(144 * 1920).all { it == 0.toShort() })
            assertEquals(0, retained.getInt(player))
            assertEquals(0, player.queuedPackets())
            assertTrue(time.get() < 10_000_000_000L)
        } finally { player.close() }
    }

    @Test fun closingRecoveryWithPendingAndFollowingDecodedSlicesReleasesRetentionWithoutLateStart() {
        val time = AtomicLong()
        val waits = AtomicInteger()
        val tick = Semaphore(0)
        val clock = PlaybackClock(time::get) {
            waits.incrementAndGet()
            check(tick.tryAcquire(3, TimeUnit.SECONDS))
        }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val errors = ConcurrentLinkedQueue<String>()
        val proofs = ConcurrentLinkedQueue<Long>()
        val output = BoundedOutput(time)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output }).apply {
                recoveryCutoffUs = 1_940_000
                onFirstSubmitted = proofs::add
                diagnosticSession = 99901
                diagnosticGeneration = 2
            }
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(99901, telemetry)
        val retained = TrackWorker::class.java.getDeclaredField("retainedInputBytes").apply { isAccessible = true }
        val pending = AudioPlayer::class.java.getDeclaredField("pending").apply { isAccessible = true }
        val following = AudioPlayer::class.java.getDeclaredField("recoveryAudio").apply { isAccessible = true }
        val worker = TrackWorker::class.java.getDeclaredField("thread").apply { isAccessible = true }
        try {
            repeat(3) { assertNull(player.tryOffer(packet(1_940_000 + it * 20_000L))) }
            player.start()
            await { waits.get() == 1 || errors.isNotEmpty() }
            for (boundary in 2..4) {
                time.addAndGet(250_000_000)
                tick.release()
                await { waits.get() == boundary || errors.isNotEmpty() }
            }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(0, player.queuedPackets())
            assertNotNull(pending.get(player))
            assertEquals(2, (following.get(player) as Collection<*>).size)
            assertEquals(3 * 3840, retained.getInt(player))
            val evidence = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload).state.single {
                it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                    it.stage == DiagnosticStage.SAMPLE.ordinal && it.detail.size == 17
            }
            assertEquals(listOf(-1L, 11520L, 3L, 0L, 0L), evidence.detail.subList(2, 7))
            assertFalse(output.playing.get())
            assertTrue(proofs.isEmpty())

            player.close()
            assertFalse((worker.get(player) as Thread).isAlive)
            assertNull(pending.get(player))
            assertTrue((following.get(player) as Collection<*>).isEmpty())
            assertEquals(0, retained.getInt(player))
            assertEquals(0, player.queuedPackets())
            assertTrue(output.closed.get())

            time.set(2_000_000_000)
            tick.release(100)
            assertThrows(TrackStoppedException::class.java) { player.tryOffer(packet(3_000_000)) }
            assertFalse(output.playing.get())
            assertEquals(0, output.samples.get())
            assertEquals(0L, player.sourceSubmittedFrames)
            assertTrue(proofs.isEmpty())
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            tick.release(100)
            player.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun recoveryFutureSourceWindowDoesNotRecursivelyOverflowEncodedAdmission() {
        for (delayUs in listOf(600_000L, 940_000L)) {
            val time = AtomicLong()
            val waits = AtomicInteger()
            val tick = Semaphore(0)
            val clock = PlaybackClock(time::get) {
                waits.incrementAndGet()
                check(tick.tryAcquire(3, TimeUnit.SECONDS))
            }.apply {
                anchorVideo(1_000_000)
                beginAudioRecovery()
            }
            val errors = ConcurrentLinkedQueue<String>()
            val proofs = ConcurrentLinkedQueue<Long>()
            val output = BoundedOutput(time)
            val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
                { code, detail -> errors.add("$code: $detail") }, { output }).apply {
                    recoveryCutoffUs = 1_000_000 + delayUs
                    onFirstSubmitted = proofs::add
                }
            try {
                player.start()
                assertNull(player.tryOffer(packet(1_000_000 + delayUs)))
                await { player.queuedPackets() == 0 || errors.isNotEmpty() }
                val count = (delayUs / 20_000).toInt()
                for (index in 1..count) {
                    val previousWaits = waits.get()
                    time.addAndGet(20_000_000)
                    assertNull("Recovery must drain the 250 ms encoded queue while awaiting source time",
                        player.tryOffer(packet(1_000_000 + delayUs + index * 20_000)))
                    tick.release()
                    await {
                        player.queuedPackets() == 0 || waits.get() > previousWaits || errors.isNotEmpty()
                    }
                    assertTrue(errors.toString(), errors.isEmpty())
                }
                await { proofs.isNotEmpty() || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
                assertEquals(listOf(1_000_000 + delayUs), proofs.toList())
                assertTrue("Never play before the original source deadline", time.get() >= delayUs * 1000)
                repeat(count + 20) {
                    time.addAndGet(20_000_000)
                    if (it < 10) assertNull(player.tryOffer(packet(
                        1_000_000 + delayUs + (count + it + 1) * 20_000)))
                    tick.release()
                    await { player.sourceSubmittedFrames >= minOf((it + 2) * 960L, (count + 11) * 960L) ||
                        errors.isNotEmpty() }
                }
                assertTrue(errors.toString(), errors.isEmpty())
                assertEquals("Recovery retains the original samples rather than jumping across discarded PCM",
                    (count + 11) * 960L, player.sourceSubmittedFrames)
                assertTrue("Recovery completes within the unchanged ten-second deadline", time.get() < 10_000_000_000)
                assertTrue(output.speeds.isEmpty())
            } finally {
                tick.release(1000)
                player.close()
            }
        }
    }

    @Test fun exactReported64112usRefreshDoesNotCancelTheProductionWorker() =
        stream(0, { index -> if (index >= 10) 64_112 else 0 })
    @Test fun realAudioWorkerDrainsContinuousPacketsWithThreeHundredMillisecondInitialOffset() = stream(300_000)
    @Test fun realAudioWorkerDrainsContinuousPacketsWithTwoSecondInitialOffset() = stream(2_000_000)
    @Test fun refreshedMonotonicCaptureTimestampDoesNotCancelContinuousPcm() =
        stream(0, { index -> if (index >= 10) 6_000 else 0 })
    @Test fun backwardTimestampPhaseCorrectionRemainsMonotonicAndDoesNotCancelPcm() =
        stream(0, { index -> if (index >= 10) -12_666 else 0 })
    @Test fun sourcePhaseChangeIsRecoverableRatherThanMalformedMedia() =
        stream(0, { index -> if (index >= 10) 100_000 else 0 })
    @Test fun sourcePhaseDoesNotDrivePhysicalAudioBufferRegulation() =
        stream(0, { index -> if (index >= 10) 30_000 else 0 }, expectedSpeed = 1.0f)

    private fun stream(offsetUs: Long, timestampCorrection: (Int) -> Long = { 0 },
        expectedFailure: String? = null, expectedSpeed: Float? = null) {
        val time = AtomicLong()
        val waits = AtomicInteger()
        val tick = Semaphore(0)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(time::get) {
            waits.incrementAndGet()
            check(tick.tryAcquire(3, TimeUnit.SECONDS))
        }.apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = BoundedOutput(time)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        try {
            assertEquals(0L, clock.videoDelayUs(1_000_000))
            player.start()
            player.offer(packet(1_000_000 + offsetUs))
            await { waits.get() > 0 || errors.isNotEmpty() }
            for (index in 1..60) {
                val previousWaits = waits.get()
                time.addAndGet(20_000_000)
                player.offer(packet(1_000_000 + offsetUs + index * 20_000 + timestampCorrection(index)))
                tick.release()
                await {
                    waits.get() > previousWaits || player.sourceSubmittedFrames >= (index + 1) * 960 || errors.isNotEmpty()
                }
                if (expectedFailure != null && errors.isNotEmpty()) {
                    assertTrue(errors.toString(), errors.any { it.startsWith("AUDIO_FAILED:") && it.contains(expectedFailure) })
                    return
                }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            assertNull("Expected a genuine discontinuity to fail", expectedFailure)
            await { player.sourceSubmittedFrames == 61 * 960L || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(61 * 960L, player.sourceSubmittedFrames)
            assertTrue(output.playing.get())
            expectedSpeed?.let { assertTrue("PCM compensation must not reconfigure the native rate", output.speeds.isEmpty()) }
            clock.endVideoPreroll()
            assertEquals(0L, clock.videoDelayUs(1_000_000))
        } finally {
            tick.release(100)
            player.close()
        }
        assertTrue(output.closed.get())
    }

    @Test fun waitingForFirstVideoConsumesAudioAsBoundedDecoderPreroll() {
        val time = AtomicLong()
        val clock = PlaybackClock(time::get, { time.addAndGet(it * 1_000_000) }).apply { audioExpected = true }
        val errors = ConcurrentLinkedQueue<String>()
        val output = BoundedOutput(time)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        try {
            player.start()
            repeat(20) {
                player.offer(packet(1_000_000L + it * 20_000))
                // Use the real queue/decoder consumer, not a separately tested clock helper.
                await { player.queuedPackets() == 0 || errors.isNotEmpty() }
                time.addAndGet(20_000_000)
            }

            assertFalse(output.playing.get())
            clock.anchorVideo(1_300_000)
            player.offer(packet(1_400_000))
            await { output.playing.get() || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { player.close() }
    }

    @Test fun actualOutputWriteFailureRemainsFatalAndReportsStageWithoutDisablingAudio() {
        val time = AtomicLong()
        val clock = PlaybackClock(time::get, { time.addAndGet(it * 1_000_000) })
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val failed = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun write(samples: ShortArray, offset: Int, count: Int) = -6
            override fun positionUs() = 0L
            override fun timing() = AudioOutputTiming(48000, 5760, 0, 0, 0, null, AudioClockSource.HEAD_UNAVAILABLE)
            override fun setSpeed(speed: Float) {}
            override fun close() {}
        }
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail"); failed.countDown() }, { output })
        try {
            player.start()
            player.offer(packet(1_000_000))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(errors.single().startsWith("AUDIO_FAILED: Audio stage=OUTPUT_WRITE exception=IllegalStateException:"))
            assertTrue(errors.single().contains("AudioTrack write failed (-6)"))
            assertTrue(clock.audioExpected)
        } finally { player.close() }
    }

    @Test fun recoveryDecodesPrerollButSubmitsOnlySampleAlignedPcmAfterTheVideoCutoff() {
        val time = AtomicLong()
        val cutoff = 1_010_001L
        val clock = PlaybackClock(time::get, { time.addAndGet(it * 1_000_000) })
            .apply { audioExpected = true; recoveryStartup = true; anchorVideo(cutoff) }
        val errors = ConcurrentLinkedQueue<String>()
        val proofs = ConcurrentLinkedQueue<Long>()
        val written = ConcurrentLinkedQueue<Short>()
        val output = BoundedOutput(time) { samples, offset, count ->
            for (index in offset until offset + count) written.add(samples[index])
        }
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output }).apply {
                recoveryCutoffUs = cutoff
                onFirstSubmitted = proofs::add
            }
        try {
            player.start()
            player.offer(packet(980_000))
            await { player.queuedPackets() == 0 || errors.isNotEmpty() }
            assertTrue(proofs.isEmpty())
            val bytes = ByteArray(3840)
            val pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            repeat(960) { frame -> repeat(2) { pcm.put(frame.toShort()) } }
            player.offer(AccessUnit(1_000_000, bytes))
            await { player.sourceSubmittedFrames == 479L || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(listOf(1_010_020L), proofs.toList())
            assertEquals(481.toShort(), written.first())
            assertEquals(960L - 481, player.sourceSubmittedFrames)
        } finally { player.close() }
    }

    @Test fun recoveryWorkerSubmitsAudioWhileFutureVideoKeepsArriving() {
        val time = AtomicLong()
        lateinit var clock: PlaybackClock
        clock = PlaybackClock(time::get) { ms ->
            val now = time.addAndGet(ms * 1_000_000)
            clock.videoPresented(2_000_000 + now / 1000, now + 1_000_000_000)
        }.apply {
            audioExpected = true
            anchorVideo(1_000_000)
            beginAudioRecovery()
            videoPresented(2_000_000, 1_000_000_000)
        }
        val errors = ConcurrentLinkedQueue<String>()
        val submitted = CountDownLatch(1)
        val output = BoundedOutput(time)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail"); submitted.countDown() }, { output }).apply {
                recoveryCutoffUs = 1_500_000
                onFirstSubmitted = { submitted.countDown() }
            }
        try {
            player.start()
            player.offer(packet(1_500_000))
            assertTrue("Recovery did not submit source audio", submitted.await(3, TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(output.playing.get())
            assertTrue("Recovery must not change the native output rate", output.speeds.isEmpty())
        } finally { player.close() }
    }

    private fun packet(pts: Long) = AccessUnit(pts, ByteArray(1920 * 2))
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue("Production audio worker made no progress", condition())
    }

    private class BoundedOutput(private val time: AtomicLong,
        private val onWrite: (ShortArray, Int, Int) -> Unit = { _, _, _ -> }) : AudioOutput {
        val samples = AtomicInteger()
        val playing = AtomicBoolean()
        val closed = AtomicBoolean()
        val speeds = ConcurrentLinkedQueue<Float>()
        private var startNs = 0L
        override val underrunCount = 0
        override fun play() { startNs = time.get(); playing.set(true) }
        override fun pause() { playing.set(false) }
        override fun positionUs(): Long = if (playing.get()) minOf((time.get() - startNs) / 1000, samples.get() * 1_000_000L / 96000) else 0
        override fun timing(): AudioOutputTiming {
            val frames = positionUs() * 48000 / 1_000_000
            return AudioOutputTiming(48000, 5760, samples.get().toLong() / 2, frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val buffered = this.samples.get() - positionUs() * 96000 / 1_000_000
            val available = (11520 - buffered).coerceAtLeast(0).toInt()
            val written = minOf(count, available)
            onWrite(samples, offset, written)
            this.samples.addAndGet(written)
            return written
        }
        override fun setSpeed(speed: Float) { speeds.add(speed) }
        override fun close() { closed.set(true) }
    }
}
