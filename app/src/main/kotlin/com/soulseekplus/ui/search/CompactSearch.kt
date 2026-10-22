package com.soulseekplus.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.SearchFilter
import com.soulseekplus.core.model.SearchTabState
import com.soulseekplus.core.search.FileResultRow
import com.soulseekplus.core.search.GroupHeaderRow
import com.soulseekplus.core.search.GroupKind
import com.soulseekplus.core.search.QuickFilter
import com.soulseekplus.core.search.ResultColumn
import com.soulseekplus.core.search.ResultRow
import com.soulseekplus.core.search.ResultSort
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.IconMenuButton
import com.soulseekplus.ui.ScrollRow
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.TabChip
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.UserActions
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.compact.CompactCaption
import com.soulseekplus.ui.compact.CompactListRow
import com.soulseekplus.ui.compact.CompactSelectionBar
import com.soulseekplus.ui.groupModeText
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.qualityText
import com.soulseekplus.ui.scopeText
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.components.MenuEntry
import desktopkit.components.SectionCaption
import desktopkit.components.SegmentedChoice
import desktopkit.screens.MessageScreen
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.theme.DeskTheme

/*
 * Telefon düzeni (WidthClass.Compact). Masaüstü düzeninden farkları:
 *  - Başlık satırı yok: filtre, yeniden arama, görünüm ve seçim üst çubuğa (CompactTopBar) taşınır.
 *  - Kapsam seçici sorgu alanının solunda tek simge; arama düğmesi simge.
 *  - Hızlı süzgeç çipleri ve gelişmiş süzgeçler yalnızca filtre panelinde (üst çubuktaki filtre simgesi).
 *  - Sonuçlar tablo yerine iki satırlı liste: dokununca eylem menüsü, uzun basınca seçim.
 */

private val SORT_CHOICES = listOf(
    ResultColumn.SPEED to R.string.col_speed,
    ResultColumn.QUEUE to R.string.col_slots,
    ResultColumn.SIZE to R.string.col_size,
    ResultColumn.BITRATE to R.string.col_quality,
    ResultColumn.FILE to R.string.col_file,
    ResultColumn.USER to R.string.col_user,
)

@Composable
internal fun CompactSearchScreen(vm: SearchViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val tabs by vm.repo.tabs.collectAsStateWithLifecycle()
    val selectedId by vm.repo.selectedTabId.collectAsStateWithLifecycle()
    val connection by container.client.connection.collectAsStateWithLifecycle()
    val prefs by container.settings.appPrefs.collectAsStateWithLifecycle()
    val tab = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()
    var viewOptionsOpen by rememberSaveable { mutableStateOf(false) }

    val topActions = if (tab == null) {
        emptyList()
    } else {
        val ui = vm.tabUi(tab.id)
        val activeFilters = activeFilterCount(tab.filter)
        listOfNotNull(
            TopBarAction(
                SoulIcons.Filter,
                if (activeFilters > 0) res.getString(R.string.compact_filters_n, activeFilters) else res.getString(R.string.search_filters),
                { vm.filtersOpen = !vm.filtersOpen },
                primary = vm.filtersOpen || activeFilters > 0,
            ),
            TopBarAction(SoulIcons.Retry, res.getString(R.string.search_rerun), { vm.rerunSelectedTab() }),
            TopBarAction(DeskIcons.Sort, res.getString(R.string.compact_view_options), { viewOptionsOpen = true }),
            TopBarAction(DeskIcons.Check, res.getString(R.string.action_select), {
                vm.setSelectMode(tab.id, !ui.selectMode)
                if (ui.selectMode) vm.clearSelection(tab.id)
            }),
            if (tabs.size > 1) {
                TopBarAction(DeskIcons.Delete, res.getString(R.string.search_close_all), {
                    tabs.forEach { vm.onTabClosed(it.id) }
                    vm.repo.closeAllTabs()
                })
            } else {
                null
            },
        )
    }
    CompactTopBar(title = res.getString(R.string.nav_search), actions = topActions, showSearch = false)

    Column(modifier.fillMaxSize()) {
        CompactQueryRow(vm, container, actions)
        if (connection !is ConnectionState.LoggedIn) {
            StatusBanner(
                tone = BannerTone.Warning,
                text = stringResource(R.string.search_not_connected),
                modifier = Modifier.padding(horizontal = DeskTheme.dimens.pagePadding, vertical = DeskTheme.dimens.gapXs),
                actionLabel = if (connection is ConnectionState.Disconnected) stringResource(R.string.action_connect) else null,
                onAction = { container.client.connect(); container.ensureServiceRunning() },
            )
        }
        if (tab == null) {
            MessageScreen(
                title = stringResource(R.string.search_empty_title),
                icon = DeskIcons.Search,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.search_empty_desc),
            )
            return@Column
        }
        val ui = vm.tabUi(tab.id)
        CompactTabRow(tabs, tab.id, vm)
        CompactCaption(
            res.getString(R.string.search_summary, tab.visibleHits.size, tab.allHits.size, tab.userCount) +
                "  ·  " + res.groupModeText(tab.groupMode),
        )
        if (vm.filtersOpen) {
            CompactFilterPanel(tab, ui, vm)
        }
        if (tab.active) DeskProgressLine()
        HorizontalRule()

        when {
            tab.visibleHits.isEmpty() && tab.active -> MessageScreen(
                title = res.getString(R.string.search_searching),
                icon = DeskIcons.Search,
                modifier = Modifier.weight(1f),
                description = res.getString(R.string.search_searching_desc, res.scopeText(tab.scope)),
            )
            tab.visibleHits.isEmpty() && tab.allHits.isNotEmpty() -> MessageScreen(
                title = res.getString(R.string.search_all_hidden_title),
                icon = SoulIcons.Filter,
                modifier = Modifier.weight(1f),
                description = res.getString(R.string.search_all_hidden, tab.allHits.size),
                actionLabel = res.getString(R.string.search_clear_filters),
                onAction = { vm.clearFilter(tab.id) },
            )
            tab.visibleHits.isEmpty() -> MessageScreen(
                title = res.getString(R.string.search_no_results),
                icon = DeskIcons.Search,
                modifier = Modifier.weight(1f),
                description = res.getString(R.string.search_no_results_desc),
                actionLabel = res.getString(R.string.search_rerun),
                onAction = { vm.rerunSelectedTab() },
            )
            else -> CompactResultList(tab, ui, prefs.expandGroups, vm, container, actions, Modifier.weight(1f))
        }

        if (ui.selectMode || ui.selected.isNotEmpty()) {
            CompactSelectionBar(
                countText = res.getString(R.string.label_selected_count, ui.selected.size),
                closeLabel = res.getString(R.string.action_close),
                onClose = { vm.clearSelection(tab.id); vm.setSelectMode(tab.id, false) },
            ) {
                DeskButton(
                    res.getString(R.string.action_download),
                    { vm.download(vm.resolveSelection(tab, ui.selected), actions); vm.clearSelection(tab.id); vm.setSelectMode(tab.id, false) },
                    icon = SoulIcons.Download,
                    primary = true,
                    enabled = ui.selected.isNotEmpty(),
                )
            }
        }
    }

    if (viewOptionsOpen && tab != null) {
        ViewOptionsDialog(tab, vm, onDismiss = { viewOptionsOpen = false })
    }
}

private fun activeFilterCount(f: SearchFilter): Int = listOf(
    f.include.isNotBlank(), f.exclude.isNotBlank(), f.minBitrate != null, f.losslessOnly, f.minSampleRate != null,
    f.minBitDepth != null, f.minSizeBytes != null, f.maxSizeBytes != null, f.extensions.isNotBlank(), f.countries.isNotBlank(),
    f.freeSlotsOnly, f.maxQueueLength != null, f.minSpeedBytes != null,
).count { it }

// ---------------------------------------------------------------------- sorgu satırı

@Composable
private fun CompactQueryRow(vm: SearchViewModel, container: AppContainer, actions: AppActions) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val recent by vm.repo.recentQueries.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusToken) { if (vm.focusToken > 0) focus.requestFocus() }

    Column(
        Modifier.fillMaxWidth().padding(start = dimens.gapS, end = dimens.pagePadding, top = dimens.gapXs, bottom = dimens.gapXs),
        verticalArrangement = Arrangement.spacedBy(dimens.gapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
            IconMenuButton(
                icon = scopeIcon(vm.scope),
                label = res.getString(R.string.compact_scope, scopeLabel(res, vm.scope)),
                entries = {
                    ScopeChoice.entries.map { choice ->
                        MenuEntry.Item(scopeLabel(res, choice), { vm.scope = choice }, icon = scopeIcon(choice), checked = vm.scope == choice)
                    }
                },
            )
            DeskTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                hint = stringResource(R.string.search_hint_query),
                imeAction = ImeAction.Search,
                onImeAction = { vm.submit(actions) },
                trailing = if (recent.isNotEmpty()) {
                    {
                        IconMenuButton(
                            icon = DeskIcons.History,
                            label = res.getString(R.string.search_history),
                            entries = { recent.take(15).map { q -> MenuEntry.Item(q, { vm.query = q; vm.submit(actions) }) } },
                        )
                    }
                } else {
                    null
                },
            )
            DeskIconButton(DeskIcons.Search, stringResource(R.string.action_search), { vm.submit(actions) }, enabled = vm.query.isNotBlank(), selected = vm.query.isNotBlank())
        }
        if (vm.scope == ScopeChoice.ROOM || vm.scope == ScopeChoice.USER) {
            val joined = container.chat.rooms.value.keys
            DeskTextField(
                value = vm.scopeArg,
                onValueChange = { vm.scopeArg = it },
                modifier = Modifier.fillMaxWidth().padding(start = dimens.gapS),
                hint = stringResource(if (vm.scope == ScopeChoice.ROOM) R.string.search_hint_room else R.string.search_hint_user),
                leading = scopeIcon(vm.scope),
                trailing = if (vm.scope == ScopeChoice.ROOM && joined.isNotEmpty()) {
                    {
                        IconMenuButton(
                            icon = DeskIcons.ChevronDown,
                            label = res.getString(R.string.search_pick_room),
                            entries = { joined.map { room -> MenuEntry.Item(room, { vm.scopeArg = room }) } },
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

private fun scopeIcon(choice: ScopeChoice) = when (choice) {
    ScopeChoice.NETWORK -> SoulIcons.Globe
    ScopeChoice.BUDDIES -> SoulIcons.Buddy
    ScopeChoice.ROOM -> SoulIcons.ChatRoom
    ScopeChoice.USER -> SoulIcons.User
}

private fun scopeLabel(res: android.content.res.Resources, choice: ScopeChoice) = when (choice) {
    ScopeChoice.NETWORK -> res.getString(R.string.scope_network)
    ScopeChoice.BUDDIES -> res.getString(R.string.scope_buddies)
    ScopeChoice.ROOM -> res.getString(R.string.scope_choice_room)
    ScopeChoice.USER -> res.getString(R.string.scope_choice_user)
}

// ---------------------------------------------------------------------- sekmeler

@Composable
private fun CompactTabRow(tabs: List<SearchTabState>, selectedId: Int, vm: SearchViewModel) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.gapS)) {
        for (t in tabs) {
            TabChip(
                label = t.query,
                selected = t.id == selectedId,
                onClick = { vm.repo.selectTab(t.id) },
                busy = t.active,
                closeLabel = res.getString(R.string.search_close_tab),
                onClose = { vm.repo.closeTab(t.id); vm.onTabClosed(t.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------- filtre paneli

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactFilterPanel(tab: SearchTabState, ui: TabUi, vm: SearchViewModel) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    // Panel ekranın en çok yarısını kaplar; fazlası kendi içinde kayar, liste her zaman görünür kalır.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        HorizontalRule()
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            ToggleChip(res.getString(R.string.chip_free_slots), QuickFilter.isActive(tab.filter, QuickFilter.FREE_SLOTS), { vm.toggleQuick(tab.id, QuickFilter.FREE_SLOTS) }, icon = SoulIcons.SlotFree)
            ToggleChip(res.getString(R.string.chip_hide_locked), QuickFilter.isActive(tab.filter, QuickFilter.HIDE_LOCKED), { vm.toggleQuick(tab.id, QuickFilter.HIDE_LOCKED) }, icon = SoulIcons.LockClosed)
            ToggleChip(res.getString(R.string.chip_lossless), QuickFilter.isActive(tab.filter, QuickFilter.LOSSLESS), { vm.toggleQuick(tab.id, QuickFilter.LOSSLESS) }, icon = SoulIcons.Lossless)
            ToggleChip(res.getString(R.string.chip_min_320), QuickFilter.isActive(tab.filter, QuickFilter.MIN_320), { vm.toggleQuick(tab.id, QuickFilter.MIN_320) }, icon = DeskIcons.Equalizer)
        }
        FilterBar(tab, ui.filterReset, vm)
        Row(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs), horizontalArrangement = Arrangement.End) {
            DeskButton(res.getString(R.string.action_close), { vm.filtersOpen = false })
        }
    }
}

// ---------------------------------------------------------------------- görünüm seçenekleri

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewOptionsDialog(tab: SearchTabState, vm: SearchViewModel, onDismiss: () -> Unit) {
    val res = appResources()
    val ui = vm.tabUi(tab.id)
    DeskFormDialog(
        title = res.getString(R.string.compact_view_options),
        confirmLabel = res.getString(R.string.action_close),
        dismissLabel = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        SectionCaption(res.getString(R.string.compact_group_by))
        SegmentedChoice(
            options = GroupMode.entries.map { it to res.groupModeText(it) },
            selected = tab.groupMode,
            onSelect = { vm.setGroupMode(tab.id, it) },
        )
        SectionCaption(res.getString(R.string.compact_sort_by))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS), verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
            for ((column, label) in SORT_CHOICES) {
                val selected = ui.sort.column == column
                ToggleChip(
                    label = res.getString(label) + if (selected) (if (ui.sort.ascending) " ↑" else " ↓") else "",
                    selected = selected,
                    onClick = {
                        // Aynı sütuna yeniden dokunmak yönü çevirir; yeni sütun azalan (en iyi önce) başlar.
                        val next = if (selected) ui.sort.copy(ascending = !ui.sort.ascending) else ResultSort(column, ascending = column == ResultColumn.FILE || column == ResultColumn.USER || column == ResultColumn.QUEUE)
                        vm.setSort(tab.id, next)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- sonuç listesi

@Composable
private fun CompactResultList(
    tab: SearchTabState,
    ui: TabUi,
    expandByDefault: Boolean,
    vm: SearchViewModel,
    container: AppContainer,
    actions: AppActions,
    modifier: Modifier,
) {
    val res = appResources()
    val userActions = rememberUserActions(container, actions)
    val rows = rememberRows(tab, ui, expandByDefault)
    val listState = rememberLazyListState()
    val tabId = tab.id

    fun toggleSelected(row: ResultRow) {
        vm.setSelection(tabId, if (row.key in ui.selected) ui.selected - row.key else ui.selected + row.key)
    }

    fun startSelection(row: ResultRow) {
        vm.setSelectMode(tabId, true)
        vm.setSelection(tabId, ui.selected + row.key)
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(rows, key = { it.key }) { row ->
            val menu = { buildMenu(row, ui, tab, vm, actions, userActions, res, expandByDefault) }
            when (row) {
                is GroupHeaderRow -> GroupRow(
                    row = row,
                    selected = row.key in ui.selected,
                    selectionMode = ui.selectMode,
                    onClick = { if (ui.selectMode) toggleSelected(row) else vm.toggleGroup(tabId, row.key) },
                    onLongClick = null,
                    menu = menu,
                )
                is FileResultRow -> FileRow(
                    row = row,
                    mode = tab.groupMode,
                    selected = row.key in ui.selected,
                    selectionMode = ui.selectMode,
                    onClick = { toggleSelected(row) },
                    onLongClick = { if (ui.selectMode) toggleSelected(row) else startSelection(row) },
                    menu = menu,
                )
            }
        }
    }
    // Yeni sekmeye geçince listenin başına dön.
    LaunchedEffect(tabId) { if (rows.isNotEmpty()) listState.scrollToItem(0) }
}

@Composable
private fun slotText(free: Boolean, queue: Long): Pair<String, androidx.compose.ui.graphics.Color> {
    val res = appResources()
    val colors = DeskTheme.colors
    return if (free) res.getString(R.string.search_slot_free) to colors.success else res.getString(R.string.search_slot_queue, queue) to colors.warning
}

@Composable
private fun GroupRow(
    row: GroupHeaderRow,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    menu: () -> List<MenuEntry>,
) {
    val res = appResources()
    val colors = DeskTheme.colors
    val (slot, slotColor) = slotText(row.freeSlots, row.queueLength)
    val flag = row.countryCode?.takeIf { it.isNotBlank() }?.let { Formatting.flag(it) + " " }.orEmpty()
    val allLocked = row.lockedCount > 0 && row.lockedCount == row.fileCount
    val title: String
    val subtitle: String
    if (row.kind == GroupKind.FOLDER) {
        val path = row.folderPath ?: row.title
        val leaf = path.substringAfterLast('\\')
        val parent = path.substringBeforeLast('\\', "").substringAfterLast('\\')
        title = leaf
        subtitle = buildList {
            add(flag + row.username)
            if (parent.isNotEmpty()) add(parent)
            add(res.getString(R.string.label_files_count, row.fileCount))
            qualitySummary(row, res)?.let { add(it) }
        }.joinToString("  ·  ")
    } else {
        title = flag + row.title
        subtitle = buildList {
            add(res.getString(R.string.label_files_count, row.fileCount))
            add(Formatting.speed(row.averageSpeed.toLong()))
            qualitySummary(row, res)?.let { add(it) }
        }.joinToString("  ·  ")
    }
    CompactListRow(
        title = title,
        subtitle = subtitle,
        leading = when {
            allLocked -> SoulIcons.LockClosed
            row.expanded -> DeskIcons.ChevronDown
            else -> DeskIcons.ChevronRight
        },
        leadingTint = if (allLocked) colors.warning else colors.textSecondary,
        trailing = Formatting.size(row.totalSize),
        trailingSub = slot,
        trailingSubColor = slotColor,
        strong = true,
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        menu = menu,
    )
}

private fun qualitySummary(row: GroupHeaderRow, res: android.content.res.Resources): String? {
    val best = row.bestBitrate
    return when {
        row.hasLossless -> "◆ " + res.getString(R.string.chip_lossless)
        best != null -> "≤ $best kbps"
        else -> null
    }
}

@Composable
private fun FileRow(
    row: FileResultRow,
    mode: GroupMode,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: () -> List<MenuEntry>,
) {
    val colors = DeskTheme.colors
    val hit = row.hit
    val file = hit.file
    val a = file.attributes
    val quality = qualityText(file.extension, a.bitrate, a.sampleRate, a.bitDepth, a.vbr)
    val duration = a.durationSeconds?.let { Formatting.duration(it) }
    val flag = hit.countryCode?.takeIf { it.isNotBlank() }?.let { Formatting.flag(it) + " " }.orEmpty()
    val subtitle = buildList {
        if (mode == GroupMode.FLAT) add(flag + hit.username)
        if (mode == GroupMode.USER) file.folder.substringAfterLast('\\').takeIf { it.isNotEmpty() }?.let { add(it) }
        if (quality.isNotBlank()) add(quality)
        if (duration != null && duration != "-") add(duration)
    }.joinToString("  ·  ")
    val (slot, slotColor) = slotText(hit.freeSlots, hit.queueLength)
    CompactListRow(
        title = file.name,
        subtitle = subtitle,
        leading = when {
            hit.locked -> SoulIcons.LockClosed
            file.isLossless -> SoulIcons.Lossless
            else -> DeskIcons.Music
        },
        leadingTint = when {
            hit.locked -> colors.warning
            file.isLossless -> colors.accentText
            else -> colors.textTertiary
        },
        trailing = Formatting.size(file.size),
        trailingSub = if (mode == GroupMode.FLAT) slot else null,
        trailingSubColor = slotColor,
        indent = if (row.depth > 0) 28.dp else 0.dp,
        selected = selected,
        selectionMode = selectionMode,
        openMenuOnTap = true,
        onClick = onClick,
        onLongClick = onLongClick,
        menu = menu,
    )
}
