package com.aboezzat.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyBrowserConfigTest {
    @Test
    fun buildsHttpProxyUrl() {
        assertEquals("http://10.100.93.82:8080", ProxyBrowserConfig.buildProxyUrl("10.100.93.82", 8080, "HTTP_CONNECT"))
    }

    @Test
    fun buildsSocksProxyUrl() {
        assertEquals("socks://10.100.93.82:1088", ProxyBrowserConfig.buildProxyUrl("10.100.93.82", 1088, "SOCKS5"))
    }

    @Test
    fun bracketsIpv6ProxyHost() {
        assertEquals("socks://[2001:db8::1]:1088", ProxyBrowserConfig.buildProxyUrl("2001:db8::1", 1088, "SOCKS5"))
    }

    @Test
    fun normalizesBareHostToHttps() {
        assertEquals("https://example.com", ProxyBrowserConfig.normalizeUrl(" example.com "))
    }

    @Test
    fun preservesExplicitHttpUrl() {
        assertEquals("http://example.com", ProxyBrowserConfig.normalizeUrl("http://example.com"))
    }
}
