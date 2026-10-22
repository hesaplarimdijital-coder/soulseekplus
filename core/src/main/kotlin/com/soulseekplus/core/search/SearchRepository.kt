package com.soulseekplus.core.search

import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.SearchFilter
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SearchTabState
import com.soulseekplus.core.model.SharedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Seeker tarzı hızlı süzgeç çipleri. */
enum class QuickFilter {
    FREE_SLOTS,
    HIDE_LOCKED,
    LOSSLESS,

    /** En az 320 kbps (kayıpsızlar da geçer). */
    MIN_320;

    companion object {
        fun isActive(filter: SearchFilter, chip: QuickFilter): Boolean = when (chip) {
            FREE_SLOTS -> filter.freeSlotsOnly
            HIDE_LOCKED -> filter.hideLocked
            LOSSLESS -> filter.losslessOnly
            MIN_320 -> (filter.minBitrate ?: 0) >= 320
        }

        fun toggled(filter: SearchFilter, chip: QuickFilter): SearchFilter = when (chip) {
            FREE_SLOTS -> filter.copy(freeSlotsOnly = !filter.freeSlotsOnly)
            HIDE_LOCKED -> filter.copy(hideLocked = !filter.hideLocked)
            LOSSLESS -> filter.copy(losslessOnly = !filter.losslessOnly)
            MIN_320 -> filter.copy(minBitrate = if (isActive(filter, MIN_320)) null else 320)
        }
    }
}

/**
 * Arama sekmelerinin sahibi. Motorun [SoulseekClient.searchResponses] akışını token'a göre sekmelere dağıtır,
 * [SearchHit.key] ile tekilleştirir, sekme başına [ClientSettings.maxSearchResultsKept] ile sınırlar ve süzgeci artımlı uygular.
 *
 * Durum bildirimleri yaklaşık [publishIntervalMillis] aralıkla birleştirilir (binlerce yanıtta arayüzü boğmamak için);
 * yapısal değişiklikler (yeni sekme, kapatma, süzgeç, temizleme) hemen yayımlanır.
 *
 * Sekme "etkin" bayrağı [searchTimeoutMillis] sonra düşer. Nicotine+ gerçek bir zaman aşımı uygulamaz
 * (SearchInactivityTimeout, kod 87, kullanımdan kalkmıştır); Soulseek ağında yanıtların büyük çoğunluğu ilk dakikada gelir.
 * Sekme etkin olmasa da yanıt kabul etmeyi sekme kapanana kadar sürdürür.
 */
class SearchRepository(
    private val client: SoulseekClient,
    private val settings: StateFlow<ClientSettings>,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val searchTimeoutMillis: Long = DEFAULT_SEARCH_TIMEOUT_MILLIS,
    private val publishIntervalMillis: Long = 120L,
) {
    private val coroutineScope: CoroutineScope = scope
    private val lock = Any()

    private val tabList = ArrayList<Tab>()
    private val tokenToTab = HashMap<Int, Tab>()
    private var nextTabId = 1

    private val _tabs = MutableStateFlow<List<SearchTabState>>(emptyList())

    /** Açık sekmeler (sekme çubuğu sırasıyla). */
    val tabs: StateFlow<List<SearchTabState>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow<Int?>(null)
    val selectedTabId: StateFlow<Int?> = _selectedTabId.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** Son aramalar (en yeni başta, tekil, en çok [MAX_RECENT]); oturum boyunca bellekte tutulur. */
    val recentQueries: StateFlow<List<String>> = _recent.asStateFlow()

    private val publishSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        // UNDISPATCHED: abonelik, ilk askıya almadan önce kaydolur; hemen ardından başlatılan aramaların yanıtı kaçmaz.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.searchResponses.collect { handleResponse(it) }
        }
        coroutineScope.launch {
            for (ignored in publishSignal) {
                synchronized(lock) { publishLocked() }
                delay(publishIntervalMillis)
            }
        }
    }

    // ------------------------------------------------------------------ sekme işlemleri

    /**
     * Yeni arama sekmesi açar, aramayı başlatır ve sekmeyi seçer. Dönen değer sekme kimliğidir ([SearchTabState.id]);
     * sorgu boşsa -1 döner ve hiçbir şey yapılmaz. [SearchScope.Wishlist] ağ araması olarak ele alınır
     * (istek listesi aramalarını [WishlistRepository] yürütür).
     */
    fun newSearch(query: String, scope: SearchScope = SearchScope.Network): Int {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return -1
        val effective = if (scope == SearchScope.Wishlist) SearchScope.Network else scope
        return synchronized(lock) {
            // Kilit altında: yanıtlar sekme kaydından önce gelirse kaybolmasın (motor çağrısı hemen döner).
            val token = client.search(trimmed, effective)
            val tab = Tab(nextTabId++, token, trimmed, effective, clock())
            tabList.add(tab)
            tokenToTab[token] = tab
            startTimeoutLocked(tab)
            _selectedTabId.value = tab.id
            rememberQuery(trimmed)
            publishLocked()
            tab.id
        }
    }

    /** Aynı sorguyu yeniden çalıştırır (sonuçlar temizlenir, yeni token alınır). */
    fun rerun(tabId: Int): Boolean {
        val oldToken = synchronized(lock) {
            val tab = tabList.firstOrNull { it.id == tabId } ?: return false
            val previous = tab.token
            val newToken = client.search(tab.query, tab.scope)
            tokenToTab.remove(previous)
            tab.token = newToken
            tokenToTab[newToken] = tab
            tab.resetResults(clock())
            startTimeoutLocked(tab)
            publishLocked()
            previous
        }
        client.cancelSearch(oldToken)
        return true
    }

    fun closeTab(tabId: Int) {
        val token = synchronized(lock) {
            val index = tabList.indexOfFirst { it.id == tabId }
            if (index < 0) return
            val tab = tabList.removeAt(index)
            tab.timeoutJob?.cancel()
            tokenToTab.remove(tab.token)
            if (_selectedTabId.value == tabId) {
                _selectedTabId.value = if (tabList.isEmpty()) null else tabList[(index - 1).coerceAtLeast(0)].id
            }
            publishLocked()
            tab.token
        }
        client.cancelSearch(token)
    }

    fun closeAllTabs() {
        val tokens = synchronized(lock) {
            val tokens = tabList.map { it.token }
            tabList.forEach { it.timeoutJob?.cancel() }
            tabList.clear()
            tokenToTab.clear()
            _selectedTabId.value = null
            publishLocked()
            tokens
        }
        tokens.forEach { client.cancelSearch(it) }
    }

    fun selectTab(tabId: Int) {
        synchronized(lock) {
            if (tabList.any { it.id == tabId }) _selectedTabId.value = tabId
        }
    }

    /** Ctrl+Tab / Ctrl+Shift+Tab: seçili sekmeyi [offset] kadar kaydırır (sarmalar). */
    fun selectRelative(offset: Int) {
        synchronized(lock) {
            if (tabList.isEmpty()) return
            val current = tabList.indexOfFirst { it.id == _selectedTabId.value }.coerceAtLeast(0)
            val next = Math.floorMod(current + offset, tabList.size)
            _selectedTabId.value = tabList[next].id
        }
    }

    /** Ctrl+1..9: 0 tabanlı sıradaki sekmeyi seçer (yoksa sonuncuyu). */
    fun selectIndex(index: Int) {
        synchronized(lock) {
            if (tabList.isEmpty()) return
            _selectedTabId.value = tabList[index.coerceIn(0, tabList.lastIndex)].id
        }
    }

    fun tab(tabId: Int): SearchTabState? = _tabs.value.firstOrNull { it.id == tabId }

    // ------------------------------------------------------------------ süzgeç ve gruplama

    fun setFilter(tabId: Int, filter: SearchFilter) {
        val begin = synchronized(lock) {
            val tab = tabList.firstOrNull { it.id == tabId } ?: return
            if (tab.requestedFilter == filter) return
            tab.requestedFilter = filter
            FilterJob(++tab.filterVersion, tab.epoch, ArrayList(tab.all))
        }
        // Ağır kısım (özellikle düzenli ifadeler) kilit dışında; çağıran iş parçacığı ana iş parçacığı olmamalıdır.
        val compiled = SearchFilterEngine.compile(filter)
        val visible: ArrayList<SearchHit> =
            if (compiled.isPassThrough) ArrayList(begin.snapshot) else begin.snapshot.filterTo(ArrayList()) { compiled.matches(it) }
        synchronized(lock) {
            val tab = tabList.firstOrNull { it.id == tabId } ?: return
            if (tab.filterVersion != begin.version || tab.epoch != begin.epoch) return
            // Hesap sürerken gelen yeni satırları da süz.
            for (i in begin.snapshot.size until tab.all.size) {
                val hit = tab.all[i]
                if (compiled.matches(hit)) visible.add(hit)
            }
            tab.filter = filter
            tab.compiled = compiled
            tab.visible = visible
            tab.dirty = true
            publishLocked()
        }
    }

    private class FilterJob(val version: Int, val epoch: Int, val snapshot: List<SearchHit>)

    fun updateFilter(tabId: Int, transform: (SearchFilter) -> SearchFilter) {
        val current = synchronized(lock) { tabList.firstOrNull { it.id == tabId }?.requestedFilter } ?: return
        setFilter(tabId, transform(current))
    }

    fun toggleQuickFilter(tabId: Int, chip: QuickFilter) = updateFilter(tabId) { QuickFilter.toggled(it, chip) }

    fun clearFilter(tabId: Int) = setFilter(tabId, SearchFilter(hideLocked = false))

    fun setGroupMode(tabId: Int, mode: GroupMode) {
        synchronized(lock) {
            val tab = tabList.firstOrNull { it.id == tabId } ?: return
            if (tab.groupMode == mode) return
            tab.groupMode = mode
            tab.dirty = true
            publishLocked()
        }
    }

    /** Sonuçları siler; sekme ve arama açık kalır (yeni yanıtlar yine eklenir). */
    fun clearResults(tabId: Int) {
        synchronized(lock) {
            val tab = tabList.firstOrNull { it.id == tabId } ?: return
            tab.clearHits()
            publishLocked()
        }
    }

    /** Bekleyen (birleştirilmiş) güncellemeleri hemen yayımlar. */
    fun flushNow() = synchronized(lock) { publishLocked() }

    // ------------------------------------------------------------------ yanıtlar

    private fun handleResponse(response: SearchResponse) {
        var changed = false
        synchronized(lock) {
            val tab = tokenToTab[response.token] ?: return
            val maxKept = settings.value.maxSearchResultsKept.coerceAtLeast(1)
            for (file in response.files) changed = tab.add(response, file, false, maxKept) || changed
            for (file in response.lockedFiles) changed = tab.add(response, file, true, maxKept) || changed
            if (changed) tab.dirty = true
        }
        if (changed) publishSignal.trySend(Unit)
    }

    private fun startTimeoutLocked(tab: Tab) {
        tab.timeoutJob?.cancel()
        tab.active = true
        tab.dirty = true
        val epoch = tab.epoch
        tab.timeoutJob = coroutineScope.launch {
            delay(searchTimeoutMillis)
            synchronized(lock) {
                if (tab.epoch == epoch && tab in tabList) {
                    tab.active = false
                    tab.dirty = true
                    publishLocked()
                }
            }
        }
    }

    private fun rememberQuery(query: String) {
        _recent.value = (listOf(query) + _recent.value.filter { !it.equals(query, ignoreCase = true) }).take(MAX_RECENT)
    }

    private fun publishLocked() {
        _tabs.value = tabList.map { it.snapshot() }
    }

    // ------------------------------------------------------------------ iç durum

    private class Tab(
        val id: Int,
        var token: Int,
        val query: String,
        val scope: SearchScope,
        var startedAt: Long,
    ) {
        var all = ArrayList<SearchHit>()
        var visible = ArrayList<SearchHit>()
        val keys = HashSet<String>()
        val users = HashSet<String>()
        var filter: SearchFilter = SearchFilter()
        var requestedFilter: SearchFilter = filter
        var compiled: CompiledFilter = SearchFilterEngine.compile(filter)
        var groupMode: GroupMode = GroupMode.FOLDER
        var active = true
        var timeoutJob: Job? = null
        var dirty = true
        var epoch = 0
        var filterVersion = 0
        private var cached: SearchTabState? = null

        fun add(response: SearchResponse, file: SharedFile, locked: Boolean, maxKept: Int): Boolean {
            if (all.size >= maxKept) return false
            val hit = SearchHit(
                username = response.username,
                file = file,
                locked = locked,
                freeSlots = response.freeSlots,
                averageSpeed = response.averageSpeed,
                queueLength = response.queueLength,
                countryCode = response.countryCode,
            )
            if (!keys.add(hit.key)) return false
            all.add(hit)
            users.add(hit.username)
            if (compiled.matches(hit)) visible.add(hit)
            return true
        }

        fun clearHits() {
            epoch++
            all = ArrayList()
            visible = ArrayList()
            keys.clear()
            users.clear()
            dirty = true
        }

        fun resetResults(now: Long) {
            clearHits()
            startedAt = now
        }

        fun snapshot(): SearchTabState {
            val existing = cached
            if (existing != null && !dirty) return existing
            val state = SearchTabState(
                id = id,
                query = query,
                scope = scope,
                startedAtMillis = startedAt,
                allHits = ArrayList(all),
                visibleHits = ArrayList(visible),
                filter = filter,
                groupMode = groupMode,
                userCount = users.size,
                active = active,
            )
            cached = state
            dirty = false
            return state
        }
    }

    companion object {
        const val DEFAULT_SEARCH_TIMEOUT_MILLIS = 60_000L
        const val MAX_RECENT = 50
    }
}
