package com.aboezzat.vpn.vpn

/**
 * Strict parser for the question section of a standard DNS query message.
 *
 * This extracts the requested name/type only. It does not resolve names,
 * forward DNS over a proxy, synthesize responses, or handle TCP-framed DNS.
 */
object DnsQueryParser {
    data class Question(
        val transactionId: Int,
        val name: String,
        val type: Int,
        val queryClass: Int,
        val recursionDesired: Boolean
    )

    fun parse(packet: ByteArray): Question? {
        if (packet.size < HEADER_SIZE + 5) return null
        val id = u16(packet, 0)
        val flags = u16(packet, 2)
        val questionCount = u16(packet, 4)
        if ((flags and 0x8000) != 0) return null // response, not query
        if (((flags ushr 11) and 0x0f) != 0) return null // unsupported opcode
        if (questionCount != 1) return null

        var offset = HEADER_SIZE
        val labels = ArrayList<String>()
        var totalNameLength = 0
        while (true) {
            if (offset >= packet.size) return null
            val length = packet[offset++].toInt() and 0xff
            if (length == 0) break
            // Compression pointers are not valid in a conventional query QNAME.
            if ((length and 0xc0) != 0 || length > 63) return null
            if (offset + length > packet.size) return null
            totalNameLength += length + 1
            if (totalNameLength > 255) return null
            val labelBytes = packet.copyOfRange(offset, offset + length)
            if (labelBytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) return null
            labels += labelBytes.toString(Charsets.US_ASCII)
            offset += length
        }

        if (labels.isEmpty() || offset + 4 != packet.size) return null
        val type = u16(packet, offset)
        val queryClass = u16(packet, offset + 2)
        if (type == 0 || queryClass == 0) return null

        return Question(
            transactionId = id,
            name = labels.joinToString(".").lowercase(),
            type = type,
            queryClass = queryClass,
            recursionDesired = (flags and 0x0100) != 0
        )
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)

    private const val HEADER_SIZE = 12
}
