package com.soulseekplus.ui.search

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soulseekplus.R
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SearchTabState
import com.soulseekplus.core.search.GroupKind
import com.soulseekplus.core.search.QuickFilter
import com.soulseekplus.core.search.ResultSort
import com.soulseekplus.core.search.SearchGrouping
import com.soulseekplus.core.search.SearchRepository
import com.soulseekplus.ui.AppActions
import kotlinx.coroutines.launch

/** Arama kapsamı seçimi (Wishlist burada yok; ayrı ekranda). */
enum class ScopeChoice { NETWORK, BUDDIES, ROOM, USER }

/** Bir sekmenin arayüz durumu (sonuçlar [SearchRepository]'de; bu yalnızca görünüm durumudur). */
data class TabUi(
    val sort: ResultSort = ResultSort(),
    /** Varsayılandan farklı duruma getirilmiş grup anahtarları (varsayılan: ayardaki "grupları aç"). */
    val toggledGroups: Set<String> = emptySet(),
    val selected: Set<Any> = emptySet(),
    /** Süzgeç alanlarını depodaki süzgeçten yeniden okutmak için artan sayaç. */
    val filterReset: Int = 0,
    val selectMode: Boolean = false,
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)
    val repo: SearchRepository get() = container.searches

    var query by mutableStateOf("")
    var scope by mutableStateOf(ScopeChoice.NETWORK)
    var scopeArg by mutableStateOf("")
    var filtersOpen by mutableStateOf(false)

    /** Artınca sorgu alanına odak verilir (Ctrl+K / Ctrl+N). */
    var focusToken by mutableIntStateOf(0)
        private set

    private val ui = mutableStateMapOf<Int, TabUi>()

    fun tabUi(tabId: Int): TabUi = ui[tabId] ?: TabUi()

    private fun updateUi(tabId: Int, transform: (TabUi) -> TabUi) {
        ui[tabId] = transform(tabUi(tabId))
    }

    fun requestFocus() {
        focusToken++
    }

    // ------------------------------------------------------------------ arama başlatma

    /** Sorgu alanındaki metinle yeni arama. Bilgi eksikse [actions] ile uyarır. */
    fun submit(actions: AppActions) {
        val text = query.trim()
        if (text.isEmpty()) return
        val resolved: SearchScope = when (scope) {
            ScopeChoice.NETWORK -> SearchScope.Network
            ScopeChoice.BUDDIES -> SearchScope.Buddies
            ScopeChoice.ROOM -> scopeArg.trim().takeIf { it.isNotEmpty() }?.let { SearchScope.Room(it) }
            ScopeChoice.USER -> scopeArg.trim().takeIf { it.isNotEmpty() }?.let { SearchScope.User(it) }
        } ?: run {
            actions.toast(if (scope == ScopeChoice.ROOM) R.string.search_need_room else R.string.search_need_user)
            return
        }
        start(text, resolved)
    }

    /** Dışarıdan (sohbet komutları, slsk://, paylaşılan metin) gelen arama. */
    fun start(text: String, searchScope: SearchScope) {
        val id = repo.newSearch(text, searchScope)
        if (id >= 0) {
            query = text
            ui[id] = TabUi()
        }
    }

    fun closeSelectedTab() {
        repo.selectedTabId.value?.let {
            repo.closeTab(it)
            onTabClosed(it)
        }
    }

    fun rerunSelectedTab() {
        repo.selectedTabId.value?.let {
            repo.rerun(it)
            ui[it] = tabUi(it).copy(selected = emptySet())
        }
    }

    fun onTabClosed(tabId: Int) {
        ui.remove(tabId)
    }

    // ------------------------------------------------------------------ görünüm durumu

    fun setSort(tabId: Int, sort: ResultSort) = updateUi(tabId) { it.copy(sort = sort) }

    fun toggleGroup(tabId: Int, key: String) = updateUi(tabId) {
        it.copy(toggledGroups = if (key in it.toggledGroups) it.toggledGroups - key else it.toggledGroups + key)
    }

    fun setAllGroups(tabId: Int, expandedByDefault: Boolean, expand: Boolean) {
        val tab = repo.tab(tabId) ?: return
        val kind = when (tab.groupMode) {
            GroupMode.FOLDER -> GroupKind.FOLDER
            GroupMode.USER -> GroupKind.USER
            GroupMode.FLAT -> return
        }
        // Anahtar kümesi "varsayılandan farklı olanlar"dır: istenen durum varsayılansa boş, değilse tüm gruplar.
        updateUi(tabId) {
            if (expand == expandedByDefault) it.copy(toggledGroups = emptySet())
            else it.copy(toggledGroups = tab.visibleHits.mapTo(HashSet()) { hit -> SearchGrouping.groupKey(kind, hit) })
        }
    }

    fun setSelection(tabId: Int, keys: Set<Any>) = updateUi(tabId) { it.copy(selected = keys) }

    fun clearSelection(tabId: Int) = updateUi(tabId) { it.copy(selected = emptySet()) }

    fun setSelectMode(tabId: Int, on: Boolean) = updateUi(tabId) { it.copy(selectMode = on) }

    fun setGroupMode(tabId: Int, mode: GroupMode) {
        repo.setGroupMode(tabId, mode)
        updateUi(tabId) { it.copy(toggledGroups = emptySet(), selected = emptySet()) }
    }

    fun toggleQuick(tabId: Int, chip: QuickFilter) = repo.toggleQuickFilter(tabId, chip)

    fun clearFilter(tabId: Int) {
        repo.clearFilter(tabId)
        updateUi(tabId) { it.copy(filterReset = it.filterReset + 1) }
    }

    // ------------------------------------------------------------------ seçim → sonuç satırları

    /** Seçili satır anahtarlarını (dosya ya da grup başlığı) gerçek sonuçlara çözer. */
    fun resolveSelection(tab: SearchTabState, keys: Set<Any>): List<SearchHit> {
        if (keys.isEmpty()) return emptyList()
        val groupKeys = keys.filterIsInstance<String>().filter { it.startsWith("g:") }.toSet()
        val fileKeys = keys.filterIsInstance<String>().filter { !it.startsWith("g:") }.toSet()
        val out = LinkedHashMap<String, SearchHit>()
        for (hit in tab.visibleHits) {
            if (hit.key in fileKeys) {
                out[hit.key] = hit
            } else if (groupKeys.isNotEmpty()) {
                val folderKey = SearchGrouping.groupKey(GroupKind.FOLDER, hit)
                val userKey = SearchGrouping.groupKey(GroupKind.USER, hit)
                if (folderKey in groupKeys || userKey in groupKeys) out[hit.key] = hit
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ indirme

    /** Kilitli olmayan sonuçları kuyruğa alır; kaç dosya eklendiğini ve atlananları bildirir. */
    fun download(hits: List<SearchHit>, actions: AppActions) {
        val (open, locked) = hits.partition { !it.locked }
        for (hit in open) container.client.download(hit.username, hit.file)
        when {
            open.isNotEmpty() && locked.isEmpty() -> actions.toast(R.string.toast_queued_downloads, open.size)
            open.isNotEmpty() -> actions.toast(R.string.toast_queued_downloads_skipped, open.size, locked.size)
            locked.isNotEmpty() -> actions.toast(R.string.toast_locked_skipped, locked.size)
        }
        if (open.isNotEmpty()) container.ensureServiceRunning()
    }

    fun downloadFolder(username: String, folderPath: String, actions: AppActions) {
        viewModelScope.launch {
            try {
                val count = container.client.downloadFolder(username, folderPath)
                actions.toast(R.string.toast_queued_downloads, count)
                container.ensureServiceRunning()
            } catch (e: SoulseekException) {
                actions.toastText(e.message ?: getApplication<Application>().getString(R.string.err_generic))
            }
        }
    }
}
