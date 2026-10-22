package com.soulseekplus.core.engine

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.api.CountryResolver
import com.soulseekplus.core.api.DownloadStore
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.ShareProvider
import com.soulseekplus.core.api.ShareSearchResult
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.api.TransferPersistence
import com.soulseekplus.core.distrib.DistribHost
import com.soulseekplus.core.distrib.DistributedNetwork
import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.DistributedState
import com.soulseekplus.core.model.NetworkStatus
import com.soulseekplus.core.model.PortMappingState
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferStats
import com.soulseekplus.core.model.UserInfo
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.model.UserPresence
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.core.net.Connection
import com.soulseekplus.core.net.PeerFailure
import com.soulseekplus.core.net.PeerHost
import com.soulseekplus.core.net.PeerNetwork
import com.soulseekplus.core.net.Reactor
import com.soulseekplus.core.net.ServerLink
import com.soulseekplus.core.protocol.AdminMessageNotification
import com.soulseekplus.core.protocol.CantConnectToPeerNotification
import com.soulseekplus.core.protocol.CantCreateRoomNotification
import com.soulseekplus.core.protocol.CheckPrivilegesRequest
import com.soulseekplus.core.protocol.ConnType
import com.soulseekplus.core.protocol.ConnectToPeerNotification
import com.soulseekplus.core.protocol.EmbeddedMessageNotification
import com.soulseekplus.core.protocol.ExcludedSearchPhrasesNotification
import com.soulseekplus.core.protocol.FileSearchRequest
import com.soulseekplus.core.protocol.FileSearchResponseMessage
import com.soulseekplus.core.protocol.FolderContentsRequest
import com.soulseekplus.core.protocol.FolderContentsResponse
import com.soulseekplus.core.protocol.GetUserStatsRequest
import com.soulseekplus.core.protocol.JoinRoomRequest
import com.soulseekplus.core.protocol.JoinRoomResponse
import com.soulseekplus.core.protocol.LeaveRoomNotification
import com.soulseekplus.core.protocol.LeaveRoomRequest
import com.soulseekplus.core.protocol.LoginFailure
import com.soulseekplus.core.protocol.LoginRejected
import com.soulseekplus.core.protocol.LoginRequest
import com.soulseekplus.core.protocol.LoginSuccess
import com.soulseekplus.core.protocol.MessageAcked
import com.soulseekplus.core.protocol.MessageUserRequest
import com.soulseekplus.core.protocol.ParentMinSpeedNotification
import com.soulseekplus.core.protocol.ParentSpeedRatioNotification
import com.soulseekplus.core.protocol.PeerAddressResponse
import com.soulseekplus.core.protocol.PeerCode
import com.soulseekplus.core.protocol.PeerMessage
import com.soulseekplus.core.protocol.PlaceInQueueRequest
import com.soulseekplus.core.protocol.PlaceInQueueResponse
import com.soulseekplus.core.protocol.PossibleParentsNotification
import com.soulseekplus.core.protocol.PrivateMessageNotification
import com.soulseekplus.core.protocol.PrivilegedUsersNotification
import com.soulseekplus.core.protocol.PrivilegesResponse
import com.soulseekplus.core.protocol.QueueUpload
import com.soulseekplus.core.protocol.ReloggedNotification
import com.soulseekplus.core.protocol.ResetDistributedNotification
import com.soulseekplus.core.protocol.RoomListRequest
import com.soulseekplus.core.protocol.RoomListResponse
import com.soulseekplus.core.protocol.RoomMessageNotification
import com.soulseekplus.core.protocol.RoomSearchRequest
import com.soulseekplus.core.protocol.RoomTickerAddNotification
import com.soulseekplus.core.protocol.RoomTickerRemoveNotification
import com.soulseekplus.core.protocol.RoomTickerSet
import com.soulseekplus.core.protocol.RoomTickerStateNotification
import com.soulseekplus.core.protocol.SayChatroomRequest
import com.soulseekplus.core.protocol.SearchRequestNotification
import com.soulseekplus.core.protocol.ServerMessage
import com.soulseekplus.core.protocol.SetStatus
import com.soulseekplus.core.protocol.SetWaitPort
import com.soulseekplus.core.protocol.SharedFileListRequest
import com.soulseekplus.core.protocol.SharedFileListResponse
import com.soulseekplus.core.protocol.SharedFoldersFiles
import com.soulseekplus.core.protocol.TransferRequest
import com.soulseekplus.core.protocol.TransferResponse
import com.soulseekplus.core.protocol.UnwatchUserRequest
import com.soulseekplus.core.protocol.UploadDenied
import com.soulseekplus.core.protocol.UploadFailed
import com.soulseekplus.core.protocol.UserInfoRequest
import com.soulseekplus.core.protocol.UserInfoResponse
import com.soulseekplus.core.protocol.UserJoinedRoomNotification
import com.soulseekplus.core.protocol.UserLeftRoomNotification
import com.soulseekplus.core.protocol.UserSearchRequest
import com.soulseekplus.core.protocol.UserStatsResponse
import com.soulseekplus.core.protocol.UserStatusResponse
import com.soulseekplus.core.protocol.WatchUserRequest
import com.soulseekplus.core.protocol.WatchUserResponse
import com.soulseekplus.core.protocol.WishlistIntervalNotification
import com.soulseekplus.core.protocol.WishlistSearchRequest
import com.soulseekplus.core.transfers.PathSanitizer
import com.soulseekplus.core.transfers.TransferManager
import com.soulseekplus.core.transfers.TransferNetwork
import com.soulseekplus.core.upnp.PortMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Soulseek motoru: sunucu oturumu, eş ağı, dağıtık ağ, transferler ve port eşlemesini tek bir [SoulseekClient] cephesinde
 * birleştirir.
 *
 * İş parçacığı modeli: bütün değişken durum tek iş parçacıklı "mantık" dağıtıcısında yaşar (kilit yok). Engelleyen işler
 * (paylaşım araması, dosya G/Ç, sıkıştırma) IO dağıtıcısında yürür ve sonuçlar `withContext(logic)` ile geri taşınır.
 * Herkese açık metotlar herhangi bir iş parçacığından çağrılabilir. Hiçbir kilit tutulurken G/Ç ya da geri çağrı yapılmaz.
 */
class SoulseekEngine private constructor(
    private val settings: StateFlow<ClientSettings>,
    private val userLists: StateFlow<UserLists>,
    private val shares: ShareProvider,
    downloadStore: DownloadStore,
    transferPersistence: TransferPersistence,
    private val countryResolver: CountryResolver,
    private val loadPicture: (uri: String) -> ByteArray?,
    private val logger: Logger,
    private val tuning: EngineTuning,
) : SoulseekClient {

    constructor(
        settings: StateFlow<ClientSettings>,
        userLists: StateFlow<UserLists>,
        shares: ShareProvider,
        downloadStore: DownloadStore,
        transferPersistence: TransferPersistence,
        countryResolver: CountryResolver,
        loadPicture: (uri: String) -> ByteArray?,
        logger: Logger,
    ) : this(
        settings, userLists, shares, downloadStore, transferPersistence, countryResolver, loadPicture, logger,
        EngineTuning(),
    )

    // ------------------------------------------------------------------------------------------------- kapsamlar

    private val errorHandler = CoroutineExceptionHandler { _, e -> logger.error(TAG, "Yakalanmamış coroutine hatası", e) }
    private val rootJob = SupervisorJob()
    private val logicExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "slsk-logic").apply { isDaemon = true } }
    private val logic: CoroutineDispatcher = logicExecutor.asCoroutineDispatcher()
    private val logicScope = CoroutineScope(rootJob + logic + errorHandler)
    private val ioScope = CoroutineScope(rootJob + Dispatchers.IO + errorHandler)
    private val reactor = Reactor(logger)

    // ------------------------------------------------------------------------------------------------- dışa açık akışlar

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    private val _users = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    override val users: StateFlow<Map<String, UserPresence>> = _users.asStateFlow()

    private val _network = MutableStateFlow(NetworkStatus())
    override val network: StateFlow<NetworkStatus> = _network.asStateFlow()

    private val _searchResponses = MutableSharedFlow<SearchResponse>(extraBufferCapacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val searchResponses: SharedFlow<SearchResponse> = _searchResponses.asSharedFlow()

    private val _wishlistInterval = MutableStateFlow(DEFAULT_WISHLIST_INTERVAL)
    override val wishlistIntervalSeconds: StateFlow<Int> = _wishlistInterval.asStateFlow()

    // ------------------------------------------------------------------------------------------------- iş parçacıkları arası durum

    @Volatile
    private var linkRef: ServerLink? = null

    @Volatile
    private var loggedIn = false

    @Volatile
    private var sessionUsername = ""

    private val searchTokens = BoundedTokenSet(MAX_ACTIVE_SEARCHES)
    private val searchTokenCounter = AtomicInteger(Random.nextInt(1, 1_000_000))
    private val solicited = SolicitedTracker()
    private val searchInFlight = AtomicInteger()
    private val serveInFlight = AtomicInteger()
    private val searchGate = Semaphore(2)
    private val serveGate = Semaphore(3)

    // ------------------------------------------------------------------------------------------------- mantık iş parçacığı durumu

    private enum class StopReason { NONE, MANUAL, REJECTED, RELOGGED }

    private var stopReason = StopReason.NONE
    private var generation = 0
    private var link: ServerLink? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var loginTimeoutJob: Job? = null
    private var probeJob: Job? = null
    private var attempt = 0
    private var backoffBaseMillis = 0L
    private var sessionSettings: ClientSettings? = null
    private var serverMessageCount = 0L
    private var away = false
    private var closed = false
    private var mappedPort = 0
    private var metered = false

    private val explicitWatch = LinkedHashSet<String>()
    private val transferWatch = HashSet<String>()
    private val roomMembers = HashMap<String, HashSet<String>>()
    private val desiredRooms = LinkedHashMap<String, Boolean>()
    private val privilegedUsers = HashSet<String>()

    /** Sunucudan durumu gerçekten öğrenilen kullanıcılar (varsayılan OFFLINE değerine güvenilmesin diye). */
    private val knownStatus = HashSet<String>()
    private val peerCountry = BoundedMap<String, String>(MAX_COUNTRY_CACHE)
    private val recentPmIds = BoundedMap<Int, Boolean>(MAX_RECENT_PMS)
    private var excludedPhrases: List<String> = emptyList()

    private val infoWaiters = WaiterTable<String, UserInfo>()
    private val browseWaiters = WaiterTable<String, BrowseResult>()

    private class FolderWaiter(val user: String, val result: CompletableDeferred<List<SharedFolder>>)

    private val folderWaiters = HashMap<Int, FolderWaiter>()
    private var folderToken = Random.nextInt(1, 1_000_000)

    private var pictureCache: Triple<String, Long, ByteArray?>? = null
    private val pictureLock = Any()

    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val shutdownDone = CompletableDeferred<Unit>()

    // ------------------------------------------------------------------------------------------------- bileşenler

    private val peerHost: PeerHost = object : PeerHost {
        override val username: String get() = sessionUsername
        override fun sendToServer(message: ServerMessage): Boolean = send(message)
        override val isServerReady: Boolean get() = loggedIn
        override fun isSearchTokenActive(token: Int): Boolean = searchTokens.contains(token)
        override fun isSolicited(username: String, code: Int): Boolean = solicited.isSolicited(username, code)
        override fun onPeerMessage(username: String, message: PeerMessage, connection: Connection) =
            handlePeerMessage(username, message, connection)

        override fun onFileConnection(username: String, connection: Connection) {
            transferManager.onFileConnection(username, connection)
        }

        override fun onDistributedConnection(username: String, connection: Connection) {
            distrib.onInboundConnection(username, connection)
        }
    }

    private val peers: PeerNetwork = PeerNetwork(reactor, logicScope, logic, ioScope, peerHost, logger, tuning.networkTimeouts)

    private val distribHost: DistribHost = object : DistribHost {
        override val username: String get() = sessionUsername
        override fun sendToServer(message: ServerMessage): Boolean = send(message)
        override fun onDistributedSearch(username: String, token: Int, query: String) = onIncomingSearch(username, token, query)
    }

    private val distrib: DistributedNetwork = DistributedNetwork(logicScope, logic, ioScope, peers, distribHost, logger)

    private val transferNet: TransferNetwork = object : TransferNetwork {
        override val isLoggedIn: Boolean get() = loggedIn

        override fun sendPeer(username: String, message: PeerMessage, onFailure: ((PeerFailure) -> Unit)?) {
            peers.send(username, message, onFailure)
        }

        override suspend fun openFileConnection(username: String): Connection =
            withContext(logic) { peers.connect(username, ConnType.FILE) }

        override fun sendServer(message: ServerMessage) {
            send(message)
        }

        override fun watchUser(username: String) {
            if (transferWatch.add(username)) {
                ensurePresence(username)
                if (loggedIn && username !in explicitWatch) send(WatchUserRequest(username))
            }
        }

        override fun unwatchUser(username: String) {
            if (transferWatch.remove(username) && username !in explicitWatch) {
                if (loggedIn) send(UnwatchUserRequest(username))
                pruneUsers(listOf(username))
            }
        }

        override fun userStatus(username: String): UserStatus? =
            if (username in knownStatus) _users.value[username]?.status else null

        override fun isPrivileged(username: String): Boolean = username in privilegedUsers
    }

    private val transferManager: TransferManager = TransferManager(
        scope = logicScope,
        io = Dispatchers.IO,
        settings = settings,
        userLists = userLists,
        shares = shares,
        store = downloadStore,
        persistence = transferPersistence,
        net = transferNet,
        emit = { emit(it) },
        logger = logger,
        timing = tuning.transferTiming,
    )

    private val portMapper: PortMapper = PortMapper(ioScope, logger)

    override val transfers: StateFlow<List<Transfer>> get() = transferManager.transfers
    override val transferStats: StateFlow<TransferStats> get() = transferManager.stats
    override val portMapping: StateFlow<PortMappingState> get() = portMapper.state
    override val distributed: StateFlow<DistributedState> get() = distrib.state

    // ------------------------------------------------------------------------------------------------- yaşam döngüsü

    /** Motoru başlatır: kalıcı transferleri yükler, dinlemeye başlar, ayarlar uygunsa bağlanır. Bir kez çağrılır. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        logicScope.launch {
            if (closed) return@launch
            peers.start()
            rebindListener()
            transferManager.start()
            launch { observeSettings() }
            launch { observeShares() }
            launch { statusLoop() }
            val s = settings.value
            if (s.autoConnect && s.isConfigured) startConnect(userInitiated = false)
        }
    }

    /** Her şeyi kapatır: oturum, soketler, port eşlemesi; bekleyen transfer kayıtları yazılır. Bekletmez. */
    fun shutdown() {
        if (!closing.compareAndSet(false, true)) return
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
        cleanup.launch {
            try {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                    withContext(logic) {
                        closed = true
                        stopReason = StopReason.MANUAL
                        reconnectJob?.cancel()
                        teardownSession()
                        peers.shutdown()
                        distrib.shutdown()
                        transferManager.shutdown()
                        _connection.value = ConnectionState.Disconnected("Shut down")
                    }
                    withContext(logic) { transferManager.flushPersistence() }
                    portMapper.stopAndWait(PORT_UNMAP_TIMEOUT_MILLIS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Kapatma sırasında hata", e)
            } finally {
                rootJob.cancel()
                reactor.close()
                logicExecutor.shutdown()
                shutdownDone.complete(Unit)
                cleanup.cancel()
            }
        }
    }

    /** [shutdown] gibi, ama kapanışın bitmesini en çok [timeoutMillis] bekler (testler ve süreç sonu için). */
    suspend fun shutdownAndWait(timeoutMillis: Long = SHUTDOWN_TIMEOUT_MILLIS + 2_000) {
        shutdown()
        withTimeoutOrNull(timeoutMillis) { shutdownDone.await() }
    }

    // ------------------------------------------------------------------------------------------------- oturum: genel API

    override fun connect() {
        logicScope.launch { startConnect(userInitiated = true) }
    }

    override fun disconnect() {
        logicScope.launch {
            if (closed) return@launch
            stopReason = StopReason.MANUAL
            reconnectJob?.cancel()
            reconnectJob = null
            if (_connection.value is ConnectionState.Disconnected) return@launch
            teardownSession()
            _connection.value = ConnectionState.Disconnected("Disconnected by user")
        }
    }

    override fun onNetworkChanged(onMetered: Boolean) {
        logicScope.launch {
            if (closed) return@launch
            metered = onMetered
            publishNetwork()
            transferManager.onNetworkChanged(onMetered)
            if (mappedPort != 0) portMapper.refresh()
            when (_connection.value) {
                is ConnectionState.Reconnecting -> {
                    if (stopReason == StopReason.NONE && settings.value.isConfigured) {
                        reconnectJob?.cancel()
                        attempt = 0
                        beginSession(settings.value)
                    }
                }
                is ConnectionState.LoggedIn -> probeLink()
                else -> Unit
            }
        }
    }

    override fun setAway(away: Boolean) {
        logicScope.launch {
            this@SoulseekEngine.away = away
            _connection.update { if (it is ConnectionState.LoggedIn) it.copy(away = away) else it }
            if (loggedIn) send(SetStatus(if (away) UserStatus.AWAY else UserStatus.ONLINE))
        }
    }

    // ------------------------------------------------------------------------------------------------- oturum: iç işleyiş

    private fun startConnect(userInitiated: Boolean) {
        if (closed) return
        val state = _connection.value
        if (state is ConnectionState.Connecting || state is ConnectionState.LoggedIn) return
        if (userInitiated) {
            stopReason = StopReason.NONE
            attempt = 0
        } else if (stopReason != StopReason.NONE) {
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        val s = settings.value
        if (!s.isConfigured) {
            _connection.value = ConnectionState.Disconnected("No account configured")
            if (userInitiated) emit(ClientEvent.Notice("Enter your Soulseek username and password first.", isError = true))
            return
        }
        beginSession(s)
    }

    private fun beginSession(s: ClientSettings) {
        if (closed) return
        val gen = ++generation
        sessionSettings = s
        sessionUsername = s.username
        _connection.value = ConnectionState.Connecting
        connectJob?.cancel()
        connectJob = ioScope.launch {
            var established: ServerLink? = null
            var handedOver = false
            try {
                val l = ServerLink.connect(reactor, ioScope, s.serverHost, s.serverPort, tuning.serverConnectTimeoutMillis, logger)
                established = l
                withContext(NonCancellable + logic) { handedOver = onLinkConnected(gen, l, s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable + logic) { onSessionFailed(gen, e.message ?: "Cannot connect to server") }
            } finally {
                if (!handedOver) established?.close()
            }
        }
    }

    private fun onLinkConnected(gen: Int, l: ServerLink, s: ClientSettings): Boolean {
        if (gen != generation || closed) return false
        link = l
        linkRef = l
        l.start(
            ioScope, logic, tuning.pingIntervalMillis,
            onMessage = { m -> onServerMessage(gen, m) },
            onClosed = { reason -> onSessionFailed(gen, reason ?: "Connection lost") },
        )
        l.send(LoginRequest(s.username, s.password))
        loginTimeoutJob?.cancel()
        loginTimeoutJob = logicScope.launch {
            delay(tuning.loginTimeoutMillis)
            if (gen == generation && !loggedIn) onSessionFailed(gen, "Login timed out")
        }
        return true
    }

    private fun onSessionFailed(gen: Int, reason: String) {
        if (gen != generation || closed) return
        val wasLoggedIn = loggedIn
        teardownSession()
        if (stopReason != StopReason.NONE) return
        if (wasLoggedIn) logger.info(TAG, "Sunucu bağlantısı koptu: $reason")
        scheduleReconnect(reason)
    }

    /** Oturumun her şeyini kapatır; eski bağlantıdan gelen geri çağrılar [generation] sayesinde yok sayılır. */
    private fun teardownSession() {
        generation++
        connectJob?.cancel()
        connectJob = null
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        probeJob?.cancel()
        probeJob = null
        val l = link
        link = null
        linkRef = null
        loggedIn = false
        l?.close()
        peers.onServerDisconnected()
        distrib.onServerDisconnected()
        transferManager.onDisconnected()
        val error = SoulseekException("Disconnected from server")
        infoWaiters.failAll(error)
        browseWaiters.failAll(error)
        for (w in folderWaiters.values.toList()) w.result.completeExceptionally(error)
        folderWaiters.clear()
        solicited.clearAll()
        roomMembers.clear()
        knownStatus.clear()
        pruneUsers(_users.value.keys)
    }

    private fun scheduleReconnect(reason: String?) {
        attempt++
        if (attempt == 1) {
            backoffBaseMillis = Random.nextLong(tuning.reconnectMinMillis, tuning.reconnectFirstMaxMillis + 1)
        }
        val shift = (attempt - 1).coerceIn(0, 16)
        val delayMillis = minOf(tuning.reconnectCapMillis, backoffBaseMillis shl shift)
        _connection.value = ConnectionState.Reconnecting(attempt, System.currentTimeMillis() + delayMillis, reason)
        reconnectJob?.cancel()
        reconnectJob = logicScope.launch {
            delay(delayMillis)
            reconnectJob = null
            if (closed || stopReason != StopReason.NONE) return@launch
            val s = settings.value
            if (!s.isConfigured) {
                _connection.value = ConnectionState.Disconnected("No account configured")
            } else {
                beginSession(s)
            }
        }
    }

    /** Ağ değişince: sunucudan yanıt isteyip gelmezse bağlantıyı ölü sayıp yeniden bağlanır. */
    private fun probeLink() {
        val gen = generation
        val before = serverMessageCount
        probeJob?.cancel()
        if (!send(GetUserStatsRequest(sessionUsername))) return
        probeJob = logicScope.launch {
            delay(tuning.probeTimeoutMillis)
            if (gen == generation && loggedIn && serverMessageCount == before) onSessionFailed(gen, "Network changed")
        }
    }

    private fun send(message: ServerMessage): Boolean {
        val l = linkRef ?: return false
        return l.send(message)
    }

    private fun emit(event: ClientEvent) {
        _events.tryEmit(event)
    }

    private fun publishNetwork() {
        val port = peers.listenPort
        _network.value = NetworkStatus(
            listening = port > 0,
            listenPort = port,
            onMetered = metered,
            connectedPeers = peers.peerCount(),
        )
    }

    private suspend fun statusLoop() {
        while (logicScope.isActive) {
            delay(tuning.statusIntervalMillis)
            publishNetwork()
        }
    }

    // ------------------------------------------------------------------------------------------------- dinleyici ve port eşleme

    private fun rebindListener() {
        if (closed) return
        val wanted = settings.value.listenPort
        val candidates = if (wanted <= 0) listOf(0) else (0..LISTEN_PORT_FALLBACKS).map { wanted + it }.filter { it <= 65535 }
        var bound = false
        for (p in candidates) {
            if (peers.listen(p)) {
                bound = true
                break
            }
        }
        if (!bound) {
            emit(ClientEvent.Notice("Cannot listen for incoming connections on port $wanted.", isError = true))
        } else if (peers.listenPort != wanted && wanted > 0) {
            emit(ClientEvent.Notice("Port $wanted is busy; listening on port ${peers.listenPort} instead."))
        }
        publishNetwork()
        if (loggedIn) {
            if (peers.listenPort > 0) send(SetWaitPort(peers.listenPort))
            ensurePortMapping()
        }
    }

    private fun ensurePortMapping() {
        val port = peers.listenPort
        if (!settings.value.portMappingEnabled || port <= 0) {
            if (mappedPort != 0) {
                portMapper.stop()
                mappedPort = 0
            }
            return
        }
        if (mappedPort != port) {
            portMapper.start(port)
            mappedPort = port
        }
    }

    // ------------------------------------------------------------------------------------------------- ayarları ve paylaşımları izleme

    private suspend fun observeSettings() {
        var previous = settings.value
        settings.collect { current ->
            val old = previous
            previous = current
            try {
                onSettingsChanged(old, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Ayar değişikliği uygulanamadı", e)
            }
        }
    }

    private fun onSettingsChanged(old: ClientSettings, new: ClientSettings) {
        if (closed || old == new) return
        if (old.listenPort != new.listenPort) rebindListener()
        if (old.portMappingEnabled != new.portMappingEnabled && loggedIn) ensurePortMapping()
        val credentialsChanged = old.username != new.username || old.password != new.password ||
            old.serverHost != new.serverHost || old.serverPort != new.serverPort
        if (credentialsChanged) onCredentialsChanged(new)
        if (old.autoJoinRooms != new.autoJoinRooms) {
            for (room in new.autoJoinRooms) {
                if (!desiredRooms.containsKey(room)) {
                    desiredRooms[room] = false
                    if (loggedIn) send(JoinRoomRequest(room, false))
                }
            }
        }
    }

    private fun onCredentialsChanged(new: ClientSettings) {
        val state = _connection.value
        if (!new.isConfigured) {
            if (state is ConnectionState.Connecting || state is ConnectionState.LoggedIn || state is ConnectionState.Reconnecting) {
                stopReason = StopReason.MANUAL
                reconnectJob?.cancel()
                reconnectJob = null
                teardownSession()
                _connection.value = ConnectionState.Disconnected("No account configured")
            }
            return
        }
        when (state) {
            is ConnectionState.Connecting, is ConnectionState.LoggedIn -> {
                teardownSession()
                attempt = 0
                beginSession(new)
            }
            is ConnectionState.Reconnecting -> {
                reconnectJob?.cancel()
                reconnectJob = null
                attempt = 0
                beginSession(new)
            }
            is ConnectionState.Disconnected -> {
                if (stopReason == StopReason.REJECTED) {
                    stopReason = StopReason.NONE
                    attempt = 0
                    beginSession(new)
                } else if (stopReason == StopReason.NONE && new.autoConnect) {
                    attempt = 0
                    beginSession(new)
                }
            }
        }
    }

    private suspend fun observeShares() {
        shares.stats.collect { stats ->
            if (loggedIn) send(SharedFoldersFiles(stats.folders, stats.files))
        }
    }

    // ------------------------------------------------------------------------------------------------- sunucu mesajları

    private fun onServerMessage(gen: Int, message: ServerMessage) {
        if (gen != generation) return
        serverMessageCount++
        try {
            dispatchServerMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Sunucu mesajı işlenemedi (kod ${message.code})", e)
        }
    }

    private fun dispatchServerMessage(m: ServerMessage) {
        when (m) {
            is LoginSuccess -> onLoginSuccess(m)
            is LoginRejected -> onLoginRejected(m)
            is PeerAddressResponse -> {
                if (!m.isOffline) rememberCountry(m.username, m.ip)
                peers.onPeerAddress(m)
            }
            is WatchUserResponse -> onWatchResponse(m)
            is UserStatusResponse -> onUserStatusResponse(m)
            is UserStatsResponse -> onUserStatsResponse(m)
            is RoomMessageNotification ->
                emit(ClientEvent.RoomMessage(m.room, m.username, m.text, System.currentTimeMillis()))
            is JoinRoomResponse -> onJoinRoomResponse(m)
            is LeaveRoomNotification -> onLeaveRoom(m.room)
            is UserJoinedRoomNotification -> {
                roomMembers.getOrPut(m.room) { HashSet() }.add(m.user.username)
                mergeRoomUsers(listOf(m.user))
                emit(ClientEvent.UserJoinedRoom(m.room, m.user))
            }
            is UserLeftRoomNotification -> {
                roomMembers[m.room]?.remove(m.username)
                pruneUsers(listOf(m.username))
                emit(ClientEvent.UserLeftRoom(m.room, m.username))
            }
            is ConnectToPeerNotification -> peers.onConnectToPeer(m)
            is CantConnectToPeerNotification -> peers.onCantConnectToPeer(m)
            is PrivateMessageNotification -> onPrivateMessage(m)
            is SearchRequestNotification -> onIncomingSearch(m.username, m.token, m.query)
            is ReloggedNotification -> {
                stopReason = StopReason.RELOGGED
                reconnectJob?.cancel()
                reconnectJob = null
                teardownSession()
                _connection.value = ConnectionState.Disconnected("Logged in from another location")
                emit(ClientEvent.Relogged)
            }
            is RoomListResponse -> emit(ClientEvent.RoomList(m.rooms))
            is AdminMessageNotification -> emit(ClientEvent.AdminMessage(m.text))
            is PrivilegedUsersNotification -> {
                privilegedUsers.clear()
                privilegedUsers.addAll(m.users)
            }
            is ParentMinSpeedNotification -> distrib.onParentMinSpeed(m.minSpeed)
            is ParentSpeedRatioNotification -> distrib.onParentSpeedRatio(m.ratio)
            is PrivilegesResponse -> _connection.update {
                if (it is ConnectionState.LoggedIn) it.copy(privileged = m.secondsRemaining > 0) else it
            }
            is EmbeddedMessageNotification -> distrib.onEmbeddedMessage(m.distribCode, m.body)
            is PossibleParentsNotification -> distrib.onPossibleParents(m)
            is WishlistIntervalNotification -> {
                val seconds = m.seconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                _wishlistInterval.value = seconds
                emit(ClientEvent.WishlistInterval(seconds))
            }
            is RoomTickerStateNotification -> emit(ClientEvent.RoomTickers(m.room, m.tickers))
            is RoomTickerAddNotification -> emit(ClientEvent.RoomTickerAdded(m.room, m.username, m.text))
            is RoomTickerRemoveNotification -> emit(ClientEvent.RoomTickerRemoved(m.room, m.username))
            is ResetDistributedNotification -> distrib.onReset()
            is ExcludedSearchPhrasesNotification -> excludedPhrases = m.phrases.map { it.lowercase() }
            is CantCreateRoomNotification -> {
                desiredRooms.remove(m.room)
                emit(ClientEvent.RoomJoinFailed(m.room, "Room cannot be created or joined"))
            }
            else -> Unit
        }
    }

    private fun onLoginSuccess(m: LoginSuccess) {
        loginTimeoutJob?.cancel()
        loginTimeoutJob = null
        loggedIn = true
        attempt = 0
        stopReason = StopReason.NONE
        val name = sessionUsername
        val publicIp = m.publicIp.takeIf { it.isNotEmpty() && it != "0.0.0.0" }
        _connection.value = ConnectionState.LoggedIn(name, publicIp, privileged = false, greeting = m.greeting, away = away)
        val port = peers.listenPort
        if (port > 0) send(SetWaitPort(port))
        send(SetStatus(if (away) UserStatus.AWAY else UserStatus.ONLINE))
        val stats = shares.stats.value
        send(SharedFoldersFiles(stats.folders, stats.files))
        send(CheckPrivilegesRequest)
        send(WatchUserRequest(name))
        distrib.onLoggedIn()
        for (u in explicitWatch + transferWatch) if (u != name) send(WatchUserRequest(u))
        val s = settings.value
        for (room in s.autoJoinRooms) desiredRooms.putIfAbsent(room, false)
        for ((room, private) in desiredRooms) send(JoinRoomRequest(room, private))
        ensurePortMapping()
        transferManager.onLoggedIn()
        emit(ClientEvent.LoggedIn(name))
    }

    private fun onLoginRejected(m: LoginRejected) {
        val text = when (m.reason) {
            LoginFailure.INVALID_PASSWORD -> "Invalid password"
            LoginFailure.INVALID_USERNAME -> m.detail?.takeIf { it.isNotBlank() }?.let { "Invalid username: $it" } ?: "Invalid username"
            LoginFailure.EMPTY_PASSWORD -> "Password is empty"
            LoginFailure.INVALID_VERSION -> "Client version rejected by server"
            LoginFailure.SERVER_FULL -> "Server is full"
            LoginFailure.SERVER_PRIVATE -> "Server is private"
            else -> "Login rejected (${m.reason})"
        }
        stopReason = StopReason.REJECTED
        reconnectJob?.cancel()
        reconnectJob = null
        teardownSession()
        _connection.value = ConnectionState.Disconnected(text)
        emit(ClientEvent.Notice(text, isError = true))
    }

    // ------------------------------------------------------------------------------------------------- kullanıcı varlığı

    private fun isWanted(username: String): Boolean =
        username == sessionUsername || username in explicitWatch || username in transferWatch ||
            roomMembers.values.any { username in it }

    private fun ensurePresence(username: String) {
        _users.update { map ->
            if (username in map) map else map + (username to newPresence(username))
        }
    }

    private fun newPresence(username: String): UserPresence =
        UserPresence(username, countryCode = peerCountry[username]?.takeIf { it.isNotEmpty() })

    /** Mevcut ya da (isteniyorsa) yeni kaydı değiştirir. Eşitse yeni durum yayımlanmaz. */
    private fun updatePresence(username: String, change: (UserPresence) -> UserPresence) {
        _users.update { map ->
            val current = map[username]
            if (current == null && !isWanted(username)) return@update map
            val base = current ?: newPresence(username)
            val next = change(base)
            if (current != null && next == current) map else map + (username to next)
        }
    }

    private fun pruneUsers(candidates: Collection<String>) {
        if (candidates.isEmpty()) return
        val snapshot = candidates.toList()
        _users.update { map ->
            val remove = snapshot.filter { it in map && !isWanted(it) }
            if (remove.isEmpty()) map else map - remove.toSet()
        }
        for (u in snapshot) if (!isWanted(u)) knownStatus.remove(u)
    }

    private fun mergeRoomUsers(users: List<RoomUser>) {
        if (users.isEmpty()) return
        for (u in users) knownStatus.add(u.username)
        _users.update { map ->
            val result = LinkedHashMap(map)
            for (u in users) {
                val cur = result[u.username]
                val country = u.countryCode ?: cur?.countryCode ?: peerCountry[u.username]?.takeIf { it.isNotEmpty() }
                result[u.username] = (cur ?: UserPresence(u.username)).copy(status = u.status, stats = u.stats, countryCode = country)
            }
            result
        }
    }

    private fun onWatchResponse(m: WatchUserResponse) {
        val user = m.username
        knownStatus.add(user)
        if (!m.exists) {
            updatePresence(user) { it.copy(status = UserStatus.OFFLINE) }
            emit(ClientEvent.UserStatusChanged(user, UserStatus.OFFLINE, false))
            if (user in transferWatch) transferManager.onUserStatus(user, UserStatus.OFFLINE)
            return
        }
        updatePresence(user) {
            it.copy(status = m.status, stats = m.stats ?: it.stats, countryCode = m.countryCode ?: it.countryCode)
        }
        emit(ClientEvent.UserStatusChanged(user, m.status, _users.value[user]?.privileged ?: (user in privilegedUsers)))
        m.stats?.let {
            emit(ClientEvent.UserStatsChanged(user, it))
            if (user == sessionUsername) distrib.onOwnSpeed(it.averageSpeed.toLong().coerceAtLeast(0))
        }
        if (user in transferWatch) transferManager.onUserStatus(user, m.status)
    }

    private fun onUserStatusResponse(m: UserStatusResponse) {
        knownStatus.add(m.username)
        if (m.privileged) privilegedUsers.add(m.username) else privilegedUsers.remove(m.username)
        updatePresence(m.username) { it.copy(status = m.status, privileged = m.privileged) }
        emit(ClientEvent.UserStatusChanged(m.username, m.status, m.privileged))
        if (m.username in transferWatch) transferManager.onUserStatus(m.username, m.status)
    }

    private fun onUserStatsResponse(m: UserStatsResponse) {
        updatePresence(m.username) { it.copy(stats = m.stats) }
        emit(ClientEvent.UserStatsChanged(m.username, m.stats))
        if (m.username == sessionUsername) distrib.onOwnSpeed(m.stats.averageSpeed.toLong().coerceAtLeast(0))
    }

    private fun rememberCountry(username: String, ip: String) {
        if (peerCountry.containsKey(username)) return
        val code = try {
            countryResolver.countryOf(ip)
        } catch (e: Exception) {
            null
        }
        peerCountry[username] = code.orEmpty()
        if (code != null) updatePresenceIfPresent(username) { if (it.countryCode == null) it.copy(countryCode = code) else it }
    }

    private fun updatePresenceIfPresent(username: String, change: (UserPresence) -> UserPresence) {
        _users.update { map ->
            val current = map[username] ?: return@update map
            val next = change(current)
            if (next == current) map else map + (username to next)
        }
    }

    // ------------------------------------------------------------------------------------------------- sohbet odaları ve mesajlar

    private fun onJoinRoomResponse(m: JoinRoomResponse) {
        desiredRooms[m.room] = m.isPrivate
        roomMembers[m.room] = m.users.mapTo(HashSet()) { it.username }
        mergeRoomUsers(m.users)
        emit(ClientEvent.RoomJoined(m.room, m.users, m.isPrivate, m.owner, m.operators))
    }

    private fun onLeaveRoom(room: String) {
        desiredRooms.remove(room)
        val members = roomMembers.remove(room)
        if (members != null) pruneUsers(members)
        emit(ClientEvent.RoomLeft(room))
    }

    private fun onPrivateMessage(m: PrivateMessageNotification) {
        // Her mesaj onaylanır (yoksayılanlar ve yinelenenler dahil); aksi halde sunucu sonsuza dek yeniden gönderir.
        send(MessageAcked(m.messageId))
        if (recentPmIds.put(m.messageId, true) != null) return
        if (userLists.value.isIgnored(m.username)) return
        emit(
            ClientEvent.PrivateMessage(
                id = m.messageId,
                username = m.username,
                text = m.text,
                timestampMillis = m.timestampSeconds * 1000,
                isAdmin = m.username == "server",
                isOfflineMessage = !m.isNew,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------------- kullanıcılar (istemci API)

    override fun watchUser(username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        logicScope.launch {
            if (!explicitWatch.add(name)) return@launch
            ensurePresence(name)
            if (loggedIn && name !in transferWatch && name != sessionUsername) send(WatchUserRequest(name))
        }
    }

    override fun unwatchUser(username: String) {
        val name = username.trim()
        logicScope.launch {
            if (!explicitWatch.remove(name)) return@launch
            if (name in transferWatch || name == sessionUsername) return@launch
            if (loggedIn) send(UnwatchUserRequest(name))
            pruneUsers(listOf(name))
        }
    }

    override suspend fun getUserInfo(username: String): UserInfo {
        val name = requireUsername(username)
        val response = requestFromPeer(
            name, PeerCode.USER_INFO_RESPONSE, infoWaiters, UserInfoRequest, tuning.userInfoTimeoutMillis,
        )
        return response
    }

    override suspend fun browseUser(username: String): BrowseResult {
        val name = requireUsername(username)
        return requestFromPeer(
            name, PeerCode.SHARED_FILE_LIST_RESPONSE, browseWaiters, SharedFileListRequest, tuning.browseTimeoutMillis,
        )
    }

    override suspend fun getFolderContents(username: String, folderPath: String): List<SharedFolder> {
        val name = requireUsername(username)
        if (folderPath.isBlank()) throw SoulseekException("Folder path is empty")
        return withContext(logic) {
            if (!loggedIn) throw SoulseekException("Not connected to server")
            val token = nextFolderToken()
            val waiter = FolderWaiter(name, CompletableDeferred())
            folderWaiters[token] = waiter
            peers.send(name, FolderContentsRequest(token, folderPath)) { failure ->
                if (folderWaiters.remove(token) != null) waiter.result.completeExceptionally(peerFailure(name, failure))
            }
            try {
                // withTimeoutOrNull: çağıranın kendi zaman aşımı/iptali SoulseekException'a dönüştürülmez.
                withTimeoutOrNull(tuning.folderTimeoutMillis) { waiter.result.await() }
                    ?: throw SoulseekException("Timed out waiting for folder contents from $name")
            } finally {
                folderWaiters.remove(token)
            }
        }
    }

    override suspend fun getUserAddress(username: String): Pair<String, Int>? {
        val name = requireUsername(username)
        return withContext(logic) {
            if (!loggedIn) throw SoulseekException("Not connected to server")
            try {
                peers.lookupAddress(name)?.let { it.ip to it.port }
            } catch (e: IOException) {
                throw SoulseekException("Could not get the address of $name", e)
            }
        }
    }

    private fun requireUsername(username: String): String {
        val name = username.trim()
        if (name.isEmpty() || name.length > MAX_USERNAME_LENGTH) throw SoulseekException("Invalid username")
        return name
    }

    private fun nextFolderToken(): Int {
        folderToken = if (folderToken >= Int.MAX_VALUE - 1) 1 else folderToken + 1
        return folderToken
    }

    private fun peerFailure(user: String, failure: PeerFailure): SoulseekException = when (failure) {
        PeerFailure.OFFLINE -> SoulseekException("User $user is offline")
        PeerFailure.UNREACHABLE -> SoulseekException("Could not connect to $user")
    }

    /** Eşe bir istek gönderip tek tip yanıtını bekler; aynı kullanıcıya aynı anda gelen çağrılar tek isteği paylaşır. */
    private suspend fun <T> requestFromPeer(
        user: String,
        responseCode: Int,
        table: WaiterTable<String, T>,
        request: PeerMessage,
        timeoutMillis: Long,
    ): T = withContext(logic) {
        if (!loggedIn) throw SoulseekException("Not connected to server")
        val waiter = CompletableDeferred<T>()
        if (table.add(user, waiter)) {
            solicited.expect(user, responseCode, timeoutMillis + SOLICITED_GRACE_MILLIS)
            peers.send(user, request) { failure ->
                if (table.fail(user, peerFailure(user, failure))) solicited.clear(user, responseCode)
            }
        }
        try {
            val box = withTimeoutOrNull(timeoutMillis) { Box(waiter.await()) }
                ?: throw SoulseekException("Timed out waiting for response from $user")
            box.value
        } finally {
            if (table.remove(user, waiter)) solicited.clear(user, responseCode)
        }
    }

    // ------------------------------------------------------------------------------------------------- arama (giden)

    override fun search(query: String, scope: SearchScope): Int {
        val token = nextSearchToken()
        searchTokens.add(token)
        val text = query.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) return token
        logicScope.launch {
            if (!loggedIn) return@launch
            when (scope) {
                is SearchScope.Network -> send(FileSearchRequest(token, text))
                is SearchScope.Room -> send(RoomSearchRequest(scope.room, token, text))
                is SearchScope.Buddies ->
                    for (buddy in userLists.value.buddies.take(MAX_BUDDY_SEARCHES)) send(UserSearchRequest(buddy, token, text))
                is SearchScope.User -> send(UserSearchRequest(scope.username, token, text))
                is SearchScope.Wishlist -> send(WishlistSearchRequest(token, text))
            }
        }
        return token
    }

    private fun nextSearchToken(): Int {
        while (true) {
            val current = searchTokenCounter.get()
            val next = if (current >= Int.MAX_VALUE - 1) 1 else current + 1
            if (searchTokenCounter.compareAndSet(current, next)) return next
        }
    }

    override fun cancelSearch(token: Int) {
        searchTokens.remove(token)
    }

    private fun onSearchResponse(username: String, m: FileSearchResponseMessage) {
        if (userLists.value.isIgnored(username)) return
        if (username == sessionUsername) return
        if (!searchTokens.contains(m.token)) return
        _searchResponses.tryEmit(
            SearchResponse(
                username = username,
                token = m.token,
                files = m.files,
                lockedFiles = m.lockedFiles,
                freeSlots = m.freeSlots,
                averageSpeed = m.uploadSpeed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                queueLength = m.queueLength,
                countryCode = peerCountry[username]?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    // ------------------------------------------------------------------------------------------------- arama (gelen)

    private fun onIncomingSearch(requester: String, token: Int, query: String) {
        if (!loggedIn || closed) return
        val s = settings.value
        if (!s.respondToSearches || s.maxSearchResultsToSend <= 0) return
        if (requester == sessionUsername && !searchTokens.contains(token)) return
        if (userLists.value.isBanned(requester)) return
        val text = query.trim()
        if (text.length < tuning.minSearchChars || text.length > MAX_INCOMING_QUERY) return
        if (excludedPhrases.isNotEmpty()) {
            val lower = text.lowercase()
            if (excludedPhrases.any { it.isNotEmpty() && lower.contains(it) }) return
        }
        if (searchInFlight.incrementAndGet() > MAX_SEARCH_IN_FLIGHT) {
            searchInFlight.decrementAndGet()
            return
        }
        val max = s.maxSearchResultsToSend
        val me = sessionUsername
        val phrases = excludedPhrases.filter { it.isNotEmpty() }
        ioScope.launch {
            try {
                val raw = searchGate.withPermit { shares.search(text, requester, max) }
                // Protokol §9 adım 7: yolunda sunucunun yasakladığı ifade geçen dosyalar yanıtta gönderilmez.
                val result = if (phrases.isEmpty()) raw else ShareSearchResult(
                    raw.files.filter { f -> f.path.lowercase().let { p -> phrases.none { p.contains(it) } } },
                    raw.locked.filter { f -> f.path.lowercase().let { p -> phrases.none { p.contains(it) } } },
                )
                if (result.isEmpty) return@launch
                val ctx = withContext(logic) {
                    if (!loggedIn) null
                    else SearchContext(
                        freeSlots = transferManager.hasFreeUploadSlot(),
                        speed = _users.value[me]?.stats?.averageSpeed?.toLong()?.coerceAtLeast(0) ?: 0L,
                        queue = transferManager.queueSizeFor(requester).toLong(),
                    )
                } ?: return@launch
                val frame = FileSearchResponseMessage(
                    me, token, result.files, result.locked, ctx.freeSlots, ctx.speed, ctx.queue,
                ).encode()
                withContext(logic) { if (loggedIn) peers.sendFrameThenRelease(requester, frame) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Arama yanıtı hazırlanamadı", e)
            } finally {
                searchInFlight.decrementAndGet()
            }
        }
    }

    /** withTimeoutOrNull ile null sonucu zaman aşımından ayırmak için. */
    private class Box<T>(val value: T)

    private class SearchContext(val freeSlots: Boolean, val speed: Long, val queue: Long)

    // ------------------------------------------------------------------------------------------------- eş mesajları

    private fun handlePeerMessage(username: String, message: PeerMessage, conn: Connection) {
        try {
            rememberCountry(username, conn.remoteIp)
            when (message) {
                is FileSearchResponseMessage -> onSearchResponse(username, message)
                is SharedFileListRequest -> serveBrowse(username, conn)
                is SharedFileListResponse -> {
                    solicited.clear(username, PeerCode.SHARED_FILE_LIST_RESPONSE)
                    browseWaiters.complete(username, BrowseResult(username, message.folders, message.lockedFolders))
                }
                is UserInfoRequest -> serveUserInfo(username, conn)
                is UserInfoResponse -> {
                    solicited.clear(username, PeerCode.USER_INFO_RESPONSE)
                    infoWaiters.complete(
                        username,
                        UserInfo(
                            username = username,
                            description = message.description,
                            picture = message.picture,
                            uploadSlots = message.totalSlots,
                            queueSize = message.queueSize,
                            slotsAvailable = message.slotsFree,
                            uploadPermission = message.uploadAllowed,
                        ),
                    )
                }
                is FolderContentsRequest -> serveFolder(username, conn, message)
                is FolderContentsResponse -> {
                    val waiter = folderWaiters[message.token]
                    if (waiter != null && waiter.user == username) {
                        folderWaiters.remove(message.token)
                        waiter.result.complete(message.folders)
                    }
                }
                is TransferRequest -> transferManager.onTransferRequest(username, message)
                is TransferResponse -> transferManager.onTransferResponse(username, message)
                is QueueUpload -> transferManager.onQueueUpload(username, message)
                is PlaceInQueueRequest -> transferManager.onPlaceInQueueRequest(username, message)
                is PlaceInQueueResponse -> transferManager.onPlaceInQueueResponse(username, message)
                is UploadFailed -> transferManager.onUploadFailed(username, message)
                is UploadDenied -> transferManager.onUploadDenied(username, message)
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Eş mesajı işlenemedi ($username)", e)
        }
    }

    /** Yanıtı isteğin geldiği bağlantıdan gönderir; kapanmışsa eşin güncel bağlantısına (gerekirse yeniden kurarak) bırakır. */
    private fun reply(username: String, conn: Connection, frame: ByteArray) {
        if (!conn.isClosed && conn.send(frame)) {
            conn.hasPostInitActivity = true
            return
        }
        peers.sendFrame(username, frame)
    }

    /** Eşlerin istekleriyle çoğalan ağır işleri sınırlar (bir bağlantı sınırsız istek yağdırabilir). */
    private fun serveAsync(username: String, label: String, work: suspend () -> ByteArray?, deliver: (ByteArray) -> Unit) {
        if (serveInFlight.incrementAndGet() > MAX_SERVE_IN_FLIGHT) {
            serveInFlight.decrementAndGet()
            return
        }
        ioScope.launch {
            try {
                val frame = serveGate.withPermit { work() } ?: return@launch
                withContext(logic) { if (loggedIn) deliver(frame) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "$label yanıtı hazırlanamadı ($username)", e)
            } finally {
                serveInFlight.decrementAndGet()
            }
        }
    }

    private fun serveBrowse(username: String, conn: Connection) {
        emit(ClientEvent.SharesBrowsedBy(username))
        val banned = userLists.value.isBanned(username)
        serveAsync(
            username, "Paylaşım listesi",
            work = {
                if (banned) {
                    SharedFileListResponse(emptyList(), emptyList()).encode()
                } else {
                    val result = shares.browse(username)
                    SharedFileListResponse(result.folders, result.lockedFolders).encode()
                }
            },
            deliver = { frame -> reply(username, conn, frame) },
        )
    }

    private fun serveFolder(username: String, conn: Connection, request: FolderContentsRequest) {
        val banned = userLists.value.isBanned(username)
        serveAsync(
            username, "Klasör içeriği",
            work = {
                val folders = if (banned) emptyList() else shares.folderContents(username, request.folder)
                FolderContentsResponse(request.token, request.folder, folders).encode()
            },
            deliver = { frame -> reply(username, conn, frame) },
        )
    }

    private fun serveUserInfo(username: String, conn: Connection) {
        val s = settings.value
        val slots = transferManager.totalUploadSlots()
        val queue = transferManager.queueSizeFor(username)
        val free = transferManager.hasFreeUploadSlot()
        val permission = if (s.uploadToBuddiesOnly) PERMISSION_BUDDIES else PERMISSION_EVERYONE
        val uri = s.userPictureUri
        val description = s.userDescription
        serveAsync(
            username, "Kullanıcı bilgisi",
            work = {
                val picture = uri?.let { cachedPicture(it) }
                UserInfoResponse(description, picture, slots, queue, free, permission).encode()
            },
            deliver = { frame -> reply(username, conn, frame) },
        )
    }

    /** Profil resmini yükler; sık gelen istekler diski yormasın diye kısa süre bellekte tutar. */
    private fun cachedPicture(uri: String): ByteArray? {
        val now = System.nanoTime()
        synchronized(pictureLock) {
            val c = pictureCache
            if (c != null && c.first == uri && now - c.second < PICTURE_CACHE_NANOS) return c.third
        }
        val bytes = try {
            loadPicture(uri)?.takeIf { it.isNotEmpty() && it.size <= MAX_PICTURE_BYTES }
        } catch (e: Exception) {
            logger.warn(TAG, "Profil resmi okunamadı", e)
            null
        }
        synchronized(pictureLock) { pictureCache = Triple(uri, now, bytes) }
        return bytes
    }

    // ------------------------------------------------------------------------------------------------- sohbet (istemci API)

    override fun requestRoomList() {
        logicScope.launch { if (loggedIn) send(RoomListRequest) }
    }

    override fun joinRoom(room: String, private: Boolean) {
        val name = room.trim()
        if (name.isEmpty()) return
        logicScope.launch {
            desiredRooms[name] = private
            if (loggedIn) send(JoinRoomRequest(name, private))
        }
    }

    override fun leaveRoom(room: String) {
        logicScope.launch {
            desiredRooms.remove(room)
            if (loggedIn) send(LeaveRoomRequest(room))
        }
    }

    override fun sayInRoom(room: String, text: String) {
        if (text.isBlank()) return
        logicScope.launch { if (loggedIn) send(SayChatroomRequest(room, text)) }
    }

    override fun sendPrivateMessage(username: String, text: String) {
        if (text.isBlank() || username.isBlank()) return
        logicScope.launch {
            if (loggedIn && send(MessageUserRequest(username, text))) {
                emit(ClientEvent.PrivateMessageSent(username, text, System.currentTimeMillis()))
            }
        }
    }

    override fun setRoomTicker(room: String, text: String) {
        logicScope.launch { if (loggedIn) send(RoomTickerSet(room, text)) }
    }

    // ------------------------------------------------------------------------------------------------- transferler (istemci API)

    override fun download(username: String, file: SharedFile, destinationSubfolder: String?) {
        logicScope.launch { transferManager.enqueueDownload(username, file, destinationSubfolder) }
    }

    override suspend fun downloadFolder(username: String, folderPath: String): Int {
        val name = requireUsername(username)
        val base = folderPath.replace('/', '\\').trimEnd('\\')
        val folders = getFolderContents(name, base)
        val baseName = base.substringAfterLast('\\')
        val prefix = if (settings.value.downloadsIntoUserFolder) name else null
        val items = ArrayList<Pair<SharedFile, String?>>()
        for (folder in folders) {
            val relative = relativeFolder(base, folder.path)
            val sub = PathSanitizer.subfolder(listOfNotNull(prefix, baseName, relative).filter { it.isNotEmpty() }.joinToString("/"))
            for (file in folder.files) {
                items.add(file to sub)
                if (items.size > MAX_FOLDER_FILES) throw SoulseekException("Folder contains too many files")
            }
        }
        if (items.isEmpty()) return 0
        withContext(logic) {
            for ((file, sub) in items) transferManager.enqueueDownload(name, file, sub)
        }
        return items.size
    }

    /** [base] altındaki göreli klasör yolu (`\` ayırıcı); [base]'in altında değilse yalnızca son parça. */
    private fun relativeFolder(base: String, path: String): String {
        val p = path.replace('/', '\\').trimEnd('\\')
        if (p.equals(base, ignoreCase = true)) return ""
        if (p.length > base.length && p.startsWith(base + "\\", ignoreCase = true)) return p.substring(base.length + 1)
        return p.substringAfterLast('\\')
    }

    override fun pauseTransfers(ids: Collection<String>) {
        val copy = ids.toList()
        logicScope.launch { transferManager.pause(copy) }
    }

    override fun resumeTransfers(ids: Collection<String>) {
        val copy = ids.toList()
        logicScope.launch { transferManager.resume(copy) }
    }

    override fun cancelTransfers(ids: Collection<String>, deleteFiles: Boolean) {
        val copy = ids.toList()
        logicScope.launch { transferManager.cancel(copy, deleteFiles) }
    }

    override fun removeTransfers(ids: Collection<String>) {
        val copy = ids.toList()
        logicScope.launch { transferManager.remove(copy) }
    }

    override fun clearFinished(direction: TransferDirection) {
        logicScope.launch { transferManager.clearFinished(direction) }
    }

    override fun refreshQueuePosition(id: String) {
        logicScope.launch { transferManager.refreshQueuePosition(id) }
    }

    companion object {
        private const val TAG = "Engine"
        private const val DEFAULT_WISHLIST_INTERVAL = 720
        private const val MAX_ACTIVE_SEARCHES = 1_000
        private const val MAX_COUNTRY_CACHE = 4_096
        private const val MAX_RECENT_PMS = 512
        private const val MAX_USERNAME_LENGTH = 64
        private const val MAX_INCOMING_QUERY = 512
        private const val MAX_SEARCH_IN_FLIGHT = 32
        private const val MAX_SERVE_IN_FLIGHT = 24
        private const val MAX_BUDDY_SEARCHES = 1_000
        private const val MAX_FOLDER_FILES = 20_000
        private const val MAX_PICTURE_BYTES = 4 * 1024 * 1024
        private const val PICTURE_CACHE_NANOS = 60_000_000_000L
        private const val LISTEN_PORT_FALLBACKS = 5
        private const val SOLICITED_GRACE_MILLIS = 5_000L
        private const val SHUTDOWN_TIMEOUT_MILLIS = 8_000L
        private const val PORT_UNMAP_TIMEOUT_MILLIS = 3_000L
        private const val PERMISSION_EVERYONE = 1
        private const val PERMISSION_BUDDIES = 2
        private val WHITESPACE = Regex("\\s+")

        /** Testler için: kısaltılmış zaman aşımlarıyla motor kurar. */
        fun withTuning(
            settings: StateFlow<ClientSettings>,
            userLists: StateFlow<UserLists>,
            shares: ShareProvider,
            downloadStore: DownloadStore,
            transferPersistence: TransferPersistence,
            countryResolver: CountryResolver,
            loadPicture: (uri: String) -> ByteArray?,
            logger: Logger,
            tuning: EngineTuning,
        ): SoulseekEngine = SoulseekEngine(
            settings, userLists, shares, downloadStore, transferPersistence, countryResolver, loadPicture, logger, tuning,
        )
    }
}
