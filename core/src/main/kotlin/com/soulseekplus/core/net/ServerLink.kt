package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.protocol.Frame
import com.soulseekplus.core.protocol.ProtocolException
import com.soulseekplus.core.protocol.ServerMessage
import com.soulseekplus.core.protocol.ServerMessages
import com.soulseekplus.core.protocol.ServerPing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sunucuyla tek bir TCP oturumu: bağlanma (DNS dahil), çerçeve okuma, mesaj çözme ve bir dakikada bir ServerPing.
 * Her bağlantı için yeni bir örnek oluşturulur; [close] sonrası geri çağrılar [onClosed] dışında gelmez.
 */
class ServerLink private constructor(
    val connection: Connection,
    private val logger: Logger,
) {
    private val finished = AtomicBoolean(false)

    val isClosed: Boolean get() = connection.isClosed

    /** Mesajı çerçeveleyip kuyruğa alır; bağlantı kapalıysa false. */
    fun send(message: ServerMessage): Boolean = connection.send(message.encode())

    fun close() {
        connection.close()
    }

    /**
     * Okuyucuyu başlatır. [onMessage] ve [onClosed] [logic] iş parçacığında, sırayla çağrılır. [onClosed] tam bir kez çağrılır.
     */
    fun start(
        ioScope: CoroutineScope,
        logic: CoroutineDispatcher,
        pingIntervalMillis: Long,
        onMessage: (ServerMessage) -> Unit,
        onClosed: (reason: String?) -> Unit,
    ) {
        ioScope.launch {
            var reason: String? = null
            try {
                readLoop(logic, onMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EOFException) {
                reason = "Connection closed by server"
            } catch (e: ProtocolException) {
                reason = "Protocol error: ${e.message}"
            } catch (e: IOException) {
                reason = e.message ?: "Connection lost"
            } catch (e: Exception) {
                logger.warn(TAG, "Sunucu okuyucusu beklenmedik hata", e)
                reason = "Internal error"
            } finally {
                connection.close()
                if (finished.compareAndSet(false, true)) {
                    withContext(logic + kotlinx.coroutines.NonCancellable) { onClosed(reason) }
                }
            }
        }
        ioScope.launch {
            while (isActive && !connection.isClosed) {
                delay(pingIntervalMillis)
                if (!send(ServerPing)) break
            }
        }
    }

    private suspend fun readLoop(logic: CoroutineDispatcher, onMessage: (ServerMessage) -> Unit) {
        while (true) {
            val length = connection.readUInt32()
            if (length < 4 || length > Frame.MAX_SERVER_MESSAGE) throw ProtocolException("Geçersiz sunucu mesaj uzunluğu $length")
            val code = connection.readInt32()
            val payload = connection.readBytes((length - 4).toInt())
            val message = try {
                ServerMessages.decode(code, payload)
            } catch (e: ProtocolException) {
                logger.warn(TAG, "Bozuk sunucu mesajı (kod $code): ${e.message}")
                null
            } ?: continue
            withContext(logic) { onMessage(message) }
        }
    }

    companion object {
        private const val TAG = "ServerLink"

        /**
         * [host]:[port] adresine bağlanır. DNS çözümlemesi engelleyici olduğundan IO dağıtıcısında yapılır; IPv4 adresleri
         * önce denenir.
         * @throws IOException bağlanılamadı.
         */
        suspend fun connect(
            reactor: Reactor,
            ioScope: CoroutineScope,
            host: String,
            port: Int,
            timeoutMillis: Long,
            logger: Logger,
        ): ServerLink {
            val addresses = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getAllByName(host).toList()
                } catch (e: java.net.UnknownHostException) {
                    throw IOException("Cannot resolve $host", e)
                }
            }.sortedBy { if (it is Inet4Address) 0 else 1 }
            var last: IOException? = null
            for (address in addresses) {
                try {
                    val socket = reactor.connect(InetSocketAddress(address, port), timeoutMillis, keepAlive = true)
                    val connection = Connection(socket, ioScope, logger, "server")
                    connection.keepOpen = true
                    return ServerLink(connection, logger)
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("No address for $host")
        }
    }
}
