package com.soulseekplus.core.protocol

import com.soulseekplus.core.CoreInfo
import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.UserStats
import com.soulseekplus.core.model.UserStatus

/**
 * Sunucu ("S") mesajları (bkz. docs/slsk-protocol.md §3). Çerçeve: uint32 uzunluk + uint32 kod + yük.
 * Hem istemciden sunucuya hem sunucudan istemciye giden mesajlar [writePayload] ile yazılabilir
 * (sahte sunucu testleri ve gidiş-dönüş testleri için); yalnızca sunucudan gelenler [ServerMessages.decode] ile okunur.
 */
sealed interface ServerMessage {
    val code: Int
    fun writePayload(w: MessageWriter)

    /** Tel biçimindeki tam çerçeve. */
    fun encode(): ByteArray {
        val w = MessageWriter()
        writePayload(w)
        return Frame.code32(code, w.toByteArray())
    }
}

// =====================================================================================================================
// İstemci → sunucu
// =====================================================================================================================

data class LoginRequest(
    val username: String,
    val password: String,
    val version: Int = CoreInfo.CLIENT_VERSION,
    val minorVersion: Int = CoreInfo.MINOR_VERSION,
) : ServerMessage {
    override val code get() = ServerCode.LOGIN
    override fun writePayload(w: MessageWriter) {
        w.string(username).string(password).uint32(version.toLong()).string(md5Hex(username + password))
            .uint32(minorVersion.toLong())
    }

    override fun toString(): String = "LoginRequest(username=$username, password=***)"
}

data class SetWaitPort(val port: Int) : ServerMessage {
    override val code get() = ServerCode.SET_WAIT_PORT
    override fun writePayload(w: MessageWriter) {
        w.uint32(port.toLong())
    }
}

data class GetPeerAddressRequest(val username: String) : ServerMessage {
    override val code get() = ServerCode.GET_PEER_ADDRESS
    override fun writePayload(w: MessageWriter) {
        w.string(username)
    }
}

data class WatchUserRequest(val username: String) : ServerMessage {
    override val code get() = ServerCode.WATCH_USER
    override fun writePayload(w: MessageWriter) {
        w.string(username)
    }
}

data class UnwatchUserRequest(val username: String) : ServerMessage {
    override val code get() = ServerCode.UNWATCH_USER
    override fun writePayload(w: MessageWriter) {
        w.string(username)
    }
}

data class GetUserStatusRequest(val username: String) : ServerMessage {
    override val code get() = ServerCode.GET_USER_STATUS
    override fun writePayload(w: MessageWriter) {
        w.string(username)
    }
}

data class GetUserStatsRequest(val username: String) : ServerMessage {
    override val code get() = ServerCode.GET_USER_STATS
    override fun writePayload(w: MessageWriter) {
        w.string(username)
    }
}

data class SayChatroomRequest(val room: String, val text: String) : ServerMessage {
    override val code get() = ServerCode.SAY_CHATROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(text)
    }
}

data class JoinRoomRequest(val room: String, val private: Boolean = false) : ServerMessage {
    override val code get() = ServerCode.JOIN_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room).uint32(if (private) 1 else 0)
    }
}

data class LeaveRoomRequest(val room: String) : ServerMessage {
    override val code get() = ServerCode.LEAVE_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room)
    }
}

data class ConnectToPeerRequest(val token: Int, val username: String, val type: String) : ServerMessage {
    override val code get() = ServerCode.CONNECT_TO_PEER
    override fun writePayload(w: MessageWriter) {
        w.int32(token).string(username).string(type)
    }
}

data class MessageUserRequest(val username: String, val text: String) : ServerMessage {
    override val code get() = ServerCode.MESSAGE_USER
    override fun writePayload(w: MessageWriter) {
        w.string(username).string(text)
    }
}

data class MessageAcked(val messageId: Int) : ServerMessage {
    override val code get() = ServerCode.MESSAGE_ACKED
    override fun writePayload(w: MessageWriter) {
        w.int32(messageId)
    }
}

data class FileSearchRequest(val token: Int, val query: String) : ServerMessage {
    override val code get() = ServerCode.FILE_SEARCH
    override fun writePayload(w: MessageWriter) {
        w.int32(token).string(query)
    }
}

data class UserSearchRequest(val username: String, val token: Int, val query: String) : ServerMessage {
    override val code get() = ServerCode.USER_SEARCH
    override fun writePayload(w: MessageWriter) {
        w.string(username).int32(token).string(query)
    }
}

data class RoomSearchRequest(val room: String, val token: Int, val query: String) : ServerMessage {
    override val code get() = ServerCode.ROOM_SEARCH
    override fun writePayload(w: MessageWriter) {
        w.string(room).int32(token).string(query)
    }
}

data class WishlistSearchRequest(val token: Int, val query: String) : ServerMessage {
    override val code get() = ServerCode.WISHLIST_SEARCH
    override fun writePayload(w: MessageWriter) {
        w.int32(token).string(query)
    }
}

data class SetStatus(val status: UserStatus) : ServerMessage {
    override val code get() = ServerCode.SET_STATUS
    override fun writePayload(w: MessageWriter) {
        w.int32(status.code)
    }
}

data object ServerPing : ServerMessage {
    override val code get() = ServerCode.SERVER_PING
    override fun writePayload(w: MessageWriter) = Unit
}

data class SharedFoldersFiles(val folders: Int, val files: Int) : ServerMessage {
    override val code get() = ServerCode.SHARED_FOLDERS_FILES
    override fun writePayload(w: MessageWriter) {
        w.uint32(folders.toLong().coerceAtLeast(0)).uint32(files.toLong().coerceAtLeast(0))
    }
}

data object RoomListRequest : ServerMessage {
    override val code get() = ServerCode.ROOM_LIST
    override fun writePayload(w: MessageWriter) = Unit
}

data class RoomTickerSet(val room: String, val text: String) : ServerMessage {
    override val code get() = ServerCode.ROOM_TICKER_SET
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(text)
    }
}

data class SendUploadSpeed(val bytesPerSecond: Int) : ServerMessage {
    override val code get() = ServerCode.SEND_UPLOAD_SPEED
    override fun writePayload(w: MessageWriter) {
        w.uint32(bytesPerSecond.toLong() and 0xFFFFFFFFL)
    }
}

data object CheckPrivilegesRequest : ServerMessage {
    override val code get() = ServerCode.CHECK_PRIVILEGES
    override fun writePayload(w: MessageWriter) = Unit
}

data class HaveNoParent(val haveNoParent: Boolean) : ServerMessage {
    override val code get() = ServerCode.HAVE_NO_PARENT
    override fun writePayload(w: MessageWriter) {
        w.bool(haveNoParent)
    }
}

data class AcceptChildren(val accept: Boolean) : ServerMessage {
    override val code get() = ServerCode.ACCEPT_CHILDREN
    override fun writePayload(w: MessageWriter) {
        w.bool(accept)
    }
}

data class BranchLevelRequest(val level: Int) : ServerMessage {
    override val code get() = ServerCode.BRANCH_LEVEL
    override fun writePayload(w: MessageWriter) {
        w.uint32(level.toLong() and 0xFFFFFFFFL)
    }
}

data class BranchRootRequest(val root: String) : ServerMessage {
    override val code get() = ServerCode.BRANCH_ROOT
    override fun writePayload(w: MessageWriter) {
        w.string(root)
    }
}

data class ChildDepthRequest(val depth: Int) : ServerMessage {
    override val code get() = ServerCode.CHILD_DEPTH
    override fun writePayload(w: MessageWriter) {
        w.uint32(depth.toLong() and 0xFFFFFFFFL)
    }
}

data class CantConnectToPeerRequest(val token: Int, val username: String) : ServerMessage {
    override val code get() = ServerCode.CANT_CONNECT_TO_PEER
    override fun writePayload(w: MessageWriter) {
        w.int32(token).string(username)
    }
}

// =====================================================================================================================
// Sunucu → istemci
// =====================================================================================================================

/** Giriş yanıtı (başarı ya da ret). */
sealed interface LoginResponse : ServerMessage {
    override val code get() = ServerCode.LOGIN
}

data class LoginSuccess(
    val greeting: String,
    /** Sunucunun gördüğü dış IP adresimiz. */
    val publicIp: String,
    val passwordHash: String,
    val isSupporter: Boolean,
) : LoginResponse {
    override fun writePayload(w: MessageWriter) {
        w.bool(true).string(greeting).ip(publicIp).string(passwordHash).bool(isSupporter)
    }
}

data class LoginRejected(val reason: String, val detail: String? = null) : LoginResponse {
    override fun writePayload(w: MessageWriter) {
        w.bool(false).string(reason)
        if (detail != null) w.string(detail)
    }
}

/** ip 0.0.0.0 ya da port 0: kullanıcı çevrimdışı / port bilinmiyor. */
data class PeerAddressResponse(
    val username: String,
    val ip: String,
    val port: Int,
    val obfuscationType: Int = 0,
    val obfuscatedPort: Int = 0,
) : ServerMessage {
    override val code get() = ServerCode.GET_PEER_ADDRESS
    override fun writePayload(w: MessageWriter) {
        w.string(username).ip(ip).uint32(port.toLong()).uint32(obfuscationType.toLong()).apply {
            // uint16
            uint8(obfuscatedPort and 0xFF).uint8((obfuscatedPort ushr 8) and 0xFF)
        }
    }

    val isOffline: Boolean get() = ip == "0.0.0.0"
}

data class WatchUserResponse(
    val username: String,
    val exists: Boolean,
    val status: UserStatus = UserStatus.OFFLINE,
    val stats: UserStats? = null,
    val countryCode: String? = null,
) : ServerMessage {
    override val code get() = ServerCode.WATCH_USER
    override fun writePayload(w: MessageWriter) {
        w.string(username).bool(exists)
        if (!exists) return
        w.uint32(status.code.toLong())
        w.userStats(stats ?: UserStats())
        if (countryCode != null) w.string(countryCode)
    }
}

data class UserStatusResponse(val username: String, val status: UserStatus, val privileged: Boolean) : ServerMessage {
    override val code get() = ServerCode.GET_USER_STATUS
    override fun writePayload(w: MessageWriter) {
        w.string(username).uint32(status.code.toLong()).bool(privileged)
    }
}

data class UserStatsResponse(val username: String, val stats: UserStats) : ServerMessage {
    override val code get() = ServerCode.GET_USER_STATS
    override fun writePayload(w: MessageWriter) {
        w.string(username).userStats(stats)
    }
}

data class RoomMessageNotification(val room: String, val username: String, val text: String) : ServerMessage {
    override val code get() = ServerCode.SAY_CHATROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(username).string(text)
    }
}

data class JoinRoomResponse(
    val room: String,
    val users: List<RoomUser>,
    /** Özel odalarda sahibi, herkese açıksa null. */
    val owner: String? = null,
    val operators: List<String> = emptyList(),
) : ServerMessage {
    override val code get() = ServerCode.JOIN_ROOM
    val isPrivate: Boolean get() = owner != null

    override fun writePayload(w: MessageWriter) {
        w.string(room)
        w.writeList(users) { string(it.username) }
        w.writeList(users) { uint32(it.status.code.toLong()) }
        w.writeList(users) { userStats(it.stats) }
        w.writeList(users) { uint32(if (it.slotsFull) 1 else 0) }
        w.writeList(users) { string(it.countryCode ?: "") }
        if (owner != null) {
            w.string(owner)
            w.writeList(operators) { string(it) }
        }
    }
}

data class LeaveRoomNotification(val room: String) : ServerMessage {
    override val code get() = ServerCode.LEAVE_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room)
    }
}

data class UserJoinedRoomNotification(val room: String, val user: RoomUser) : ServerMessage {
    override val code get() = ServerCode.USER_JOINED_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(user.username).uint32(user.status.code.toLong()).userStats(user.stats)
            .uint32(if (user.slotsFull) 1 else 0).string(user.countryCode ?: "")
    }
}

data class UserLeftRoomNotification(val room: String, val username: String) : ServerMessage {
    override val code get() = ServerCode.USER_LEFT_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(username)
    }
}

/** Başka bir kullanıcı bize bağlanmamızı istiyor (dolaylı bağlantı) — biz hedefiz. */
data class ConnectToPeerNotification(
    val username: String,
    val type: String,
    val ip: String,
    val port: Int,
    val token: Int,
    val privileged: Boolean,
    val obfuscationType: Int = 0,
    val obfuscatedPort: Int = 0,
) : ServerMessage {
    override val code get() = ServerCode.CONNECT_TO_PEER
    override fun writePayload(w: MessageWriter) {
        w.string(username).string(type).ip(ip).uint32(port.toLong()).int32(token).bool(privileged)
            .uint32(obfuscationType.toLong()).uint32(obfuscatedPort.toLong())
    }
}

data class PrivateMessageNotification(
    val messageId: Int,
    val timestampSeconds: Long,
    val username: String,
    val text: String,
    val isNew: Boolean,
) : ServerMessage {
    override val code get() = ServerCode.MESSAGE_USER
    override fun writePayload(w: MessageWriter) {
        w.int32(messageId).uint32(timestampSeconds).string(username).string(text).bool(isNew)
    }
}

/** Sunucunun ilettiği arama (UserSearch/RoomSearch hedefi): dağıtık aramayla aynı işlenir. */
data class SearchRequestNotification(val username: String, val token: Int, val query: String) : ServerMessage {
    override val code get() = ServerCode.FILE_SEARCH
    override fun writePayload(w: MessageWriter) {
        w.string(username).int32(token).string(query)
    }
}

data object ReloggedNotification : ServerMessage {
    override val code get() = ServerCode.RELOGGED
    override fun writePayload(w: MessageWriter) = Unit
}

data class RoomListResponse(val rooms: List<RoomInfo>) : ServerMessage {
    override val code get() = ServerCode.ROOM_LIST

    override fun writePayload(w: MessageWriter) {
        val public = rooms.filter { !it.isPrivate }
        val owned = rooms.filter { it.isPrivate && it.isOwned }
        val member = rooms.filter { it.isPrivate && !it.isOwned && !it.isOperated }
        val operated = rooms.filter { it.isPrivate && !it.isOwned && it.isOperated }
        for (group in listOf(public, owned, member)) {
            w.writeList(group) { string(it.name) }
            w.writeList(group) { uint32(it.userCount.toLong()) }
        }
        w.writeList(operated) { string(it.name) }
    }
}

data class AdminMessageNotification(val text: String) : ServerMessage {
    override val code get() = ServerCode.ADMIN_MESSAGE
    override fun writePayload(w: MessageWriter) {
        w.string(text)
    }
}

data class PrivilegedUsersNotification(val users: List<String>) : ServerMessage {
    override val code get() = ServerCode.PRIVILEGED_USERS
    override fun writePayload(w: MessageWriter) {
        w.writeList(users) { string(it) }
    }
}

data class ParentMinSpeedNotification(val minSpeed: Long) : ServerMessage {
    override val code get() = ServerCode.PARENT_MIN_SPEED
    override fun writePayload(w: MessageWriter) {
        w.uint32(minSpeed)
    }
}

data class ParentSpeedRatioNotification(val ratio: Long) : ServerMessage {
    override val code get() = ServerCode.PARENT_SPEED_RATIO
    override fun writePayload(w: MessageWriter) {
        w.uint32(ratio)
    }
}

data class PrivilegesResponse(val secondsRemaining: Long) : ServerMessage {
    override val code get() = ServerCode.CHECK_PRIVILEGES
    override fun writePayload(w: MessageWriter) {
        w.uint32(secondsRemaining)
    }
}

/** Sunucunun dağıtık mesajı doğrudan gömdüğü durum (dal kökü olduğumuzda). [body] dağıtık mesaj gövdesidir. */
class EmbeddedMessageNotification(val distribCode: Int, val body: ByteArray) : ServerMessage {
    override val code get() = ServerCode.EMBEDDED_MESSAGE
    override fun writePayload(w: MessageWriter) {
        w.uint8(distribCode).raw(body)
    }

    override fun equals(other: Any?): Boolean =
        other is EmbeddedMessageNotification && other.distribCode == distribCode && other.body.contentEquals(body)

    override fun hashCode(): Int = distribCode * 31 + body.contentHashCode()
}

data class PossibleParent(val username: String, val ip: String, val port: Int)

data class PossibleParentsNotification(val parents: List<PossibleParent>) : ServerMessage {
    override val code get() = ServerCode.POSSIBLE_PARENTS
    override fun writePayload(w: MessageWriter) {
        w.writeList(parents) { string(it.username).ip(it.ip).uint32(it.port.toLong()) }
    }
}

data class WishlistIntervalNotification(val seconds: Long) : ServerMessage {
    override val code get() = ServerCode.WISHLIST_INTERVAL
    override fun writePayload(w: MessageWriter) {
        w.uint32(seconds)
    }
}

data class RoomTickerStateNotification(val room: String, val tickers: Map<String, String>) : ServerMessage {
    override val code get() = ServerCode.ROOM_TICKER_STATE
    override fun writePayload(w: MessageWriter) {
        w.string(room).writeList(tickers.entries) { string(it.key).string(it.value) }
    }
}

data class RoomTickerAddNotification(val room: String, val username: String, val text: String) : ServerMessage {
    override val code get() = ServerCode.ROOM_TICKER_ADD
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(username).string(text)
    }
}

data class RoomTickerRemoveNotification(val room: String, val username: String) : ServerMessage {
    override val code get() = ServerCode.ROOM_TICKER_REMOVE
    override fun writePayload(w: MessageWriter) {
        w.string(room).string(username)
    }
}

data object ResetDistributedNotification : ServerMessage {
    override val code get() = ServerCode.RESET_DISTRIBUTED
    override fun writePayload(w: MessageWriter) = Unit
}

data class ExcludedSearchPhrasesNotification(val phrases: List<String>) : ServerMessage {
    override val code get() = ServerCode.EXCLUDED_SEARCH_PHRASES
    override fun writePayload(w: MessageWriter) {
        w.writeList(phrases) { string(it) }
    }
}

/** Dolaylı bağlantı isteğimiz başarısız oldu (karşı taraf bize bağlanamadı). */
data class CantConnectToPeerNotification(val token: Int, val username: String? = null) : ServerMessage {
    override val code get() = ServerCode.CANT_CONNECT_TO_PEER
    override fun writePayload(w: MessageWriter) {
        w.int32(token)
        if (username != null) w.string(username)
    }
}

data class CantCreateRoomNotification(val room: String) : ServerMessage {
    override val code get() = ServerCode.CANT_CREATE_ROOM
    override fun writePayload(w: MessageWriter) {
        w.string(room)
    }
}

// =====================================================================================================================
// Çözücü
// =====================================================================================================================

object ServerMessages {

    /**
     * Sunucudan gelen bir mesajı çözer. [payload] kod alanından sonraki bayttır. Bilinmeyen kod için null döner;
     * bozuk gövde için [ProtocolException] fırlatır (çağıran günlüğe yazıp bağlantıyı korur).
     */
    fun decode(code: Int, payload: ByteArray): ServerMessage? {
        val r = MessageReader(payload)
        return when (code) {
            ServerCode.LOGIN -> decodeLogin(r)
            ServerCode.GET_PEER_ADDRESS -> {
                val user = r.string()
                val ip = r.ip()
                val port = r.uint32().clampToInt()
                var obfType = 0
                var obfPort = 0
                if (r.remaining >= 4) obfType = r.uint32().clampToInt()
                if (r.remaining >= 2) obfPort = r.uint16()
                PeerAddressResponse(user, ip, port, obfType, obfPort)
            }
            ServerCode.WATCH_USER -> {
                val user = r.string()
                if (!r.bool()) {
                    WatchUserResponse(user, exists = false)
                } else {
                    val status = UserStatus.fromCode(r.uint32().clampToInt())
                    val stats = r.userStats()
                    val country = if (r.hasRemaining()) r.string().takeIf { it.isNotEmpty() } else null
                    WatchUserResponse(user, true, status, stats, country)
                }
            }
            ServerCode.GET_USER_STATUS -> {
                val user = r.string()
                val status = UserStatus.fromCode(r.uint32().clampToInt())
                val privileged = if (r.hasRemaining()) r.bool() else false
                UserStatusResponse(user, status, privileged)
            }
            ServerCode.GET_USER_STATS -> UserStatsResponse(r.string(), r.userStats())
            ServerCode.SAY_CHATROOM -> RoomMessageNotification(r.string(), r.string(), r.string())
            ServerCode.JOIN_ROOM -> decodeJoinRoom(r)
            ServerCode.LEAVE_ROOM -> LeaveRoomNotification(r.string())
            ServerCode.USER_JOINED_ROOM -> {
                val room = r.string()
                val user = r.string()
                val status = UserStatus.fromCode(r.uint32().clampToInt())
                val stats = r.userStats()
                val slotsFull = r.uint32() != 0L
                val country = if (r.hasRemaining()) r.string().takeIf { it.isNotEmpty() } else null
                UserJoinedRoomNotification(room, RoomUser(user, status, stats, slotsFull, country))
            }
            ServerCode.USER_LEFT_ROOM -> UserLeftRoomNotification(r.string(), r.string())
            ServerCode.CONNECT_TO_PEER -> {
                val user = r.string()
                val type = r.string()
                val ip = r.ip()
                val port = r.uint32().clampToInt()
                val token = r.int32()
                val privileged = if (r.hasRemaining()) r.bool() else false
                val obfType = if (r.remaining >= 4) r.uint32().clampToInt() else 0
                val obfPort = if (r.remaining >= 4) r.uint32().clampToInt() else 0
                ConnectToPeerNotification(user, type, ip, port, token, privileged, obfType, obfPort)
            }
            ServerCode.MESSAGE_USER -> {
                val id = r.int32()
                val ts = r.uint32()
                val user = r.string()
                val text = r.string()
                val isNew = if (r.hasRemaining()) r.bool() else true
                PrivateMessageNotification(id, ts, user, text, isNew)
            }
            ServerCode.FILE_SEARCH -> SearchRequestNotification(r.string(), r.int32(), r.string())
            ServerCode.RELOGGED -> ReloggedNotification
            ServerCode.ROOM_LIST -> decodeRoomList(r)
            ServerCode.ADMIN_MESSAGE -> AdminMessageNotification(r.string())
            ServerCode.PRIVILEGED_USERS -> PrivilegedUsersNotification(r.readList(4) { string() })
            ServerCode.PARENT_MIN_SPEED -> ParentMinSpeedNotification(r.uint32())
            ServerCode.PARENT_SPEED_RATIO -> ParentSpeedRatioNotification(r.uint32())
            ServerCode.CHECK_PRIVILEGES -> PrivilegesResponse(r.uint32())
            ServerCode.EMBEDDED_MESSAGE -> {
                val distribCode = r.uint8()
                EmbeddedMessageNotification(distribCode, r.rest())
            }
            ServerCode.POSSIBLE_PARENTS ->
                PossibleParentsNotification(r.readList(12) { PossibleParent(string(), ip(), uint32().clampToInt()) })
            ServerCode.WISHLIST_INTERVAL -> WishlistIntervalNotification(r.uint32())
            ServerCode.ROOM_TICKER_STATE -> {
                val room = r.string()
                val entries = r.readList(8) { string() to string() }
                RoomTickerStateNotification(room, LinkedHashMap<String, String>().apply { entries.forEach { put(it.first, it.second) } })
            }
            ServerCode.ROOM_TICKER_ADD -> RoomTickerAddNotification(r.string(), r.string(), r.string())
            ServerCode.ROOM_TICKER_REMOVE -> RoomTickerRemoveNotification(r.string(), r.string())
            ServerCode.RESET_DISTRIBUTED -> ResetDistributedNotification
            ServerCode.EXCLUDED_SEARCH_PHRASES -> ExcludedSearchPhrasesNotification(r.readList(4) { string() })
            ServerCode.CANT_CONNECT_TO_PEER -> {
                val token = r.int32()
                val user = if (r.hasRemaining()) runCatching { r.string() }.getOrNull() else null
                CantConnectToPeerNotification(token, user)
            }
            ServerCode.CANT_CREATE_ROOM -> CantCreateRoomNotification(r.string())
            else -> null
        }
    }

    private fun decodeLogin(r: MessageReader): LoginResponse {
        return if (r.bool()) {
            val greeting = r.string()
            val ip = r.ip()
            val hash = if (r.hasRemaining()) r.string() else ""
            val supporter = if (r.hasRemaining()) r.bool() else false
            LoginSuccess(greeting, ip, hash, supporter)
        } else {
            val reason = r.string()
            val detail = if (r.hasRemaining()) runCatching { r.string() }.getOrNull() else null
            LoginRejected(reason, detail)
        }
    }

    private fun decodeJoinRoom(r: MessageReader): JoinRoomResponse {
        val room = r.string()
        val names = r.readList(4) { string() }
        val statuses = r.readList(4) { UserStatus.fromCode(uint32().clampToInt()) }
        val stats = r.readList(20) { userStats() }
        val slots = r.readList(4) { uint32() != 0L }
        val countries = r.readList(4) { string() }
        val users = names.mapIndexed { i, name ->
            RoomUser(
                username = name,
                status = statuses.getOrElse(i) { UserStatus.OFFLINE },
                stats = stats.getOrElse(i) { UserStats() },
                slotsFull = slots.getOrElse(i) { false },
                countryCode = countries.getOrNull(i)?.takeIf { it.isNotEmpty() },
            )
        }
        var owner: String? = null
        var operators: List<String> = emptyList()
        if (r.hasRemaining()) {
            owner = r.string()
            if (r.hasRemaining()) operators = r.readList(4) { string() }
        }
        return JoinRoomResponse(room, users, owner, operators)
    }

    private fun decodeRoomList(r: MessageReader): RoomListResponse {
        val rooms = ArrayList<RoomInfo>()
        fun group(isPrivate: Boolean, isOwned: Boolean) {
            if (!r.hasRemaining()) return
            val names = r.readList(4) { string() }
            val counts = if (r.hasRemaining()) r.readList(4) { uint32().clampToInt() } else emptyList()
            names.forEachIndexed { i, n -> rooms.add(RoomInfo(n, counts.getOrElse(i) { 0 }, isPrivate, isOwned, false)) }
        }
        group(isPrivate = false, isOwned = false)
        group(isPrivate = true, isOwned = true)
        group(isPrivate = true, isOwned = false)
        if (r.hasRemaining()) {
            val operated = r.readList(4) { string() }
            operated.forEach { rooms.add(RoomInfo(it, 0, isPrivate = true, isOwned = false, isOperated = true)) }
        }
        return RoomListResponse(rooms)
    }
}
