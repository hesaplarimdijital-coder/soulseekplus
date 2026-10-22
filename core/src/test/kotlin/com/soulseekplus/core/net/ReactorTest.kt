package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

class ReactorTest {

    private lateinit var reactor: Reactor
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        reactor = Reactor(Logger.NONE, "test-selector")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        reactor.close()
    }

    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = payload.size.toByte()
        out[1] = (payload.size ushr 8).toByte()
        out[2] = (payload.size ushr 16).toByte()
        out[3] = (payload.size ushr 24).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    @Test
    fun echoOfALargeFrameKeepsEveryByte() = runBlocking {
        val accepted = CopyOnWriteArrayList<Connection>()
        val listener = reactor.listen(0) { socket ->
            val conn = Connection(socket, scope, Logger.NONE, "server-side")
            accepted.add(conn)
            scope.launch {
                try {
                    val length = conn.readInt32()
                    val data = conn.readBytes(length)
                    conn.send(frame(data))
                } catch (_: IOException) {
                    conn.close()
                }
            }
        }
        try {
            val payload = ByteArray(3 * 1024 * 1024 + 17).also { Random(7).nextBytes(it) }
            val socket = reactor.connect(InetSocketAddress(loopback, listener.port), 5_000)
            val client = Connection(socket, scope, Logger.NONE, "client")
            try {
                client.send(frame(payload))
                val echoed = withTimeout(15_000) {
                    val length = client.readInt32()
                    client.readBytes(length)
                }
                assertArrayEquals(payload, echoed)
                assertTrue(client.bytesRead.get() >= payload.size)
                assertTrue(client.bytesWritten.get() >= payload.size)
            } finally {
                client.close()
            }
        } finally {
            listener.close()
            accepted.forEach { it.close() }
        }
    }

    @Test
    fun closeWhenFlushedDeliversEverythingThenEndsTheStream() = runBlocking {
        val payload = ByteArray(512 * 1024).also { Random(3).nextBytes(it) }
        val listener = reactor.listen(0) { socket ->
            val conn = Connection(socket, scope, Logger.NONE, "server-side")
            conn.send(payload)
            conn.closeWhenFlushed()
        }
        try {
            val socket = reactor.connect(InetSocketAddress(loopback, listener.port), 5_000)
            val client = Connection(socket, scope, Logger.NONE, "client")
            try {
                val received = withTimeout(10_000) { client.readBytes(payload.size) }
                assertArrayEquals(payload, received)
                try {
                    withTimeout(10_000) { client.readUInt8() }
                    fail("EOFException bekleniyordu")
                } catch (_: EOFException) {
                }
            } finally {
                client.close()
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun connectionToAClosedPortFailsWithIoException() = runBlocking {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        try {
            withTimeout(10_000) { reactor.connect(InetSocketAddress(loopback, port), 3_000) }
            fail("IOException bekleniyordu")
        } catch (_: IOException) {
        }
    }

    @Test
    fun listenerStopsAcceptingAfterClose() = runBlocking {
        val listener = reactor.listen(0) { it.close() }
        val port = listener.port
        listener.close()
        try {
            withTimeout(10_000) { reactor.connect(InetSocketAddress(loopback, port), 3_000) }
            fail("IOException bekleniyordu")
        } catch (_: IOException) {
        }
    }

    @Test
    fun closeListenersFireExactlyOnceAndPendingReadsEnd() = runBlocking {
        val listener = reactor.listen(0) { socket -> Connection(socket, scope, Logger.NONE, "s") }
        try {
            val client = Connection(reactor.connect(InetSocketAddress(loopback, listener.port), 5_000), scope, Logger.NONE, "c")
            var closes = 0
            client.onClose { closes++ }
            val reader = scope.launch {
                try {
                    client.readUInt8()
                } catch (_: IOException) {
                }
            }
            client.close()
            client.close()
            withTimeout(5_000) { reader.join() }
            assertEquals(1, closes)
            assertTrue(client.isClosed)
            assertTrue(!client.send(byteArrayOf(1)))
        } finally {
            listener.close()
        }
    }
}
