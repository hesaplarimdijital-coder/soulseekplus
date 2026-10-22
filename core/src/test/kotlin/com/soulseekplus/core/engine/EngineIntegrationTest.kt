package com.soulseekplus.core.engine

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.api.CountryResolver
import com.soulseekplus.core.api.DownloadStore
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.ShareProvider
import com.soulseekplus.core.api.ShareSearchResult
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.api.TransferPersistence
import com.soulseekplus.core.api.UploadResolution
import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.ShareStats
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.core.net.NetworkTimeouts
import com.soulseekplus.core.protocol.ExcludedSearchPhrasesNotification
import com.soulseekplus.core.protocol.ServerCode
import com.soulseekplus.core.transfers.PathSanitizer
import com.soulseekplus.core.transfers.TransferTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Uçtan uca: sahte Soulseek sunucusu (düz soketler) ve gerçek soketler üzerinden konuşan iki gerçek [SoulseekEngine].
 * Senaryolar: giriş, özel mesaj, arama, gözatma, indirme (bayt doğrulama), kısmi dosyadan devam, klasör indirme,
 * dolaylı bağlantı, yeniden bağlanma ve canlı ayar değişiklikleri.
 */
class EngineIntegrationTest {

    @get:Rule
    val timeoutRule: Timeout = Timeout(60, TimeUnit.SECONDS)

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val durations: TestWatcher = object : TestWatcher() {
        private var started = 0L
        override fun starting(description: Description) {
            started = System.nanoTime()
        }

        override fun finished(description: Description) {
            val ms = (System.nanoTime() - started) / 1_000_000
            if (System.getProperty("slsk.testTimes") != null) println("TIME ${description.methodName} ${ms}ms")
        }
    }

    // ------------------------------------------------------------------------------------------------- test altyapısı

    private class RecordingLogger : Logger {
        val warnings = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        private val verbose = System.getProperty("slsk.debug") != null
        override fun debug(tag: String, message: String) {
            if (verbose) println("D $tag: $message")
        }

        override fun info(tag: String, message: String) {
            if (verbose) println("I $tag: $message")
        }

        override fun warn(tag: String, message: String, error: Throwable?) {
            warnings.add("$tag: $message ${error ?: ""}")
            if (verbose) println("W $tag: $message ${error ?: ""}")
        }

        override fun error(tag: String, message: String, error: Throwable?) {
            errors.add("$tag: $message ${error ?: ""}")
            error?.printStackTrace()
        }
    }

    /** Bellekte tutulan paylaşım: sanal yol → dosya baytları. */
    private class MemoryShares(private val files: Map<String, ByteArray>) : ShareProvider {
        override val stats: MutableStateFlow<ShareStats> = MutableStateFlow(computeStats())
        val openedOffsets = CopyOnWriteArrayList<Long>()
        val searches = CopyOnWriteArrayList<String>()

        private fun folderOf(path: String) = path.substringBeforeLast('\\')

        private fun computeStats() = ShareStats(files.keys.map { folderOf(it) }.toSet().size, files.size)

        private fun sharedFile(path: String) = SharedFile(path, files.getValue(path).size.toLong())

        override fun search(query: String, requester: String, maxResults: Int): ShareSearchResult {
            searches.add("$requester:$query")
            val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
            val hits = files.keys.filter { p -> terms.isNotEmpty() && terms.all { p.lowercase().contains(it) } }.take(maxResults)
            return ShareSearchResult(hits.map { sharedFile(it) }, emptyList())
        }

        private fun folders(filter: (String) -> Boolean): List<SharedFolder> =
            files.keys.filter { filter(folderOf(it)) }.groupBy { folderOf(it) }
                .map { (folder, paths) -> SharedFolder(folder, paths.map { sharedFile(it) }) }

        override fun browse(requester: String): BrowseResult = BrowseResult("me", folders { true })

        override fun folderContents(requester: String, folderPath: String): List<SharedFolder> =
            folders { it == folderPath || it.startsWith("$folderPath\\") }

        override fun resolveUpload(requester: String, virtualPath: String): UploadResolution {
            val data = files[virtualPath] ?: return UploadResolution.NotShared
            return UploadResolution.Allowed(
                sharedFile(virtualPath),
                object : UploadSource {
                    override val size: Long = data.size.toLong()
                    override fun open(offset: Long): InputStream {
                        openedOffsets.add(offset)
                        return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
                    }
                },
            )
        }
    }

    /** Dosya sistemi tabanlı indirme deposu: `<kök>/<alt klasör>/<ad>.part`, bitince yeniden adlandırılır. */
    private class DirDownloadStore(val root: File) : DownloadStore {
        private fun dirOf(t: Transfer): File = t.destinationSubfolder?.let { File(root, it) } ?: root
        private fun partOf(t: Transfer) = File(dirOf(t), PathSanitizer.segment(t.fileName) + ".part")

        override fun isReady(): Boolean = root.isDirectory || root.mkdirs()

        override suspend fun partialSize(transfer: Transfer): Long = partOf(transfer).let { if (it.isFile) it.length() else 0L }

        override suspend fun openPartial(transfer: Transfer, offset: Long): OutputStream {
            val file = partOf(transfer)
            file.parentFile?.mkdirs()
            return FileOutputStream(file, offset > 0 && file.length() == offset)
        }

        override suspend fun complete(transfer: Transfer): String {
            val target = File(dirOf(transfer), PathSanitizer.segment(transfer.fileName))
            check(partOf(transfer).renameTo(target)) { "rename failed" }
            return target.absolutePath
        }

        override suspend fun discard(transfer: Transfer) {
            partOf(transfer).delete()
        }
    }

    private class MemoryPersistence : TransferPersistence {
        val rows = ConcurrentHashMap<String, Transfer>()
        override suspend fun loadAll(): List<Transfer> = rows.values.toList()
        override suspend fun upsert(transfers: List<Transfer>) {
            for (t in transfers) rows[t.id] = t
        }

        override suspend fun delete(ids: Collection<String>) {
            for (id in ids) rows.remove(id)
        }
    }

    private val picture = ByteArray(2_000) { (it * 7).toByte() }

    private inner class Node(
        val name: String,
        password: String,
        files: Map<String, ByteArray>,
        autoConnect: Boolean = false,
        extra: (ClientSettings) -> ClientSettings = { it },
    ) {
        val settings = MutableStateFlow(
            extra(
                ClientSettings(
                    username = name, password = password, serverHost = "127.0.0.1", serverPort = server.port,
                    autoConnect = autoConnect, listenPort = 0, portMappingEnabled = false, uploadSlots = 2,
                    userDescription = "Hello from $name", userPictureUri = "pic://$name",
                ),
            ),
        )
        val userLists = MutableStateFlow(UserLists())
        val shares = MemoryShares(files)
        val store = DirDownloadStore(File(tmp.root, "downloads-$name"))
        val persistence = MemoryPersistence()
        val logger = RecordingLogger()
        val events = CopyOnWriteArrayList<ClientEvent>()
        val responses = CopyOnWriteArrayList<SearchResponse>()
        val states = CopyOnWriteArrayList<ConnectionState>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val engine: SoulseekEngine = SoulseekEngine.withTuning(
            settings, userLists, shares, store, persistence,
            CountryResolver { ip -> if (ip == "127.0.0.1") "XX" else null },
            { uri -> if (uri == "pic://$name") picture else null },
            logger, TUNING,
        )

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events.add(it) } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.searchResponses.collect { responses.add(it) } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.connection.collect { states.add(it) } }
            engine.start()
            nodes.add(this)
        }

        val loggedIn: Boolean get() = engine.connection.value is ConnectionState.LoggedIn

        fun connect(): Node {
            engine.connect()
            await("$name logged in") { loggedIn }
            return this
        }

        inline fun <reified T : ClientEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

        fun close() {
            runBlocking { engine.shutdownAndWait(10_000) }
            scope.cancel()
        }
    }

    private lateinit var server: FakeSlskServer
    private val nodes = CopyOnWriteArrayList<Node>()

    @Before
    fun setUp() {
        server = FakeSlskServer()
        server.addAccount("alice", "pw1")
        server.addAccount("bob", "pw2")
        server.addAccount("carol", "pw3")
    }

    @After
    fun tearDown() {
        nodes.forEach { it.close() }
        server.close()
        assertEquals("sunucu hataları", emptyList<String>(), server.errors.toList())
        for (n in nodes) {
            assertEquals("${n.name} günlük hataları", emptyList<String>(), n.logger.errors.toList())
        }
    }

    private fun await(message: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Zaman aşımı: $message")
    }

    private fun <T> blocking(timeoutMillis: Long = 20_000, block: suspend () -> T): T =
        runBlocking { withTimeout(timeoutMillis) { block() } }

    private val song1 = ByteArray(300_000).also { Random(1).nextBytes(it) }
    private val song2 = ByteArray(120_000).also { Random(2).nextBytes(it) }
    private val deep = ByteArray(50_000).also { Random(3).nextBytes(it) }

    private val bobFiles: Map<String, ByteArray> = linkedMapOf(
        "Music\\Album\\01 Song One.flac" to song1,
        "Music\\Album\\02 Song Two.mp3" to song2,
        "Music\\Album\\Disc 2\\03 Deep Track.flac" to deep,
        "Other\\notes.txt" to "hi".toByteArray(),
    )

    private fun alice(autoConnect: Boolean = false, extra: (ClientSettings) -> ClientSettings = { it }) =
        Node("alice", "pw1", emptyMap(), autoConnect, extra)

    private fun bob(extra: (ClientSettings) -> ClientSettings = { it }) = Node("bob", "pw2", bobFiles, false, extra)

    private fun finished(node: Node, direction: TransferDirection): List<Transfer> =
        node.engine.transfers.value.filter { it.direction == direction && it.state == TransferState.FINISHED }

    // ------------------------------------------------------------------------------------------------- oturum

    @Test
    fun autoConnectLogsInAndAnnouncesPortSharesAndPrivilegeCheck() {
        val b = bob { it.copy(autoConnect = true) }
        await("bob logged in") { b.loggedIn }
        val state = b.engine.connection.value as ConnectionState.LoggedIn
        assertEquals("bob", state.username)
        assertEquals("Welcome to the fake server", state.greeting)
        assertEquals("127.0.0.1", state.publicIp)
        await("bob announced") {
            server.countOf("bob", ServerCode.SET_WAIT_PORT) >= 1 && server.countOf("bob", ServerCode.SHARED_FOLDERS_FILES) >= 1 &&
                server.countOf("bob", ServerCode.CHECK_PRIVILEGES) >= 1 && server.countOf("bob", ServerCode.HAVE_NO_PARENT) >= 1
        }
        val listenPort = b.engine.network.value.listenPort
        assertTrue(listenPort > 0)
        assertTrue(b.engine.network.value.listening)
        assertEquals(listenPort.toLong(), server.messages("bob", ServerCode.SET_WAIT_PORT).last().reader().uint32())
        val shares = server.messages("bob", ServerCode.SHARED_FOLDERS_FILES).last().reader()
        assertEquals(3L, shares.uint32())
        assertEquals(4L, shares.uint32())
        assertEquals(1, server.messages("bob", ServerCode.HAVE_NO_PARENT).last().payload[0].toInt())
        assertEquals("bob", server.messages("bob", ServerCode.WATCH_USER).first().reader().string())
        assertEquals(b.eventsOf<ClientEvent.LoggedIn>().map { it.username }, listOf("bob"))
        assertEquals(720, b.engine.wishlistIntervalSeconds.value)
        assertEquals(emptyList<String>(), b.logger.warnings.filter { it.contains("Port") })
    }

    @Test
    fun changingTheSharesReportsNewCountsToTheServer() {
        val b = bob().connect()
        await("first report") { server.countOf("bob", ServerCode.SHARED_FOLDERS_FILES) >= 1 }
        b.shares.stats.value = ShareStats(9, 99)
        await("second report") { server.countOf("bob", ServerCode.SHARED_FOLDERS_FILES) >= 2 }
        val r = server.messages("bob", ServerCode.SHARED_FOLDERS_FILES).last().reader()
        assertEquals(9L, r.uint32())
        assertEquals(99L, r.uint32())
    }

    @Test
    fun serverDropTriggersBackoffReconnectButManualDisconnectDoesNot() {
        val a = alice().connect()
        assertEquals(1, server.loginSuccesses["alice"]!!.get())
        server.kick("alice")
        await("reconnected") { server.loginSuccesses["alice"]!!.get() == 2 && a.loggedIn }
        await("states observed") { a.states.any { it is ConnectionState.Reconnecting } && a.eventsOf<ClientEvent.LoggedIn>().size == 2 }
        val reconnecting = a.states.filterIsInstance<ConnectionState.Reconnecting>()
        assertTrue("Reconnecting durumu görülmeli", reconnecting.isNotEmpty())
        assertEquals(1, reconnecting.first().attempt)
        assertTrue(reconnecting.first().retryAtMillis > System.currentTimeMillis() - 5_000)

        a.engine.disconnect()
        await("disconnected") { (a.engine.connection.value as? ConnectionState.Disconnected)?.reason != null }
        assertEquals("Disconnected by user", (a.engine.connection.value as ConnectionState.Disconnected).reason)
        Thread.sleep(900)
        assertEquals(2, server.loginSuccesses["alice"]!!.get())
        assertFalse(server.isOnline("alice"))
        // Elle yeniden bağlanabilir.
        a.connect()
        assertEquals(3, server.loginSuccesses["alice"]!!.get())
    }

    @Test
    fun rejectedLoginAndReloggedDoNotReconnect() {
        val wrong = Node("alice", "not-the-password", emptyMap())
        wrong.engine.connect()
        await("rejected") { (wrong.engine.connection.value as? ConnectionState.Disconnected)?.reason != null }
        assertEquals("Invalid password", (wrong.engine.connection.value as ConnectionState.Disconnected).reason)
        await("error notice") { wrong.eventsOf<ClientEvent.Notice>().any { it.isError && it.text == "Invalid password" } }
        Thread.sleep(700)
        assertEquals(1, server.loginAttempts["alice"]!!.get())

        val first = alice().connect()
        val second = alice().connect()
        await("first relogged") { (first.engine.connection.value as? ConnectionState.Disconnected)?.reason != null }
        await("relogged event") { first.eventsOf<ClientEvent.Relogged>().isNotEmpty() }
        assertEquals("Logged in from another location", (first.engine.connection.value as ConnectionState.Disconnected).reason)
        Thread.sleep(700)
        assertTrue(second.loggedIn)
        assertEquals(3, server.loginAttempts["alice"]!!.get())
    }

    @Test
    fun liveSettingChangesRebindPortAndReconnectWithNewCredentials() {
        val a = alice().connect()
        await("announced") { server.countOf("alice", ServerCode.SET_WAIT_PORT) >= 1 }
        val newPort = ServerSocket(0).use { it.localPort }
        a.settings.value = a.settings.value.copy(listenPort = newPort)
        await("new wait port") {
            server.messages("alice", ServerCode.SET_WAIT_PORT).any { it.reader().uint32() == newPort.toLong() }
        }
        assertEquals(newPort, a.engine.network.value.listenPort)

        server.addAccount("alice", "pw1-new")
        a.settings.value = a.settings.value.copy(password = "pw1-new")
        await("reconnected with new password") { server.loginSuccesses["alice"]!!.get() == 2 && a.loggedIn }

        a.settings.value = a.settings.value.copy(autoJoinRooms = listOf("lobby"))
        await("joined lobby") { a.eventsOf<ClientEvent.RoomJoined>().any { it.room == "lobby" } }
    }

    // ------------------------------------------------------------------------------------------------- mesajlaşma ve kullanıcılar

    @Test
    fun privateMessagesAreAckedAndIgnoredUsersAreDropped() {
        val a = alice().connect()
        val b = bob().connect()
        a.engine.sendPrivateMessage("bob", "hello bob")
        await("pm delivered") { b.eventsOf<ClientEvent.PrivateMessage>().any { it.text == "hello bob" } }
        val pm = b.eventsOf<ClientEvent.PrivateMessage>().first { it.text == "hello bob" }
        assertEquals("alice", pm.username)
        assertFalse(pm.isAdmin)
        assertFalse(pm.isOfflineMessage)
        assertTrue(pm.timestampMillis > 0)
        await("acked") { server.ackedMessageIds.contains(pm.id) }
        assertEquals("hello bob", a.eventsOf<ClientEvent.PrivateMessageSent>().single().text)

        b.userLists.value = UserLists(ignored = setOf("alice"))
        val acks = server.ackedMessageIds.size
        a.engine.sendPrivateMessage("bob", "you cannot see this")
        await("ignored message still acked") { server.ackedMessageIds.size == acks + 1 }
        Thread.sleep(300)
        assertTrue(b.eventsOf<ClientEvent.PrivateMessage>().none { it.text == "you cannot see this" })

        b.userLists.value = UserLists()
        a.engine.sendPrivateMessage("bob", "visible again")
        await("visible again") { b.eventsOf<ClientEvent.PrivateMessage>().any { it.text == "visible again" } }
    }

    @Test
    fun watchedUsersReportStatusStatsAndCountry() {
        val a = alice().connect()
        a.engine.watchUser("bob")
        await("bob offline entry") { a.engine.users.value["bob"]?.status == UserStatus.OFFLINE }
        val b = bob().connect()
        await("bob online") { a.engine.users.value["bob"]?.status == UserStatus.ONLINE }
        val presence = a.engine.users.value.getValue("bob")
        assertEquals("DE", presence.countryCode)
        assertEquals(100_000, presence.stats?.averageSpeed)
        await("status event") { a.eventsOf<ClientEvent.UserStatusChanged>().any { it.username == "bob" && it.status == UserStatus.ONLINE } }
        b.engine.disconnect()
        await("bob offline again") { a.engine.users.value["bob"]?.status == UserStatus.OFFLINE }
        a.engine.unwatchUser("bob")
        await("bob forgotten") { !a.engine.users.value.containsKey("bob") }
        await("unwatch sent") { server.countOf("alice", ServerCode.UNWATCH_USER) == 1 }
    }

    @Test
    fun roomsDeliverMessagesAndMembership() {
        val a = alice().connect()
        val b = bob().connect()
        a.engine.joinRoom("lobby")
        await("alice joined") { a.eventsOf<ClientEvent.RoomJoined>().isNotEmpty() }
        b.engine.joinRoom("lobby")
        await("bob joined") {
            b.eventsOf<ClientEvent.RoomJoined>().isNotEmpty() && a.eventsOf<ClientEvent.UserJoinedRoom>().any { it.user.username == "bob" }
        }
        a.engine.sayInRoom("lobby", "hi room")
        await("room message") { b.eventsOf<ClientEvent.RoomMessage>().any { it.text == "hi room" && it.username == "alice" } }
        assertEquals("DE", a.engine.users.value["bob"]?.countryCode)
        a.engine.leaveRoom("lobby")
        await("left") { a.eventsOf<ClientEvent.RoomLeft>().any { it.room == "lobby" } }
    }

    @Test
    fun requestsToOfflineUsersAndWhileDisconnectedFailWithShortMessages() {
        val a = alice()
        try {
            blocking { a.engine.browseUser("bob") }
            fail()
        } catch (e: SoulseekException) {
            assertEquals("Not connected to server", e.message)
        }
        a.connect()
        try {
            blocking { a.engine.browseUser("nobody") }
            fail()
        } catch (e: SoulseekException) {
            assertEquals("User nobody is offline", e.message)
        }
        try {
            blocking { a.engine.getUserInfo("  ") }
            fail()
        } catch (e: SoulseekException) {
            assertEquals("Invalid username", e.message)
        }
        assertNull(blocking { a.engine.getUserAddress("nobody") })
    }

    // ------------------------------------------------------------------------------------------------- arama, gözatma, bilgi

    @Test
    fun searchResultsComeFromTheSharingPeer() {
        val a = alice().connect()
        val b = bob().connect()
        val token = a.engine.search("song flac", SearchScope.Network)
        await("response") { a.responses.any { it.token == token } }
        val r = a.responses.first { it.token == token }
        assertEquals("bob", r.username)
        assertEquals(listOf("Music\\Album\\01 Song One.flac"), r.files.map { it.path })
        assertEquals(song1.size.toLong(), r.files[0].size)
        assertTrue(r.freeSlots)
        assertEquals(100_000, r.averageSpeed)
        assertEquals(0L, r.queueLength)
        assertEquals("XX", r.countryCode)
        assertTrue(b.shares.searches.contains("alice:song flac"))

        // Kapatılan aramaya gelen yanıtlar atılır.
        val closed = a.engine.search("album", SearchScope.Network)
        a.engine.cancelSearch(closed)
        val open = a.engine.search("album", SearchScope.Network)
        await("open response") { a.responses.any { it.token == open } }
        assertEquals(3, a.responses.first { it.token == open }.files.size)
        Thread.sleep(200)
        assertTrue(a.responses.none { it.token == closed })

        // Tek kullanıcıya arama.
        val direct = a.engine.search("notes", SearchScope.User("bob"))
        await("direct response") { a.responses.any { it.token == direct } }
        assertEquals(listOf("Other\\notes.txt"), a.responses.first { it.token == direct }.files.map { it.path })
    }

    @Test
    fun bannedRequestersAndDisabledResponsesProduceNoResults() {
        val a = alice().connect()
        val b = bob().connect()
        b.userLists.value = UserLists(banned = setOf("alice"))
        val banned = a.engine.search("album", SearchScope.Network)
        a.engine.sendPrivateMessage("bob", "marker-1")
        await("marker-1") { b.eventsOf<ClientEvent.PrivateMessage>().any { it.text == "marker-1" } }
        Thread.sleep(300)
        assertTrue(a.responses.none { it.token == banned })

        b.userLists.value = UserLists()
        b.settings.value = b.settings.value.copy(respondToSearches = false)
        val disabled = a.engine.search("album", SearchScope.Network)
        a.engine.sendPrivateMessage("bob", "marker-2")
        await("marker-2") { b.eventsOf<ClientEvent.PrivateMessage>().any { it.text == "marker-2" } }
        Thread.sleep(300)
        assertTrue(a.responses.none { it.token == disabled })

        b.settings.value = b.settings.value.copy(respondToSearches = true)
        // Yoksayılan yanıtlayıcıdan gelen sonuçlar alıcıda atılır.
        a.userLists.value = UserLists(ignored = setOf("bob"))
        val ignored = a.engine.search("album", SearchScope.Network)
        a.engine.sendPrivateMessage("bob", "marker-3")
        await("marker-3") { b.eventsOf<ClientEvent.PrivateMessage>().any { it.text == "marker-3" } }
        await("bob answered the ignored search") { b.shares.searches.any { it == "alice:album" } }
        Thread.sleep(300)
        assertTrue(a.responses.none { it.token == ignored })
        a.userLists.value = UserLists()
        val finally = a.engine.search("album", SearchScope.Network)
        await("finally") { a.responses.any { it.token == finally } }
    }

    @Test
    fun browseFolderContentsUserInfoAndAddress() {
        val a = alice().connect()
        val b = bob().connect()
        val browse = blocking { a.engine.browseUser("bob") }
        assertEquals("bob", browse.username)
        assertEquals(4, browse.fileCount)
        assertEquals(
            setOf("Music\\Album", "Music\\Album\\Disc 2", "Other"),
            browse.folders.map { it.path }.toSet(),
        )
        assertEquals(
            listOf("Music\\Album\\01 Song One.flac", "Music\\Album\\02 Song Two.mp3"),
            browse.folders.first { it.path == "Music\\Album" }.files.map { it.path },
        )
        await("browse event on bob") { b.eventsOf<ClientEvent.SharesBrowsedBy>().any { it.username == "alice" } }

        val folders = blocking { a.engine.getFolderContents("bob", "Music\\Album") }
        assertEquals(setOf("Music\\Album", "Music\\Album\\Disc 2"), folders.map { it.path }.toSet())
        assertEquals(3, folders.sumOf { it.files.size })

        val info = blocking { a.engine.getUserInfo("bob") }
        assertEquals("Hello from bob", info.description)
        assertArrayEquals(picture, info.picture)
        assertEquals(2, info.uploadSlots)
        assertEquals(0, info.queueSize)
        assertTrue(info.slotsAvailable)
        assertEquals(1, info.uploadPermission)

        val address = blocking { a.engine.getUserAddress("bob") }
        assertEquals("127.0.0.1", address!!.first)
        assertEquals(b.engine.network.value.listenPort, address.second)
    }

    // ------------------------------------------------------------------------------------------------- dağıtık ağ

    @Test
    fun distributedNetworkAdoptsAParentAndAnswersForwardedSearches() {
        server.parentCandidate = "bob"
        val b = bob().connect()
        // Sunucu bob'a gömülü bir arama yollayınca bob dal kökü olur ve çocuk kabul eder.
        server.sendEmbeddedSearch("bob", "carol", 1, "warmup query")
        await("bob accepts children") { b.engine.distributed.value.acceptingChildren }
        assertEquals("bob", b.engine.distributed.value.branchRoot)
        assertNull(b.engine.distributed.value.parentUsername)

        val a = alice().connect()
        await("bob counts one child") { b.engine.distributed.value.childCount == 1 }
        // Aday ebeveyn, ilk iletilen aramadan sonra gerçek ebeveyn olarak benimsenir.
        server.sendEmbeddedSearch("bob", "carol", 77, "album")
        await("alice adopted bob as parent") { a.engine.distributed.value.parentUsername == "bob" }
        await("search forwarded to alice") { a.shares.searches.contains("carol:album") }
        assertTrue(b.shares.searches.contains("carol:album"))
        val state = a.engine.distributed.value
        assertEquals("bob", state.branchRoot)
        assertEquals(1, state.branchLevel)
        await("alice reported her branch") {
            server.countOf("alice", ServerCode.BRANCH_LEVEL) >= 1 && server.countOf("alice", ServerCode.BRANCH_ROOT) >= 1
        }
        assertEquals(1L, server.messages("alice", ServerCode.BRANCH_LEVEL).last().reader().uint32())
        assertEquals("bob", server.messages("alice", ServerCode.BRANCH_ROOT).last().reader().string())
        // Ebeveyn bulduktan sonra sunucuya artık ebeveyn aramadığımız bildirilir.
        assertTrue(server.messages("alice", ServerCode.HAVE_NO_PARENT).any { it.payload[0].toInt() == 0 })
    }

    // ------------------------------------------------------------------------------------------------- transferler

    private fun songFile(data: ByteArray = song1, path: String = "Music\\Album\\01 Song One.flac") = SharedFile(path, data.size.toLong())

    @Test
    fun downloadTransfersEveryByteAndReportsBothSides() {
        val a = alice().connect()
        val b = bob().connect()
        a.engine.download("bob", songFile())
        await("download finished") { finished(a, TransferDirection.DOWNLOAD).isNotEmpty() }
        val t = finished(a, TransferDirection.DOWNLOAD).single()
        assertEquals(song1.size.toLong(), t.bytesTransferred)
        assertEquals("bob", t.username)
        val saved = File(t.localUri!!)
        assertEquals("01 Song One.flac", saved.name)
        assertArrayEquals(song1, saved.readBytes())
        assertFalse(File(saved.path + ".part").exists())
        assertTrue(a.eventsOf<ClientEvent.TransferFinished>().any { it.transfer.id == t.id })

        await("upload finished on bob") { finished(b, TransferDirection.UPLOAD).isNotEmpty() }
        val up = finished(b, TransferDirection.UPLOAD).single()
        assertEquals("alice", up.username)
        assertEquals(song1.size.toLong(), up.bytesTransferred)
        await("download persisted") { a.persistence.rows.values.any { it.state == TransferState.FINISHED } }
        assertEquals(listOf(0L), b.shares.openedOffsets.toList())
        await("stats settle") { a.engine.transferStats.value.activeDownloads == 0 && b.engine.transferStats.value.activeUploads == 0 }
    }

    @Test
    fun partialFileIsResumedFromItsOffset() {
        val a = alice().connect()
        val b = bob().connect()
        val partial = File(a.store.root, "01 Song One.flac.part")
        partial.parentFile.mkdirs()
        partial.writeBytes(song1.copyOf(100_000))
        a.engine.download("bob", songFile())
        await("resumed download finished") { finished(a, TransferDirection.DOWNLOAD).isNotEmpty() }
        val saved = File(finished(a, TransferDirection.DOWNLOAD).single().localUri!!)
        assertArrayEquals(song1, saved.readBytes())
        assertEquals(listOf(100_000L), b.shares.openedOffsets.toList())
    }

    @Test
    fun downloadFolderKeepsTheRemoteFolderNameAndSubfolders() {
        val a = alice().connect()
        bob().connect()
        val count = blocking { a.engine.downloadFolder("bob", "Music\\Album") }
        assertEquals(3, count)
        await("three downloads finished") { finished(a, TransferDirection.DOWNLOAD).size == 3 }
        assertArrayEquals(song1, File(a.store.root, "Album/01 Song One.flac").readBytes())
        assertArrayEquals(song2, File(a.store.root, "Album/02 Song Two.mp3").readBytes())
        assertArrayEquals(deep, File(a.store.root, "Album/Disc 2/03 Deep Track.flac").readBytes())
        val subs = finished(a, TransferDirection.DOWNLOAD).map { it.destinationSubfolder }.toSet()
        assertEquals(setOf("Album", "Album/Disc 2"), subs)
    }

    @Test
    fun remoteFileNamesCannotEscapeTheDownloadFolder() {
        val a = alice().connect()
        val evil = linkedMapOf("Music\\..\\..\\evil.txt" to "pwned".toByteArray())
        val c = Node("carol", "pw3", evil).connect()
        a.engine.download("carol", SharedFile("Music\\..\\..\\evil.txt", 5), destinationSubfolder = "../../escape")
        await("evil download finished") { finished(a, TransferDirection.DOWNLOAD).isNotEmpty() }
        val saved = File(finished(a, TransferDirection.DOWNLOAD).single().localUri!!).canonicalFile
        assertTrue("indirme kökünün içinde kalmalı: $saved", saved.path.startsWith(a.store.root.canonicalPath))
        assertEquals("pwned", saved.readText())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertTrue(c.loggedIn)
    }

    @Test
    fun filesWithServerExcludedPhrasesAreNotSentInSearchResults() {
        val a = alice().connect()
        val b = bob().connect()
        server.push("bob", ExcludedSearchPhrasesNotification(listOf("Song Two")))
        Thread.sleep(200)
        val token = a.engine.search("album", SearchScope.Network)
        await("response") { a.responses.any { it.token == token } }
        val paths = a.responses.first { it.token == token }.files.map { it.path }
        assertEquals(2, paths.size)
        assertTrue(paths.none { it.contains("Song Two") })
        assertTrue(b.loggedIn)
    }

    @Test
    fun zeroByteFilesFinishOnBothSides() {
        val a = alice().connect()
        val c = Node("carol", "pw3", linkedMapOf("Music\\empty.txt" to ByteArray(0))).connect()
        a.engine.download("carol", SharedFile("Music\\empty.txt", 0))
        await("empty download finished") { finished(a, TransferDirection.DOWNLOAD).isNotEmpty() }
        val saved = File(finished(a, TransferDirection.DOWNLOAD).single().localUri!!)
        assertTrue(saved.isFile)
        assertEquals(0L, saved.length())
        await("empty upload finished") { finished(c, TransferDirection.UPLOAD).isNotEmpty() }
    }

    @Test
    fun indirectConnectionsWorkWhenNoDirectPortIsKnown() {
        server.hidePorts = true
        val a = alice().connect()
        val b = bob().connect()
        val browse = blocking { a.engine.browseUser("bob") }
        assertEquals(4, browse.fileCount)
        a.engine.download("bob", songFile(song2, "Music\\Album\\02 Song Two.mp3"))
        await("indirect download finished") { finished(a, TransferDirection.DOWNLOAD).isNotEmpty() }
        assertArrayEquals(song2, File(finished(a, TransferDirection.DOWNLOAD).single().localUri!!).readBytes())
        assertTrue(b.loggedIn)
    }

    @Test
    fun pausedDownloadsWaitAndResumeLater() {
        val a = alice().connect()
        bob().connect()
        a.engine.download("bob", songFile(deep, "Music\\Album\\Disc 2\\03 Deep Track.flac"))
        val id = Transfer.idOf(TransferDirection.DOWNLOAD, "bob", "Music\\Album\\Disc 2\\03 Deep Track.flac")
        a.engine.pauseTransfers(listOf(id))
        await("paused or finished") {
            val t = a.engine.transfers.value.firstOrNull { it.id == id }
            t != null && (t.state == TransferState.PAUSED || t.state == TransferState.FINISHED)
        }
        if (a.engine.transfers.value.first { it.id == id }.state == TransferState.PAUSED) {
            a.engine.resumeTransfers(listOf(id))
        }
        await("finished after resume") { a.engine.transfers.value.firstOrNull { it.id == id }?.state == TransferState.FINISHED }
        a.engine.clearFinished(TransferDirection.DOWNLOAD)
        await("cleared") { a.engine.transfers.value.none { it.id == id } }
        assertNotNull(deep)
    }
}

private val TUNING = EngineTuning(
    serverConnectTimeoutMillis = 5_000,
    loginTimeoutMillis = 10_000,
    pingIntervalMillis = 30_000,
    reconnectMinMillis = 100,
    reconnectFirstMaxMillis = 200,
    reconnectCapMillis = 800,
    probeTimeoutMillis = 1_000,
    userInfoTimeoutMillis = 10_000,
    browseTimeoutMillis = 20_000,
    folderTimeoutMillis = 10_000,
    statusIntervalMillis = 200,
    networkTimeouts = NetworkTimeouts(
        directConnectMillis = 1_000, indirectMillis = 5_000, addressLookupMillis = 5_000, initReadMillis = 5_000,
        idleMillis = 30_000, ghostMillis = 10_000, sweepIntervalMillis = 1_000,
    ),
    transferTiming = TransferTiming(tickMillis = 50, persistDebounceMillis = 100, queueRecheckMillis = 1_000),
)
