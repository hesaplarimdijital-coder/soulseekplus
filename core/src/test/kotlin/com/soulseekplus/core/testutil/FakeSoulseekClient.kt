package com.soulseekplus.core.testutil

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.DistributedState
import com.soulseekplus.core.model.NetworkStatus
import com.soulseekplus.core.model.PortMappingState
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferStats
import com.soulseekplus.core.model.UserInfo
import com.soulseekplus.core.model.UserPresence
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Testler için bellek içi [SoulseekClient]: çağrıları kaydeder, olayları elle yayımlar. */
class FakeSoulseekClient(username: String = "me") : SoulseekClient {

    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.LoggedIn(username, null, false, ""))
    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<ClientEvent> = _events
    override val users = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    override val transfers = MutableStateFlow<List<Transfer>>(emptyList())
    override val transferStats = MutableStateFlow(TransferStats())
    override val portMapping = MutableStateFlow<PortMappingState>(PortMappingState.Disabled)
    override val distributed = MutableStateFlow(DistributedState())
    override val network = MutableStateFlow(NetworkStatus())
    private val _searchResponses = MutableSharedFlow<SearchResponse>(extraBufferCapacity = 4096)
    override val searchResponses: SharedFlow<SearchResponse> = _searchResponses
    override val wishlistIntervalSeconds = MutableStateFlow(0)

    // ---- kayıtlar
    val searches = CopyOnWriteArrayList<Pair<String, SearchScope>>()
    val cancelledTokens = CopyOnWriteArrayList<Int>()
    val tokens = CopyOnWriteArrayList<Int>()
    val joinedRooms = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val leftRooms = CopyOnWriteArrayList<String>()
    val roomSays = CopyOnWriteArrayList<Pair<String, String>>()
    val privateMessages = CopyOnWriteArrayList<Pair<String, String>>()
    val tickers = CopyOnWriteArrayList<Pair<String, String>>()
    val awayCalls = CopyOnWriteArrayList<Boolean>()
    val watched = CopyOnWriteArrayList<String>()
    var roomListRequests = 0
    private var nextToken = 1000

    var browseHandler: suspend (String) -> BrowseResult = { throw SoulseekException("Not supported") }
    var userInfoHandler: suspend (String) -> UserInfo = { throw SoulseekException("Not supported") }

    fun emit(event: ClientEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }

    fun emitResponse(response: SearchResponse) {
        check(_searchResponses.tryEmit(response)) { "response buffer full" }
    }

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun onNetworkChanged(onMetered: Boolean) = Unit
    override fun setAway(away: Boolean) {
        awayCalls += away
    }

    override fun watchUser(username: String) {
        watched += username
    }

    override fun unwatchUser(username: String) {
        watched -= username
    }

    override suspend fun getUserInfo(username: String): UserInfo = userInfoHandler(username)
    override suspend fun browseUser(username: String): BrowseResult = browseHandler(username)
    override suspend fun getFolderContents(username: String, folderPath: String): List<SharedFolder> = emptyList()
    override suspend fun getUserAddress(username: String): Pair<String, Int>? = null

    override fun search(query: String, scope: SearchScope): Int {
        searches += query to scope
        val token = nextToken++
        tokens += token
        return token
    }

    override fun cancelSearch(token: Int) {
        cancelledTokens += token
    }

    override fun requestRoomList() {
        roomListRequests++
    }

    override fun joinRoom(room: String, private: Boolean) {
        joinedRooms += room to private
    }

    override fun leaveRoom(room: String) {
        leftRooms += room
    }

    override fun sayInRoom(room: String, text: String) {
        roomSays += room to text
    }

    override fun sendPrivateMessage(username: String, text: String) {
        privateMessages += username to text
    }

    override fun setRoomTicker(room: String, text: String) {
        tickers += room to text
    }

    override fun download(username: String, file: SharedFile, destinationSubfolder: String?) = Unit
    override suspend fun downloadFolder(username: String, folderPath: String): Int = 0
    override fun pauseTransfers(ids: Collection<String>) = Unit
    override fun resumeTransfers(ids: Collection<String>) = Unit
    override fun cancelTransfers(ids: Collection<String>, deleteFiles: Boolean) = Unit
    override fun removeTransfers(ids: Collection<String>) = Unit
    override fun clearFinished(direction: TransferDirection) = Unit
    override fun refreshQueuePosition(id: String) = Unit
}
