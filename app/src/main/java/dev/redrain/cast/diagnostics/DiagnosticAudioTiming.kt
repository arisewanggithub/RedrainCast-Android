package dev.redrain.cast.diagnostics

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal class DiagnosticAudioTiming(deadlineNs: Long = Long.MAX_VALUE, val workEnabled: Boolean = false,
                                     val detailEnabled: Boolean = false, val ownerEnabled: Boolean = false) {
    companion object {
        const val CAPACITY = 1024
        const val MARKER_FRAME_BYTES = 512
        const val RECEIVED = 1
        const val ADMISSION_CHECK = 2
        const val ADMITTED = 3
        const val ADMISSION_PRESSURE = 4
        const val DEQUEUED = 5
        const val DECODE_BEGIN = 6
        const val DECODE_END = 7
        const val AAC_INPUT = 8
        const val AAC_OUTPUT = 9
        const val TRIM = 10
        const val REPLACED = 11
        const val OUTPUT_REQUEST = 12
        const val OUTPUT_RESULT = 13
        const val OUTPUT_SILENCE_REQUEST = 14
        const val OUTPUT_SILENCE_RESULT = 15
        const val DROPPED = 16
        const val OUTPUT_PROGRESS = 17
        const val OUTPUT_PRESENTED = 18
        const val OPUS_DECODE_BEGIN = 19
        const val OPUS_DECODE_END = 20
        const val REGULATOR_DROP = 21
        const val WORK_BEGIN = 22
        const val WORK_END = 23
        const val WORK_FAILED = 24
        const val QUEUE_DETAIL = 25
        const val OUTPUT_DETAIL = 26
        const val TRACE_OVERHEAD = 27
        const val OUTPUT_NATIVE_CAPACITY = 28
        const val OUTPUT_FRAMES = 29
        const val OWNER_BEGIN = 30
        const val OWNER_END = 31
        const val PREPARATION = 1
        const val START_GATE = 2
        const val RESAMPLING = 3
        const val PLAYBACK_START = 4
        const val REFILL = 5
        const val TIMING = 6
        const val START_DECISION = 7
        const val CALLBACK = 8
        const val UNDERRUN = 9
        const val SOURCE_ANCHOR = 10
        const val COMPENSATION = 11
        const val STARTUP_SUBMISSION = 12
        const val DECODE = 13
        const val WRITE = 14
        const val RESERVATION_BYTES = 3
        const val RESERVATION_PACKETS = 4
    }

    data class Event(
        val sequence: Long,
        val monoNs: Long,
        val generation: Int,
        val packetId: Long,
        val type: Int,
        val value1: Long,
        val value2: Long
    )

    data class Pressure(val monoNs: Long, val session: Long, val generation: Int, val reason: Int)

    private val next = AtomicLong()
    private val nextPacket = AtomicLong()
    private val nextOwner = AtomicLong()
    private val state = AtomicLongArray(CAPACITY)
    private val times = AtomicLongArray(CAPACITY)
    private val generations = AtomicLongArray(CAPACITY)
    private val packets = AtomicLongArray(CAPACITY)
    private val types = AtomicLongArray(CAPACITY)
    private val values1 = AtomicLongArray(CAPACITY)
    private val values2 = AtomicLongArray(CAPACITY)
    private val lost = AtomicLong()
    private val presequenceLost = AtomicLong()
    private val producers = AtomicLong()
    private val firstPressure = AtomicReference<Pressure?>()
    @Volatile private var ownerGate: AtomicBoolean? = null
    @Volatile private var deadline = deadlineNs
    private var drainCursor = 0

    fun shortenDeadline(value: Long) { deadline = minOf(deadline, value) }

    private fun enter(): Boolean {
        val previous = producers.getAndIncrement()
        if (previous < 0 || previous == Long.MAX_VALUE) {
            producers.decrementAndGet()
            presequenceLost.incrementAndGet()
            return false
        }
        return true
    }

    fun packetId(): Long {
        if (!enter()) return 0
        return try {
            val id = nextPacket.incrementAndGet()
            if (id > 0) id else {
                presequenceLost.incrementAndGet()
                0
            }
        } finally {
            producers.decrementAndGet()
        }
    }

    fun record(monoNs: Long, generation: Int, packetId: Long, type: Int, value1: Long, value2: Long) {
        if (type in WORK_BEGIN..WORK_FAILED && !workEnabled) return
        if ((type >= QUEUE_DETAIL || type in WORK_BEGIN..WORK_FAILED && value1 > STARTUP_SUBMISSION) && !detailEnabled) return
        if (type >= OWNER_BEGIN && !ownerEnabled) return
        if (monoNs >= deadline) {
            presequenceLost.incrementAndGet()
            return
        }
        if (!enter()) return
        try {
            if (monoNs >= deadline) {
                presequenceLost.incrementAndGet()
                return
            }
            val sequence = next.incrementAndGet()
            if (sequence <= 0) {
                lost.incrementAndGet()
                return
            }
            val slot = ((sequence - 1) % CAPACITY).toInt()
            if (!state.compareAndSet(slot, 0, -1)) {
                lost.incrementAndGet()
                return
            }
            times.set(slot, monoNs)
            generations.set(slot, generation.toLong())
            packets.set(slot, packetId)
            types.set(slot, type.toLong())
            values1.set(slot, value1)
            values2.set(slot, value2)
            state.set(slot, sequence)
        } finally {
            producers.decrementAndGet()
        }
    }

    fun bindOwnerGate(owner: AtomicBoolean) { ownerGate = owner }

    fun ownerBegin(generation: Int): Long {
        if (ownerGate?.get() == false) {
            presequenceLost.incrementAndGet()
            return 0
        }
        val span = nextOwner.incrementAndGet()
        if (span > 0) record(System.nanoTime(), generation, 0, OWNER_BEGIN, Thread.currentThread().id, span)
        else presequenceLost.incrementAndGet()
        return span
    }

    fun ownerEnd(generation: Int, span: Long) {
        if (span > 0 && ownerGate?.get() == false) {
            presequenceLost.incrementAndGet()
            return
        }
        if (span > 0) record(System.nanoTime(), generation, 0, OWNER_END, Thread.currentThread().id, span)
    }

    inline fun <T> ownerInterval(generation: Int, block: () -> T): T {
        val span = ownerBegin(generation)
        try {
            return block()
        } finally {
            ownerEnd(generation, span)
        }
    }

    fun <T> callbackLock(
        lock: Any, generation: Int, nanoTime: () -> Long, cpuTime: () -> Long,
        around: ((() -> T) -> T) = { it() }, block: () -> T
    ): T {
        if (!workEnabled) return synchronized(lock, block)
        boundary(nanoTime(), generation, WORK_BEGIN, CALLBACK, cpuTime())
        return synchronized(lock) { around {
            boundary(nanoTime(), generation, WORK_END, CALLBACK, cpuTime())
            boundary(nanoTime(), generation, WORK_BEGIN, CALLBACK, cpuTime())
            var failed = false
            try {
                block()
            } catch (error: Throwable) {
                failed = true
                throw error
            } finally {
                boundary(nanoTime(), generation, if (failed) WORK_FAILED else WORK_END,
                    CALLBACK, cpuTime())
            } }
        }
    }

    private fun boundary(atNs: Long, generation: Int, type: Int, stage: Int, cpuNs: Long) =
        record(atNs, generation, 0, type, stage.toLong(), cpuNs.coerceAtLeast(-1))

    fun pressure(monoNs: Long, session: Long, generation: Int, reason: Int) {
        if (firstPressure.get() != null) return
        if (monoNs >= deadline) {
            presequenceLost.incrementAndGet()
            return
        }
        if (!enter()) return
        try {
            if (monoNs >= deadline) {
                presequenceLost.incrementAndGet()
                return
            }
            if (firstPressure.get() == null)
                firstPressure.compareAndSet(null, Pressure(monoNs, session, generation, reason))
        } finally {
            producers.decrementAndGet()
        }
    }

    fun close(): Long = producers.getAndUpdate { it or Long.MIN_VALUE } and Long.MAX_VALUE
    fun pendingProducers(): Long = producers.get() and Long.MAX_VALUE
    fun firstPressure(): Pressure? = firstPressure.get()
    fun dropped(): Long = lost.get()
    fun droppedBeforeSequence(): Long = presequenceLost.get()
    fun attempted(): Long = next.get()

    @Synchronized fun drain(limit: Int): List<Event> {
        require(limit in 1..CAPACITY)
        val result = ArrayList<Event>(limit)
        repeat(CAPACITY) {
            if (result.size == limit) return result
            val slot = drainCursor
            drainCursor = (drainCursor + 1) % CAPACITY
            val sequence = state.get(slot)
            if (sequence <= 0 || !state.compareAndSet(slot, sequence, -1)) return@repeat
            result.add(Event(sequence, times.get(slot), generations.get(slot).toInt(), packets.get(slot),
                types.get(slot).toInt(), values1.get(slot), values2.get(slot)))
            state.set(slot, 0)
        }
        return result
    }
}
