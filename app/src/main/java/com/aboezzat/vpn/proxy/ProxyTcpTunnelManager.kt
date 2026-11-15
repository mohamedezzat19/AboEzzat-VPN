package com.aboezzat.vpn.proxy

import java.io.IOException
import java.net.Socket

/**
 * Owns the lifecycle of concurrently opened upstream TCP tunnels.
 *
 * Call release() when the consumer finishes with a socket. closeAll() closes
 * every currently registered tunnel but keeps the manager reusable.
 * close()/shutdown() permanently rejects new tunnels and closes active ones.
 *
 * This manager does not detect a remote half-close by itself; the relay owner
 * must call release() from its finally block when either side finishes.
 */
class ProxyTcpTunnelManager(
    private val maxConcurrentTunnels: Int = DEFAULT_MAX_CONCURRENT_TUNNELS
) : AutoCloseable {
    private val lock = Any()
    private val activeTunnels = LinkedHashSet<Socket>()
    private var openings = 0
    private var closed = false

    init {
        require(maxConcurrentTunnels > 0) { "Maximum concurrent tunnels must be positive." }
    }

    val activeCount: Int
        get() = synchronized(lock) { activeTunnels.size }

    val isShutdown: Boolean
        get() = synchronized(lock) { closed }

    @Throws(IOException::class)
    fun openTunnel(
        profile: ProxyProfile,
        destinationHost: String,
        destinationPort: Int,
        connectTimeoutMs: Int = 7_000,
        readTimeoutMs: Int = 7_000,
        protectSocket: ((Socket) -> Boolean)? = null
    ): Socket {
        synchronized(lock) {
            if (closed) throw IOException("TCP tunnel manager is shut down.")
            if (activeTunnels.size + openings >= maxConcurrentTunnels) {
                throw IOException("Maximum concurrent TCP tunnel limit reached.")
            }
            openings++
        }

        val socket = try {
            ProxyTcpTunnel.open(
                profile = profile,
                destinationHost = destinationHost,
                destinationPort = destinationPort,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                protectSocket = protectSocket
            )
        } catch (error: Exception) {
            synchronized(lock) { openings-- }
            throw error
        }

        val accepted = synchronized(lock) {
            openings--
            if (closed) false else activeTunnels.add(socket)
        }
        if (!accepted) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Preserve the manager shutdown result.
            }
            throw IOException("TCP tunnel manager was shut down while the tunnel was opening.")
        }
        return socket
    }

    /** Removes and closes a tunnel. Safe to call more than once. */
    fun release(socket: Socket): Boolean {
        val wasManaged = synchronized(lock) { activeTunnels.remove(socket) }
        if (wasManaged) {
            try {
                socket.close()
            } catch (_: IOException) {
                // The tunnel is removed from ownership even if close reports an error.
            }
        }
        return wasManaged
    }

    /** Closes all active tunnels but allows subsequent openTunnel() calls. */
    fun closeAll() {
        val sockets = synchronized(lock) {
            activeTunnels.toList().also { activeTunnels.clear() }
        }
        sockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
                // Continue closing the remaining tunnels.
            }
        }
    }

    /** Permanently shuts down this manager and closes every active tunnel. */
    fun shutdown() {
        synchronized(lock) { closed = true }
        closeAll()
    }

    override fun close() = shutdown()

    companion object {
        const val DEFAULT_MAX_CONCURRENT_TUNNELS = 64
    }
}
