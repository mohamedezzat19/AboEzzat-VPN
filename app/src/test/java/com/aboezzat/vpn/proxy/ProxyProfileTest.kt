package com.aboezzat.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyProfileTest {
    private fun profile(name: String = "Phone proxy", host: String = "192.168.1.10", port: Int = 8080, username: String? = null, password: String? = null) =
        ProxyProfile(name, host, port, ProxyProtocol.HTTP_CONNECT, username, password)

    @Test fun acceptsValidUnauthenticatedProfile() { assertNull(profile().validate()) }
    @Test fun rejectsBlankName() { assertEquals("Profile name is required", profile(name = " ").validate()) }
    @Test fun rejectsWhitespaceInHost() { assertEquals("Enter a valid server IP or hostname", profile(host = "proxy server").validate()) }
    @Test fun acceptsPortBoundaries() { assertNull(profile(port = 1).validate()); assertNull(profile(port = 65535).validate()) }
    @Test fun rejectsOutOfRangePort() { assertEquals("Port must be between 1 and 65535", profile(port = 65536).validate()) }
    @Test fun requiresCredentialsAsAPair() {
        val message = "Username and password must both be provided, or both left empty"
        assertEquals(message, profile(username = "user").validate())
        assertEquals(message, profile(password = "secret").validate())
    }
    @Test fun acceptsCompleteCredentials() { assertNull(profile(username = "user", password = "secret").validate()) }
}
