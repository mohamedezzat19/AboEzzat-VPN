package com.aboezzat.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSocketProtectionTest {

    @Test
    fun rejectsNegativeFileDescriptorWithoutCallingAndroid() {
        var called = false

        val result = NativeSocketProtection.protect(-1) {
            called = true
            true
        }

        assertFalse(result)
        assertFalse(called)
    }

    @Test
    fun returnsAndroidProtectionResult() {
        assertTrue(NativeSocketProtection.protect(42) { it == 42 })
        assertFalse(NativeSocketProtection.protect(42) { false })
    }

    @Test
    fun failsClosedWhenProtectionCallbackThrows() {
        val result = NativeSocketProtection.protect(42) {
            throw IllegalStateException("protection failed")
        }

        assertFalse(result)
    }
}
