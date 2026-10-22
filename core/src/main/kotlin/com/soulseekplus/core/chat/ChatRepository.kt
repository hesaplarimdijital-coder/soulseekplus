package com.soulseekplus.core.chat

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.model.ChatMessage
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.PrivateConversation
import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomState
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.model.UserStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sohbet odaları ve özel sohbetlerin durumu. Motor olaylarından ([SoulseekClient.events]) durum kurar; mesaj gönderme,
 * katılma/ayrılma motora devredilir. Oda başına ve sohbet başına en çok [MAX_HISTORY] mesaj tutulur.
 *
 * - Odaya ait kendi mesajlarımız sunucudan geri yankılanır (yerelde eklenmez); özel mesajlar `PrivateMessageSent` ile eklenir.
 * - Yoksayılan kullanıcıların oda mesajları ve duvar yazıları gösterilmez.
 * - Her girişte (yeniden bağlanmalar dahil) otomatik katılım odaları ve daha önce katılınmış odalar yeniden katılınır
 *   (sunucu üyelikleri bağlantıyla birlikte unutur). Motor bunu ayrıca yapmamalıdır.
 */
class ChatRepository(
    private val client: SoulseekClient,
    private val settings: StateFlow<ClientSettings>,
    private val userLists: StateFlow<UserLists>,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val coroutineScope: CoroutineScope = scope
    private val lock = Any()

    /** Komutların kullanıcı listelerini/takma adları değiştirmesi için; uygulama başlangıcında atanır. */
    @Volatile
    var mutator: ChatMutator? = null

    private val _rooms = MutableStateFlow<Map<String, RoomState>>(emptyMap())

    /** Katılınmış odalar, katılma sırasıyla. */
    val rooms: StateFlow<Map<String, RoomState>> = _rooms.asStateFlow()

    private val _roomList = MutableStateFlow<List<RoomInfo>>(emptyList())

    /** Sunucudaki oda listesi (son [refreshRoomList] yanıtı). */
    val roomList: StateFlow<List<RoomInfo>> = _roomList.asStateFlow()

    private val _private = MutableStateFlow<Map<String, PrivateConversation>>(emptyMap())

    /** Özel sohbetler, açılma sırasıyla. */
    val privateChats: StateFlow<Map<String, PrivateConversation>> = _private.asStateFlow()

    private val _totalUnread = MutableStateFlow(0)

    /** Odalar + özel sohbetlerde okunmamış toplam. */
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    private val _active = MutableStateFlow<ChatTarget?>(null)

    /** Şu an ekranda açık sohbet (bildirimleri bastırmak ve okundu saymak için). */
    val activeTarget: StateFlow<ChatTarget?> = _active.asStateFlow()

    private val _uiRequests = MutableSharedFlow<UiRequest>(extraBufferCapacity = 16)

    /** Komutların istediği gezinme/arama başlatma; arayüz toplar. */
    val uiRequests: SharedFlow<UiRequest> = _uiRequests.asSharedFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    /** Her girişte yeniden katılınacak odalar: ad → özel mi. */
    private val desiredRooms = LinkedHashMap<String, Boolean>()

    init {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { handleEvent(it) }
        }
    }

    // ------------------------------------------------------------------ giden

    fun refreshRoomList() = client.requestRoomList()

    fun joinRoom(room: String, private: Boolean = false) {
        val name = room.trim()
        if (name.isEmpty()) return
        synchronized(lock) { desiredRooms[name] = private }
        client.joinRoom(name, private)
    }

    fun leaveRoom(room: String) {
        synchronized(lock) {
            desiredRooms.remove(room)
            if (room in _rooms.value) _rooms.value = _rooms.value - room
            if ((_active.value as? ChatTarget.Room)?.name == room) _active.value = null
            recountUnreadLocked()
        }
        client.leaveRoom(room)
    }

    fun say(room: String, text: String) {
        val clean = sanitize(text)
        if (clean.isNotEmpty()) client.sayInRoom(room, clean)
    }

    fun sendPrivateMessage(username: String, text: String) {
        val clean = sanitize(text)
        if (clean.isEmpty()) return
        ensureConversation(username)
        client.sendPrivateMessage(username, clean)
    }

    fun setTicker(room: String, text: String) = client.setRoomTicker(room, sanitize(text))

    /** Özel sohbet penceresini açar (yoksa boş oluşturur). */
    fun openPrivate(username: String) = ensureConversation(username)

    fun closePrivate(username: String) {
        synchronized(lock) {
            if (username in _private.value) _private.value = _private.value - username
            if ((_active.value as? ChatTarget.Private)?.username == username) _active.value = null
            recountUnreadLocked()
        }
    }

    /** Pencerenin mesaj geçmişini siler. */
    fun clearMessages(target: ChatTarget) {
        synchronized(lock) {
            when (target) {
                is ChatTarget.Room -> _rooms.value[target.name]?.let { _rooms.value = _rooms.value + (target.name to it.copy(messages = emptyList(), unread = 0)) }
                is ChatTarget.Private -> _private.value[target.username]?.let { _private.value = _private.value + (target.username to it.copy(messages = emptyList(), unread = 0)) }
            }
            recountUnreadLocked()
        }
    }

    fun markRead(target: ChatTarget) {
        synchronized(lock) {
            when (target) {
                is ChatTarget.Room -> _rooms.value[target.name]?.takeIf { it.unread > 0 }?.let {
                    _rooms.value = _rooms.value + (target.name to it.copy(unread = 0))
                }
                is ChatTarget.Private -> _private.value[target.username]?.takeIf { it.unread > 0 }?.let {
                    _private.value = _private.value + (target.username to it.copy(unread = 0))
                }
            }
            recountUnreadLocked()
        }
    }

    /** Ekranda açık sohbeti bildirir (null: sohbet ekranı kapalı). Açılan sohbet okundu sayılır. */
    fun setActiveTarget(target: ChatTarget?) {
        _active.value = target
        if (target != null) markRead(target)
    }

    // ------------------------------------------------------------------ komutlar

    /**
     * Sohbet kutusuna yazılanı işler: komutsa çalıştırır, değilse [target] penceresine mesaj olarak gönderir.
     * Kullanıcı listesi/takma ad komutları [mutator] ile uygulanır; yoksa sonuç `applied = false` döner.
     */
    fun submit(input: String, target: ChatTarget?): CommandResult {
        val context = CommandContext(target, _rooms.value.keys.toList(), settings.value.commandAliases)
        return when (val parsed = CommandParser.parse(input, context)) {
            ParsedCommand.Empty -> CommandResult.Empty
            is ParsedCommand.Say -> when (target) {
                is ChatTarget.Room -> { say(target.name, parsed.text); CommandResult.Sent }
                is ChatTarget.Private -> { sendPrivateMessage(target.username, parsed.text); CommandResult.Sent }
                null -> CommandResult.NoTarget("say")
            }
            is ParsedCommand.Me -> when (target) {
                is ChatTarget.Room -> { say(target.name, "/me ${parsed.text}"); CommandResult.Sent }
                is ChatTarget.Private -> { sendPrivateMessage(target.username, "/me ${parsed.text}"); CommandResult.Sent }
                null -> CommandResult.NoTarget("me")
            }
            is ParsedCommand.Join -> { joinRoom(parsed.room); _uiRequests.tryEmit(UiRequest.OpenRoom(parsed.room)); CommandResult.Executed("join", parsed.room) }
            is ParsedCommand.Leave -> {
                val room = parsed.room ?: (target as? ChatTarget.Room)?.name
                if (room == null) CommandResult.NoTarget("leave") else { leaveRoom(room); CommandResult.Executed("leave", room) }
            }
            is ParsedCommand.SayInRoom -> { say(parsed.room, parsed.text); CommandResult.Sent }
            is ParsedCommand.Msg -> { sendPrivateMessage(parsed.username, parsed.text); CommandResult.Sent }
            is ParsedCommand.OpenPm -> {
                openPrivate(parsed.username)
                _uiRequests.tryEmit(UiRequest.OpenPrivateChat(parsed.username))
                CommandResult.Executed("pm", parsed.username)
            }
            is ParsedCommand.ClosePm -> {
                val user = parsed.username ?: (target as? ChatTarget.Private)?.username
                if (user == null) CommandResult.NoTarget("close") else { closePrivate(user); CommandResult.Executed("close", user) }
            }
            is ParsedCommand.Browse -> { _uiRequests.tryEmit(UiRequest.OpenBrowse(parsed.username)); CommandResult.Executed("browse", parsed.username) }
            is ParsedCommand.ShowUserInfo -> { _uiRequests.tryEmit(UiRequest.OpenUserInfo(parsed.username)); CommandResult.Executed("whois", parsed.username) }
            is ParsedCommand.Search -> { _uiRequests.tryEmit(UiRequest.NewSearch(parsed.query, parsed.scope)); CommandResult.Executed("search", parsed.query) }
            is ParsedCommand.UserList -> {
                val m = mutator
                if (m != null) m.apply(parsed.action)
                CommandResult.UserListChanged(parsed.action, applied = m != null)
            }
            ParsedCommand.ToggleAway -> {
                val away = (client.connection.value as? ConnectionState.LoggedIn)?.away ?: false
                client.setAway(!away)
                CommandResult.Executed(if (away) "back" else "away")
            }
            ParsedCommand.Back -> { client.setAway(false); CommandResult.Executed("back") }
            ParsedCommand.Clear -> if (target == null) CommandResult.NoTarget("clear") else { clearMessages(target); CommandResult.Executed("clear") }
            is ParsedCommand.SetAlias -> {
                val m = mutator
                if (m != null) m.setAlias(parsed.name, parsed.expansion)
                CommandResult.AliasChanged(parsed.name, parsed.expansion, applied = m != null)
            }
            is ParsedCommand.RemoveAlias -> {
                val m = mutator
                if (m != null) m.setAlias(parsed.name, null)
                CommandResult.AliasChanged(parsed.name, null, applied = m != null)
            }
            ParsedCommand.ListAliases -> CommandResult.AliasList(settings.value.commandAliases)
            is ParsedCommand.Help -> CommandResult.Help(parsed.query, CommandParser.help(parsed.query))
            is ParsedCommand.InvalidUsage -> CommandResult.InvalidUsage(parsed.command, parsed.usage)
            is ParsedCommand.InvalidAlias -> CommandResult.InvalidAlias(parsed.name)
            is ParsedCommand.Unknown -> CommandResult.UnknownCommand(parsed.name)
        }
    }

    /** Sohbet kutusu tamamlaması: yazılan öneki taşıyan komutlar (başında `/`) ve takma adlar. */
    fun completeCommand(prefix: String): List<String> {
        if (!prefix.startsWith("/") || prefix.any { it.isWhitespace() }) return emptyList()
        val p = prefix.substring(1).lowercase()
        val names = CommandParser.COMMANDS.flatMap { listOf(it.name) + it.aliases } + settings.value.commandAliases.keys
        return names.filter { it.startsWith(p) }.distinct().sorted().map { "/$it" }
    }

    // ------------------------------------------------------------------ gelen olaylar

    private fun handleEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.LoggedIn -> rejoinRooms()
            is ClientEvent.RoomList -> _roomList.value = event.rooms
            is ClientEvent.RoomJoined -> onRoomJoined(event)
            is ClientEvent.RoomLeft -> synchronized(lock) {
                desiredRooms.remove(event.room)
                if (event.room in _rooms.value) _rooms.value = _rooms.value - event.room
                recountUnreadLocked()
            }
            is ClientEvent.UserJoinedRoom -> updateRoom(event.room) { room ->
                room.copy(users = room.users.filter { it.username != event.user.username } + event.user)
            }
            is ClientEvent.UserLeftRoom -> updateRoom(event.room) { room ->
                room.copy(users = room.users.filter { it.username != event.username }, tickers = room.tickers - event.username)
            }
            is ClientEvent.RoomMessage -> onRoomMessage(event)
            is ClientEvent.RoomTickers -> updateRoom(event.room) { it.copy(tickers = filterIgnored(event.tickers)) }
            is ClientEvent.RoomTickerAdded -> updateRoom(event.room) { room ->
                if (event.username in userLists.value.ignored) room else room.copy(tickers = room.tickers + (event.username to event.text))
            }
            is ClientEvent.RoomTickerRemoved -> updateRoom(event.room) { it.copy(tickers = it.tickers - event.username) }
            is ClientEvent.RoomJoinFailed -> {
                synchronized(lock) { desiredRooms.remove(event.room) }
                _events.tryEmit(ChatEvent.RoomJoinFailed(event.room, event.reason))
            }
            is ClientEvent.PrivateMessage -> onPrivateMessage(event)
            is ClientEvent.PrivateMessageSent -> onPrivateSent(event)
            is ClientEvent.UserStatusChanged -> updateUsers(event.username) { it.copy(status = event.status) }
            is ClientEvent.UserStatsChanged -> updateUsers(event.username) { it.copy(stats = event.stats) }
            else -> Unit
        }
    }

    private fun rejoinRooms() {
        val toJoin = LinkedHashMap<String, Boolean>()
        synchronized(lock) {
            for (room in settings.value.autoJoinRooms) {
                val name = room.trim()
                if (name.isNotEmpty() && name !in desiredRooms) desiredRooms[name] = false
            }
            toJoin.putAll(desiredRooms)
        }
        for ((room, private) in toJoin) client.joinRoom(room, private)
    }

    private fun onRoomJoined(event: ClientEvent.RoomJoined) {
        synchronized(lock) {
            desiredRooms[event.room] = event.isPrivate
            val existing = _rooms.value[event.room]
            val state = (existing ?: RoomState(name = event.room)).copy(users = event.users, isPrivate = event.isPrivate)
            // Yeniden katılımda mevcut sıra ve geçmiş korunur; yeni oda sona eklenir.
            _rooms.value = _rooms.value + (event.room to state)
        }
    }

    private fun onRoomMessage(event: ClientEvent.RoomMessage) {
        val own = ownUsername()
        val isOwn = event.username == own
        if (!isOwn && event.username in userLists.value.ignored) return
        val (text, action) = splitAction(event.text)
        val mentions = !isOwn && own.isNotEmpty() && containsWholeWord(text, own)
        val message = ChatMessage(
            timestampMillis = event.timestampMillis,
            username = event.username,
            text = text,
            isOwn = isOwn,
            isAction = action,
            mentionsMe = mentions,
        )
        var open = false
        var known = false
        synchronized(lock) {
            val room = _rooms.value[event.room]
            if (room != null) {
                known = true
                open = (_active.value as? ChatTarget.Room)?.name == event.room
                val unread = if (open || isOwn) room.unread else room.unread + 1
                _rooms.value = _rooms.value + (event.room to room.copy(messages = appendCapped(room.messages, message), unread = unread))
                recountUnreadLocked()
            }
        }
        if (known && mentions) _events.tryEmit(ChatEvent.RoomMention(event.room, message, open))
    }

    private fun onPrivateMessage(event: ClientEvent.PrivateMessage) {
        if (event.username in userLists.value.ignored) return
        val (text, action) = splitAction(event.text)
        val message = ChatMessage(
            timestampMillis = event.timestampMillis,
            username = event.username,
            text = text,
            isOwn = false,
            isAction = action,
            isSystem = event.isAdmin,
            mentionsMe = false,
        )
        val open = synchronized(lock) {
            val current = _private.value[event.username] ?: PrivateConversation(event.username)
            val isOpen = (_active.value as? ChatTarget.Private)?.username == event.username
            val updated = current.copy(
                messages = appendCapped(current.messages, message),
                unread = if (isOpen) current.unread else current.unread + 1,
            )
            _private.value = _private.value + (event.username to updated)
            recountUnreadLocked()
            isOpen
        }
        _events.tryEmit(ChatEvent.PrivateMessageReceived(event.username, message, event.isOfflineMessage, open))
    }

    private fun onPrivateSent(event: ClientEvent.PrivateMessageSent) {
        val (text, action) = splitAction(event.text)
        val message = ChatMessage(event.timestampMillis, ownUsername(), text, isOwn = true, isAction = action)
        synchronized(lock) {
            val current = _private.value[event.username] ?: PrivateConversation(event.username)
            _private.value = _private.value + (event.username to current.copy(messages = appendCapped(current.messages, message)))
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun ensureConversation(username: String) {
        synchronized(lock) {
            if (username !in _private.value) _private.value = _private.value + (username to PrivateConversation(username))
        }
    }

    private fun updateRoom(room: String, transform: (RoomState) -> RoomState) {
        synchronized(lock) {
            val current = _rooms.value[room] ?: return
            val next = transform(current)
            if (next != current) _rooms.value = _rooms.value + (room to next)
        }
    }

    private fun updateUsers(username: String, transform: (RoomUser) -> RoomUser) {
        synchronized(lock) {
            var changed = false
            val next = LinkedHashMap<String, RoomState>(_rooms.value.size * 2)
            for ((name, state) in _rooms.value) {
                if (state.users.any { it.username == username }) {
                    val users = state.users.map { if (it.username == username) transform(it) else it }
                    if (users != state.users) {
                        next[name] = state.copy(users = users)
                        changed = true
                        continue
                    }
                }
                next[name] = state
            }
            if (changed) _rooms.value = next
        }
    }

    private fun filterIgnored(tickers: Map<String, String>): Map<String, String> {
        val ignored = userLists.value.ignored
        return if (ignored.isEmpty()) tickers else tickers.filterKeys { it !in ignored }
    }

    private fun recountUnreadLocked() {
        _totalUnread.value = _rooms.value.values.sumOf { it.unread } + _private.value.values.sumOf { it.unread }
    }

    private fun ownUsername(): String =
        (client.connection.value as? ConnectionState.LoggedIn)?.username ?: settings.value.username

    private fun appendCapped(list: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
        if (list.size < MAX_HISTORY) return list + message
        val out = ArrayList<ChatMessage>(MAX_HISTORY)
        out.addAll(list.subList(list.size - MAX_HISTORY + 1, list.size))
        out.add(message)
        return out
    }

    /** Sunucu yeni satırlı iletileri reddeder (Nicotine+ ile aynı). */
    private fun sanitize(text: String): String = text.replace("\r", "").replace("\n", " ").trim()

    companion object {
        const val MAX_HISTORY = 2000

        /** `/me eylem` iletisini (metin, eylem mi) olarak ayırır. */
        fun splitAction(raw: String): Pair<String, Boolean> =
            if (raw.startsWith("/me ")) raw.substring(4) to true else raw to false

        /** [word] metinde tam sözcük olarak (harf/rakam sınırlı) geçiyor mu; büyük/küçük harf duyarsız. */
        fun containsWholeWord(text: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var from = 0
            while (true) {
                val index = text.indexOf(word, from, ignoreCase = true)
                if (index < 0) return false
                val before = if (index == 0) ' ' else text[index - 1]
                val afterIndex = index + word.length
                val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                from = index + 1
            }
        }
    }
}
