package com.soulseekplus.ui.wishlist

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.soulseekplus.R
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.search.WishlistRepository
import androidx.lifecycle.viewModelScope
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.ui.AppActions
import kotlinx.coroutines.launch

/** İstek listesi ekranının görünüm durumu; asıl veri [WishlistRepository]'de. */
class WishlistViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)
    val repo: WishlistRepository get() = container.wishlist

    /** Sonuçları gösterilen öğe (sorgu metni). */
    var selectedQuery by mutableStateOf<String?>(null)
        private set

    var newItemText by mutableStateOf("")

    var selectedHits by mutableStateOf<Set<Any>>(emptySet())

    fun select(query: String?) {
        selectedQuery = query
        selectedHits = emptySet()
        if (query != null) repo.clearBadge(query)
    }

    fun add(actions: AppActions) {
        val text = newItemText.trim()
        if (text.isEmpty()) return
        if (repo.add(text)) {
            newItemText = ""
            actions.toast(R.string.wishlist_added, text)
        } else {
            actions.toast(R.string.wishlist_exists)
        }
    }

    fun remove(query: String) {
        repo.remove(query)
        if (selectedQuery == query) select(null)
    }

    fun searchNow(query: String, actions: AppActions) {
        if (repo.searchNow(query)) actions.toast(R.string.wishlist_searching, query) else actions.toast(R.string.wishlist_cannot_search)
    }

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

    fun downloadFolder(hit: SearchHit, actions: AppActions) {
        viewModelScope.launch {
            try {
                val count = container.client.downloadFolder(hit.username, hit.file.folder)
                actions.toast(R.string.toast_queued_downloads, count)
                container.ensureServiceRunning()
            } catch (e: SoulseekException) {
                actions.toastText(e.message ?: getApplication<Application>().getString(R.string.err_generic))
            }
        }
    }
}
