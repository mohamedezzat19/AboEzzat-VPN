package com.aboezzat.vpn.proxy

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpStreamRelayTest {
    @Test
    fun relaysBytesInBothDirectionsAndClosesAfterBothHalfCloses() {
        val clientListener = ServerSocket(0)
        val upstreamListener = ServerSocket(0)
        val pool = Executors.newFixedThreadPool(2)
        val closed = CountDownLatch(1)
        val clientPeer = Socket("127.0.0.1", clientListener.localPort)
        val clientRelay = clientListener.accept()
        val upstreamPeer = Socket("127.0.0.1", upstreamListener.localPort)
        val upstreamRelay = upstreamListener.accept()
        val session = TcpStreamRelay.start(clientRelay, upstreamRelay, pool) { closed.countDown() }

        try {
            clientPeer.getOutputStream().write("client-to-upstream".toByteArray())
            clientPeer.shutdownOutput()
            val upstreamReceived = upstreamPeer.getInputStream().readNBytes(19)
            assertEquals("client-to-upstream", String(upstreamReceived))

            upstreamPeer.getOutputStream().write("upstream-to-client".toByteArray())
            upstreamPeer.shutdownOutput()
            val clientReceived = clientPeer.getInputStream().readNBytes(19)
            assertEquals("upstream-to-client", String(clientReceived))

            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(session.isClosed)
        } finally {
            session.close()
            clientPeer.close()
            upstreamPeer.close()
            clientListener.close()
            upstreamListener.close()
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun manualCloseClosesBothSockets() {
        val listenerA = ServerSocket(0)
        val listenerB = ServerSocket(0)
        val pool = Executors.newFixedThreadPool(2)
        val aPeer = Socket("127.0.0.1", listenerA.localPort)
        val aRelay = listenerA.accept()
        val bPeer = Socket("127.0.0.1", listenerB.localPort)
        val bRelay = listenerB.accept()
        val session = TcpStreamRelay.start(aRelay, bRelay, pool)
        try {
            session.close()
            assertTrue(session.isClosed)
            assertTrue(aRelay.isClosed)
            assertTrue(bRelay.isClosed)
        } finally {
            aPeer.close()
            bPeer.close()
            listenerA.close()
            listenerB.close()
            pool.shutdownNow()
        }
    }
}
