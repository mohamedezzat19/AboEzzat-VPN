package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Tun2ProxyCommandBuilderTest {
    @Test
    fun buildsHttpConnectCommandWithOwnedTunDescriptor() {
        val profile = ProxyProfile(
            name = "HTTP upstream",
            host = "proxy.example",
            port = 8080,
            protocol = ProxyProtocol.HTTP_CONNECT
        )

        val command = Tun2ProxyCommandBuilder.build(profile, tunFd = 42)

        assertTrue(command!!.startsWith("'tun2proxy-bin' "))
        assertTrue(command.contains("'--tun-fd' '42'"))
        assertTrue(command.contains("'--close-fd-on-drop' 'false'"))
        assertTrue(command.contains("'--proxy' 'http://proxy.example:8080'"))
        assertTrue(command.contains("'--dns' 'virtual'"))
        // IPv6 stays routed by Android's TUN, but AAAA answers are filtered
        // until this upstream proxy can relay IPv6 destination literals.
        assertTrue(!command.contains("'--ipv6-enabled'"))
        assertTrue(command.contains("'--mtu' '1500'"))
        assertTrue(command.contains("'--tcp-mss' '1440'"))
        assertTrue(!command.contains("'--udp-enabled'"))
        assertTrue(!command.contains("'--udp-timeout'"))
    }

    @Test
    fun percentEncodesCredentialsAndBracketsIpv6ProxyHost() {
        val profile = ProxyProfile(
            name = "SOCKS upstream",
            host = "2001:db8::10",
            port = 1080,
            protocol = ProxyProtocol.SOCKS5,
            username = "user name",
            password = "p@ss:/#?"
        )

        val command = Tun2ProxyCommandBuilder.build(profile, tunFd = 9)

        assertTrue(command!!.contains("socks5://user%20name:p%40ss%3A%2F%23%3F@[2001:db8::10]:1080"))
        assertTrue(command.contains("'--udp-enabled'"))
        assertTrue(command.contains("'--udp-timeout' '30'"))
        assertTrue(!command.contains("p@ss:/#?"))
    }

    @Test
    fun usesTcpDnsForwardingWhenVirtualRemoteDnsIsDisabled() {
        val profile = ProxyProfile(
            name = "HTTP",
            host = "proxy.example",
            port = 8080,
            protocol = ProxyProtocol.HTTP_CONNECT,
            remoteDns = false
        )

        val command = Tun2ProxyCommandBuilder.build(profile, tunFd = 8)

        assertTrue(command!!.contains("'--dns' 'over-tcp'"))
    }

    @Test
    fun rejectsInvalidDescriptorAndMtu() {
        val profile = ProxyProfile(
            name = "SOCKS",
            host = "127.0.0.1",
            port = 1080,
            protocol = ProxyProtocol.SOCKS5
        )

        assertNull(Tun2ProxyCommandBuilder.build(profile, tunFd = -1))
        assertNull(Tun2ProxyCommandBuilder.build(profile, tunFd = 3, mtu = 500))
        assertNull(Tun2ProxyCommandBuilder.build(profile, tunFd = 3, mtu = 65536))
    }
}
