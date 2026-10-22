package com.soulseekplus.ui.browse

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soulseekplus.R
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.api.SoulseekException
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.view.BrowseTree
import com.soulseekplus.ui.AppActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowseState {
    data object Idle : BrowseState
    data class Loading(val username: String) : BrowseState
    data class Failed(val username: String, val message: String?) : BrowseState
    data class Loaded(val username: String, val tree: BrowseTree, val loadedAtMillis: Long) : BrowseState
}

/** Göz atma ekranı: yüklenen ağaç, seçili klasör, açık düğümler ve dosya seçimi. */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)

    var usernameInput by mutableStateOf("")
    var state by mutableStateOf<BrowseState>(BrowseState.Idle)
        private set
    var selectedPath by mutableStateOf<String?>(null)
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var includeSubfolders by mutableStateOf(false)
    var filter by mutableStateOf("")
    var fileSelection by mutableStateOf<Set<Any>>(emptySet())

    /** Dar ekranda: dosya listesi mi (true) ağaç mı (false) görünüyor. */
    var showFilesOnCompact by mutableStateOf(false)

    var sortColumn by mutableStateOf(0)
    var sortAscending by mutableStateOf(true)

    private var job: Job? = null

    /** Kullanıcıyı açar ve (varsa) [path] klasörünü seçer. Aynı kullanıcı yakın zamanda yüklendiyse yeniden yüklemez. */
    fun open(username: String, path: String? = null) {
        val name = username.trim()
        if (name.isEmpty()) return
        usernameInput = name
        val current = state
        val fresh = current is BrowseState.Loaded && current.username == name &&
            System.currentTimeMillis() - current.loadedAtMillis < REUSE_MILLIS
        if (fresh) {
            select(path)
        } else {
            load(name, path)
        }
    }

    fun reload() {
        val name = usernameInput.trim()
        if (name.isNotEmpty()) load(name, selectedPath)
    }

    private fun load(name: String, path: String?) {
        job?.cancel()
        state = BrowseState.Loading(name)
        selectedPath = null
        fileSelection = emptySet()
        filter = ""
        job = viewModelScope.launch {
            try {
                val result = container.client.browseUser(name)
                val tree = withContext(Dispatchers.Default) { BrowseTree(result) }
                state = BrowseState.Loaded(name, tree, System.currentTimeMillis())
                expanded = tree.roots.map { it.path }.toSet()
                select(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SoulseekException) {
                state = BrowseState.Failed(name, e.message)
            }
        }
    }

    /** Klasörü seçer ve üst klasörlerini açar. */
    fun select(path: String?) {
        val loaded = state as? BrowseState.Loaded
        val clean = path?.trim('\\')
        if (loaded == null || clean.isNullOrEmpty() || loaded.tree.node(clean) == null) {
            selectedPath = loaded?.tree?.roots?.firstOrNull()?.path?.takeIf { clean.isNullOrEmpty() }
            fileSelection = emptySet()
            return
        }
        val target: String = clean
        val open = HashSet(expanded)
        var p: String = target
        while (p.contains('\\')) {
            p = p.substringBeforeLast('\\')
            open += p
        }
        open += target
        expanded = open
        selectedPath = target
        fileSelection = emptySet()
        showFilesOnCompact = true
    }

    fun toggleExpanded(path: String) {
        expanded = if (path in expanded) expanded - path else expanded + path
    }

    fun expandAll(tree: BrowseTree) {
        expanded = tree.allFolderPaths().toSet()
    }

    fun collapseAll() {
        expanded = emptySet()
    }

    // ------------------------------------------------------------------ indirme

    fun downloadFiles(username: String, files: List<SharedFile>, tree: BrowseTree, actions: AppActions) {
        val allowed = files.filter { !tree.isLocked(it.folder) }
        for (file in allowed) container.client.download(username, file)
        report(allowed.size, files.size - allowed.size, actions)
    }

    /** Klasörü ve alt klasörlerini, klasör yapısını koruyarak indirir. */
    fun downloadFolder(username: String, node: BrowseTree.Node, tree: BrowseTree, actions: AppActions) {
        val files = tree.filesRecursive(node.path).filter { !tree.isLocked(it.folder) }
        val skipped = tree.node(node.path)?.totalFiles?.minus(files.size) ?: 0
        for (file in files) {
            val relative = file.folder.removePrefix(node.path).trim('\\').replace('\\', '/')
            val destination = if (relative.isEmpty()) node.name else node.name + "/" + relative
            container.client.download(username, file, destination)
        }
        report(files.size, skipped, actions)
    }

    private fun report(queued: Int, skipped: Int, actions: AppActions) {
        when {
            queued > 0 && skipped == 0 -> actions.toast(R.string.toast_queued_downloads, queued)
            queued > 0 -> actions.toast(R.string.toast_queued_downloads_skipped, queued, skipped)
            skipped > 0 -> actions.toast(R.string.toast_locked_skipped, skipped)
        }
        if (queued > 0) container.ensureServiceRunning()
    }

    private companion object {
        const val REUSE_MILLIS = 120_000L
    }
}
