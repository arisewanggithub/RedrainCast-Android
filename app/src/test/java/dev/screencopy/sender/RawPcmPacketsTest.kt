package dev.screencopy.sender

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RawPcmPacketsTest {
    @Test fun partialReadsProduceExactlyTwentyMillisecondsWithoutChangingSamplesOrSourceTime() {
        val packets = mutableListOf<Pair<ByteArray, Long>>()
        val source = ByteArray(3840 * 3 + 16) { (it * 17 + 3).toByte() }
        val packetizer = RawPcmPackets(1_234_567) { bytes, pts -> packets += bytes to pts }
        val sizes = listOf(4, 192, 48, 768, 3840, 512, 1636)
        var offset = 0
        var index = 0
        while (offset < source.size) {
            val count = minOf(sizes[index++ % sizes.size], source.size - offset)
            val read = source.copyOfRange(offset, offset + count)
            packetizer.append(read, count)
            read.fill(0)
            offset += count
        }
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.first.size == 3840 })
        assertEquals(listOf(1_234_567L, 1_254_567L, 1_274_567L), packets.map { it.second })
        assertArrayEquals(source.copyOfRange(0, 3840 * 3), packets.flatMap { it.first.toList() }.toByteArray())
        val tail = ByteArray(3824) { 0x5a }
        packetizer.append(tail, tail.size)
        tail.fill(0)
        assertEquals(4, packets.size)
        assertEquals(1_294_567L, packets.last().second)
        assertArrayEquals(source.copyOfRange(3840 * 3, source.size) + ByteArray(3824) { 0x5a }, packets.last().first)
    }

    @Test fun tinyDeviceReadsDoNotBecomeATcpPacketPerRead() {
        val packets = mutableListOf<Pair<ByteArray, Long>>()
        val packetizer = RawPcmPackets(8_000_000) { bytes, pts -> packets += bytes to pts }
        repeat(19) { packetizer.append(ByteArray(192), 192) }
        assertTrue(packets.isEmpty())
        packetizer.append(ByteArray(192), 192)
        assertEquals(1, packets.size)
        assertEquals(3840, packets.single().first.size)
        repeat(20) { packetizer.append(ByteArray(192), 192) }
        assertEquals(listOf(8_000_000L, 8_020_000L), packets.map { it.second })
    }

    @Test fun largeReadsAreSplitIntoBoundedImmutablePacketsInFifoOrder() {
        val packets = mutableListOf<Pair<ByteArray, Long>>()
        val source = ByteArray(3840 * 50) { (it % 127).toByte() }
        RawPcmPackets(2_000_000) { bytes, pts -> packets += bytes to pts }.append(source, source.size)
        assertEquals(50, packets.size)
        assertEquals(2_980_000L, packets.last().second)
        assertTrue(packets.zipWithNext().all { (first, second) -> second.second - first.second == 20_000L })
        assertArrayEquals(source, packets.flatMap { it.first.toList() }.toByteArray())
        assertNotSame(packets[0].first, packets[1].first)
    }

    @Test fun invalidAlignmentAndAdmissionFailuresRemainExplicit() {
        assertThrows(IllegalArgumentException::class.java) { RawPcmPackets(-1) { _, _ -> } }
        val packetizer = RawPcmPackets(0) { _, _ -> fail("Invalid input emitted audio") }
        for (count in listOf(-4, 0, 3, 12))
            assertThrows(IOException::class.java) { packetizer.append(ByteArray(8), count) }
        val failure = IOException("CONGESTION: admission exceeded")
        val failed = RawPcmPackets(0) { _, _ -> throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { failed.append(ByteArray(3840), 3840) })
    }
}
