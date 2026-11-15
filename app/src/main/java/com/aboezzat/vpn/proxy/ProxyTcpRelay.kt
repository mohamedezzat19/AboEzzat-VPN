package com.aboezzat.vpn.proxy

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService

/**
 * Connects a client TCP socket to a destination through the configured
 * upstream proxy, then relays bytes in both directions.
 *
 * Stream-level bridge only: it does not create/read a TUN interface, recover
 * packet destinations, or enforce Android per-app routing.
 *
 * The returned session owns both sockets. The supplied manager and executor
 * remain owned by the caller.
 */
class ProxyTcpRelay(
    private val tunnelManager: ProxyTcpTunnelManager,
    private val relayExecutor: ExecutorService
) {
    @Throws(IOException::class)
    fun open(
        clientSocket: Socket,
        profile: ProxyProfile,
        destinationHost: String,
        destinationPort: Int,
        protectSocket: ((Socket) -> Boolean)? = null
    ): TcpStreamRelay.Session {
        val upstream = try {
            tunnelManager.openTunnel(
                profile = profile,
                destinationHost = destinationHost,
                destinationPort = destinationPort,
                protectSocket = protectSocket
            )
        } catch (error: Exception) {
            closeQuietly(clientSocket)
            throw error
        }

        return try {
            TcpStreamRelay.start(
                client = clientSocket,
                upstream = upstream,
                executor = relayExecutor,
                onClosed = { tunnelManager.release(upstream) }
            )
        } catch (error: Exception) {
            tunnelManager.release(upstream)
            closeQuietly(clientSocket)
            throw error
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Preserve the original open/relay failure.
        }
    }
}
