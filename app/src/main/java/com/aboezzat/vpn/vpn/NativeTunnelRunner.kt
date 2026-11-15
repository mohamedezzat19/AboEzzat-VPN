package com.aboezzat.vpn.vpn

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the native run/stop ordering. The TUN descriptor must stay open for
 * the complete blocking native run call and is closed only after it returns.
 */
internal class NativeTunnelRunner(
    private val executor: ExecutorService,
    private val runEngine: (String, Int, Boolean) -> Int,
    private val stopEngine: () -> Int,
    private val onFinished: (Int) -> Unit
) {
    private val lock = Any()
    private var active = false

    fun isActive(): Boolean = synchronized(lock) { active }

    fun start(
        cliArgs: String,
        mtu: Int,
        packetInformation: Boolean = false,
        closeTunDescriptor: () -> Unit
    ): Boolean = synchronized(lock) {
        if (active) return@synchronized false
        active = true
        try {
            executor.execute {
                val result = try {
                    runEngine(cliArgs, mtu, packetInformation)
                } catch (_: Throwable) {
                    NATIVE_RUN_EXCEPTION
                } finally {
                    // Do not close the Android PFD until native run has
                    // completely returned and released its raw-fd use.
                }
                try {
                    closeTunDescriptor()
                } finally {
                    synchronized(lock) { active = false }
                    onFinished(result)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            active = false
            false
        }
    }

    fun stop(): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        try {
            stopEngine() >= 0
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val NATIVE_RUN_EXCEPTION = -100
    }
}
