package com.aboezzat.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeTunnelRunnerTest {
    @Test
    fun keepsTunDescriptorOpenUntilNativeRunReturns() {
        val executor = Executors.newSingleThreadExecutor()
        val nativeStarted = CountDownLatch(1)
        val allowNativeReturn = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val order = mutableListOf<String>()
        try {
            val runner = NativeTunnelRunner(
                executor = executor,
                runEngine = { _, _, _ ->
                    synchronized(order) { order.add("native-started") }
                    nativeStarted.countDown()
                    assertTrue(allowNativeReturn.await(2, TimeUnit.SECONDS))
                    synchronized(order) { order.add("native-returned") }
                    0
                },
                stopEngine = { 0 },
                onFinished = { finished.countDown() }
            )

            assertTrue(runner.start("args", 1500) {
                synchronized(order) { order.add("tun-closed") }
            })
            assertTrue(nativeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(runner.isActive())
            allowNativeReturn.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))

            assertEquals(
                listOf("native-started", "native-returned", "tun-closed"),
                synchronized(order) { order.toList() }
            )
            assertFalse(runner.isActive())
        } finally {
            allowNativeReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsSecondStartAndForwardsStopRequest() {
        val executor = Executors.newSingleThreadExecutor()
        val nativeStarted = CountDownLatch(1)
        val allowNativeReturn = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val finished = CountDownLatch(1)
        try {
            val runner = NativeTunnelRunner(
                executor = executor,
                runEngine = { _, _, _ ->
                    nativeStarted.countDown()
                    allowNativeReturn.await(2, TimeUnit.SECONDS)
                    0
                },
                stopEngine = { stopCalls.incrementAndGet(); 0 },
                onFinished = { finished.countDown() }
            )
            assertTrue(runner.start("args", 1500) {})
            assertTrue(nativeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(runner.start("other", 1500) {})
            assertTrue(runner.stop())
            assertEquals(1, stopCalls.get())
            allowNativeReturn.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            allowNativeReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closesDescriptorEvenWhenNativeRunThrows() {
        val executor = Executors.newSingleThreadExecutor()
        val finished = CountDownLatch(1)
        val closed = AtomicInteger()
        val result = AtomicInteger(0)
        try {
            val runner = NativeTunnelRunner(
                executor = executor,
                runEngine = { _, _, _ -> error("native unavailable") },
                stopEngine = { 0 },
                onFinished = { result.set(it); finished.countDown() }
            )
            assertTrue(runner.start("args", 1500) { closed.incrementAndGet() })
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, closed.get())
            assertEquals(NativeTunnelRunner.NATIVE_RUN_EXCEPTION, result.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
