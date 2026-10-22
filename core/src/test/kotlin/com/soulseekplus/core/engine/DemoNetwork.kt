package com.soulseekplus.core.engine

import com.soulseekplus.core.api.CountryResolver
import com.soulseekplus.core.api.DownloadStore
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.TransferPersistence
import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.shares.ShareFile
import com.soulseekplus.core.shares.ShareFolder
import com.soulseekplus.core.shares.ShareIndex
import com.soulseekplus.core.shares.ShareRoot
import com.soulseekplus.core.shares.ShareSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

/*
 * Emülatör uçtan uca testi için demo Soulseek ağı: sahte sunucu + paylaşım yapan demo eşler (gerçek SoulseekEngine ve
 * ShareIndex). Emülatördeki uygulama sunucuya 10.0.2.2:<port> ile bağlanır; sunucu ona eşleri 10.0.2.2 olarak, eşlere
 * emülatörü 127.0.0.1 olarak bildirir (CI, `adb forward tcp:2234 tcp:2234` ile emülatörün dinleme portunu açar).
 *
 * Çalıştırma: ./gradlew :core:demoNetwork  (DEMO_PORT, varsayılan 2242)
 * Yerel öz denetim: argüman "--selftest" — aynı makinede bir istemci motoru arar, göz atar ve dosya indirir.
 */

private class DemoPeer(
    val name: String,
    val roots: List<ShareRoot>,
    val uploadLimitKiB: Int,
    val countryCode: String,
)

/** Belirlenimci içerik: dosya baytları yoldan türetilen tohumla üretilir (bellekte tutulmaz). */
private class GeneratedSource(override val size: Long, private val seed: Long) : UploadSource {
    override fun open(offset: Long): InputStream = object : InputStream() {
        private var pos = offset
        override fun read(): Int {
            if (pos >= size) return -1
            pos++
            return ((pos * 31 + seed) and 0xFF).toInt()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            for (i in 0 until n) b[off + i] = (((pos + i) * 31 + seed) and 0xFF).toByte()
            pos += n
            return n
        }

        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), size - pos).toInt()
    }
}

private object NoDownloads : DownloadStore {
    override fun isReady() = false
    override suspend fun partialSize(transfer: Transfer) = 0L
    override suspend fun openPartial(transfer: Transfer, offset: Long): OutputStream = throw IOException("demo peer does not download")
    override suspend fun complete(transfer: Transfer): String = throw IOException("demo peer does not download")
    override suspend fun discard(transfer: Transfer) = Unit
}

private class NoPersistence : TransferPersistence {
    override suspend fun loadAll(): List<Transfer> = emptyList()
    override suspend fun upsert(transfers: List<Transfer>) = Unit
    override suspend fun delete(ids: Collection<String>) = Unit
}

private object StdoutLogger : Logger {
    private val verbose = System.getenv("DEMO_DEBUG") != null
    override fun debug(tag: String, message: String) { if (verbose) println("D $tag: $message") }
    override fun info(tag: String, message: String) { if (verbose) println("I $tag: $message") }
    override fun warn(tag: String, message: String, error: Throwable?) = println("W $tag: $message ${error ?: ""}")
    override fun error(tag: String, message: String, error: Throwable?) = println("E $tag: $message ${error ?: ""}")
}

// ------------------------------------------------------------------------------------------------ kitaplık üretimi

private val TRACK_WORDS = listOf(
    "Silent", "Electric", "River", "Midnight", "Glass", "Echoes", "Static", "Northern", "Lights", "Dust", "Signal",
    "Hollow", "Crown", "Winter", "Machine", "Garden", "Fever", "Ashes", "Orbit", "Paper", "Velvet", "Thunder",
    "Mirror", "Desert", "Harbor", "Shadow", "Golden", "Tide", "Iron", "Dream",
)

private data class AlbumSpec(val artist: String, val year: Int, val title: String, val tracks: Int)

private fun trackTitle(rnd: Random): String {
    val n = 1 + rnd.nextInt(3)
    return (1..n).joinToString(" ") { TRACK_WORDS[rnd.nextInt(TRACK_WORDS.size)] }
}

/** Bir albüm klasörü: biçime göre gerçekçi boyut ve öznitelikler. */
private fun album(spec: AlbumSpec, format: String, rnd: Random, smallFiles: Boolean): ShareFolder {
    val folder = "${spec.artist}\\${spec.year} - ${spec.title}" + when (format) {
        "flac24" -> " [24-96 FLAC]"
        "flac" -> " [FLAC]"
        "v0" -> " [MP3 V0]"
        else -> " [MP3 320]"
    }
    val files = (1..spec.tracks).map { i ->
        val seconds = 150 + rnd.nextInt(330)
        val (ext, attrs, bytesPerSecond) = when (format) {
            "flac24" -> Triple("flac", FileAttributes(bitrate = 2300 + rnd.nextInt(800), durationSeconds = seconds, sampleRate = 96000, bitDepth = 24), 330_000L)
            "flac" -> Triple("flac", FileAttributes(bitrate = 800 + rnd.nextInt(300), durationSeconds = seconds, sampleRate = 44100, bitDepth = 16), 115_000L)
            "v0" -> Triple("mp3", FileAttributes(bitrate = 230 + rnd.nextInt(40), durationSeconds = seconds, vbr = true), 31_000L)
            else -> Triple("mp3", FileAttributes(bitrate = 320, durationSeconds = seconds, vbr = false), 40_000L)
        }
        val size = if (smallFiles) 600_000L + rnd.nextInt(900_000) else seconds * bytesPerSecond
        ShareFile("%02d - %s.%s".format(i, trackTitle(rnd), ext), size, attrs)
    } + ShareFile("cover.jpg", 180_000L + rnd.nextInt(400_000))
    return ShareFolder(folder, files)
}

private fun library(alias: String, albums: List<Pair<AlbumSpec, String>>, seed: Int, smallFiles: Boolean = false): ShareRoot {
    val rnd = Random(seed)
    return ShareRoot(id = alias, alias = alias, folders = albums.map { (spec, format) -> album(spec, format, rnd, smallFiles) })
}

private fun demoPeers(): List<DemoPeer> = listOf(
    DemoPeer(
        "vinyl_archive",
        listOf(
            library(
                "Music",
                listOf(
                    AlbumSpec("Megadeth", 1990, "Rust in Peace", 9) to "flac24",
                    AlbumSpec("Megadeth", 1992, "Countdown to Extinction", 11) to "flac",
                    AlbumSpec("Metallica", 1986, "Master of Puppets", 8) to "flac24",
                    AlbumSpec("Miles Davis", 1959, "Kind of Blue", 5) to "flac24",
                    AlbumSpec("Pink Floyd", 1973, "The Dark Side of the Moon", 10) to "flac",
                ),
                seed = 1,
            ),
        ),
        uploadLimitKiB = 0,
        countryCode = "DE",
    ),
    DemoPeer(
        "metal_cave",
        listOf(
            library(
                "shared",
                listOf(
                    AlbumSpec("Megadeth", 1986, "Peace Sells... but Who's Buying", 8) to "320",
                    AlbumSpec("Megadeth", 1994, "Youthanasia", 12) to "v0",
                    AlbumSpec("Lamb of God", 2004, "Ashes of the Wake", 11) to "320",
                    AlbumSpec("Slayer", 1986, "Reign in Blood", 10) to "320",
                ),
                seed = 2,
            ),
        ),
        uploadLimitKiB = 0,
        countryCode = "BR",
    ),
    DemoPeer(
        "jazz_corner",
        listOf(
            library(
                "Jazz",
                listOf(
                    AlbumSpec("Miles Davis", 1970, "Bitches Brew", 4) to "flac",
                    AlbumSpec("John Coltrane", 1965, "A Love Supreme", 4) to "flac24",
                    AlbumSpec("Bill Evans", 1961, "Waltz for Debby", 6) to "v0",
                ),
                seed = 3,
            ),
        ),
        uploadLimitKiB = 0,
        countryCode = "FR",
    ),
    // Küçük dosyalar ve yavaş yükleme: emülatörde indirme sürerken ilerleme çubuğu görünsün.
    DemoPeer(
        "slow_lofi",
        listOf(
            library(
                "lofi",
                listOf(
                    AlbumSpec("Megadeth", 1988, "So Far, So Good... So What! (demo rips)", 8) to "320",
                    AlbumSpec("Boards of Canada", 1998, "Music Has the Right to Children", 12) to "v0",
                ),
                seed = 4,
                smallFiles = true,
            ),
        ),
        uploadLimitKiB = 96,
        countryCode = "TR",
    ),
)

// ------------------------------------------------------------------------------------------------ ağ

class DemoNetwork(port: Int, private val bindHost: String = "0.0.0.0") : AutoCloseable {
    val server = FakeSlskServer(bindHost, port)
    private val peerNames = mutableSetOf<String>()
    val engines = mutableListOf<SoulseekEngine>()
    private val greeted = mutableSetOf<String>()

    @Volatile
    private var running = true

    init {
        server.autoRegister = true
        // Emülatördeki istemci ana makinedeki eşlere 10.0.2.2 ile ulaşır; eşler emülatöre 127.0.0.1 (adb forward) ile.
        server.advertisedIp = { requester, _ ->
            if (requester != null && requester !in peerNames && bindHost != "127.0.0.1") "10.0.2.2" else "127.0.0.1"
        }
        server.roomList["Soulseek+ Demo"] = 4
        server.roomList["metal"] = 312
        server.roomList["jazz lounge"] = 88
        server.roomList["electronic"] = 154
        for (peer in demoPeers()) {
            peerNames += peer.name
            val index = ShareIndex(StdoutLogger)
            val opener = com.soulseekplus.core.shares.UploadOpener { path, _ ->
                val file = peer.roots.flatMap { r -> r.folders.flatMap { f -> f.files.map { "${r.alias}\\${f.path}\\${it.name}" to it } } }
                    .firstOrNull { it.first == path }?.second ?: return@UploadOpener null
                GeneratedSource(file.size, path.hashCode().toLong())
            }
            index.update(ShareSnapshot(peer.roots, opener), UserLists())
            val settings = MutableStateFlow(
                ClientSettings(
                    username = peer.name, password = "demo", serverHost = "127.0.0.1", serverPort = server.port,
                    autoConnect = true, listenPort = 0, portMappingEnabled = false, uploadSlots = 3,
                    uploadSpeedLimitKiB = peer.uploadLimitKiB,
                    userDescription = "Demo peer ${peer.name} — shares ${peer.roots.sumOf { r -> r.folders.size }} albums.",
                    autoJoinRooms = listOf("Soulseek+ Demo"),
                ),
            )
            val engine = SoulseekEngine(
                settings, MutableStateFlow(UserLists()), index, NoDownloads, NoPersistence(),
                CountryResolver { null },
                { null }, StdoutLogger,
            )
            engine.start()
            engines += engine
        }
        Thread({ greeterLoop() }, "demo-greeter").apply { isDaemon = true; start() }
    }

    /** Yeni giren (demo olmayan) kullanıcıya özel mesaj ve odada selam. */
    private fun greeterLoop() {
        var roomGreeted = false
        while (running) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                return
            }
            val online = server.onlineUsers()
            val newcomers = online.filter { it !in peerNames && it !in greeted }
            for (user in newcomers) {
                greeted += user
                engines.firstOrNull()?.sendPrivateMessage(user, "Hi $user! I'm a demo peer. Search for \"megadeth\" or browse my shares.")
                println("DEMO: greeted $user")
            }
            if (!roomGreeted && engines.all { it.connection.value is ConnectionState.LoggedIn }) {
                roomGreeted = true
                engines.getOrNull(1)?.sayInRoom("Soulseek+ Demo", "Anyone got the 24-bit Rust in Peace rip?")
                engines.getOrNull(0)?.sayInRoom("Soulseek+ Demo", "Yes, it's in my Music share. Queue it up.")
            }
        }
    }

    override fun close() {
        running = false
        engines.forEach { it.shutdown() }
        server.close()
    }
}

fun main(args: Array<String>) {
    val port = System.getenv("DEMO_PORT")?.toIntOrNull() ?: 2242
    if ("--selftest" in args) {
        selfTest()
        return
    }
    val network = DemoNetwork(port)
    Runtime.getRuntime().addShutdownHook(Thread { network.close() })
    println("DEMO: server listening on ${network.server.port}")
    while (true) Thread.sleep(60_000)
}

/** Yerel öz denetim: demo ağına aynı makineden bağlanan bir istemci arar, göz atar, indirir. */
private fun selfTest() {
    val network = DemoNetwork(0, bindHost = "127.0.0.1")
    val dir = kotlin.io.path.createTempDirectory("demo-dl").toFile()
    val store = object : DownloadStore {
        fun part(t: Transfer) = java.io.File(dir, t.fileName + ".part")
        override fun isReady() = true
        override suspend fun partialSize(transfer: Transfer) = part(transfer).let { if (it.exists()) it.length() else 0L }
        override suspend fun openPartial(transfer: Transfer, offset: Long): OutputStream = java.io.FileOutputStream(part(transfer), offset > 0)
        override suspend fun complete(transfer: Transfer): String {
            val target = java.io.File(dir, transfer.fileName)
            part(transfer).renameTo(target)
            return target.path
        }
        override suspend fun discard(transfer: Transfer) { part(transfer).delete() }
    }
    val client = SoulseekEngine(
        MutableStateFlow(
            ClientSettings(
                username = "selftest", password = "x", serverHost = "127.0.0.1", serverPort = network.server.port,
                autoConnect = true, listenPort = 0, portMappingEnabled = false,
            ),
        ),
        MutableStateFlow(UserLists()), ShareIndex(StdoutLogger), store, NoPersistence(), CountryResolver { null }, { null }, StdoutLogger,
    )
    val responses = java.util.concurrent.CopyOnWriteArrayList<com.soulseekplus.core.model.SearchResponse>()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    scope.launch { client.searchResponses.collect { responses.add(it) } }
    client.start()
    waitFor("login") { client.connection.value is ConnectionState.LoggedIn }
    waitFor("peers") { network.engines.all { it.connection.value is ConnectionState.LoggedIn } }
    client.search("megadeth", SearchScope.Network)
    waitFor("search responses") { responses.map { it.username }.toSet().size >= 3 }
    println("SELFTEST: ${responses.size} responses, ${responses.sumOf { it.files.size }} files from ${responses.map { it.username }.toSet()}")
    val browse = kotlinx.coroutines.runBlocking { client.browseUser("jazz_corner") }
    println("SELFTEST: browse jazz_corner -> ${browse.folders.size} folders, ${browse.fileCount} files")
    val small = responses.first { it.username == "slow_lofi" }.files.first { it.extension == "mp3" }
    client.download("slow_lofi", SharedFile(small.path, small.size, attributes = small.attributes))
    waitFor("download", 60_000) { client.transfers.value.any { it.state == TransferState.FINISHED } }
    val done = client.transfers.value.first { it.state == TransferState.FINISHED }
    val bytes = java.io.File(done.localUri!!).length()
    println("SELFTEST: downloaded ${done.fileName} $bytes bytes (expected ${small.size})")
    check(bytes == small.size) { "size mismatch" }
    client.shutdown()
    network.close()
    println("SELFTEST: OK")
    kotlin.system.exitProcess(0)
}

private fun waitFor(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timeout waiting for $what" }
        Thread.sleep(100)
    }
}
