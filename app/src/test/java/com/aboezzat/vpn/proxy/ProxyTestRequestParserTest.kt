package com.aboezzat.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProxyTestRequestParserTest {
    private fun parse(
        name: String? = "Phone proxy",
        host: String? = "192.168.1.10",
        port: Int = 8080,
        protocol: String? = ProxyProtocol.HTTP_CONNECT.name,
        username: String? = null,
        password: String? = null,
        remoteDns: Boolean = true
    ) = ProxyTestRequestParser.parse(name, host, port, protocol, username, password, remoteDns)

    @Test fun parsesValidHttpProfile() {
        val profile = parse()
        assertNotNull(profile)
        assertEquals(ProxyProtocol.HTTP_CONNECT, profile?.protocol)
    }

    @Test fun defaultsMissingProtocolToHttpConnect() {
        assertEquals(ProxyProtocol.HTTP_CONNECT, parse(protocol = null)?.protocol)
    }

    @Test fun parsesSocks5AndPreservesOptions() {
        val profile = parse(
            protocol = ProxyProtocol.SOCKS5.name,
            username = "user",
            password = "secret",
            remoteDns = false
        )
        assertEquals(ProxyProtocol.SOCKS5, profile?.protocol)
        assertEquals("user", profile?.username)
        assertEquals("secret", profile?.password)
        assertEquals(false, profile?.remoteDns)
    }

    @Test fun rejectsUnknownProtocol() {
        assertNull(parse(protocol = "SOCKS4"))
    }

    @Test fun rejectsMissingNameOrHost() {
        assertNull(parse(name = null))
        assertNull(parse(host = null))
    }

    @Test fun rejectsInvalidPort() {
        assertNull(parse(port = 0))
        assertNull(parse(port = 65536))
    }

    @Test fun rejectsIncompleteCredentials() {
        assertNull(parse(username = "user"))
        assertNull(parse(password = "secret"))
    }
}
