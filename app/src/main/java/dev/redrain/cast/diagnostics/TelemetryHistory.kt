package dev.redrain.cast.diagnostics

import java.util.ArrayDeque

/**
 * Sender-owned, single-consumer retention. Blocks are shared between the rolling window and
 * the frozen report; remote clocks are not compared with this endpoint's clock.
 */
internal class TelemetryHistory(private val receiverSupported: Boolean) {
    companion object {
        const val WINDOW_NS = 60_000_000_000L
        const val POST_NS = 10_000_000_000L
        const val ROLLING_BYTES = 480 * 1024
        private const val FROZEN_BYTES = ROLLING_BYTES + 80 * 1024
        const val RETAINED_BYTES = 3 * 1024 * 1024
    }

    enum class Endpoint { SENDER, RECEIVER }
    class Block internal constructor(val sequence: Long, val arrivalNs: Long, bytes: ByteArray) {
        private val content = bytes.copyOf()
        val size: Int get() = content.size
        fun copyPayload(): ByteArray = content.copyOf()
        fun writeTo(output: java.io.OutputStream) = output.write(content)
    }
    data class Gap(val first: Long, val last: Long)
    data class Source(
        val blocks: List<Block>, val missingPublications: Long, val truncated: Long,
        val finalSequence: Long?, val terminalSequence: Long?, val cleanupSucceeded: Boolean?,
        val gaps: List<Gap>, val omittedGapRanges: Long,
        val finalSkipReason: ReceiverTelemetrySession.FinalSkipReason?
    )
    data class Snapshot(
        val sender: Source, val receiver: Source, val receiverSupported: Boolean,
        val receiverComplete: Boolean, val windowClosed: Boolean, val final: Boolean
    )

    private class EndpointState {
        val rolling = ArrayDeque<Block>()
        val frozen = ArrayList<Block>()
        var rollingBytes = 0
        var frozenBytes = 0
        var lastSequence = 0L
        var missing = 0L
        val gaps = ArrayList<Gap>()
        var omittedGapRanges = 0L
        var truncated = 0L
        var finalSequence: Long? = null
        var finalBlock: Block? = null
        var terminalSequence: Long? = null
        var cleanupSucceeded: Boolean? = null
        var finalSkipReason: ReceiverTelemetrySession.FinalSkipReason? = null
    }
    private val sender = EndpointState()
    private val receiver = EndpointState()
    private var triggerNs: Long? = null
    private var closed = false
    private var finished = false

    fun accept(endpoint: Endpoint, payload: ByteArray, arrivalNs: Long): Boolean {
        require(arrivalNs >= 0)
        check(!finished)
        if (endpoint == Endpoint.RECEIVER && !receiverSupported) return false
        val batch = SessionTelemetryProtocol.parse(payload, if (endpoint == Endpoint.SENDER) "sender" else "receiver")
        val source = source(endpoint)
        if (batch.sequence <= source.lastSequence) return false
        val gap = batch.sequence - source.lastSequence - 1
        source.missing = saturatingAdd(source.missing, gap)
        if (gap > 0) {
            if (source.gaps.size < 64) source.gaps.add(Gap(source.lastSequence + 1, batch.sequence - 1))
            else source.omittedGapRanges = saturatingAdd(source.omittedGapRanges, 1)
        }
        source.lastSequence = batch.sequence
        val block = Block(batch.sequence, arrivalNs, payload)
        source.rolling.addLast(block)
        source.rollingBytes += block.size
        evict(source, arrivalNs)
        if (batch.final) retainFinal(source, block)
        if (triggerNs == null && (batch.firstPressure != null || batch.firstFailure != null)) {
            trigger(arrivalNs)
        } else {
            val trigger = triggerNs
            if (trigger != null && !closed && arrivalNs - trigger <= POST_NS) freeze(source, block)
        }
        check(retainedPayloadBytes() <= RETAINED_BYTES)
        return true
    }

    fun closeWindow(nowNs: Long): Snapshot? {
        val trigger = triggerNs ?: return null
        if (closed || finished || nowNs - trigger < POST_NS) return null
        closed = true
        return snapshot(final = false, nowNs = nowNs)
    }

    fun initial(): Snapshot? = if (triggerNs == null || finished) null else snapshot(final = false, nowNs = 0)

    /** A terminal is complete only when it names the received final batch and cleanup succeeded. */
    fun terminal(endpoint: Endpoint, finalSequence: Long?, cleanupSucceeded: Boolean, arrivalNs: Long,
                 finalSkipReason: ReceiverTelemetrySession.FinalSkipReason? = null) {
        require(arrivalNs >= 0)
        check(!finished)
        val source = source(endpoint)
        source.terminalSequence = finalSequence
        source.cleanupSucceeded = cleanupSucceeded
        source.finalSkipReason = finalSkipReason
        if (!cleanupSucceeded && triggerNs == null) trigger(arrivalNs)
    }

    fun finish(nowNs: Long): Snapshot? {
        check(!finished)
        finished = true
        if (triggerNs == null) return null
        evict(sender, nowNs)
        evict(receiver, nowNs)
        return snapshot(final = true, nowNs = nowNs)
    }

    fun retainedPayloadBytes(): Int {
        val unique = java.util.IdentityHashMap<Block, Boolean>()
        for (source in listOf(sender, receiver)) {
            source.rolling.forEach { unique[it] = true }
            source.frozen.forEach { unique[it] = true }
            source.finalBlock?.let { unique[it] = true }
        }
        return unique.keys.sumOf { it.size }
    }

    private fun snapshot(final: Boolean, nowNs: Long): Snapshot {
        fun report(source: EndpointState): Source {
            val selected = ArrayList<Block>(source.frozen)
            if (final) {
                source.finalBlock?.let { if (!selected.contains(it)) selected.add(it) }
                source.rolling.filter { it.arrivalNs >= nowNs - POST_NS }.forEach {
                    if (!selected.contains(it)) selected.add(it)
                }
            }
            return Source(selected.sortedBy { it.sequence }, source.missing, source.truncated, source.finalSequence,
                source.terminalSequence, source.cleanupSucceeded, source.gaps.toList(), source.omittedGapRanges,
                source.finalSkipReason)
        }
        return Snapshot(report(sender), report(receiver), receiverSupported,
            receiverSupported && receiver.finalSequence != null &&
                receiver.finalSequence == receiver.terminalSequence && receiver.cleanupSucceeded == true,
            closed, final)
    }

    private fun evict(source: EndpointState, nowNs: Long) {
        while (source.rolling.isNotEmpty() &&
            (source.rollingBytes > ROLLING_BYTES || nowNs - source.rolling.first().arrivalNs > WINDOW_NS)) {
            val removed = source.rolling.removeFirst()
            source.rollingBytes -= removed.size
            source.truncated++
        }
    }

    private fun trigger(nowNs: Long) {
        triggerNs = nowNs
        sender.rolling.forEach { freeze(sender, it) }
        receiver.rolling.forEach { freeze(receiver, it) }
    }

    private fun freeze(source: EndpointState, block: Block) {
        // The final block shares the frozen budget even when it arrived after the window.
        val finalBlock = source.finalBlock
        val reserved = if (finalBlock != null && finalBlock !== block && !source.frozen.contains(finalBlock))
            finalBlock.size else 0
        if (source.frozenBytes + block.size + reserved > FROZEN_BYTES) {
            source.truncated++
            return
        }
        source.frozen.add(block)
        source.frozenBytes += block.size
    }

    private fun retainFinal(source: EndpointState, block: Block) {
        source.finalSequence = block.sequence
        source.finalBlock = block
        while (source.frozenBytes + block.size > FROZEN_BYTES) {
            val removed = source.frozen.removeAt(source.frozen.lastIndex)
            source.frozenBytes -= removed.size
            source.truncated++
        }
    }

    private fun source(endpoint: Endpoint): EndpointState =
        if (endpoint == Endpoint.SENDER) sender else receiver

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
}
