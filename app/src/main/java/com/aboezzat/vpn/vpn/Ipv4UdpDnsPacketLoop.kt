package com.aboezzat.vpn.vpn

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TUN-independent packet loop. A real TUN adapter can implement PacketSource
 * and PacketSink around the file descriptor after this loop is validated.
 *
 * Per-packet DNS/resolver failures are counted and skipped; a source or sink
 * I/O failure exits the loop. This class does not create a VPN interface.
 */
interface DnsPacketSource : Closeable {
    /** Returns the next complete IP packet, or null when the source reaches EOF. */
    fun readPacket(): ByteArray?
}

fun interface DnsPacketSink {
    /** Writes one complete response IP packet back to the packet interface. */
    fun writePacket(packet: ByteArray)
}

class Ipv4UdpDnsPacketLoop(
    private val source: DnsPacketSource,
    private val sink: DnsPacketSink,
    private val processor: Ipv4UdpDnsPacketProcessor,
    private val onPacketError: (Exception) -> Unit = {}
) {
    private val stopped = AtomicBoolean(false)
    private val sourceClosed = AtomicBoolean(false)
    private val processedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)

    val processedPackets: Long get() = processedCount.get()
    val failedPackets: Long get() = failedCount.get()

    /**
     * Runs on the caller's worker thread until EOF, stop(), or an I/O failure.
     * Call stop() from another thread to close a blocking source read.
     */
    fun run() {
        try {
            while (!stopped.get()) {
                val packet = source.readPacket() ?: break
                val response = try {
                    processor.process(packet)
                } catch (error: Exception) {
                    failedCount.incrementAndGet()
                    try {
                        onPacketError(error)
                    } catch (_: Exception) {
                        // Diagnostics must not terminate packet processing.
                    }
                    continue
                }
                sink.writePacket(response)
                processedCount.incrementAndGet()
            }
        } finally {
            closeSource()
        }
    }

    fun stop() {
        stopped.set(true)
        closeSource()
    }

    private fun closeSource() {
        if (sourceClosed.compareAndSet(false, true)) {
            source.close()
        }
    }
}
