package com.soulseekplus.core.search

import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.WishlistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * İstek listesinin kalıcı deposu (uygulama katmanı SharedPreferences ile uygular).
 * Görülen anahtarlar sorgu başına sıralı listedir (eskiden yeniye); depo sınırlama yapmaz, [WishlistRepository] yapar.
 */
interface WishlistStore {
    fun loadItems(): List<WishlistItem>

    fun saveItems(items: List<WishlistItem>)

    /** [query] için daha önce görülen sonuçların sıkıştırılmış anahtarları. */
    fun loadSeenKeys(query: String): List<String>

    fun saveSeenKeys(query: String, keys: List<String>)

    fun deleteSeenKeys(query: String)
}

/** İstek listesi sorgusu için yeni sonuç bulundu (bildirim tetikler). */
data class WishlistHitEvent(
    val query: String,
    /** Bu olaydan önceki olaydan bu yana düşen yeni sonuç sayısı. */
    val newCount: Int,
    /** En çok üç örnek. */
    val sample: List<SearchHit>,
)

/**
 * İstek listesi (Nicotine+ davranışı): sunucunun bildirdiği aralıkta ([SoulseekClient.wishlistIntervalSeconds]) her tikte
 * *bir* etkin öğe aranır, öğeler sırayla döner (sonuncudan sonraki etkin öğe). Sonuçlar token'a göre toplanır ve
 * öğe başına görülen anahtarlarla karşılaştırılır; yalnızca yeni düşenler rozet sayacını artırır ve [events] yayımlar.
 *
 * Yeni öğe eklenince (bağlıysa) hemen bir kez aranır; [searchNow] elle tetikler.
 */
class WishlistRepository(
    private val client: SoulseekClient,
    private val store: WishlistStore,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Yeni sonuçların bildirim olayında toplanma süresi. */
    private val notifyDelayMillis: Long = 5_000L,
    private val maxSeenKeysPerQuery: Int = MAX_SEEN_KEYS,
    private val maxResultsPerQuery: Int = MAX_RESULTS,
) {
    private val coroutineScope: CoroutineScope = scope
    private val lock = Any()

    private val _items = MutableStateFlow<List<WishlistItem>>(emptyList())

    /** Öğeler, eklenme sırasıyla. */
    val items: StateFlow<List<WishlistItem>> = _items.asStateFlow()

    private val _results = MutableStateFlow<Map<String, List<SearchHit>>>(emptyMap())

    /** Öğe başına sonuçlar (yeni olanlar [SearchHit.isNew] işaretli ve başta). Oturum boyunca bellekte. */
    val results: StateFlow<Map<String, List<SearchHit>>> = _results.asStateFlow()

    private val _totalNew = MutableStateFlow(0)

    /** Tüm öğelerdeki okunmamış yeni sonuç sayısı (gezinti rozeti). */
    val totalNewHits: StateFlow<Int> = _totalNew.asStateFlow()

    private val _events = MutableSharedFlow<WishlistHitEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<WishlistHitEvent> = _events.asSharedFlow()

    /** Sunucunun bildirdiği arama aralığı (saniye). */
    val intervalSeconds: StateFlow<Int> get() = client.wishlistIntervalSeconds

    private class Runtime(val query: String) {
        var token: Int? = null
        var seen: LinkedHashSet<String>? = null
        val results = LinkedHashMap<String, SearchHit>()
        var pendingNew = 0
        val pendingSample = ArrayList<SearchHit>(3)
        var notifyJob: Job? = null
        var seenDirty = false
    }

    private val runtimes = HashMap<String, Runtime>()
    private val tokenToQuery = HashMap<Int, String>()

    /** Dönen sırada son aranan öğenin indeksi. */
    private var rotationIndex = -1

    init {
        _items.value = store.loadItems().distinctBy { it.query }.map { it.copy(query = it.query.trim()) }.filter { it.query.isNotEmpty() }
        publishDerivedLocked()
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.searchResponses.collect { handleResponse(it) }
        }
        coroutineScope.launch {
            // Aralık her değiştiğinde (ör. girişte sunucu bildirince) zamanlayıcı yeniden kurulur.
            client.wishlistIntervalSeconds.collectLatest { seconds ->
                if (seconds <= 0) return@collectLatest
                while (isActive) {
                    delay(seconds * 1000L)
                    searchNext()
                }
            }
        }
    }

    // ------------------------------------------------------------------ düzenleme

    /** Öğe ekler. Boşsa ya da zaten varsa false. */
    fun add(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            if (_items.value.any { it.query == trimmed }) return false
            _items.value = _items.value + WishlistItem(query = trimmed, addedAtMillis = clock())
            persistItemsLocked()
            publishDerivedLocked()
        }
        if (isLoggedIn()) searchNow(trimmed)
        return true
    }

    fun remove(query: String) {
        val token: Int?
        synchronized(lock) {
            val index = _items.value.indexOfFirst { it.query == query }
            if (index < 0) return
            _items.value = _items.value.filterIndexed { i, _ -> i != index }
            if (index <= rotationIndex) rotationIndex--
            val rt = runtimes.remove(query)
            rt?.notifyJob?.cancel()
            token = rt?.token
            if (token != null) tokenToQuery.remove(token)
            persistItemsLocked()
            publishDerivedLocked()
        }
        if (token != null) client.cancelSearch(token)
        store.deleteSeenKeys(query)
    }

    /** Etkin/pasif (pasif öğeler döngüde atlanır). */
    fun setEnabled(query: String, enabled: Boolean) {
        synchronized(lock) { updateItemLocked(query) { it.copy(enabled = enabled) } }
    }

    fun toggle(query: String) {
        synchronized(lock) {
            val current = _items.value.firstOrNull { it.query == query } ?: return
            updateItemLocked(query) { it.copy(enabled = !current.enabled) }
        }
    }

    /** Öğenin yeni sonuç rozetini sıfırlar ve sonuçlarını "görüldü" yapar. */
    fun clearBadge(query: String) {
        synchronized(lock) {
            updateItemLocked(query) { it.copy(newHitCount = 0) }
            val rt = runtimes[query]
            if (rt != null) {
                for ((k, v) in rt.results) if (v.isNew) rt.results[k] = v.copy(isNew = false)
                publishResultsLocked()
            }
        }
    }

    fun clearAllBadges() {
        val queries = synchronized(lock) { _items.value.filter { it.newHitCount > 0 }.map { it.query } }
        queries.forEach { clearBadge(it) }
    }

    /** Öğenin tutulan sonuçlarını siler (görülen anahtarlar korunur, yeniden "yeni" sayılmaz). */
    fun clearResults(query: String) {
        synchronized(lock) {
            runtimes[query]?.results?.clear()
            publishResultsLocked()
        }
    }

    // ------------------------------------------------------------------ arama

    /** [query]'yi hemen arar (sıra döngüsünü etkilemez). Bağlı değilse false. */
    fun searchNow(query: String): Boolean {
        if (!isLoggedIn()) return false
        synchronized(lock) {
            val item = _items.value.firstOrNull { it.query == query } ?: return false
            return searchLocked(item)
        }
    }

    /** Sıradaki etkin öğeyi arar (zamanlayıcı tiki). Etkin öğe ya da bağlantı yoksa false. */
    fun searchNext(): Boolean {
        if (!isLoggedIn()) return false
        synchronized(lock) {
            val list = _items.value
            if (list.isEmpty()) return false
            for (step in 1..list.size) {
                val index = Math.floorMod(rotationIndex + step, list.size)
                val item = list[index]
                if (item.enabled) {
                    rotationIndex = index
                    return searchLocked(item)
                }
            }
            return false
        }
    }

    private fun isLoggedIn(): Boolean = client.connection.value is ConnectionState.LoggedIn

    private fun searchLocked(item: WishlistItem): Boolean {
        val rt = runtimes.getOrPut(item.query) { Runtime(item.query) }
        ensureSeenLoadedLocked(rt)
        val previous = rt.token
        val token = client.search(item.query, SearchScope.Wishlist)
        if (previous != null) {
            tokenToQuery.remove(previous)
            client.cancelSearch(previous)
        }
        rt.token = token
        tokenToQuery[token] = item.query
        updateItemLocked(item.query) { it.copy(lastSearchedAtMillis = clock()) }
        return true
    }

    private fun handleResponse(response: SearchResponse) {
        synchronized(lock) {
            val query = tokenToQuery[response.token] ?: return
            val rt = runtimes[query] ?: return
            ensureSeenLoadedLocked(rt)
            val seen = rt.seen!!
            var newInResponse = 0
            fun consider(file: SharedFile, locked: Boolean) {
                val hit = SearchHit(
                    username = response.username, file = file, locked = locked, freeSlots = response.freeSlots,
                    averageSpeed = response.averageSpeed, queueLength = response.queueLength, countryCode = response.countryCode,
                )
                if (rt.results.containsKey(hit.key)) return
                if (rt.results.size >= maxResultsPerQuery) return
                val compact = compactKey(hit.key)
                val isNew = seen.add(compact)
                if (isNew) {
                    rt.seenDirty = true
                    newInResponse++
                    if (rt.pendingSample.size < 3) rt.pendingSample.add(hit.copy(isNew = true))
                }
                rt.results[hit.key] = hit.copy(isNew = isNew)
            }
            for (file in response.files) consider(file, false)
            for (file in response.lockedFiles) consider(file, true)
            if (newInResponse == 0) return
            while (seen.size > maxSeenKeysPerQuery) {
                val it = seen.iterator()
                it.next()
                it.remove()
            }
            rt.pendingNew += newInResponse
            updateItemLocked(query) { it.copy(newHitCount = it.newHitCount + newInResponse) }
            publishResultsLocked()
            persistSeenLocked(rt)
            if (rt.notifyJob == null) {
                rt.notifyJob = coroutineScope.launch {
                    delay(notifyDelayMillis)
                    val ready = synchronized(lock) {
                        val r = runtimes[query]
                        if (r == null || r !== rt) null else {
                            val e = WishlistHitEvent(query, r.pendingNew, r.pendingSample.toList())
                            r.pendingNew = 0
                            r.pendingSample.clear()
                            r.notifyJob = null
                            e
                        }
                    }
                    if (ready != null && ready.newCount > 0) _events.emit(ready)
                }
            }
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun ensureSeenLoadedLocked(rt: Runtime) {
        if (rt.seen == null) rt.seen = LinkedHashSet(store.loadSeenKeys(rt.query))
    }

    private fun persistSeenLocked(rt: Runtime) {
        val seen = rt.seen ?: return
        if (!rt.seenDirty) return
        rt.seenDirty = false
        store.saveSeenKeys(rt.query, seen.toList())
    }

    private fun updateItemLocked(query: String, transform: (WishlistItem) -> WishlistItem) {
        val updated = _items.value.map { if (it.query == query) transform(it) else it }
        if (updated == _items.value) return
        _items.value = updated
        persistItemsLocked()
        publishDerivedLocked()
    }

    private fun persistItemsLocked() = store.saveItems(_items.value)

    private fun publishDerivedLocked() {
        _totalNew.value = _items.value.sumOf { it.newHitCount }
        publishResultsLocked()
    }

    private fun publishResultsLocked() {
        val map = LinkedHashMap<String, List<SearchHit>>()
        for (item in _items.value) {
            val rt = runtimes[item.query] ?: continue
            if (rt.results.isEmpty()) continue
            val (fresh, old) = rt.results.values.partition { it.isNew }
            map[item.query] = fresh + old
        }
        _results.value = map
    }

    companion object {
        const val MAX_SEEN_KEYS = 3000
        const val MAX_RESULTS = 2000

        /** 64 bit FNV-1a, 16 onaltılık hane: görülen kümesini küçük ve düz metin tutar. */
        fun compactKey(key: String): String {
            var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
            for (c in key) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h)
        }
    }
}
