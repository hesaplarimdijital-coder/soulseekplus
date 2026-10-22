package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.protocol.CantConnectToPeerNotification
import com.soulseekplus.core.protocol.CantConnectToPeerRequest
import com.soulseekplus.core.protocol.ConnType
import com.soulseekplus.core.protocol.ConnectToPeerNotification
import com.soulseekplus.core.protocol.ConnectToPeerRequest
import com.soulseekplus.core.protocol.DistributedMessages
import com.soulseekplus.core.protocol.Frame
import com.soulseekplus.core.protocol.GetPeerAddressRequest
import com.soulseekplus.core.protocol.InitMessages
import com.soulseekplus.core.protocol.PeerAddressResponse
import com.soulseekplus.core.protocol.PeerCode
import com.soulseekplus.core.protocol.PeerInit
import com.soulseekplus.core.protocol.PeerMessage
import com.soulseekplus.core.protocol.PeerMessages
import com.soulseekplus.core.protocol.PierceFireWall
import com.soulseekplus.core.protocol.ProtocolException
import com.soulseekplus.core.protocol.ServerMessage
import com.soulseekplus.core.protocol.tokenToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Eşin adresi (GetPeerAddress). [port] 0 ise sunucu portu bilmiyor (yalnızca dolaylı bağlantı denenir). */
data class PeerAddress(val ip: String, val port: Int)

/** Bir eşe giden mesajın neden teslim edilemediği. */
enum class PeerFailure { OFFLINE, UNREACHABLE }

/** Kullanıcı çevrimdışı (adres 0.0.0.0). */
class PeerOfflineException(val username: String) : IOException("User offline: $username")

/** Zaman aşımı değerleri (testlerde kısaltılabilir). */
class NetworkTimeouts(
    val directConnectMillis: Long = 2_000,
    val indirectMillis: Long = 20_000,
    val addressLookupMillis: Long = 10_000,
    val addressTtlMillis: Long = 1_800_000,
    val initReadMillis: Long = 15_000,
    val idleMillis: Long = 60_000,
    val ghostMillis: Long = 10_000,
    val sweepIntervalMillis: Long = 5_000,
)

/** [PeerNetwork]'ün ana bilgisayarla (motor) sözleşmesi. Geri çağrılar mantık iş parçacığında yapılır, `thread-safe` olanlar hariç. */
interface PeerHost {
    /** Kendi kullanıcı adımız (girişsiz: boş). Thread-safe. */
    val username: String

    /** Sunucuya mesaj gönderir; bağlı değilse false. Thread-safe. */
    fun sendToServer(message: ServerMessage): Boolean

    /** Sunucuya giriş yapılmış mı. Thread-safe. */
    val isServerReady: Boolean

    /** Bu token'lı arama yanıtı bekleniyor mu. Thread-safe. */
    fun isSearchTokenActive(token: Int): Boolean

    /** [username]'den bu kodlu istenmemiş büyük yanıt (paylaşım listesi, kullanıcı bilgisi) kabul edilir mi. Thread-safe. */
    fun isSolicited(username: String, code: Int): Boolean

    fun onPeerMessage(username: String, message: PeerMessage, connection: Connection)

    /** Kurulmuş bir "F" bağlantısı (el sıkışma tamam; sonraki baytlar dosya akışıdır). Sahiplik devredilir. */
    fun onFileConnection(username: String, connection: Connection)

    /** Kurulmuş bir "D" bağlantısı (bize bağlanan ya da bizi çağıran). Sahiplik devredilir. */
    fun onDistributedConnection(username: String, connection: Connection)
}

/**
 * Eş bağlantı yöneticisi: dinleyici, doğrudan + dolaylı (PierceFireWall) bağlanma yarışı, "P" bağlantı önbelleği,
 * adres önbelleği, gelen başlangıç mesajı ayrıştırma ve boşta kalan bağlantı süpürücüsü.
 *
 * İş parçacığı: durumun tamamı [logicScope]'un tek iş parçacıklı dağıtıcısında yaşar; genel metotlar o iş parçacığından
 * çağrılmalıdır (kilitsiz). Ağ G/Ç'si [ioScope] içinde yürür ve sonuçlar `withContext(logic)` ile geri taşınır.
 */
class PeerNetwork(
    private val reactor: Reactor,
    private val logicScope: CoroutineScope,
    private val logic: CoroutineDispatcher,
    private val ioScope: CoroutineScope,
    private val host: PeerHost,
    private val logger: Logger,
    private val timeouts: NetworkTimeouts = NetworkTimeouts(),
) {
    private class PendingSend(val frame: ByteArray, val onFailure: ((PeerFailure) -> Unit)?, val closeAfter: Boolean)

    private class PeerEntry(val username: String) {
        var primary: Connection? = null
        val others = ArrayList<Connection>(2)
        var connecting: Job? = null
        val pending = ArrayList<PendingSend>()
        val isIdle: Boolean get() = primary == null && others.isEmpty() && connecting == null && pending.isEmpty()
    }

    /** Giden bağlantı denemesi: doğrudan ve dolaylı yolun yarışı. */
    private class Attempt(val username: String, val type: String, val token: Int, val deadlineNanos: Long, directPossible: Boolean) {
        val signal = CompletableDeferred<Unit>()

        /** Doğrudan yol başarısız olunca çağrılır ("D" için dolaylı isteği o zaman göndermek üzere). */
        @Volatile
        var onDirectFailure: (() -> Unit)? = null
        private var connection: Connection? = null
        private var state = OPEN
        private var directFailed = !directPossible
        private var indirectFailed = false
        private var failure: Throwable? = null

        @Synchronized
        fun offer(c: Connection): Boolean {
            if (state != OPEN) return false
            state = OFFERED
            connection = c
            signal.complete(Unit)
            return true
        }

        /** Teslim alınmış bağlantıyı verir (yalnızca bir kez). */
        @Synchronized
        fun take(): Connection? {
            if (state != OFFERED) return null
            state = DONE
            val c = connection
            connection = null
            return c
        }

        /** Vazgeç: teslim edilmemiş bağlantı varsa döndürür (çağıran kapatır). */
        @Synchronized
        fun abandon(): Connection? {
            val c = connection
            connection = null
            state = DONE
            return c
        }

        @Synchronized
        fun directFailed(e: Throwable) {
            directFailed = true
            failure = failure ?: e
            maybeFail()
        }

        @Synchronized
        fun indirectFailed() {
            indirectFailed = true
            maybeFail()
        }

        private fun maybeFail() {
            if (state == OPEN && directFailed && indirectFailed) {
                state = DONE
                signal.completeExceptionally(failure ?: IOException("Could not connect to $username"))
            }
        }

        @Synchronized
        fun isOpen(): Boolean = state == OPEN

        companion object {
            private const val OPEN = 0
            private const val OFFERED = 1
            private const val DONE = 2
        }
    }

    private class CachedAddress(val address: PeerAddress, val expiresAtNanos: Long)

    private val entries = HashMap<String, PeerEntry>()
    private val attempts = HashMap<Int, Attempt>()
    private val addressCache = HashMap<String, CachedAddress>()
    private val addressWaiters = HashMap<String, CompletableDeferred<PeerAddress?>>()
    private var tokenCounter: Int = Random.nextInt(0, Int.MAX_VALUE / 1000 + 1)
    private val openConnections = AtomicInteger()
    private var listener: Listener? = null
    private var sweeper: Job? = null
    private var closed = false
    private val pConnectGate = Semaphore(MAX_CONCURRENT_P_CONNECTS)

    /** Dinleme portu (0: dinlemiyor). */
    var listenPort: Int = 0
        private set

    val connectionCount: Int get() = openConnections.get()

    // ------------------------------------------------------------------------------------------------- yaşam döngüsü

    fun start() {
        if (sweeper != null) return
        sweeper = logicScope.launch {
            while (isActive) {
                delay(timeouts.sweepIntervalMillis)
                sweep()
            }
        }
    }

    /** Dinlemeye başlar ya da portu değiştirir. Başarısızsa false (bağlantı yine de giden yönde çalışır). */
    fun listen(port: Int): Boolean {
        val old = listener
        if (old != null && old.port == port) return true
        old?.close()
        listener = null
        listenPort = 0
        if (closed) return false
        return try {
            val l = reactor.listen(port) { socket -> onAccepted(socket) }
            listener = l
            listenPort = l.port
            true
        } catch (e: IOException) {
            logger.warn(TAG, "Port $port dinlenemedi: ${e.message}")
            false
        }
    }

    fun stopListening() {
        listener?.close()
        listener = null
        listenPort = 0
    }

    /** Sunucu bağlantısı koptu: bekleyen her şeyi düşür, tüm eş bağlantılarını kapat. */
    fun onServerDisconnected() {
        for (a in attempts.values) a.indirectFailed()
        attempts.clear()
        addressCache.clear()
        for (w in addressWaiters.values) w.complete(null)
        addressWaiters.clear()
        closeAllPeers(PeerFailure.OFFLINE)
    }

    fun shutdown() {
        closed = true
        sweeper?.cancel()
        sweeper = null
        stopListening()
        onServerDisconnected()
    }

    private fun closeAllPeers(reason: PeerFailure) {
        val all = entries.values.toList()
        entries.clear()
        for (e in all) {
            e.connecting?.cancel()
            e.connecting = null
            e.primary?.close()
            for (c in e.others) c.close()
            val pending = e.pending.toList()
            e.pending.clear()
            for (p in pending) p.onFailure?.invoke(reason)
        }
    }

    // ------------------------------------------------------------------------------------------------- sunucu mesajları

    fun onPeerAddress(message: PeerAddressResponse) {
        val waiter = addressWaiters.remove(message.username)
        if (message.isOffline) {
            addressCache.remove(message.username)
            waiter?.complete(null)
            return
        }
        val address = PeerAddress(message.ip, message.port)
        if (message.port in 1..65535) {
            addressCache[message.username] =
                CachedAddress(address, System.nanoTime() + timeouts.addressTtlMillis * 1_000_000)
        } else {
            addressCache.remove(message.username)
        }
        waiter?.complete(address)
    }

    fun invalidateAddress(username: String) {
        addressCache.remove(username)
    }

    fun onCantConnectToPeer(message: CantConnectToPeerNotification) {
        attempts[message.token]?.indirectFailed()
    }

    /** Sunucu: bir kullanıcı bizden kendisine bağlanmamızı istiyor (biz hedefiz). */
    fun onConnectToPeer(message: ConnectToPeerNotification) {
        if (!ConnType.isValid(message.type)) return
        if (closed) return
        val port = message.port
        if (port !in 1..65535 || !isIpLiteral(message.ip)) {
            host.sendToServer(CantConnectToPeerRequest(message.token, message.username))
            return
        }
        ioScope.launch {
            var conn: Connection? = null
            try {
                val socket = reactor.connect(InetSocketAddress(InetAddress.getByName(message.ip), port), timeouts.directConnectMillis)
                conn = newConnection(socket, "${message.type}<${message.username}")
                conn.send(PierceFireWall(message.token).encode())
                val ready = conn
                conn = null
                withContext(logic) { onTargetConnected(message.username, message.type, ready) }
            } catch (e: CancellationException) {
                conn?.close()
                throw e
            } catch (e: Exception) {
                conn?.close()
                logger.debug(TAG, "${message.username} kullanıcısına geri bağlanılamadı: ${e.message}")
                host.sendToServer(CantConnectToPeerRequest(message.token, message.username))
            }
        }
    }

    private fun onTargetConnected(username: String, type: String, conn: Connection) {
        if (closed) {
            conn.close()
            return
        }
        when (type) {
            ConnType.PEER -> adoptPeerConnection(username, conn, replace = true)
            ConnType.FILE -> host.onFileConnection(username, conn)
            ConnType.DISTRIBUTED -> host.onDistributedConnection(username, conn)
            else -> conn.close()
        }
    }

    // ------------------------------------------------------------------------------------------------- adres

    /**
     * Kullanıcının adresini döndürür (önbellek ya da GetPeerAddress). Çevrimdışıysa null.
     * @throws IOException sunucuya ulaşılamadı ya da zaman aşımı.
     */
    suspend fun lookupAddress(username: String): PeerAddress? {
        val cached = addressCache[username]
        if (cached != null) {
            if (System.nanoTime() < cached.expiresAtNanos) return cached.address
            addressCache.remove(username)
        }
        val waiter = addressWaiters.getOrPut(username) {
            val d = CompletableDeferred<PeerAddress?>()
            if (!host.sendToServer(GetPeerAddressRequest(username))) {
                d.completeExceptionally(IOException("Not connected to server"))
            }
            d
        }
        try {
            return withTimeout(timeouts.addressLookupMillis) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            if (addressWaiters[username] === waiter) addressWaiters.remove(username)
            throw SocketTimeoutException("Address lookup timed out for $username")
        } catch (e: IOException) {
            if (addressWaiters[username] === waiter) addressWaiters.remove(username)
            throw e
        }
    }

    // ------------------------------------------------------------------------------------------------- bağlantı kurma

    private fun nextToken(): Int {
        var t = tokenCounter + 1
        if (t == 0) t = 1
        tokenCounter = t
        return t
    }

    /**
     * [username] ile [type] türünde yeni bir bağlantı kurar (doğrudan ve dolaylı yol yarışır). Dönen bağlantıda başlangıç
     * mesajı (PeerInit ya da karşı tarafın PierceFireWall'ı) tamamlanmıştır; sahiplik çağırana geçer.
     * @throws PeerOfflineException kullanıcı çevrimdışı; [IOException] ulaşılamadı.
     */
    suspend fun connect(username: String, type: String): Connection {
        if (closed) throw IOException("Network closed")
        val address = lookupAddress(username) ?: throw PeerOfflineException(username)
        if (!host.isServerReady) throw IOException("Not connected to server")
        val token = nextToken()
        val directPossible = address.port in 1..65535 && isIpLiteral(address.ip)
        val deadline = System.nanoTime() + timeouts.indirectMillis * 1_000_000
        val attempt = Attempt(username, type, token, deadline, directPossible)
        attempts[token] = attempt
        var delivered = false
        try {
            val request = ConnectToPeerRequest(token, username, type)
            if (directPossible && type == ConnType.DISTRIBUTED) {
                // "D": ebeveyn adayı gelen bağlantıyı çocuk olarak kabul eder; aynı anda iki bağlantı gelirse kopya
                // sayıp gerçekten kullanılanı reddedebilir. Bu yüzden önce yalnızca doğrudan, olmazsa dolaylı denenir.
                attempt.onDirectFailure = { if (attempt.isOpen()) host.sendToServer(request) }
                startDirect(attempt, address)
            } else {
                host.sendToServer(request)
                if (directPossible) startDirect(attempt, address)
            }
            try {
                withTimeout(timeouts.indirectMillis) { attempt.signal.await() }
            } catch (e: TimeoutCancellationException) {
                throw SocketTimeoutException("Connection to $username timed out")
            }
            val conn = attempt.take() ?: throw IOException("Connection to $username failed")
            delivered = true
            return conn
        } finally {
            if (!delivered) {
                attempt.abandon()?.close()
                attempts.remove(token)
            }
        }
    }

    private fun startDirect(attempt: Attempt, address: PeerAddress) {
        ioScope.launch {
            var conn: Connection? = null
            try {
                val socket = reactor.connect(
                    InetSocketAddress(InetAddress.getByName(address.ip), address.port),
                    timeouts.directConnectMillis,
                )
                conn = newConnection(socket, "${attempt.type}>${attempt.username}")
                conn.send(PeerInit(host.username, attempt.type, 0).encode())
                if (attempt.offer(conn)) conn = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt.directFailed(e)
                attempt.onDirectFailure?.invoke()
            } finally {
                conn?.close()
            }
        }
    }

    private fun newConnection(socket: AsyncSocket, label: String): Connection {
        val conn = Connection(socket, ioScope, logger, label)
        openConnections.incrementAndGet()
        conn.onClose { openConnections.decrementAndGet() }
        return conn
    }

    private fun isIpLiteral(ip: String): Boolean {
        if (ip.isEmpty() || ip == "0.0.0.0") return false
        // Yalnızca rakam, nokta ve iki nokta: DNS çözümlemesi (engelleyici) asla tetiklenmesin.
        return ip.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
    }

    // ------------------------------------------------------------------------------------------------- "P" gönderimi

    /** Mesajı eşe gönderir; bağlantı yoksa kurulana kadar bekletir, kurulamazsa [onFailure] çağrılır. */
    fun send(username: String, message: PeerMessage, onFailure: ((PeerFailure) -> Unit)? = null) =
        sendFrame(username, message.encode(), onFailure)

    /** Önceden kodlanmış çerçeve (büyük/sıkıştırılmış yükler iş parçacığı dışında kodlanır). */
    fun sendFrame(username: String, frame: ByteArray, onFailure: ((PeerFailure) -> Unit)? = null) {
        enqueue(username, PendingSend(frame, onFailure, closeAfter = false))
    }

    /** Gönderir ve (başka bekleyen iş yoksa) tüm veri yazılınca bağlantıyı kapatır (arama yanıtları). */
    fun sendFrameThenRelease(username: String, frame: ByteArray, onFailure: ((PeerFailure) -> Unit)? = null) {
        enqueue(username, PendingSend(frame, onFailure, closeAfter = true))
    }

    private fun enqueue(username: String, item: PendingSend) {
        if (closed) {
            item.onFailure?.invoke(PeerFailure.OFFLINE)
            return
        }
        val entry = entries.getOrPut(username) { PeerEntry(username) }
        val conn = entry.primary
        if (conn != null && !conn.isClosed && entry.pending.isEmpty()) {
            if (writeTo(conn, item, entry)) return
        }
        entry.pending.add(item)
        ensureConnecting(entry)
    }

    private fun writeTo(conn: Connection, item: PendingSend, entry: PeerEntry): Boolean {
        if (!conn.send(item.frame)) return false
        conn.hasPostInitActivity = true
        if (item.closeAfter && entry.pending.isEmpty()) conn.closeWhenFlushed()
        return true
    }

    private fun ensureConnecting(entry: PeerEntry) {
        if (entry.connecting != null) return
        val username = entry.username
        entry.connecting = logicScope.launch {
            try {
                val conn = pConnectGate.withPermit { connect(username, ConnType.PEER) }
                adoptPeerConnection(username, conn, replace = true, fromConnector = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeerOfflineException) {
                failPending(username, PeerFailure.OFFLINE)
            } catch (e: Exception) {
                logger.debug(TAG, "$username ile P bağlantısı kurulamadı: ${e.message}")
                failPending(username, PeerFailure.UNREACHABLE)
            } finally {
                entries[username]?.let { if (it.connecting === coroutineContext[Job]) it.connecting = null }
            }
        }
    }

    private fun failPending(username: String, reason: PeerFailure) {
        val entry = entries[username] ?: return
        val pending = entry.pending.toList()
        entry.pending.clear()
        if (entry.isIdle || (entry.primary == null && entry.others.isEmpty() && entry.connecting == null)) {
            entries.remove(username)
        }
        for (p in pending) {
            try {
                p.onFailure?.invoke(reason)
            } catch (e: Exception) {
                logger.warn(TAG, "Başarısızlık geri çağrısı hata verdi", e)
            }
        }
    }

    /**
     * Kurulmuş bir "P" bağlantısını kaydeder, bekleyen mesajları boşaltır ve okuyucuyu başlatır.
     * [replace] true ise var olan birincil bağlantı bırakılır (gelen PeerInit); false ise (geç gelen PierceFireWall)
     * birincil bağlantı doluysa yeni bağlantı ikincil olarak tutulur.
     */
    private fun adoptPeerConnection(username: String, conn: Connection, replace: Boolean, fromConnector: Boolean = false) {
        if (closed || conn.isClosed) {
            conn.close()
            return
        }
        val entry = entries.getOrPut(username) { PeerEntry(username) }
        val old = entry.primary
        conn.onClose { c -> logicScope.launch { onPeerConnectionClosed(username, c) } }
        if (old != null && old !== conn && !old.isClosed) {
            if (!replace) {
                entry.others.add(conn)
                trimSecondary(entry)
                startReader(username, conn)
                return
            }
            // Eski bağlantı kapatılmaz: karşı taraf aynı anda bağlandığında uçuştaki bir istek bu bağlantıda olabilir.
            // İkincil olarak okunmaya devam eder; boşta kalınca süpürücü kapatır.
            entry.others.add(old)
            trimSecondary(entry)
        }
        entry.primary = conn
        if (!fromConnector) {
            // Bu arada bağlanmakta olan kendi denememizi bırak (gelen bağlantı kazandı).
            entry.connecting?.cancel()
            entry.connecting = null
        }
        flushPending(entry, conn)
        startReader(username, conn)
    }

    /** İkincil bağlantı sayısını sınırlar (en eskiler kapatılır). */
    private fun trimSecondary(entry: PeerEntry) {
        while (entry.others.size > MAX_SECONDARY_CONNECTIONS) {
            val oldest = entry.others.removeAt(0)
            if (!oldest.closeRequested) oldest.close()
        }
    }

    private fun flushPending(entry: PeerEntry, conn: Connection) {
        if (entry.pending.isEmpty()) return
        val items = entry.pending.toList()
        entry.pending.clear()
        for ((i, item) in items.withIndex()) {
            val last = i == items.lastIndex
            if (!conn.send(item.frame)) {
                for (rest in items.subList(i, items.size)) rest.onFailure?.invoke(PeerFailure.UNREACHABLE)
                return
            }
            conn.hasPostInitActivity = true
            if (item.closeAfter && last) conn.closeWhenFlushed()
        }
    }

    private fun onPeerConnectionClosed(username: String, conn: Connection) {
        val entry = entries[username] ?: return
        entry.others.remove(conn)
        if (entry.primary === conn) {
            entry.primary = entry.others.firstOrNull { !it.isClosed }
            if (entry.primary != null) entry.others.remove(entry.primary)
        }
        if (entry.isIdle) entries.remove(username)
    }

    private fun startReader(username: String, conn: Connection) {
        ioScope.launch { peerReadLoop(username, conn) }
    }

    // ------------------------------------------------------------------------------------------------- "P" okuma

    private suspend fun peerReadLoop(username: String, conn: Connection) {
        try {
            while (true) {
                val length = conn.readUInt32()
                if (length < 4 || length > Frame.MAX_PEER_HUGE) throw ProtocolException("Geçersiz P mesaj uzunluğu $length")
                val code = conn.readInt32()
                val bodyLength = (length - 4).toInt()
                conn.hasPostInitActivity = true
                if (bodyLength > PeerMessages.maxIncomingSize(code)) throw ProtocolException("P mesajı çok büyük (kod $code, $bodyLength)")
                if (!PeerMessages.isKnownCode(code)) {
                    conn.skip(bodyLength.toLong())
                    continue
                }
                if ((code == PeerCode.SHARED_FILE_LIST_RESPONSE || code == PeerCode.USER_INFO_RESPONSE) &&
                    !host.isSolicited(username, code)
                ) {
                    throw ProtocolException("İstenmemiş yanıt (kod $code) $username")
                }
                val payload = conn.readBytes(bodyLength)
                if (code == PeerCode.FILE_SEARCH_RESPONSE) {
                    val header = PeerMessages.peekSearchResponse(payload)
                    if (header == null || !host.isSearchTokenActive(header.token)) continue
                }
                val message = try {
                    PeerMessages.decode(code, payload)
                } catch (e: ProtocolException) {
                    logger.debug(TAG, "$username: bozuk P mesajı (kod $code): ${e.message}")
                    null
                } ?: continue
                withContext(logic) {
                    val entry = entries[username]
                    if (entry != null && entry.primary !== conn && !conn.isClosed) promote(entry, conn)
                    host.onPeerMessage(username, message, conn)
                    if (code == PeerCode.FILE_SEARCH_RESPONSE) {
                        val e = entries[username]
                        if (e != null && e.pending.isEmpty() && e.primary === conn) conn.closeWhenFlushed()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EOFException) {
            // normal kapanış
        } catch (e: IOException) {
            logger.debug(TAG, "$username P bağlantısı kapandı: ${e.message}")
        } catch (e: ProtocolException) {
            logger.debug(TAG, "$username protokol hatası: ${e.message}")
        } catch (e: Exception) {
            logger.warn(TAG, "$username P okuyucusu beklenmedik hata", e)
        } finally {
            conn.close()
        }
    }

    private fun promote(entry: PeerEntry, conn: Connection) {
        val old = entry.primary
        entry.others.remove(conn)
        if (old != null && !old.isClosed) entry.others.add(old)
        entry.primary = conn
    }

    // ------------------------------------------------------------------------------------------------- gelen bağlantılar

    private fun onAccepted(socket: AsyncSocket) {
        // Seçici iş parçacığı: hızlı dön.
        if (closed) {
            socket.close()
            return
        }
        ioScope.launch { handleInbound(socket) }
    }

    private suspend fun handleInbound(socket: AsyncSocket) {
        if (openConnections.get() >= MAX_CONNECTIONS) {
            withContext(logic) { evictIdle() }
            if (openConnections.get() >= MAX_CONNECTIONS) {
                socket.close()
                return
            }
        }
        val conn = newConnection(socket, "in")
        try {
            val init = withTimeout(timeouts.initReadMillis) {
                val length = conn.readUInt32()
                if (length < 1 || length > Frame.MAX_INIT_MESSAGE) throw ProtocolException("Geçersiz başlangıç uzunluğu $length")
                val code = conn.readUInt8()
                InitMessages.decode(code, conn.readBytes((length - 1).toInt()))
            } ?: throw ProtocolException("Bilinmeyen başlangıç mesajı")
            when (init) {
                is PierceFireWall -> withContext(logic) { onPierce(conn, init.token) }
                is PeerInit -> {
                    if (!DistributedMessages.isValidUsername(init.username) || !ConnType.isValid(init.type)) {
                        throw ProtocolException("Geçersiz PeerInit")
                    }
                    withContext(logic) { onPeerInit(conn, init) }
                }
            }
        } catch (e: CancellationException) {
            conn.close()
            throw e
        } catch (e: TimeoutCancellationException) {
            conn.close()
        } catch (e: Exception) {
            logger.debug(TAG, "Gelen bağlantı reddedildi: ${e.message}")
            conn.close()
        }
    }

    private fun onPierce(conn: Connection, token: Int) {
        val attempt = attempts[token]
        if (attempt == null || closed) {
            logger.debug(TAG, "Bilinmeyen PierceFireWall token=${tokenToString(token)}")
            conn.close()
            return
        }
        conn.label = "${attempt.type}~${attempt.username}"
        if (attempt.offer(conn)) return
        // Deneme zaten tamamlandı (doğrudan yol kazandı): P için ikincil bağlantı olarak tut, diğerlerini kapat.
        if (attempt.type == ConnType.PEER) {
            adoptPeerConnection(attempt.username, conn, replace = false)
        } else {
            conn.close()
        }
    }

    private fun onPeerInit(conn: Connection, init: PeerInit) {
        if (closed) {
            conn.close()
            return
        }
        conn.label = "${init.type}<${init.username}"
        when (init.type) {
            ConnType.PEER -> adoptPeerConnection(init.username, conn, replace = true)
            ConnType.FILE -> host.onFileConnection(init.username, conn)
            ConnType.DISTRIBUTED -> host.onDistributedConnection(init.username, conn)
            else -> conn.close()
        }
    }

    // ------------------------------------------------------------------------------------------------- bakım

    private fun sweep() {
        val now = System.nanoTime()
        val idleNanos = timeouts.idleMillis * 1_000_000
        val ghostNanos = timeouts.ghostMillis * 1_000_000
        val expired = attempts.entries.filter { now - it.value.deadlineNanos > 0 }.map { it.key }
        for (t in expired) attempts.remove(t)
        for (entry in entries.values.toList()) {
            val all = ArrayList<Connection>(1 + entry.others.size)
            entry.primary?.let { all.add(it) }
            all.addAll(entry.others)
            for (c in all) {
                val idle = now - c.lastActivityNanos
                if (c.keepOpen) continue
                if ((!c.hasPostInitActivity && idle > ghostNanos) || idle > idleNanos) c.close()
            }
        }
        val cached = addressCache.entries.filter { now - it.value.expiresAtNanos > 0 }.map { it.key }
        for (k in cached) addressCache.remove(k)
    }

    private fun evictIdle() {
        val now = System.nanoTime()
        for (entry in entries.values.toList()) {
            for (c in listOfNotNull(entry.primary) + entry.others) {
                if (now - c.lastActivityNanos > 2_000_000_000L) c.close()
            }
        }
    }

    /** Kullanıcıyla olan tüm "P" bağlantılarını kapatır (ör. yasaklama). */
    fun closePeer(username: String) {
        val entry = entries.remove(username) ?: return
        entry.connecting?.cancel()
        entry.primary?.close()
        for (c in entry.others) c.close()
        for (p in entry.pending) p.onFailure?.invoke(PeerFailure.OFFLINE)
    }

    /** Açık "P" bağlantısı olan eş sayısı. */
    fun peerCount(): Int = entries.values.count { it.primary?.isClosed == false }

    fun hasPeerConnection(username: String): Boolean = entries[username]?.primary?.isClosed == false

    /** Elle yapılandırılmış bir bağlantıyı yöneticinin sayacına ekler (testler ve sunucu bağlantısı dışı). */
    fun wrap(socket: AsyncSocket, label: String): Connection = newConnection(socket, label)

    companion object {
        private const val TAG = "PeerNetwork"

        /** Aynı anda açık eş bağlantısı üst sınırı (dosya tanıtıcıları için). */
        const val MAX_CONNECTIONS = 512

        /** Aynı anda kurulmaya çalışılan "P" bağlantısı üst sınırı (toplu istekler soket seline dönüşmesin). */
        const val MAX_CONCURRENT_P_CONNECTS = 48

        /** Bir eşle birincil bağlantıya ek olarak tutulan en çok ikincil "P" bağlantısı. */
        const val MAX_SECONDARY_CONNECTIONS = 3
    }
}
