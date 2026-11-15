package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsTcpForwarderTest {
    @Test
    fun rejectsMalformedDnsQueryBeforeOpeningProxySocket() {
        val forwarder = DnsTcpForwarder()
        val profile = ProxyProfile(
            name = "test",
            host = "127.0.0.1",
            port = 1,
            protocol = ProxyProtocol.HTTP_CONNECT
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            forwarder.forward(profile, "127.0.0.1", ByteArray(12))
        }
        assertEquals("DNS query is malformed or uses an unsupported query layout.", error.message)
    }

    @Test
    fun rejectsInvalidConstructorLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsTcpForwarder(maxMessageBytes = 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsTcpForwarder(readTimeoutMs = 0)
        }
    }
}
