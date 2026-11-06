package org.screencopy.receiver.net

import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Wire
import org.screencopy.receiver.media.QueueCongestionException
import org.screencopy.receiver.media.QueueLimit
import org.screencopy.receiver.media.QueuePressure
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Socket

internal class ControlWriter(
    private val socket: Socket,
    private val written: () -> Unit,
    private val failed: (String) -> Unit
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<Message>(64)
    private var queuedBytes = 0
    private var queuedPackets = 0
    private val accepting = AtomicBoolean(true)
    @Volatile private var writingSince = 0L
    private val thread = Thread({ loop() }, "ScreenCopy-control-writer").also { it.start() }

    @Synchronized
    fun offer(message: Message) {
        check(accepting.get()) { "Connection writer has stopped" }
        val size = 40 + message.payload.size
        if (queuedBytes + size > 256 * 1024 || !queue.offer(message)) {
            throw QueueCongestionException(QueuePressure(0,
                if (queuedBytes + size > 256 * 1024) QueueLimit.BYTES else QueueLimit.PACKETS,
                queuedPackets, queuedBytes, queuedPackets + 1, queuedBytes.toLong() + size, 0, 64, 256 * 1024, 0))
        }
        queuedBytes += size
        queuedPackets++
    }

    private fun loop() {
        try {
            while (accepting.get() || queue.isNotEmpty()) {
                val message = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                synchronized(this) { queuedBytes -= 40 + message.payload.size; queuedPackets-- }
                writingSince = System.nanoTime() / 1_000_000
                Wire.write(socket.outputStream, message)
                writingSince = 0
                written()
            }
        } catch (e: java.io.IOException) {
            accepting.set(false)
            failed("Control write failed (${e.javaClass.simpleName})")
        } catch (_: InterruptedException) {
            if (accepting.get()) failed("Control writer interrupted unexpectedly")
        } catch (_: IllegalArgumentException) {
            accepting.set(false)
            failed("Internal control frame validation failed")
        }
    }

    fun stalled(now: Long): Boolean = writingSince != 0L && now - writingSince >= 10_000

    override fun close() {
        accepting.set(false)
        if (thread !== Thread.currentThread()) {
            thread.join(1500)
            if (thread.isAlive) {
                socket.close()
                thread.interrupt()
                thread.join(500)
            }
            check(!thread.isAlive) { "Control writer did not terminate after socket close" }
        }
        queue.clear()
    }
}
