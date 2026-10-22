package com.soulseekplus.ui.transfers

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.view.TransferCategory
import com.soulseekplus.core.view.TransferGroupMode
import com.soulseekplus.core.view.TransferGrouping
import com.soulseekplus.core.view.TransferSort

/** Transferler ekranının görünüm durumu ve transfer eylemleri. */
class TransfersViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)

    var direction by mutableStateOf(TransferDirection.DOWNLOAD)
    var category by mutableStateOf(TransferCategory.ALL)
    var groupMode by mutableStateOf(TransferGroupMode.NONE)
    var sort by mutableStateOf(TransferSort())
    var query by mutableStateOf("")

    /** Daraltılmış grup anahtarları (gruplar varsayılan olarak açık). */
    var collapsed by mutableStateOf<Set<String>>(emptySet())
    var selected by mutableStateOf<Set<Any>>(emptySet())
    var selectMode by mutableStateOf(false)

    fun toggleGroup(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
    }

    fun setGroupMode(mode: TransferGroupMode) {
        groupMode = mode
        collapsed = emptySet()
        selected = emptySet()
    }

    fun setDirection(value: TransferDirection) {
        direction = value
        selected = emptySet()
    }

    /** Seçili anahtarları (transfer kimliği ya da grup başlığı) güncel transferlere çözer. */
    fun resolve(keys: Set<Any>): List<Transfer> {
        if (keys.isEmpty()) return emptyList()
        val all = container.client.transfers.value
        val groupKeys = keys.filterIsInstance<String>().filter { it.startsWith("g:") }.toSet()
        val ids = keys.filterIsInstance<String>().filter { !it.startsWith("g:") }.toSet()
        return all.filter { t ->
            t.id in ids || (groupKeys.isNotEmpty() && TransferGrouping.groupKey(groupMode, t) in groupKeys)
        }
    }

    fun pause(items: List<Transfer>) {
        val ids = items.filter { canPause(it) }.map { it.id }
        if (ids.isNotEmpty()) container.client.pauseTransfers(ids)
    }

    fun resume(items: List<Transfer>) {
        val ids = items.filter { canResume(it) }.map { it.id }
        if (ids.isNotEmpty()) {
            container.client.resumeTransfers(ids)
            container.ensureServiceRunning()
        }
    }

    fun cancel(items: List<Transfer>, deletePartial: Boolean) {
        val ids = items.filter { canCancel(it) }.map { it.id }
        if (ids.isNotEmpty()) container.client.cancelTransfers(ids, deletePartial)
    }

    fun remove(items: List<Transfer>) {
        if (items.isNotEmpty()) container.client.removeTransfers(items.map { it.id })
        selected = emptySet()
    }

    fun clearFinished() = container.client.clearFinished(direction)

    fun refreshQueue(items: List<Transfer>) {
        items.filter { it.state == TransferState.QUEUED_REMOTE }.forEach { container.client.refreshQueuePosition(it.id) }
    }

    fun play(item: Transfer) {
        val uri = item.localUri ?: return
        container.player.play(uri, item.fileName)
    }

    companion object {
        fun canPause(t: Transfer) = !t.state.isTerminal && t.state != TransferState.PAUSED && t.state != TransferState.FAILED
        fun canResume(t: Transfer) = t.state == TransferState.PAUSED || t.state == TransferState.FAILED ||
            t.state == TransferState.USER_OFFLINE
        fun canCancel(t: Transfer) = !t.state.isTerminal
        fun canPlay(t: Transfer) = t.direction == TransferDirection.DOWNLOAD && t.state == TransferState.FINISHED && t.localUri != null &&
            com.soulseekplus.core.model.SharedFile.extensionOf(t.virtualPath) in com.soulseekplus.core.model.SharedFile.AUDIO_EXTENSIONS
    }
}
