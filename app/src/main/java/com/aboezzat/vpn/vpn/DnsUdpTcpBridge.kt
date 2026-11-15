package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Localhost-only UDP-to-TCP DNS adapter.
 *
 * A future TUN packet engine may send intercepted DNS datagrams to this
 * loopback endpoint. This class does not capture device traffic or establish
 * a VPN interface by itself.
 */
class DnsUdpTcpBridge(
    private val forwarder: DnsTcpForwarder = DnsTcpForwarder(),
    private val maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : AutoCloseable {
    private val lock = Any()
    private val received = AtomicLong()
    private val forwarded = AtomicLong()
    private val dropped = AtomicLong()

    @Volatile private var datagramSocket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var executor: ThreadPoolExecutor? = null
    @Volatile private var running = false

    data class Stats(
        val received: Long,
        val forwarded: Long,
        val dropped: Long
    )

    init {
        require(maxConcurrentRequests > 0)
        require(queueCapacity > 0)
    }

    val isRunning: Boolean
        get() = running

    fun stats(): Stats = Stats(received.get(), forwarded.get(), dropped.get())

    /**
     * Starts a DNS listener bound only to 127.0.0.1 on an ephemeral port.
     * The returned address is intended for an in-process packet/TUN adapter.
     */
    @Throws(IOException::class)
    fun start(
        profile: ProxyProfile,
        resolverHost: String,
        protectSocket: ((Socket) -> Boolean)? = null
    ): InetSocketAddress = synchronized(lock) {
        check(!running) { "DNS UDP bridge is already running." }
        require(resolverHost.isNotBlank() && !resolverHost.any(Char::isWhitespace)) {
            "A resolver hostname or IP is required."
        }

        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0))
            val pool = ThreadPoolExecutor(
                maxConcurrentRequests,
                maxConcurrentRequests,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(queueCapacity),
                daemonThreadFactory(),
                ThreadPoolExecutor.AbortPolicy()
            )
            datagramSocket = socket
            executor = pool
            running = true
            worker = Thread({
                receiveLoop(socket, pool, profile, resolverHost, protectSocket)
            }, "AboEzzat-DNS-UDP-Bridge").apply {
                isDaemon = true
                start()
            }
            InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), socket.localPort)
        } catch (error: Exception) {
            running = false
            datagramSocket = null
            executor?.shutdownNow()
            executor = null
            socket.close()
            if (error is IOException) throw error
            throw IOException("Could not start the local DNS bridge.", error)
        }
    }

    private fun receiveLoop(
        socket: DatagramSocket,
        pool: ThreadPoolExecutor,
        profile: ProxyProfile,
        resolverHost: String,
        protectSocket: ((Socket) -> Boolean)?
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET_BYTES)
        try {
            while (running && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketException) {
                    if (running) dropped.incrementAndGet()
                    break
                }
                received.incrementAndGet()
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val clientAddress = packet.socketAddress
                try {
                    pool.execute {
                        try {
                            val response = forwarder.forward(profile, resolverHost, query, protectSocket)
                            if (running && response.size <= MAX_UDP_PACKET_BYTES) {
                                val reply = DatagramPacket(response, response.size, clientAddress)
                                socket.send(reply)
                                forwarded.incrementAndGet()
                            } else {
                                dropped.incrementAndGet()
                            }
                        } catch (_: Exception) {
                            // DNS transport failures are counted and dropped; never leak
                            // the query onto the device's direct network as a fallback.
                            dropped.incrementAndGet()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    dropped.incrementAndGet()
                }
            }
        } finally {
            running = false
        }
    }

    override fun close() {
        val socket: DatagramSocket?
        val pool: ThreadPoolExecutor?
        val thread: Thread?
        synchronized(lock) {
            running = false
            socket = datagramSocket
            pool = executor
            thread = worker
            datagramSocket = null
            executor = null
            worker = null
        }
        socket?.close()
        pool?.shutdownNow()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(CLOSE_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun daemonThreadFactory() = ThreadFactory { runnable ->
        Thread(runnable, "AboEzzat-DNS-Forward").apply { isDaemon = true }
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MAX_UDP_PACKET_BYTES = 65_507
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 8
        const val DEFAULT_QUEUE_CAPACITY = 32
        const val CLOSE_JOIN_TIMEOUT_MS = 1_000L
    }
}
