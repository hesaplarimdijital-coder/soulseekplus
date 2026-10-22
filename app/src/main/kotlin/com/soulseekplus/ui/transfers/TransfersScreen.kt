package com.soulseekplus.ui.transfers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.view.TransferCategory
import com.soulseekplus.core.view.TransferColumn
import com.soulseekplus.core.view.TransferGroupHeader
import com.soulseekplus.core.view.TransferGroupMode
import com.soulseekplus.core.view.TransferGrouping
import com.soulseekplus.core.view.TransferItemRow
import com.soulseekplus.core.view.TransferRow
import com.soulseekplus.core.view.TransferSort
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.AppShortcuts
import com.soulseekplus.ui.FilterTextField
import com.soulseekplus.ui.ScrollRow
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.transferStateText
import desktopkit.components.ColumnWidth
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DenseTable
import desktopkit.components.HorizontalRule
import desktopkit.components.MenuEntry
import desktopkit.components.SegmentedChoice
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hesaplanmış görünüm: tablo satırları, kategori sayıları ve süzülmüş liste. */
internal data class TransferView(
    val rows: List<TransferRow>,
    val counts: Map<TransferCategory, Int>,
    val total: Int,
    val downloadsActive: Int,
)

internal val EmptyView = TransferView(emptyList(), emptyMap(), 0, 0)

@Composable
fun TransfersScreen(vm: TransfersViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    // Telefon (dar pencere): ayrı, kompakt düzen. Geniş pencerelerin düzeni aşağıda ve değişmez.
    if (currentWidthClass() == WidthClass.Compact) {
        CompactTransfersScreen(vm, container, actions, modifier)
        return
    }
    val res = appResources()
    val dimens = DeskTheme.dimens
    val all by container.client.transfers.collectAsStateWithLifecycle()
    val stats by container.client.transferStats.collectAsStateWithLifecycle()

    val view by produceState(EmptyView, all, vm.direction, vm.category, vm.groupMode, vm.sort, vm.query, vm.collapsed) {
        value = withContext(Dispatchers.Default) {
            val ofDirection = all.filter { it.direction == vm.direction }
            val counts = TransferCategory.entries.associateWith { c -> ofDirection.count { c.matches(it) } }
            val filtered = TransferGrouping.filter(ofDirection, vm.category, vm.query)
            val rows = TransferGrouping.rows(filtered, vm.groupMode, vm.sort) { key -> key !in vm.collapsed }
            TransferView(rows, counts, ofDirection.size, ofDirection.count { it.state.isActive })
        }
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_transfers),
            subtitle = res.getString(R.string.transfers_subtitle, Formatting.speed(stats.downloadSpeed), Formatting.speed(stats.uploadSpeed)),
            actions = {
                DeskIconButton(DeskIcons.Delete, stringResource(R.string.transfers_clear_finished), { vm.clearFinished() }, enabled = view.counts[TransferCategory.FINISHED].orZero() > 0)
            },
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            ScrollRow {
                SegmentedChoice(
                    options = listOf(
                        TransferDirection.DOWNLOAD to res.getString(R.string.transfers_downloads),
                        TransferDirection.UPLOAD to res.getString(R.string.transfers_uploads),
                    ),
                    selected = vm.direction,
                    onSelect = { vm.changeDirection(it) },
                )
                SegmentedChoice(
                    options = listOf(
                        TransferGroupMode.NONE to res.getString(R.string.transfers_group_none),
                        TransferGroupMode.USER to res.getString(R.string.transfers_group_user),
                        TransferGroupMode.FOLDER to res.getString(R.string.transfers_group_folder),
                    ),
                    selected = vm.groupMode,
                    onSelect = { vm.changeGroupMode(it) },
                )
                ToggleChip(res.getString(R.string.action_select), vm.selectMode, { vm.selectMode = !vm.selectMode }, icon = DeskIcons.Check)
            }
            ScrollRow {
                for (category in TransferCategory.entries) {
                    val count = view.counts[category].orZero()
                    ToggleChip(
                        label = res.getString(categoryLabel(category)) + if (category != TransferCategory.ALL && count > 0) " ($count)" else "",
                        selected = vm.category == category,
                        onClick = { vm.category = category },
                    )
                }
            }
            FilterTextField(vm.query, { vm.query = it }, stringResource(R.string.transfers_filter_hint), Modifier.fillMaxWidth())
        }

        if (view.rows.isEmpty()) {
            MessageScreen(
                title = stringResource(if (view.total == 0) R.string.transfers_empty_title else R.string.transfers_none_match),
                icon = if (vm.direction == TransferDirection.DOWNLOAD) SoulIcons.Download else SoulIcons.Upload,
                modifier = Modifier.weight(1f),
                description = stringResource(
                    if (view.total != 0) R.string.transfers_none_match_desc
                    else if (vm.direction == TransferDirection.DOWNLOAD) R.string.transfers_empty_downloads
                    else R.string.transfers_empty_uploads,
                ),
            )
        } else {
            TransfersTable(vm, container, actions, view.rows, Modifier.weight(1f))
        }
        if (vm.selected.isNotEmpty()) TransfersSelectionBar(vm)
    }
}

internal fun Int?.orZero() = this ?: 0

internal fun categoryLabel(c: TransferCategory): Int = when (c) {
    TransferCategory.ALL -> R.string.cat_all
    TransferCategory.ACTIVE -> R.string.cat_active
    TransferCategory.WAITING -> R.string.cat_waiting
    TransferCategory.FINISHED -> R.string.cat_finished
    TransferCategory.FAILED -> R.string.cat_failed
}

// ---------------------------------------------------------------------- tablo

private class TCol(
    val column: TransferColumn,
    val title: String,
    val width: ColumnWidth,
    val alignEnd: Boolean = false,
    val mono: Boolean = false,
    val sortable: Boolean = true,
    val cell: (TransferRow) -> String,
)

@Composable
private fun TransfersTable(vm: TransfersViewModel, container: AppContainer, actions: AppActions, rows: List<TransferRow>, modifier: Modifier) {
    val res = appResources()
    val context = LocalContext.current
    val widthClass = currentWidthClass()
    val ua = rememberUserActions(container, actions)
    val mode = vm.groupMode
    val cols = remember(res, widthClass, mode) { transferColumns(res, widthClass, mode) }
    val tableColumns = remember(cols) {
        cols.map { c -> TableColumn<TransferRow>(c.title, c.width, alignEnd = c.alignEnd, mono = c.mono, sortable = c.sortable) { row -> c.cell(row) } }
    }
    val sortIndex = cols.indexOfFirst { it.column == vm.sort.column }

    DenseTable(
        columns = tableColumns,
        rows = rows,
        key = { it.key },
        onRowClick = { row, _ -> if (row is TransferGroupHeader) vm.toggleGroup(row.key) },
        modifier = modifier.fillMaxWidth(),
        selectedKeys = vm.selected,
        onSelectionChange = { vm.selected = it },
        toggleOnClick = vm.selectMode,
        sort = if (sortIndex >= 0) TableSort(sortIndex, vm.sort.ascending) else null,
        onSortChange = { s -> cols.getOrNull(s.column)?.let { vm.sort = TransferSort(it.column, s.ascending) } },
        rowMenuLabel = res.getString(R.string.action_more),
        rowMenu = { row, _ -> buildTransferMenu(row, vm, res, context, actions, ua) },
    )
}


/** Transfer satırının (ya da seçimin) bağlam menüsü; tablo ve telefon listesi ortak kullanır. */
internal fun buildTransferMenu(
    row: TransferRow,
    vm: TransfersViewModel,
    res: android.content.res.Resources,
    context: Context,
    actions: AppActions,
    ua: com.soulseekplus.ui.common.UserActions,
): List<MenuEntry> {
    val keys: Set<Any> = if (row.key in vm.selected) vm.selected else setOf(row.key)
    val items = vm.resolve(keys)
    val single = items.singleOrNull()
    val out = ArrayList<MenuEntry>()
    out += MenuEntry.Item(
        res.getString(R.string.transfers_pause),
        { vm.pause(items) },
        shortcut = AppShortcuts.label(AppShortcuts.PAUSE_SELECTED, res),
        icon = DeskIcons.Pause,
        enabled = items.any(TransfersViewModel::canPause),
    )
    out += MenuEntry.Item(
        res.getString(R.string.transfers_resume),
        { vm.resume(items) },
        shortcut = AppShortcuts.label(AppShortcuts.RESUME_SELECTED, res),
        icon = DeskIcons.Resume,
        enabled = items.any(TransfersViewModel::canResume),
    )
    out += MenuEntry.Item(
        res.getString(R.string.transfers_retry),
        { vm.resume(items) },
        icon = SoulIcons.Retry,
        enabled = items.any { it.state == TransferState.FAILED || it.state == TransferState.USER_OFFLINE },
    )
    out += MenuEntry.Item(
        res.getString(R.string.transfers_refresh_queue),
        { vm.refreshQueue(items) },
        icon = DeskIcons.Queue,
        enabled = items.any { it.state == TransferState.QUEUED_REMOTE },
    )
    out += MenuEntry.Separator
    out += MenuEntry.Item(
        res.getString(R.string.transfers_cancel),
        { vm.cancel(items, deletePartial = false) },
        icon = DeskIcons.Close,
        enabled = items.any(TransfersViewModel::canCancel),
    )
    if (items.any { it.direction == TransferDirection.DOWNLOAD && TransfersViewModel.canCancel(it) }) {
        out += MenuEntry.Item(
            res.getString(R.string.transfers_cancel_delete),
            { vm.cancel(items, deletePartial = true) },
            icon = DeskIcons.Delete,
            destructive = true,
        )
    }
    out += MenuEntry.Item(res.getString(R.string.transfers_remove), { vm.remove(items) }, icon = DeskIcons.Delete, enabled = items.isNotEmpty())
    if (single != null) {
        if (TransfersViewModel.canPlay(single)) {
            out += MenuEntry.Separator
            out += MenuEntry.Item(res.getString(R.string.transfers_play), { vm.play(single) }, icon = DeskIcons.Play)
            out += MenuEntry.Item(res.getString(R.string.transfers_open_file), { openFile(context, single, actions) }, icon = DeskIcons.OpenWindow)
        }
        out += MenuEntry.Separator
        out += ua.copyFileName(single.fileName)
        out += ua.copyFileLink(single.username, single.virtualPath)
        if (single.folder.isNotEmpty()) {
            out += MenuEntry.Item(res.getString(R.string.menu_browse_folder), { actions.browseUser(single.username, single.folder) }, icon = SoulIcons.BrowseUser)
        }
        out += MenuEntry.Separator
        out += ua.userEntries(single.username)
    }
    return out
}

private fun openFile(context: Context, transfer: Transfer, actions: AppActions) {
    val uri = transfer.localUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mimeFor(transfer.fileName))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        actions.toast(R.string.transfers_no_app)
    } catch (e: SecurityException) {
        actions.toast(R.string.transfers_no_app)
    }
}

private fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

private fun transferColumns(res: android.content.res.Resources, width: WidthClass, mode: TransferGroupMode): List<TCol> {
    val file = TCol(TransferColumn.FILE, res.getString(R.string.col_file), ColumnWidth.Weight(1f)) { row ->
        when (row) {
            is TransferGroupHeader -> {
                val caret = if (row.expanded) "▾ " else "▸ "
                caret + row.title + "  ·  " + res.getString(R.string.transfers_group_progress, row.finished, row.count)
            }
            is TransferItemRow -> {
                val t = row.transfer
                if (mode == TransferGroupMode.NONE) {
                    val folder = t.folder.substringAfterLast('\\')
                    (if (folder.isNotEmpty()) "$folder\\" else "") + t.fileName
                } else {
                    "      " + t.fileName
                }
            }
        }
    }
    val user = TCol(TransferColumn.USER, res.getString(R.string.col_user), ColumnWidth.Fixed(120.dp)) { row ->
        when (row) {
            is TransferGroupHeader -> if (row.mode == TransferGroupMode.FOLDER) row.username else ""
            is TransferItemRow -> if (mode == TransferGroupMode.NONE) row.transfer.username else ""
        }
    }
    val size = TCol(TransferColumn.SIZE, res.getString(R.string.col_size), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { row ->
        when (row) {
            is TransferGroupHeader -> Formatting.size(row.totalBytes)
            is TransferItemRow -> if (row.transfer.size > 0) Formatting.size(row.transfer.size) else "-"
        }
    }
    val progress = TCol(TransferColumn.PROGRESS, res.getString(R.string.col_progress), ColumnWidth.Fixed(64.dp), alignEnd = true, mono = true) { row ->
        when (row) {
            is TransferGroupHeader -> Formatting.percent(row.progress)
            is TransferItemRow -> {
                val t = row.transfer
                if (t.state == TransferState.FINISHED) "100%" else if (t.bytesTransferred > 0) Formatting.percent(t.progress) else "-"
            }
        }
    }
    val speed = TCol(TransferColumn.SPEED, res.getString(R.string.col_speed), ColumnWidth.Fixed(92.dp), alignEnd = true, mono = true) { row ->
        when (row) {
            is TransferGroupHeader -> Formatting.speed(row.speed)
            is TransferItemRow -> Formatting.speed(row.transfer.speedBytesPerSecond)
        }
    }
    val eta = TCol(TransferColumn.QUEUE, res.getString(R.string.col_eta), ColumnWidth.Fixed(64.dp), alignEnd = true, mono = true, sortable = false) { row ->
        when (row) {
            is TransferGroupHeader -> ""
            is TransferItemRow -> if (row.transfer.state == TransferState.TRANSFERRING) Formatting.eta(row.transfer.etaSeconds) else ""
        }
    }
    val status = TCol(TransferColumn.STATE, res.getString(R.string.col_status), ColumnWidth.Fixed(176.dp)) { row ->
        when (row) {
            is TransferGroupHeader -> buildString {
                if (row.active > 0) append(res.getString(R.string.transfers_n_active, row.active))
                if (row.failed > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(res.getString(R.string.transfers_n_failed, row.failed))
                }
            }
            is TransferItemRow -> res.transferStateText(row.transfer)
        }
    }
    return when (width) {
        WidthClass.Compact -> listOf(file, progress, status.withWidth(ColumnWidth.Fixed(132.dp)))
        WidthClass.Medium -> listOf(file, size, progress, status)
        else -> listOf(file, user, size, progress, speed, eta, status)
    }
}

private fun TCol.withWidth(width: ColumnWidth) = TCol(column, title, width, alignEnd, mono, sortable, cell)

// ---------------------------------------------------------------------- seçim çubuğu

@Composable
private fun TransfersSelectionBar(vm: TransfersViewModel) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val items = remember(vm.selected, vm.direction) { vm.resolve(vm.selected) }
    Column {
        HorizontalRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.label_selected_count, vm.selected.size),
                style = DeskTheme.type.bodyStrong,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            DeskIconButton(DeskIcons.Pause, stringResource(R.string.transfers_pause), { vm.pause(items) }, enabled = items.any(TransfersViewModel::canPause))
            DeskIconButton(DeskIcons.Resume, stringResource(R.string.transfers_resume), { vm.resume(items) }, enabled = items.any(TransfersViewModel::canResume))
            DeskIconButton(DeskIcons.Close, stringResource(R.string.transfers_cancel), { vm.cancel(items, false) }, enabled = items.any(TransfersViewModel::canCancel))
            DeskButton(stringResource(R.string.transfers_remove), { vm.remove(items) }, icon = DeskIcons.Delete)
            DeskButton(stringResource(R.string.action_clear), { vm.selected = emptySet() })
        }
    }
}
