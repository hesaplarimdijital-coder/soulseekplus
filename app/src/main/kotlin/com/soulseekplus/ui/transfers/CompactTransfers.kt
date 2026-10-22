package com.soulseekplus.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.soulseekplus.core.view.TransferGroupHeader
import com.soulseekplus.core.view.TransferGroupMode
import com.soulseekplus.core.view.TransferGrouping
import com.soulseekplus.core.view.TransferItemRow
import com.soulseekplus.core.view.TransferRow
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.FilterTextField
import com.soulseekplus.ui.ScrollRow
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.compact.CompactListRow
import com.soulseekplus.ui.compact.CompactSelectionBar
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.transferStateText
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.components.SectionCaption
import desktopkit.components.SegmentedChoice
import desktopkit.screens.MessageScreen
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Telefon düzeni (WidthClass.Compact): yön seçici + kategori çipleri iki ince satırda; arama, gruplama, seçim ve
 * "bitenleri temizle" üst çubukta. Satırlar iki satırlı: dosya adı, altında kullanıcı · durum · hız; ince ilerleme çubuğu.
 */

@Composable
internal fun CompactTransfersScreen(vm: TransfersViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val all by container.client.transfers.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var viewOptionsOpen by rememberSaveable { mutableStateOf(false) }

    val view by produceState(EmptyView, all, vm.direction, vm.category, vm.groupMode, vm.sort, vm.query, vm.collapsed) {
        value = withContext(Dispatchers.Default) {
            val ofDirection = all.filter { it.direction == vm.direction }
            val counts = TransferCategory.entries.associateWith { c -> ofDirection.count { c.matches(it) } }
            val filtered = TransferGrouping.filter(ofDirection, vm.category, vm.query)
            val rows = TransferGrouping.rows(filtered, vm.groupMode, vm.sort) { key -> key !in vm.collapsed }
            TransferView(rows, counts, ofDirection.size, ofDirection.count { it.state.isActive })
        }
    }

    CompactTopBar(
        title = res.getString(R.string.nav_transfers),
        actions = listOf(
            TopBarAction(DeskIcons.Search, res.getString(R.string.transfers_filter_hint), {
                searchOpen = !searchOpen
                if (!searchOpen) vm.query = ""
            }, primary = searchOpen || vm.query.isNotEmpty()),
            TopBarAction(DeskIcons.Sort, res.getString(R.string.compact_view_options), { viewOptionsOpen = true }),
            TopBarAction(DeskIcons.Check, res.getString(R.string.action_select), {
                vm.selectMode = !vm.selectMode
                if (!vm.selectMode) vm.selected = emptySet()
            }),
            TopBarAction(
                DeskIcons.Delete,
                res.getString(R.string.transfers_clear_finished),
                { vm.clearFinished() },
                enabled = view.counts[TransferCategory.FINISHED].orZero() > 0,
            ),
        ),
        showSearch = false,
    )

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs),
            verticalArrangement = Arrangement.spacedBy(dimens.gapXs),
        ) {
            SegmentedChoice(
                options = listOf(
                    TransferDirection.DOWNLOAD to res.getString(R.string.transfers_downloads),
                    TransferDirection.UPLOAD to res.getString(R.string.transfers_uploads),
                ),
                selected = vm.direction,
                onSelect = { vm.changeDirection(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (searchOpen) {
                FilterTextField(vm.query, { vm.query = it }, stringResource(R.string.transfers_filter_hint), Modifier.fillMaxWidth())
            }
        }
        ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(bottom = dimens.gapXs)) {
            for (category in TransferCategory.entries) {
                val count = view.counts[category].orZero()
                ToggleChip(
                    label = res.getString(categoryLabel(category)) + if (category != TransferCategory.ALL && count > 0) " $count" else "",
                    selected = vm.category == category,
                    onClick = { vm.category = category },
                )
            }
        }
        HorizontalRule()

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
            CompactTransferList(vm, container, actions, view.rows, Modifier.weight(1f))
        }

        if (vm.selectMode || vm.selected.isNotEmpty()) {
            val items = vm.resolve(vm.selected)
            CompactSelectionBar(
                countText = res.getString(R.string.label_selected_count, vm.selected.size),
                closeLabel = res.getString(R.string.action_close),
                onClose = { vm.selected = emptySet(); vm.selectMode = false },
            ) {
                DeskIconButton(DeskIcons.Pause, res.getString(R.string.transfers_pause), { vm.pause(items) }, enabled = items.any(TransfersViewModel::canPause))
                DeskIconButton(DeskIcons.Resume, res.getString(R.string.transfers_resume), { vm.resume(items) }, enabled = items.any(TransfersViewModel::canResume))
                DeskIconButton(DeskIcons.Close, res.getString(R.string.transfers_cancel), { vm.cancel(items, false) }, enabled = items.any(TransfersViewModel::canCancel))
                DeskIconButton(DeskIcons.Delete, res.getString(R.string.transfers_remove), { vm.remove(items); vm.selected = emptySet() }, enabled = items.isNotEmpty())
            }
        }
    }

    if (viewOptionsOpen) {
        DeskFormDialog(
            title = res.getString(R.string.compact_view_options),
            confirmLabel = res.getString(R.string.action_close),
            dismissLabel = null,
            onConfirm = { viewOptionsOpen = false },
            onDismiss = { viewOptionsOpen = false },
        ) {
            SectionCaption(res.getString(R.string.compact_group_by))
            SegmentedChoice(
                options = listOf(
                    TransferGroupMode.NONE to res.getString(R.string.transfers_group_none),
                    TransferGroupMode.USER to res.getString(R.string.transfers_group_user),
                    TransferGroupMode.FOLDER to res.getString(R.string.transfers_group_folder),
                ),
                selected = vm.groupMode,
                onSelect = { vm.changeGroupMode(it) },
            )
        }
    }
}

@Composable
private fun CompactTransferList(vm: TransfersViewModel, container: AppContainer, actions: AppActions, rows: List<TransferRow>, modifier: Modifier) {
    val res = appResources()
    val context = LocalContext.current
    val ua = rememberUserActions(container, actions)

    fun toggle(row: TransferRow) {
        vm.selected = if (row.key in vm.selected) vm.selected - row.key else vm.selected + row.key
    }

    LazyColumn(modifier.fillMaxWidth()) {
        items(rows, key = { it.key }) { row ->
            val menu = { buildTransferMenu(row, vm, res, context, actions, ua) }
            when (row) {
                is TransferGroupHeader -> GroupHeader(
                    row = row,
                    selected = row.key in vm.selected,
                    selectionMode = vm.selectMode,
                    onClick = { if (vm.selectMode) toggle(row) else vm.toggleGroup(row.key) },
                    menu = menu,
                )
                is TransferItemRow -> TransferItem(
                    row = row,
                    showUser = vm.groupMode != TransferGroupMode.USER,
                    selected = row.key in vm.selected,
                    selectionMode = vm.selectMode,
                    onClick = { toggle(row) },
                    onLongClick = {
                        if (!vm.selectMode) vm.selectMode = true
                        toggle(row)
                    },
                    menu = menu,
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(
    row: TransferGroupHeader,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    menu: () -> List<desktopkit.components.MenuEntry>,
) {
    val res = appResources()
    val colors = DeskTheme.colors
    val title = if (row.mode == TransferGroupMode.FOLDER) row.title.substringAfterLast('\\') else row.title
    val subtitle = buildList {
        if (row.mode == TransferGroupMode.FOLDER) add(row.username)
        add(res.getString(R.string.transfers_group_progress, row.finished, row.count))
        if (row.active > 0) add(res.getString(R.string.transfers_n_active, row.active))
        if (row.failed > 0) add(res.getString(R.string.transfers_n_failed, row.failed))
        if (row.speed > 0) add(Formatting.speed(row.speed))
    }.joinToString("  ·  ")
    CompactListRow(
        title = title,
        subtitle = subtitle,
        leading = if (row.expanded) DeskIcons.ChevronDown else DeskIcons.ChevronRight,
        trailing = Formatting.percent(row.progress),
        trailingSub = Formatting.size(row.totalBytes),
        strong = true,
        selected = selected,
        selectionMode = selectionMode,
        progress = if (row.finished < row.count && row.transferredBytes > 0) row.progress else null,
        progressColor = if (row.failed > 0) colors.warning else colors.accent,
        onClick = onClick,
        menu = menu,
    )
}

@Composable
private fun TransferItem(
    row: TransferItemRow,
    showUser: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: () -> List<desktopkit.components.MenuEntry>,
) {
    val res = appResources()
    val colors = DeskTheme.colors
    val t = row.transfer
    val (icon, tint) = stateIcon(t)
    val subtitle = buildList {
        if (showUser) add(t.username)
        add(res.transferStateText(t))
        if (t.state == TransferState.TRANSFERRING) {
            if (t.speedBytesPerSecond > 0) add(Formatting.speed(t.speedBytesPerSecond))
            t.etaSeconds?.let { add(Formatting.eta(it)) }
        }
    }.joinToString("  ·  ")
    val partial = t.state != TransferState.FINISHED && t.bytesTransferred > 0
    CompactListRow(
        title = t.fileName,
        subtitle = subtitle,
        subtitleColor = when (t.state) {
            TransferState.FAILED, TransferState.DENIED -> colors.danger
            TransferState.USER_OFFLINE -> colors.warning
            else -> colors.textTertiary
        },
        leading = icon,
        leadingTint = tint,
        trailing = when {
            t.state == TransferState.FINISHED -> "100%"
            partial -> Formatting.percent(t.progress)
            else -> null
        },
        trailingSub = if (t.size > 0) Formatting.size(t.size) else null,
        indent = if (row.depth > 0) 28.dp else 0.dp,
        selected = selected,
        selectionMode = selectionMode,
        progress = if (partial || t.state == TransferState.TRANSFERRING) t.progress else null,
        progressColor = if (t.state == TransferState.PAUSED) colors.textTertiary else colors.accent,
        openMenuOnTap = true,
        onClick = onClick,
        onLongClick = onLongClick,
        menu = menu,
    )
}

@Composable
private fun stateIcon(t: Transfer): Pair<ImageVector, Color> {
    val colors = DeskTheme.colors
    return when (t.state) {
        TransferState.FINISHED -> DeskIcons.Check to colors.success
        TransferState.TRANSFERRING -> (if (t.direction == TransferDirection.DOWNLOAD) SoulIcons.Download else SoulIcons.Upload) to colors.accentText
        TransferState.CONNECTING -> SoulIcons.P2pConnecting to colors.accentText
        TransferState.QUEUED, TransferState.QUEUED_REMOTE -> DeskIcons.Queue to colors.textSecondary
        TransferState.PAUSED -> DeskIcons.Pause to colors.textSecondary
        TransferState.USER_OFFLINE -> SoulIcons.P2pOffline to colors.warning
        TransferState.FAILED, TransferState.DENIED -> SoulIcons.Stop to colors.danger
        TransferState.CANCELLED -> DeskIcons.Close to colors.textTertiary
    }
}

