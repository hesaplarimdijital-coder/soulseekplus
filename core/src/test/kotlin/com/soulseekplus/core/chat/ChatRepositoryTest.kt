package com.soulseekplus.core.chat

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.model.UserStats
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.core.testutil.FakeSoulseekClient
import com.soulseekplus.core.testutil.close
import com.soulseekplus.core.testutil.testScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {

    private val client = FakeSoulseekClient("me")
    private val settings = MutableStateFlow(ClientSettings(username = "me"))
    private val lists = MutableStateFlow(UserLists())
    private val scope = testScope()
    private val chat = ChatRepository(client, settings, lists, scope)

    @After
    fun tearDown() = scope.close()

    private fun user(name: String, status: UserStatus = UserStatus.ONLINE) = RoomUser(name, status, UserStats())

    private fun join(room: String, vararg users: String) =
        client.emit(ClientEvent.RoomJoined(room, users.map { user(it) }, isPrivate = false))

    private class RecordingMutator : ChatMutator {
        val actions = ArrayList<UserListAction>()
        val aliases = ArrayList<Pair<String, String?>>()
        override fun apply(action: UserListAction) {
            actions += action
        }

        override fun setAlias(name: String, expansion: String?) {
            aliases += name to expansion
        }
    }

    @Test
    fun joinedRoomsKeepOrderAndUsers() {
        join("B", "x", "me")
        join("A", "y")
        assertEquals(listOf("B", "A"), chat.rooms.value.keys.toList())
        assertEquals(listOf("x", "me"), chat.rooms.value["B"]!!.users.map { it.username })
        client.emit(ClientEvent.UserJoinedRoom("B", user("z")))
        client.emit(ClientEvent.UserLeftRoom("B", "x"))
        assertEquals(listOf("me", "z"), chat.rooms.value["B"]!!.users.map { it.username })
        client.emit(ClientEvent.RoomLeft("B"))
        assertEquals(listOf("A"), chat.rooms.value.keys.toList())
    }

    @Test
    fun roomMessagesUnreadMentionsAndActions() {
        join("Lobby", "x", "me")
        val events = ArrayList<ChatEvent>()
        scope.launch { chat.events.collect { events += it } }
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "hello everyone", 1L))
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "/me nods at ME!", 2L))
        client.emit(ClientEvent.RoomMessage("Lobby", "me", "my own", 3L))
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "someone said memes", 4L))
        client.emit(ClientEvent.RoomMessage("Unknown", "x", "ignored room", 5L))
        val room = chat.rooms.value["Lobby"]!!
        assertEquals(4, room.messages.size)
        assertEquals(3, room.unread)
        assertTrue(room.messages[1].isAction && room.messages[1].mentionsMe)
        assertEquals("nods at ME!", room.messages[1].text)
        assertTrue(room.messages[2].isOwn && !room.messages[2].mentionsMe)
        assertFalse(room.messages[3].mentionsMe)
        assertEquals(1, events.filterIsInstance<ChatEvent.RoomMention>().size)
        assertEquals(3, chat.totalUnread.value)

        chat.markRead(ChatTarget.Room("Lobby"))
        assertEquals(0, chat.totalUnread.value)
    }

    @Test
    fun activeRoomDoesNotAccumulateUnread() {
        join("Lobby", "x")
        chat.setActiveTarget(ChatTarget.Room("Lobby"))
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "hi", 1L))
        assertEquals(0, chat.rooms.value["Lobby"]!!.unread)
        chat.setActiveTarget(null)
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "hi again", 2L))
        assertEquals(1, chat.rooms.value["Lobby"]!!.unread)
        chat.setActiveTarget(ChatTarget.Room("Lobby"))
        assertEquals(0, chat.totalUnread.value)
    }

    @Test
    fun historyIsCapped() {
        join("Lobby", "x")
        for (i in 1..(ChatRepository.MAX_HISTORY + 50)) client.emit(ClientEvent.RoomMessage("Lobby", "x", "m$i", i.toLong()))
        val messages = chat.rooms.value["Lobby"]!!.messages
        assertEquals(ChatRepository.MAX_HISTORY, messages.size)
        assertEquals("m${ChatRepository.MAX_HISTORY + 50}", messages.last().text)
        assertEquals("m51", messages.first().text)
    }

    @Test
    fun ignoredUsersAreSilenced() {
        join("Lobby", "x", "spam")
        lists.value = UserLists(ignored = setOf("spam"))
        client.emit(ClientEvent.RoomMessage("Lobby", "spam", "buy now", 1L))
        client.emit(ClientEvent.RoomTickerAdded("Lobby", "spam", "ad"))
        client.emit(ClientEvent.RoomTickerAdded("Lobby", "x", "fine"))
        client.emit(ClientEvent.PrivateMessage(1, "spam", "psst", 2L, isAdmin = false, isOfflineMessage = false))
        assertTrue(chat.rooms.value["Lobby"]!!.messages.isEmpty())
        assertEquals(mapOf("x" to "fine"), chat.rooms.value["Lobby"]!!.tickers)
        assertTrue(chat.privateChats.value.isEmpty())
    }

    @Test
    fun tickersLifecycle() {
        join("Lobby", "x", "y")
        client.emit(ClientEvent.RoomTickers("Lobby", mapOf("x" to "one", "y" to "two")))
        client.emit(ClientEvent.RoomTickerRemoved("Lobby", "x"))
        client.emit(ClientEvent.RoomTickerAdded("Lobby", "z", "three"))
        assertEquals(mapOf("y" to "two", "z" to "three"), chat.rooms.value["Lobby"]!!.tickers)
        client.emit(ClientEvent.UserLeftRoom("Lobby", "y"))
        assertEquals(mapOf("z" to "three"), chat.rooms.value["Lobby"]!!.tickers)
        chat.setTicker("Lobby", "mine\nnew")
        assertEquals("Lobby" to "mine new", client.tickers.single())
    }

    @Test
    fun userStatusUpdatesAcrossRooms() {
        join("A", "x"); join("B", "x", "y")
        client.emit(ClientEvent.UserStatusChanged("x", UserStatus.AWAY, false))
        client.emit(ClientEvent.UserStatsChanged("x", UserStats(averageSpeed = 99, files = 5)))
        assertEquals(UserStatus.AWAY, chat.rooms.value["A"]!!.users.single().status)
        assertEquals(99, chat.rooms.value["B"]!!.users.first { it.username == "x" }.stats.averageSpeed)
        assertEquals(UserStatus.ONLINE, chat.rooms.value["B"]!!.users.first { it.username == "y" }.status)
    }

    @Test
    fun privateMessagesFlowAndUnread() {
        val events = ArrayList<ChatEvent>()
        scope.launch { chat.events.collect { events += it } }
        client.emit(ClientEvent.PrivateMessage(1, "bob", "hey", 10L, isAdmin = false, isOfflineMessage = false))
        client.emit(ClientEvent.PrivateMessage(2, "bob", "/me smiles", 11L, isAdmin = false, isOfflineMessage = true))
        assertEquals(2, chat.privateChats.value["bob"]!!.unread)
        assertTrue(chat.privateChats.value["bob"]!!.messages[1].isAction)
        assertEquals(2, events.size)
        assertEquals(false, (events[0] as ChatEvent.PrivateMessageReceived).conversationOpen)
        assertEquals(true, (events[1] as ChatEvent.PrivateMessageReceived).isOfflineMessage)

        chat.setActiveTarget(ChatTarget.Private("bob"))
        assertEquals(0, chat.totalUnread.value)
        client.emit(ClientEvent.PrivateMessage(3, "bob", "again", 12L, isAdmin = false, isOfflineMessage = false))
        assertEquals(0, chat.privateChats.value["bob"]!!.unread)
        assertEquals(true, (events[2] as ChatEvent.PrivateMessageReceived).conversationOpen)

        chat.sendPrivateMessage("bob", "yo\nthere")
        assertEquals("bob" to "yo there", client.privateMessages.single())
        client.emit(ClientEvent.PrivateMessageSent("bob", "yo there", 13L))
        val last = chat.privateChats.value["bob"]!!.messages.last()
        assertTrue(last.isOwn)
        assertEquals("me", last.username)
        chat.closePrivate("bob")
        assertTrue(chat.privateChats.value.isEmpty())
    }

    @Test
    fun adminPrivateMessageIsSystem() {
        client.emit(ClientEvent.PrivateMessage(1, "server", "maintenance", 1L, isAdmin = true, isOfflineMessage = false))
        assertTrue(chat.privateChats.value["server"]!!.messages.single().isSystem)
    }

    @Test
    fun roomListAndJoinFailure() {
        val events = ArrayList<ChatEvent>()
        scope.launch { chat.events.collect { events += it } }
        chat.refreshRoomList()
        assertEquals(1, client.roomListRequests)
        client.emit(ClientEvent.RoomList(listOf(RoomInfo("A", 5), RoomInfo("B", 9, isPrivate = true))))
        assertEquals(listOf("A", "B"), chat.roomList.value.map { it.name })
        client.emit(ClientEvent.RoomJoinFailed("Secret", "denied"))
        assertEquals(ChatEvent.RoomJoinFailed("Secret", "denied"), events.single())
    }

    @Test
    fun loginRejoinsAutoJoinAndPreviousRooms() {
        settings.value = ClientSettings(username = "me", autoJoinRooms = listOf("Auto", " "))
        chat.joinRoom("Manual")
        join("Manual", "me")
        client.joinedRooms.clear()
        client.emit(ClientEvent.LoggedIn("me"))
        assertEquals(listOf("Auto" to false, "Manual" to false), client.joinedRooms.toList().sortedBy { it.first })
        // Kasıtlı ayrılan oda bir daha katılınmaz.
        chat.leaveRoom("Manual")
        client.joinedRooms.clear()
        client.emit(ClientEvent.LoggedIn("me"))
        assertEquals(listOf("Auto" to false), client.joinedRooms.toList())
    }

    @Test
    fun submitPlainTextAndActions() {
        join("Lobby", "me")
        assertEquals(CommandResult.Sent, chat.submit("hello", ChatTarget.Room("Lobby")))
        assertEquals(CommandResult.Sent, chat.submit("/me dances", ChatTarget.Room("Lobby")))
        assertEquals(listOf("Lobby" to "hello", "Lobby" to "/me dances"), client.roomSays.toList())
        assertEquals(CommandResult.Sent, chat.submit("/me grins", ChatTarget.Private("bob")))
        assertEquals("bob" to "/me grins", client.privateMessages.single())
        assertEquals(CommandResult.NoTarget("say"), chat.submit("orphan", null))
        assertEquals(CommandResult.Empty, chat.submit("  ", null))
    }

    @Test
    fun submitJoinLeaveAndNavigation() {
        val requests = ArrayList<UiRequest>()
        scope.launch { chat.uiRequests.collect { requests += it } }
        assertEquals(CommandResult.Executed("join", "The Cave"), chat.submit("/join The Cave", null))
        assertEquals("The Cave" to false, client.joinedRooms.single())
        join("The Cave", "me")
        assertEquals(CommandResult.Executed("leave", "The Cave"), chat.submit("/leave", ChatTarget.Room("The Cave")))
        assertEquals(listOf("The Cave"), client.leftRooms.toList())
        assertTrue(chat.rooms.value.isEmpty())
        assertEquals(CommandResult.NoTarget("leave"), chat.submit("/leave", null))

        chat.submit("/browse alice", null)
        chat.submit("/whois alice", null)
        chat.submit("/pm carol", null)
        chat.submit("/search daft punk", null)
        chat.submit("/usearch bob flac", null)
        chat.submit("/bsearch jazz", null)
        assertEquals(
            listOf(
                UiRequest.OpenRoom("The Cave"),
                UiRequest.OpenBrowse("alice"),
                UiRequest.OpenUserInfo("alice"),
                UiRequest.OpenPrivateChat("carol"),
                UiRequest.NewSearch("daft punk", SearchScope.Network),
                UiRequest.NewSearch("flac", SearchScope.User("bob")),
                UiRequest.NewSearch("jazz", SearchScope.Buddies),
            ),
            requests,
        )
        assertTrue("carol" in chat.privateChats.value)
    }

    @Test
    fun submitUserListCommandsUseMutatorOrReportUnapplied() {
        val unapplied = chat.submit("/ignore bob", null)
        assertEquals(CommandResult.UserListChanged(UserListAction.Ignore("bob"), applied = false), unapplied)
        val mutator = RecordingMutator()
        chat.mutator = mutator
        assertEquals(CommandResult.UserListChanged(UserListAction.Ban("x"), applied = true), chat.submit("/ban x", null))
        chat.submit("/add y", null)
        chat.submit("/rem y", null)
        chat.submit("/unban x", null)
        chat.submit("/unignore bob", ChatTarget.Private("bob"))
        assertEquals(
            listOf(
                UserListAction.Ban("x"), UserListAction.AddBuddy("y"), UserListAction.RemoveBuddy("y"),
                UserListAction.Unban("x"), UserListAction.Unignore("bob"),
            ),
            mutator.actions,
        )
    }

    @Test
    fun aliasesGoThroughMutatorAndExpandFromSettings() {
        val mutator = RecordingMutator()
        chat.mutator = mutator
        assertEquals(CommandResult.AliasChanged("hi", "/me waves", true), chat.submit("/alias hi /me waves", null))
        assertEquals(CommandResult.AliasChanged("hi", null, true), chat.submit("/unalias hi", null))
        assertEquals(listOf("hi" to "/me waves", "hi" to null), mutator.aliases)
        assertEquals(CommandResult.InvalidAlias("join"), chat.submit("/alias join /x", null))

        settings.value = ClientSettings(username = "me", commandAliases = mapOf("hi" to "/me waves at \$1"))
        join("Lobby", "me")
        assertEquals(CommandResult.Sent, chat.submit("/hi bob", ChatTarget.Room("Lobby")))
        assertEquals("Lobby" to "/me waves at bob", client.roomSays.single())
        assertEquals(CommandResult.AliasList(mapOf("hi" to "/me waves at \$1")), chat.submit("/alias", null))
        assertEquals(listOf("/help", "/hi"), chat.completeCommand("/h"))
    }

    @Test
    fun awayBackClearHelpUnknownUsage() {
        client.connection.value = ConnectionState.LoggedIn("me", null, false, "", away = false)
        assertEquals(CommandResult.Executed("away"), chat.submit("/away", null))
        client.connection.value = ConnectionState.LoggedIn("me", null, false, "", away = true)
        assertEquals(CommandResult.Executed("back"), chat.submit("/away", null))
        chat.submit("/back", null)
        assertEquals(listOf(true, false, false), client.awayCalls.toList())

        join("Lobby", "x")
        client.emit(ClientEvent.RoomMessage("Lobby", "x", "hi", 1L))
        assertEquals(CommandResult.Executed("clear"), chat.submit("/clear", ChatTarget.Room("Lobby")))
        assertTrue(chat.rooms.value["Lobby"]!!.messages.isEmpty())
        assertEquals(0, chat.totalUnread.value)
        assertEquals(CommandResult.NoTarget("clear"), chat.submit("/clear", null))

        val help = chat.submit("/help join", null) as CommandResult.Help
        assertEquals(listOf("join"), help.commands.map { it.name })
        assertEquals(CommandResult.UnknownCommand("zap"), chat.submit("/zap", null))
        assertEquals(CommandResult.InvalidUsage("msg", "<user> <message..>"), chat.submit("/msg bob", null))
        assertNull(chat.completeCommand("hello").firstOrNull())
    }

    @Test
    fun wholeWordMentionDetection() {
        assertTrue(ChatRepository.containsWholeWord("hey Me, look", "me"))
        assertTrue(ChatRepository.containsWholeWord("me", "me"))
        assertFalse(ChatRepository.containsWholeWord("memes", "me"))
        assertTrue(ChatRepository.containsWholeWord("memes and (me)", "me"))
        assertFalse(ChatRepository.containsWholeWord("anything", ""))
    }
}
