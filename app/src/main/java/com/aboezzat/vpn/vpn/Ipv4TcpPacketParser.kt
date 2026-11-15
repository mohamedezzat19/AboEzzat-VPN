package com.aboezzat.vpn.vpn

/**
 * Parses complete, unfragmented outbound IPv4/TCP packets for a future TCP
 * session engine. This is a packet decoder only: it does not create sockets,
 * acknowledge packets, or establish a VPN interface.
 */
object Ipv4TcpPacketParser {
    data class Packet(
        val tuple: NetworkConnectionTuple,
        val sequenceNumber: Long,
        val acknowledgementNumber: Long,
        val flags: Int,
        val windowSize: Int,
        val urgentPointer: Int,
        val ipHeaderLength: Int,
        val tcpHeaderLength: Int,
        val ipOptions: ByteArray,
        val tcpOptions: ByteArray,
        val payload: ByteArray
    ) {
        val isFin: Boolean get() = flags and FLAG_FIN != 0
        val isSyn: Boolean get() = flags and FLAG_SYN != 0
        val isRst: Boolean get() = flags and FLAG_RST != 0
        val isPsh: Boolean get() = flags and FLAG_PSH != 0
        val isAck: Boolean get() = flags and FLAG_ACK != 0
        val isUrg: Boolean get() = flags and FLAG_URG != 0
        val isEce: Boolean get() = flags and FLAG_ECE != 0
        val isCwr: Boolean get() = flags and FLAG_CWR != 0
        val isNs: Boolean get() = flags and FLAG_NS != 0
    }

    fun parse(packet: ByteArray): Packet {
        val tuple = Ipv4ConnectionTupleParser.parseOutbound(packet)
        require(tuple.protocol == IpTransportProtocol.TCP) { "IPv4 packet is not TCP." }

        val ipHeaderLength = (u8(packet, 0) and 0x0f) * 4
        val tcpOffset = ipHeaderLength
        val tcpLength = packet.size - tcpOffset
        val dataOffsetAndReserved = u8(packet, tcpOffset + 12)
        require((dataOffsetAndReserved and RESERVED_TCP_BITS) == 0) {
            "TCP reserved bits must be zero."
        }
        val tcpHeaderLength = (dataOffsetAndReserved ushr 4) * 4
        require(tcpHeaderLength in TCP_MIN_HEADER_BYTES..tcpLength) {
            "Invalid TCP data offset."
        }

        val flags = ((dataOffsetAndReserved and NS_MASK) shl 8) or u8(packet, tcpOffset + 13)
        val sourceAddress = packet.copyOfRange(12, 16)
        val destinationAddress = packet.copyOfRange(16, 20)
        require(validTcpChecksum(sourceAddress, destinationAddress, packet, tcpOffset, tcpLength)) {
            "Invalid TCP checksum."
        }

        val tcpOptions = packet.copyOfRange(tcpOffset + TCP_MIN_HEADER_BYTES, tcpOffset + tcpHeaderLength)
        validateTcpOptions(tcpOptions)

        return Packet(
            tuple = tuple,
            sequenceNumber = u32(packet, tcpOffset + 4),
            acknowledgementNumber = u32(packet, tcpOffset + 8),
            flags = flags,
            windowSize = u16(packet, tcpOffset + 14),
            urgentPointer = u16(packet, tcpOffset + 18),
            ipHeaderLength = ipHeaderLength,
            tcpHeaderLength = tcpHeaderLength,
            ipOptions = packet.copyOfRange(IPV4_MIN_HEADER_BYTES, ipHeaderLength),
            tcpOptions = tcpOptions,
            payload = packet.copyOfRange(tcpOffset + tcpHeaderLength, packet.size)
        )
    }

    private fun validateTcpOptions(options: ByteArray) {
        var index = 0
        while (index < options.size) {
            when (val kind = u8(options, index)) {
                OPTION_END -> return
                OPTION_NOP -> index += 1
                else -> {
                    require(index + 1 < options.size) { "Truncated TCP option length." }
                    val length = u8(options, index + 1)
                    require(length >= 2 && index + length <= options.size) {
                        "Invalid TCP option length for kind $kind."
                    }
                    index += length
                }
            }
        }
    }

    private fun validTcpChecksum(
        source: ByteArray,
        destination: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        var sum = 0L
        for (index in 0 until 4 step 2) {
            sum += ((source[index].toInt() and 0xff) shl 8) or (source[index + 1].toInt() and 0xff)
            sum += ((destination[index].toInt() and 0xff) shl 8) or (destination[index + 1].toInt() and 0xff)
        }
        sum += TCP_PROTOCOL
        sum += length
        sum = addWords(sum, packet, offset, length)
        return finishChecksum(sum) == 0
    }

    private fun addWords(initial: Long, bytes: ByteArray, offset: Int, length: Int): Long {
        var sum = initial
        var index = 0
        while (index + 1 < length) {
            sum += ((bytes[offset + index].toInt() and 0xff) shl 8) or
                (bytes[offset + index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < length) sum += (bytes[offset + index].toInt() and 0xff) shl 8
        return sum
    }

    private fun finishChecksum(value: Long): Int {
        var sum = value
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((u16(bytes, offset).toLong() shl 16) or u16(bytes, offset + 2).toLong()) and 0xffff_ffffL

    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val TCP_MIN_HEADER_BYTES = 20
    private const val TCP_PROTOCOL = 6
    private const val RESERVED_TCP_BITS = 0x0e
    private const val NS_MASK = 0x01
    private const val OPTION_END = 0
    private const val OPTION_NOP = 1

    const val FLAG_FIN = 0x001
    const val FLAG_SYN = 0x002
    const val FLAG_RST = 0x004
    const val FLAG_PSH = 0x008
    const val FLAG_ACK = 0x010
    const val FLAG_URG = 0x020
    const val FLAG_ECE = 0x040
    const val FLAG_CWR = 0x080
    const val FLAG_NS = 0x100
}
