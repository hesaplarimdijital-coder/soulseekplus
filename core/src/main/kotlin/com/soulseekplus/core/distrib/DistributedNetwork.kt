package com.soulseekplus.core.distrib

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.DistributedState
import com.soulseekplus.core.net.Connection
import com.soulseekplus.core.net.PeerNetwork
import com.soulseekplus.core.protocol.AcceptChildren
import com.soulseekplus.core.protocol.BranchLevelRequest
import com.soulseekplus.core.protocol.BranchRootRequest
import com.soulseekplus.core.protocol.DistribBranchLevel
import com.soulseekplus.core.protocol.DistribBranchRoot
import com.soulseekplus.core.protocol.DistribCode
import com.soulseekplus.core.protocol.DistribEmbedded
import com.soulseekplus.core.protocol.DistribSearch
import com.soulseekplus.core.protocol.DistributedMessage
import com.soulseekplus.core.protocol.DistributedMessages
import com.soulseekplus.core.protocol.Frame
import com.soulseekplus.core.protocol.HaveNoParent
import com.soulseekplus.core.protocol.PossibleParentsNotification
import com.soulseekplus.core.protocol.ProtocolException
import com.soulseekplus.core.protocol.ConnType
import com.soulseekplus.core.protocol.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** [DistributedNetwork]'ün motorla sözleşmesi. Hepsi mantık iş parçacığında çağrılır. */
interface DistribHost {
    /** Kendi kullanıcı adımız. */
    val username: String

    fun sendToServer(message: ServerMessage): Boolean

    /** Dağıtık ağdan (ya da dal kökü olarak sunucudan) gelen bir arama; yerelde yanıtlanır. */
    fun onDistributedSearch(username: String, token: Int, query: String)
}

/**
 * Dağıtık arama ağı (bkz. docs/slsk-protocol.md §6): ebeveyn seçimi, çocuklara iletme, dal seviyesi/kökü bildirimi.
 * Durum yalnızca mantık iş parçacığında değişir.
 */
class DistributedNetwork(
    private val logicScope: CoroutineScope,
    private val logic: CoroutineDispatcher,
    private val ioScope: CoroutineScope,
    private val peers: PeerNetwork,
    private val host: DistribHost,
    private val logger: Logger,
    private val parentIdleMillis: Long = 90_000,
    private val candidateIdleMillis: Long = 30_000,
    private val parentRequestRetryMillis: Long = 90_000,
) {
    private class Candidate(val username: String) {
        var connection: Connection? = null
        var level: Int? = null
        var root: String? = null
        var job: Job? = null
    }

    private val candidates = HashMap<String, Candidate>()
    private var parent: Candidate? = null
    private val children = LinkedHashMap<String, Connection>()

    private var loggedIn = false
    private var branchLevel = 0
    private var branchRoot: String? = null
    private var isServerParent = false
    private var accepting = false

    private var minSpeed = 0L
    private var speedRatio = 1L
    private var ownSpeed = 0L
    private var maxChildren = 0
    private var lastParentRequestNanos = 0L
    private var ticker: Job? = null

    private val _state = MutableStateFlow(DistributedState())
    val state: StateFlow<DistributedState> = _state

    // ------------------------------------------------------------------------------------------------- yaşam döngüsü

    /** Girişten sonra: ebeveyn istendiğini sunucuya bildirir. */
    fun onLoggedIn() {
        loggedIn = true
        resetState(closeConnections = true)
        sendHaveNoParent()
        if (ticker == null) ticker = logicScope.launch { tickLoop() }
    }

    fun onServerDisconnected() {
        loggedIn = false
        ticker?.cancel()
        ticker = null
        resetState(closeConnections = true)
        minSpeed = 0
        speedRatio = 1
        ownSpeed = 0
        maxChildren = 0
        publish()
    }

    fun shutdown() = onServerDisconnected()

    private fun resetState(closeConnections: Boolean) {
        if (closeConnections) {
            closeCandidates(except = null)
            parent?.connection?.close()
            for (c in children.values.toList()) c.close()
        }
        parent = null
        children.clear()
        isServerParent = false
        accepting = false
        branchLevel = 0
        branchRoot = host.username.ifEmpty { null }
        publish()
    }

    private suspend fun tickLoop() {
        while (logicScope.isActive) {
            delay(TICK_MILLIS)
            val now = System.nanoTime()
            val p = parent
            val pc = p?.connection
            if (pc != null && now - pc.lastActivityNanos > parentIdleMillis * 1_000_000) {
                logger.debug(TAG, "Ebeveyn ${p.username} sessiz, bırakılıyor")
                pc.close()
            }
            for (c in candidates.values.toList()) {
                val cc = c.connection ?: continue
                if (now - cc.lastActivityNanos > candidateIdleMillis * 1_000_000) {
                    cc.close()
                }
            }
            if (loggedIn && parent == null && !isServerParent && candidates.isEmpty() &&
                now - lastParentRequestNanos > parentRequestRetryMillis * 1_000_000
            ) {
                sendHaveNoParent()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- sunucu mesajları

    fun onPossibleParents(message: PossibleParentsNotification) {
        if (!loggedIn || parent != null) return
        closeCandidates(except = null)
        for (pp in message.parents.take(MAX_CANDIDATES)) {
            if (pp.username == host.username) continue
            val candidate = Candidate(pp.username)
            candidates[pp.username] = candidate
            candidate.job = logicScope.launch {
                try {
                    val conn = peers.connect(pp.username, ConnType.DISTRIBUTED)
                    onCandidateConnected(candidate, conn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    logger.debug(TAG, "Ebeveyn adayı ${pp.username} bağlanamadı: ${e.message}")
                    if (candidates[pp.username] === candidate) candidates.remove(pp.username)
                } catch (e: Exception) {
                    logger.warn(TAG, "Ebeveyn adayı hatası", e)
                    if (candidates[pp.username] === candidate) candidates.remove(pp.username)
                }
            }
        }
    }

    fun onEmbeddedMessage(distribCode: Int, body: ByteArray) {
        if (!loggedIn || parent != null) return
        if (distribCode != DistribCode.SEARCH) return
        val search = try {
            DistributedMessages.decodeSearch(body)
        } catch (e: ProtocolException) {
            return
        }
        if (!isServerParent) {
            isServerParent = true
            branchLevel = 0
            branchRoot = host.username
            if (children.size < maxChildren) sendAccept(true)
            logger.info(TAG, "Sunucu ebeveynimiz: dal kökü olarak çalışıyoruz")
            publish()
        }
        forwardToChildren(DistributedMessages.searchFrame(body))
        host.onDistributedSearch(search.username, search.token, search.query)
    }

    fun onReset() {
        logger.debug(TAG, "Dağıtık ağ sıfırlama isteği")
        if (!loggedIn) return
        resetState(closeConnections = true)
        sendHaveNoParent()
    }

    fun onParentMinSpeed(value: Long) {
        minSpeed = value
        updateMaxChildren()
    }

    fun onParentSpeedRatio(value: Long) {
        speedRatio = value
        updateMaxChildren()
    }

    /** Sunucunun bildirdiği kendi ortalama yükleme hızımız (bayt/sn). */
    fun onOwnSpeed(bytesPerSecond: Long) {
        ownSpeed = bytesPerSecond
        updateMaxChildren()
    }

    private fun updateMaxChildren() {
        val previous = maxChildren
        maxChildren = if (ownSpeed >= minSpeed && speedRatio > 0) {
            minOf(ownSpeed / speedRatio / 100, MAX_CHILDREN.toLong()).toInt()
        } else {
            0
        }
        if (!loggedIn) return
        val count = children.size
        if (maxChildren <= count && count < previous) {
            sendAccept(false)
        } else if ((parent != null || isServerParent) && count < maxChildren && !accepting) {
            sendAccept(true)
        }
    }

    // ------------------------------------------------------------------------------------------------- bağlantılar

    private fun onCandidateConnected(candidate: Candidate, conn: Connection) {
        if (!loggedIn || parent != null || candidates[candidate.username] !== candidate) {
            conn.close()
            return
        }
        candidate.connection = conn
        conn.keepOpen = true
        conn.label = "D>${candidate.username}"
        conn.onClose { logicScope.launch { onCandidateClosed(candidate, conn) } }
        startReader(candidate.username, conn)
    }

    private fun onCandidateClosed(candidate: Candidate, conn: Connection) {
        if (candidates[candidate.username] === candidate && candidate.connection === conn) {
            candidates.remove(candidate.username)
        }
        val p = parent
        if (p === candidate && p.connection === conn) {
            logger.info(TAG, "Ebeveyn ${candidate.username} bağlantısı koptu")
            onParentLost()
        }
    }

    /** Bize bağlanan "D" bağlantısı: çocuk olarak kabul edilebilir. */
    fun onInboundConnection(username: String, conn: Connection) {
        val reason = when {
            !loggedIn -> "not logged in"
            username == host.username -> "self"
            candidates.containsKey(username) || parent?.username == username -> "candidate parent"
            parent == null && !isServerParent -> "no parent"
            children.containsKey(username) -> "duplicate"
            children.size >= maxChildren -> "child limit"
            else -> null
        }
        if (reason != null) {
            logger.debug(TAG, "$username çocuk bağlantısı reddedildi: $reason")
            conn.close()
            return
        }
        conn.keepOpen = true
        conn.label = "D<$username"
        children[username] = conn
        conn.onClose { logicScope.launch { onChildClosed(username, conn) } }
        conn.send(DistribBranchLevel(branchLevel).encode())
        conn.send(DistribBranchRoot(branchRoot ?: host.username).encode())
        logger.debug(TAG, "$username dağıtık çocuk olarak alındı (${children.size}/$maxChildren)")
        if (children.size >= maxChildren) sendAccept(false)
        publish()
        startReader(username, conn)
    }

    private fun onChildClosed(username: String, conn: Connection) {
        if (children[username] !== conn) return
        children.remove(username)
        if (loggedIn && children.size == maxChildren - 1 && (parent != null || isServerParent)) sendAccept(true)
        publish()
    }

    private fun startReader(username: String, conn: Connection) {
        ioScope.launch {
            try {
                while (true) {
                    val length = conn.readUInt32()
                    if (length < 1 || length > Frame.MAX_DISTRIB_MESSAGE) throw ProtocolException("Geçersiz D mesaj uzunluğu $length")
                    val code = conn.readUInt8()
                    val payload = conn.readBytes((length - 1).toInt())
                    conn.hasPostInitActivity = true
                    val message = try {
                        DistributedMessages.decode(code, payload)
                    } catch (e: ProtocolException) {
                        if (code == DistribCode.BRANCH_LEVEL || code == DistribCode.BRANCH_ROOT) throw e
                        logger.debug(TAG, "$username: geçersiz D mesajı (kod $code): ${e.message}")
                        null
                    } ?: continue
                    val keep = withContext(logic) { handleMessage(username, conn, message, payload) }
                    if (!keep) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // bağlantı koptu
            } catch (e: ProtocolException) {
                logger.debug(TAG, "$username dağıtık protokol hatası: ${e.message}")
            } catch (e: Exception) {
                logger.warn(TAG, "Dağıtık okuyucu hatası", e)
            } finally {
                conn.close()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- mesaj işleme

    private enum class ParentStatus { WAITING, ACCEPTED, REJECTED }

    private fun parentStatus(conn: Connection): ParentStatus {
        val p = parent ?: return ParentStatus.WAITING
        return if (p.connection === conn) ParentStatus.ACCEPTED else ParentStatus.REJECTED
    }

    /** @return bağlantı açık kalsın mı. */
    private fun handleMessage(username: String, conn: Connection, message: DistributedMessage, payload: ByteArray): Boolean {
        when (message) {
            is DistribSearch -> return handleSearch(username, conn, message, payload)
            is DistribEmbedded -> {
                if (message.distribCode != DistribCode.SEARCH) return true
                val search = try {
                    DistributedMessages.decodeSearch(message.body)
                } catch (e: ProtocolException) {
                    return true
                }
                return handleSearch(username, conn, search, message.body)
            }
            is DistribBranchLevel -> {
                when (parentStatus(conn)) {
                    ParentStatus.ACCEPTED -> {
                        branchLevel = message.level + 1
                        host.sendToServer(BranchLevelRequest(branchLevel))
                        sendToChildren(DistribBranchLevel(branchLevel).encode())
                        publish()
                    }
                    ParentStatus.WAITING -> {
                        val c = candidates[username]
                        if (c != null && c.connection === conn) {
                            c.level = message.level
                            if (message.level == 0) c.root = username
                        }
                    }
                    ParentStatus.REJECTED -> return false
                }
            }
            is DistribBranchRoot -> {
                when (parentStatus(conn)) {
                    ParentStatus.ACCEPTED -> {
                        branchRoot = message.root
                        host.sendToServer(BranchRootRequest(message.root))
                        sendToChildren(DistribBranchRoot(message.root).encode())
                        publish()
                    }
                    ParentStatus.WAITING -> {
                        val c = candidates[username]
                        if (c != null && c.connection === conn) c.root = message.root
                    }
                    ParentStatus.REJECTED -> return false
                }
            }
            else -> Unit
        }
        return true
    }

    private fun handleSearch(username: String, conn: Connection, search: DistribSearch, body: ByteArray): Boolean {
        if (parent == null) {
            val c = candidates[username]
            if (c != null && c.connection === conn) adoptParent(c)
        }
        return when (parentStatus(conn)) {
            ParentStatus.REJECTED -> false
            ParentStatus.WAITING -> true
            ParentStatus.ACCEPTED -> {
                forwardToChildren(DistributedMessages.searchFrame(body))
                host.onDistributedSearch(search.username, search.token, search.query)
                true
            }
        }
    }

    private fun adoptParent(candidate: Candidate) {
        if (parent != null) return
        val level = candidate.level ?: return
        val root = candidate.root?.takeIf { it.isNotEmpty() } ?: return
        if (candidate.connection == null) return
        parent = candidate
        isServerParent = false
        branchLevel = level + 1
        branchRoot = root
        candidates.remove(candidate.username)
        closeCandidates(except = candidate)
        host.sendToServer(HaveNoParent(false))
        host.sendToServer(BranchRootRequest(root))
        host.sendToServer(BranchLevelRequest(branchLevel))
        if (children.size < maxChildren) sendAccept(true)
        sendToChildren(DistribBranchLevel(branchLevel).encode())
        sendToChildren(DistribBranchRoot(root).encode())
        logger.info(TAG, "Ebeveyn ${candidate.username}, dal seviyesi $branchLevel, kök $root")
        publish()
    }

    private fun onParentLost() {
        if (!loggedIn) return
        resetState(closeConnections = true)
        sendHaveNoParent()
    }

    // ------------------------------------------------------------------------------------------------- yardımcılar

    private fun closeCandidates(except: Candidate?) {
        for (c in candidates.values.toList()) {
            if (c === except) continue
            c.job?.cancel()
            c.connection?.close()
        }
        candidates.clear()
        if (except != null) candidates[except.username] = except
    }

    private fun sendHaveNoParent() {
        lastParentRequestNanos = System.nanoTime()
        branchLevel = 0
        branchRoot = host.username
        host.sendToServer(HaveNoParent(true))
        host.sendToServer(BranchRootRequest(host.username))
        host.sendToServer(BranchLevelRequest(0))
        sendAccept(false)
        publish()
    }

    private fun sendAccept(accept: Boolean) {
        accepting = accept
        host.sendToServer(AcceptChildren(accept))
        publish()
    }

    private fun sendToChildren(frame: ByteArray) {
        for (c in children.values) c.send(frame)
    }

    private fun forwardToChildren(frame: ByteArray) = sendToChildren(frame)

    private fun publish() {
        _state.value = DistributedState(
            parentUsername = parent?.username,
            branchLevel = branchLevel,
            branchRoot = branchRoot,
            childCount = children.size,
            acceptingChildren = accepting,
        )
    }

    /** Test ve tanılama için: çocuk sayısı sınırı. */
    fun maxChildrenAllowed(): Int = maxChildren

    companion object {
        private const val TAG = "Distributed"
        const val MAX_CHILDREN = 10
        private const val MAX_CANDIDATES = 10
        private const val TICK_MILLIS = 15_000L
    }
}
