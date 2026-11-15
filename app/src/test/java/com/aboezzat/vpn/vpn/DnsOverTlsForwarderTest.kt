package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsOverTlsForwarderTest {
    @Test
    fun rejectsMalformedQueryBeforeOpeningProxySocket() {
        val forwarder = DnsOverTlsForwarder()
        val profile = ProxyProfile(
            name = "test",
            host = "127.0.0.1",
            port = 1,
            protocol = ProxyProtocol.HTTP_CONNECT
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            forwarder.forward(profile, "resolver.example", ByteArray(12))
        }
        assertEquals(
            "DNS query is malformed or uses an unsupported query layout.",
            error.message
        )
    }

    @Test
    fun rejectsBlankResolverBeforeOpeningProxySocket() {
        val forwarder = DnsOverTlsForwarder()
        val profile = ProxyProfile(
            name = "test",
            host = "127.0.0.1",
            port = 1,
            protocol = ProxyProtocol.HTTP_CONNECT
        )
        assertThrows(IllegalArgumentException::class.java) {
            forwarder.forward(profile, " ", validQuery())
        }
    }

    @Test
    fun rejectsInvalidTimeoutAndMessageLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsOverTlsForwarder(connectTimeoutMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsOverTlsForwarder(maxMessageBytes = 11)
        }
    }

    private fun validQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
        'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01
    )
}
