package com.soulseekplus.core.engine

import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.UserStats
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.core.protocol.ConnectToPeerNotification
import com.soulseekplus.core.protocol.CantConnectToPeerNotification
import com.soulseekplus.core.protocol.JoinRoomResponse
import com.soulseekplus.core.protocol.LeaveRoomNotification
import com.soulseekplus.core.protocol.LoginRejected
import com.soulseekplus.core.protocol.LoginSuccess
import com.soulseekplus.core.protocol.MessageReader
import com.soulseekplus.core.protocol.ParentMinSpeedNotification
import com.soulseekplus.core.protocol.ParentSpeedRatioNotification
import com.soulseekplus.core.protocol.DistribCode
import com.soulseekplus.core.protocol.EmbeddedMessageNotification
import com.soulseekplus.core.protocol.MessageWriter
import com.soulseekplus.core.protocol.PeerAddressResponse
import com.soulseekplus.core.protocol.PossibleParent
import com.soulseekplus.core.protocol.PossibleParentsNotification
import com.soulseekplus.core.protocol.PrivateMessageNotification
import com.soulseekplus.core.protocol.PrivilegedUsersNotification
import com.soulseekplus.core.protocol.PrivilegesResponse
import com.soulseekplus.core.protocol.ReloggedNotification
import com.soulseekplus.core.protocol.RoomMessageNotification
import com.soulseekplus.core.protocol.SearchRequestNotification
import com.soulseekplus.core.protocol.ServerCode
import com.soulseekplus.core.protocol.ServerMessage
import com.soulseekplus.core.protocol.UserJoinedRoomNotification
import com.soulseekplus.core.protocol.UserStatsResponse
import com.soulseekplus.core.protocol.UserStatusResponse
import com.soulseekplus.core.protocol.WatchUserResponse
import com.soulseekplus.core.protocol.WishlistIntervalNotification
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test için Soulseek sunucusu taklidi: düz engelleyen soketler, her istemci için bir iş parçacığı.
 * İstemciden gelen mesajlar gerçek uygulamadan bağımsız olarak elle (MessageReader ile) çözülür; böylece motorun tel biçimi
 * burada ayrıca doğrulanır.
 */
class FakeSlskServer : AutoCloseable {

    class Received(val user: String?, val code: Int, val payload: ByteArray) {
        fun reader() = MessageReader(payload)
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    val received = CopyOnWriteArrayList<Received>()
    val errors = CopyOnWriteArrayList<String>()
    val ackedMessageIds = CopyOnWriteArrayList<Int>()
    val loginAttempts = ConcurrentHashMap<String, AtomicInteger>()
    val loginSuccesses = ConcurrentHashMap<String, AtomicInteger>()

    /** Dolu ise "ebeveyn yok" bildiren istemcilere bu kullanıcı olası ebeveyn olarak önerilir. */
    @Volatile
    var parentCandidate: String? = null

    /** Sunucunun bir istemciye gömülü dağıtık arama göndermesi (o istemci dal kökü olur). */
    fun sendEmbeddedSearch(to: String, requester: String, token: Int, query: String) {
        val body = MessageWriter().uint32(DistribCode.SEARCH_IDENTIFIER.toLong()).string(requester).int32(token).string(query).toByteArray()
        clients[to]?.send(EmbeddedMessageNotification(DistribCode.SEARCH, body))
    }

    /** true: GetPeerAddress port 0 döndürür, eşler yalnızca dolaylı (ConnectToPeer) bağlanabilir. */
    @Volatile
    var hidePorts = false

    private val accounts = ConcurrentHashMap<String, String>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val rooms = ConcurrentHashMap<String, MutableSet<Client>>()
    private val allSockets = CopyOnWriteArrayList<Socket>()
    private val messageIds = AtomicInteger(1000)

    @Volatile
    private var running = true

    private val acceptThread = Thread({
        while (running) {
            try {
                val socket = server.accept()
                allSockets.add(socket)
                Thread({
                    try {
                        Client(socket).run()
                    } catch (_: IOException) {
                        runCatching { socket.close() }
                    }
                }, "fake-slsk-client").apply { isDaemon = true; start() }
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
            }
        }
    }, "fake-slsk-accept").apply { isDaemon = true; start() }

    fun addAccount(user: String, password: String) {
        accounts[user] = password
    }

    fun isOnline(user: String): Boolean = clients.containsKey(user)

    fun countOf(user: String?, code: Int): Int = received.count { it.user == user && it.code == code }

    fun messages(user: String?, code: Int): List<Received> = received.filter { it.user == user && it.code == code }

    /** Sunucu tarafından bağlantıyı koparır. */
    fun kick(user: String) {
        clients[user]?.let { runCatching { it.socket.close() } }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun stats(user: String) = UserStats(averageSpeed = 100_000, uploadCount = 5, files = 10, folders = 2)

    private inner class Client(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile
        var user: String? = null

        @Volatile
        var waitPort = 0

        fun send(message: ServerMessage) {
            val bytes = message.encode()
            try {
                synchronized(output) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (_: IOException) {
            }
        }

        fun run() {
            try {
                while (true) {
                    val header = ByteArray(8)
                    input.readFully(header)
                    val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val length = b.int
                    val code = b.int
                    if (length < 4 || length > 16 * 1024 * 1024) {
                        errors.add("bad frame length $length")
                        return
                    }
                    val payload = ByteArray(length - 4)
                    input.readFully(payload)
                    received.add(Received(user, code, payload))
                    handle(code, MessageReader(payload))
                }
            } catch (_: EOFException) {
            } catch (_: IOException) {
            } catch (e: Exception) {
                errors.add("handler failed: $e")
            } finally {
                onClosed()
            }
        }

        private fun onClosed() {
            runCatching { socket.close() }
            val name = user
            if (name != null && clients.remove(name, this)) {
                for (r in rooms.values) r.remove(this)
                for (c in clients.values) if (name in c.watched) c.send(UserStatusResponse(name, UserStatus.OFFLINE, false))
            }
        }

        private fun handle(code: Int, r: MessageReader) {
            when (code) {
                ServerCode.LOGIN -> login(r)
                ServerCode.SET_WAIT_PORT -> waitPort = r.uint32().toInt()
                ServerCode.GET_PEER_ADDRESS -> {
                    val target = r.string()
                    val t = clients[target]
                    send(
                        if (t == null) PeerAddressResponse(target, "0.0.0.0", 0)
                        else PeerAddressResponse(target, "127.0.0.1", if (hidePorts) 0 else t.waitPort),
                    )
                }
                ServerCode.CONNECT_TO_PEER -> {
                    val token = r.int32()
                    val target = r.string()
                    val type = r.string()
                    val me = user ?: return
                    val t = clients[target]
                    if (t == null) send(CantConnectToPeerNotification(token, target))
                    else t.send(ConnectToPeerNotification(me, type, "127.0.0.1", waitPort, token, false))
                }
                ServerCode.WATCH_USER -> {
                    val target = r.string()
                    watched.add(target)
                    val online = clients.containsKey(target)
                    send(
                        if (online || accounts.containsKey(target)) {
                            WatchUserResponse(target, true, if (online) UserStatus.ONLINE else UserStatus.OFFLINE, stats(target), "DE")
                        } else {
                            WatchUserResponse(target, false)
                        },
                    )
                }
                ServerCode.UNWATCH_USER -> watched.remove(r.string())
                ServerCode.GET_USER_STATUS -> {
                    val target = r.string()
                    send(UserStatusResponse(target, if (clients.containsKey(target)) UserStatus.ONLINE else UserStatus.OFFLINE, false))
                }
                ServerCode.GET_USER_STATS -> {
                    val target = r.string()
                    send(UserStatsResponse(target, stats(target)))
                }
                ServerCode.MESSAGE_USER -> {
                    val to = r.string()
                    val text = r.string()
                    val me = user ?: return
                    clients[to]?.send(PrivateMessageNotification(messageIds.incrementAndGet(), System.currentTimeMillis() / 1000, me, text, true))
                }
                ServerCode.MESSAGE_ACKED -> ackedMessageIds.add(r.int32())
                ServerCode.FILE_SEARCH -> {
                    val token = r.int32()
                    val query = r.string()
                    val me = user ?: return
                    for ((name, c) in clients) if (name != me) c.send(SearchRequestNotification(me, token, query))
                }
                ServerCode.USER_SEARCH -> {
                    val target = r.string()
                    val token = r.int32()
                    val query = r.string()
                    val me = user ?: return
                    clients[target]?.send(SearchRequestNotification(me, token, query))
                }
                ServerCode.JOIN_ROOM -> joinRoom(r.string())
                ServerCode.LEAVE_ROOM -> {
                    val room = r.string()
                    rooms[room]?.remove(this)
                    send(LeaveRoomNotification(room))
                }
                ServerCode.SAY_CHATROOM -> {
                    val room = r.string()
                    val text = r.string()
                    val me = user ?: return
                    rooms[room]?.forEach { it.send(RoomMessageNotification(room, me, text)) }
                }
                ServerCode.CHECK_PRIVILEGES -> send(PrivilegesResponse(0))
                ServerCode.HAVE_NO_PARENT -> {
                    val candidate = parentCandidate
                    val t = if (candidate != null && candidate != user) clients[candidate] else null
                    if (r.bool() && t != null) send(PossibleParentsNotification(listOf(PossibleParent(candidate!!, "127.0.0.1", t.waitPort))))
                }
                else -> Unit
            }
        }

        private fun joinRoom(room: String) {
            val me = user ?: return
            val members = rooms.getOrPut(room) { ConcurrentHashMap.newKeySet() }
            val others = members.toList()
            members.add(this)
            val users = members.mapNotNull { c -> c.user?.let { RoomUser(it, UserStatus.ONLINE, stats(it), false, "DE") } }
            send(JoinRoomResponse(room, users))
            for (c in others) c.send(UserJoinedRoomNotification(room, RoomUser(me, UserStatus.ONLINE, stats(me), false, "DE")))
        }

        private fun login(r: MessageReader) {
            val name = r.string()
            val password = r.string()
            val version = r.uint32()
            val hash = r.string()
            val minor = r.uint32()
            loginAttempts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            if (version != 160L || minor != 3L) errors.add("login version $version.$minor")
            if (hash != md5(name + password)) errors.add("login hash mismatch for $name")
            if (accounts[name] != password) {
                send(LoginRejected("INVALIDPASS"))
                runCatching { socket.close() }
                return
            }
            val previous = clients.put(name, this)
            user = name
            if (previous != null && previous !== this) {
                previous.send(ReloggedNotification)
                runCatching { previous.socket.close() }
            }
            loginSuccesses.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            send(LoginSuccess("Welcome to the fake server", "127.0.0.1", md5(password), false))
            send(ParentMinSpeedNotification(1))
            send(ParentSpeedRatioNotification(50))
            send(PrivilegedUsersNotification(emptyList()))
            send(WishlistIntervalNotification(720))
            for (c in clients.values) if (name in c.watched) c.send(UserStatusResponse(name, UserStatus.ONLINE, false))
        }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        for (s in allSockets) runCatching { s.close() }
        acceptThread.join(2_000)
    }
}
