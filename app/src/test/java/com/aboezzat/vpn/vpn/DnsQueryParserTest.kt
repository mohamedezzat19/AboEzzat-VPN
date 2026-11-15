package com.aboezzat.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsQueryParserTest {
    @Test
    fun parsesSingleAQuestion() {
        val packet = query("Example.COM", type = 1, rd = true)
        val parsed = DnsQueryParser.parse(packet)
        assertEquals(0x1234, parsed?.transactionId)
        assertEquals("example.com", parsed?.name)
        assertEquals(1, parsed?.type)
        assertEquals(1, parsed?.queryClass)
        assertTrue(parsed?.recursionDesired == true)
    }

    @Test
    fun parsesAaaaQuestionAndRecursionFlag() {
        val parsed = DnsQueryParser.parse(query("ipv6.example", type = 28, rd = false))
        assertEquals(28, parsed?.type)
        assertFalse(parsed?.recursionDesired ?: true)
    }

    @Test
    fun rejectsResponseMalformedAndUnsupportedQueries() {
        val valid = query("example.com", 1, true)
        val response = valid.copyOf().also { it[2] = 0x81.toByte() }
        assertNull(DnsQueryParser.parse(response))
        assertNull(DnsQueryParser.parse(valid.copyOf(valid.size - 2)))
        assertNull(DnsQueryParser.parse(ByteArray(10)))
        val multipleQuestions = valid.copyOf().also { it[5] = 2 }
        assertNull(DnsQueryParser.parse(multipleQuestions))
        val compressedName = valid.copyOf().also { it[12] = 0xc0.toByte() }
        assertNull(DnsQueryParser.parse(compressedName))
    }

    private fun query(name: String, type: Int, rd: Boolean): ByteArray {
        val bytes = ArrayList<Byte>()
        fun add(value: Int) { bytes += value.toByte() }
        add(0x12); add(0x34)
        add(0x01); add(if (rd) 0x00 else 0x00)
        add(0x00); add(0x01) // QDCOUNT
        repeat(3) { add(0) } // ANCOUNT, NSCOUNT, high ARCOUNT
        add(0)
        name.split(".").forEach { label ->
            add(label.length)
            label.toByteArray(Charsets.US_ASCII).forEach { bytes += it }
        }
        add(0)
        add(type shr 8); add(type and 0xff)
        add(0); add(1)
        return bytes.toByteArray().also { if (rd) it[2] = 0x01 }
    }
}
