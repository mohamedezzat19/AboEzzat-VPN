package com.aboezzat.vpn.proxy

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relays bytes bidirectionally between an accepted client TCP socket and an
 * already-established upstream proxy tunnel.
 *
 * The caller owns the executor and must provide a bounded, long-lived pool.
 * The session owns both sockets after start() succeeds and closes them when
 * both directions finish, either direction fails, or close() is called.
 *
 * This is a stream relay primitive only: it does not parse IP/TCP packets,
 * create a TUN interface, or implement TCP state tracking for VPN packets.
 */
object TcpStreamRelay {
    fun start(
        client: Socket,
        upstream: Socket,
        executor: ExecutorService,
        onClosed: (() -> Unit)? = null
    ): Session {
        require(client !== upstream) { "Client and upstream sockets must be different." }
        require(!client.isClosed && !upstream.isClosed) { "Both sockets must be open." }

        val session = Session(client, upstream, onClosed)
        try {
            session.submit(executor, client, upstream)
            session.submit(executor, upstream, client)
        } catch (error: Exception) {
            session.close()
            throw error
        }
        return session
    }

    class Session internal constructor(
        private val client: Socket,
        private val upstream: Socket,
        private val onClosed: (() -> Unit)?
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val completedDirections = AtomicInteger(0)
        @Volatile private var first: Future<*>? = null
        @Volatile private var second: Future<*>? = null

        internal fun submit(executor: ExecutorService, source: Socket, destination: Socket) {
            val task = executor.submit {
                try {
                    source.getInputStream().use { input ->
                        val output = destination.getOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (!closed.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                output.flush()
                            }
                        }
                    }
                    try {
                        destination.shutdownOutput()
                    } catch (_: IOException) {
                        // The peer may already have closed the connection.
                    }
                    if (completedDirections.incrementAndGet() == 2) close()
                } catch (_: IOException) {
                    close()
                } catch (_: RuntimeException) {
                    close()
                }
            }
            synchronized(this) {
                if (first == null) first = task else second = task
                if (closed.get()) task.cancel(true)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { client.close() } catch (_: IOException) { }
            try { upstream.close() } catch (_: IOException) { }
            first?.cancel(true)
            second?.cancel(true)
            try { onClosed?.invoke() } catch (_: RuntimeException) { }
        }

        val isClosed: Boolean
            get() = closed.get()
    }
}
