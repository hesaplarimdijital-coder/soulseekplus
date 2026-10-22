package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.protocol.PeerMessage
import com.soulseekplus.core.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class PeerNetworkTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val logic = executor.asCoroutineDispatcher()
    private val logicScope = CoroutineScope(SupervisorJob() + logic)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reactor = Reactor(Logger.NONE, "test-selector")

    private val host = object : PeerHost {
        override val username: String = "me"
        override fun sendToServer(message: ServerMessage): Boolean = true // sunucu hiç yanıt vermez
        override val isServerReady: Boolean = true
        override fun isSearchTokenActive(token: Int): Boolean = false
        override fun isSolicited(username: String, code: Int): Boolean = false
        override fun onPeerMessage(username: String, message: PeerMessage, connection: Connection) = Unit
        override fun onFileConnection(username: String, connection: Connection) = connection.close()
        override fun onDistributedConnection(username: String, connection: Connection) = connection.close()
    }

    private val peers = PeerNetwork(reactor, logicScope, logic, ioScope, host, Logger.NONE, NetworkTimeouts(addressLookupMillis = 10_000))

    @After
    fun tearDown() {
        logicScope.cancel()
        ioScope.cancel()
        reactor.close()
        executor.shutdownNow()
    }

    @Test
    fun outerTimeoutIsNotTurnedIntoAnIoError() = runBlocking {
        // Çağıranın kendi (daha kısa) zaman aşımı TimeoutCancellationException olarak kalmalı; IOException'a dönüşürse
        // yüklemeler "zaman aşımı" yerine "bağlantı kapandı" sayılıp yeniden denenmez.
        try {
            withContext(logic) { withTimeout(100) { peers.connect("ghost", "F") } }
            fail("zaman aşımı bekleniyordu")
        } catch (e: TimeoutCancellationException) {
            // beklenen
        } catch (e: SocketTimeoutException) {
            fail("dış zaman aşımı iç SocketTimeoutException'a dönüştü")
        }
    }

    @Test
    fun ownLookupTimeoutIsAnIoError() = runBlocking {
        val short = PeerNetwork(reactor, logicScope, logic, ioScope, host, Logger.NONE, NetworkTimeouts(addressLookupMillis = 50))
        try {
            withContext(logic) { short.lookupAddress("ghost") }
            fail("zaman aşımı bekleniyordu")
        } catch (e: SocketTimeoutException) {
            assertTrue(e.message!!.contains("ghost"))
        }
    }
}
