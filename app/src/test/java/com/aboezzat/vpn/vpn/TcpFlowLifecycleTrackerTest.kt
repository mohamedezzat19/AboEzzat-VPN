package com.aboezzat.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpFlowLifecycleTrackerTest {
    @Test
    fun tracksThreeWayHandshakeWithoutClaimingToImplementTcp() {
        val tracker = TcpFlowLifecycleTracker()
        val syn = parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_SYN))
        val first = tracker.observe(syn, TcpFlowLifecycleTracker.Direction.OUTBOUND, 10)
        assertEquals(TcpFlowLifecycleTracker.State.SYN_SENT, (first as TcpFlowLifecycleTracker.ObserveResult.Updated).snapshot.state)

        val synAck = parse(tcpPacket(
            outbound = false,
            flags = Ipv4TcpPacketParser.FLAG_SYN or Ipv4TcpPacketParser.FLAG_ACK
        ))
        val second = tracker.observe(synAck, TcpFlowLifecycleTracker.Direction.INBOUND, 20)
        assertEquals(TcpFlowLifecycleTracker.State.SYN_ACK_RECEIVED, (second as TcpFlowLifecycleTracker.ObserveResult.Updated).snapshot.state)

        val ack = parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_ACK))
        val third = tracker.observe(ack, TcpFlowLifecycleTracker.Direction.OUTBOUND, 30)
        assertEquals(TcpFlowLifecycleTracker.State.ESTABLISHED, (third as TcpFlowLifecycleTracker.ObserveResult.Updated).snapshot.state)
        assertEquals(3, (third).snapshot.packetsSeen)
    }

    @Test
    fun tracksHalfCloseAndBothFinObservations() {
        val tracker = TcpFlowLifecycleTracker()
        tracker.observe(
            parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_SYN)),
            TcpFlowLifecycleTracker.Direction.OUTBOUND, 1
        )
        val localFin = tracker.observe(
            parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_FIN or Ipv4TcpPacketParser.FLAG_ACK)),
            TcpFlowLifecycleTracker.Direction.OUTBOUND, 2
        ) as TcpFlowLifecycleTracker.ObserveResult.Updated
        assertEquals(TcpFlowLifecycleTracker.State.LOCAL_HALF_CLOSED, localFin.snapshot.state)

        val remoteFin = tracker.observe(
            parse(tcpPacket(outbound = false, flags = Ipv4TcpPacketParser.FLAG_FIN or Ipv4TcpPacketParser.FLAG_ACK)),
            TcpFlowLifecycleTracker.Direction.INBOUND, 3
        ) as TcpFlowLifecycleTracker.ObserveResult.Updated
        assertEquals(TcpFlowLifecycleTracker.State.CLOSING, remoteFin.snapshot.state)
    }

    @Test
    fun rstClosesFlowAndPruneRemovesAfterRetention() {
        val tracker = TcpFlowLifecycleTracker(closedRetentionMillis = 100)
        tracker.observe(
            parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_SYN)),
            TcpFlowLifecycleTracker.Direction.OUTBOUND, 100
        )
        val closed = tracker.observe(
            parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_RST)),
            TcpFlowLifecycleTracker.Direction.OUTBOUND, 120
        ) as TcpFlowLifecycleTracker.ObserveResult.Updated
        assertEquals(TcpFlowLifecycleTracker.State.CLOSED, closed.snapshot.state)
        assertEquals(0, tracker.prune(219))
        assertEquals(1, tracker.prune(220))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun enforcesCapacityAndRejectsUnsolicitedInitialAck() {
        val tracker = TcpFlowLifecycleTracker(maxFlows = 1)
        val ack = parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_ACK))
        assertEquals(
            TcpFlowLifecycleTracker.ObserveResult.InvalidInitialPacket,
            tracker.observe(ack, TcpFlowLifecycleTracker.Direction.OUTBOUND, 1)
        )

        val syn = parse(tcpPacket(flags = Ipv4TcpPacketParser.FLAG_SYN))
        tracker.observe(syn, TcpFlowLifecycleTracker.Direction.OUTBOUND, 2)
        val secondSyn = parse(tcpPacket(
            sourcePort = 43121,
            flags = Ipv4TcpPacketParser.FLAG_SYN
        ))
        assertEquals(
            TcpFlowLifecycleTracker.ObserveResult.CapacityReached,
            tracker.observe(secondSyn, TcpFlowLifecycleTracker.Direction.OUTBOUND, 3)
        )
    }

    private fun parse(bytes: ByteArray) = Ipv4TcpPacketParser.parse(bytes)

    private fun tcpPacket(
        outbound: Boolean = true,
        sourcePort: Int = 43120,
        flags: Int
    ): ByteArray {
        val bytes = ByteArray(40)
        bytes[0] = 0x45
        put16(bytes, 2, bytes.size)
        put16(bytes, 4, 0x1234)
        bytes[8] = 64
        bytes[9] = 6
        val local = byteArrayOf(10, 0, 0, 2)
        val remote = byteArrayOf(203.toByte(), 0, 113, 10)
        (if (outbound) local else remote).copyInto(bytes, 12)
        (if (outbound) remote else local).copyInto(bytes, 16)

        val tcp = 20
        put16(bytes, tcp, if (outbound) sourcePort else 443)
        put16(bytes, tcp + 2, if (outbound) 443 else sourcePort)
        put32(bytes, tcp + 4, 100L)
        put32(bytes, tcp + 8, 200L)
        bytes[tcp + 12] = ((5 shl 4) or ((flags ushr 8) and 1)).toByte()
        bytes[tcp + 13] = flags.toByte()
        put16(bytes, tcp + 14, 4096)
        put16(bytes, tcp + 16, tcpChecksum(bytes, tcp, 20))
        fixIpChecksum(bytes)
        return bytes
    }

    private fun tcpChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        for (i in 12 until 20 step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        sum += 6
        sum += length
        for (i in offset until offset + length step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun fixIpChecksum(bytes: ByteArray) {
        bytes[10] = 0
        bytes[11] = 0
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        put16(bytes, 10, sum.inv() and 0xffff)
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        put16(bytes, offset, (value ushr 16).toInt() and 0xffff)
        put16(bytes, offset + 2, value.toInt() and 0xffff)
    }
}
