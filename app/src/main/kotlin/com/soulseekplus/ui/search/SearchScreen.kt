package com.soulseekplus.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SearchTabState
import com.soulseekplus.core.search.FilterField
import com.soulseekplus.core.search.FileResultRow
import com.soulseekplus.core.search.GroupHeaderRow
import com.soulseekplus.core.search.GroupKind
import com.soulseekplus.core.search.QuickFilter
import com.soulseekplus.core.search.ResultColumn
import com.soulseekplus.core.search.ResultRow
import com.soulseekplus.core.search.ResultSort
import com.soulseekplus.core.search.SearchFilterEngine
import com.soulseekplus.core.search.SearchGrouping
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.AppShortcuts
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.IconMenuButton
import com.soulseekplus.ui.MenuButton
import com.soulseekplus.ui.OptionalNumberField
import com.soulseekplus.ui.ScrollRow
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.TabChip
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.UserActions
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.groupModeText
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.scopeText
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MIB = 1024L * 1024L

/** Arama ekranı: sorgu çubuğu, sekmeler, hızlı çipler, gelişmiş süzgeç, gruplu sonuç tablosu. */
@Composable
fun SearchScreen(vm: SearchViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val tabs by vm.repo.tabs.collectAsStateWithLifecycle()
    val selectedId by vm.repo.selectedTabId.collectAsStateWithLifecycle()
    val connection by container.client.connection.collectAsStateWithLifecycle()
    val tab = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_search),
            subtitle = tab?.let { res.getString(R.string.search_summary, it.visibleHits.size, it.allHits.size, it.userCount) },
            actions = {
                if (tab != null) {
                    DeskIconButton(
                        SoulIcons.Filter,
                        stringResource(R.string.search_filters),
                        { vm.filtersOpen = !vm.filtersOpen },
                        selected = vm.filtersOpen,
                    )
                    DeskIconButton(SoulIcons.Retry, stringResource(R.string.search_rerun), { vm.rerunSelectedTab() })
                }
            },
        )
        QueryBar(vm, container, actions)
        if (connection !is ConnectionState.LoggedIn) {
            StatusBanner(
                tone = BannerTone.Warning,
                text = stringResource(R.string.search_not_connected),
                modifier = Modifier.padding(horizontal = DeskTheme.dimens.pagePadding, vertical = DeskTheme.dimens.gapS),
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
        } else {
            TabStrip(tabs, tab.id, vm)
            TabContent(tab, vm, container, actions, Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------- sorgu çubuğu

@Composable
private fun QueryBar(vm: SearchViewModel, container: AppContainer, actions: AppActions) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val compact = currentWidthClass() == WidthClass.Compact
    val recent by vm.repo.recentQueries.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusToken) { if (vm.focusToken > 0) focus.requestFocus() }

    val queryField: @Composable (Modifier) -> Unit = { m ->
        DeskTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = m.focusRequester(focus),
            hint = stringResource(R.string.search_hint_query),
            leading = DeskIcons.Search,
            imeAction = ImeAction.Search,
            onImeAction = { vm.submit(actions) },
            trailing = {
                IconMenuButton(
                    icon = DeskIcons.History,
                    label = res.getString(R.string.search_history),
                    enabled = recent.isNotEmpty(),
                    entries = {
                        recent.take(15).map { q ->
                            MenuEntry.Item(q, { vm.query = q; vm.submit(actions) })
                        }
                    },
                )
            },
        )
    }
    val scopeControls: @Composable () -> Unit = {
        MenuButton(
            text = when (vm.scope) {
                ScopeChoice.NETWORK -> res.getString(R.string.scope_network)
                ScopeChoice.BUDDIES -> res.getString(R.string.scope_buddies)
                ScopeChoice.ROOM -> res.getString(R.string.scope_choice_room)
                ScopeChoice.USER -> res.getString(R.string.scope_choice_user)
            },
            icon = when (vm.scope) {
                ScopeChoice.NETWORK -> SoulIcons.Globe
                ScopeChoice.BUDDIES -> SoulIcons.Buddy
                ScopeChoice.ROOM -> SoulIcons.ChatRoom
                ScopeChoice.USER -> SoulIcons.User
            },
            entries = {
                listOf(
                    MenuEntry.Item(res.getString(R.string.scope_network), { vm.scope = ScopeChoice.NETWORK }, icon = SoulIcons.Globe, checked = vm.scope == ScopeChoice.NETWORK),
                    MenuEntry.Item(res.getString(R.string.scope_buddies), { vm.scope = ScopeChoice.BUDDIES }, icon = SoulIcons.Buddy, checked = vm.scope == ScopeChoice.BUDDIES),
                    MenuEntry.Item(res.getString(R.string.scope_choice_room), { vm.scope = ScopeChoice.ROOM }, icon = SoulIcons.ChatRoom, checked = vm.scope == ScopeChoice.ROOM),
                    MenuEntry.Item(res.getString(R.string.scope_choice_user), { vm.scope = ScopeChoice.USER }, icon = SoulIcons.User, checked = vm.scope == ScopeChoice.USER),
                )
            },
        )
        if (vm.scope == ScopeChoice.ROOM || vm.scope == ScopeChoice.USER) {
            val joined = container.chat.rooms.value.keys
            DeskTextField(
                value = vm.scopeArg,
                onValueChange = { vm.scopeArg = it },
                modifier = Modifier.width(160.dp),
                hint = stringResource(if (vm.scope == ScopeChoice.ROOM) R.string.search_hint_room else R.string.search_hint_user),
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

    if (compact) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
                queryField(Modifier.weight(1f))
                DeskButton(stringResource(R.string.action_search), { vm.submit(actions) }, primary = true, enabled = vm.query.isNotBlank())
            }
            ScrollRow { scopeControls() }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            scopeControls()
            queryField(Modifier.weight(1f))
            DeskButton(stringResource(R.string.action_search), { vm.submit(actions) }, primary = true, icon = DeskIcons.Search, enabled = vm.query.isNotBlank())
        }
    }
}

// ---------------------------------------------------------------------- sekmeler

@Composable
private fun TabStrip(tabs: List<SearchTabState>, selectedId: Int, vm: SearchViewModel) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS)) {
        for (t in tabs) {
            TabChip(
                label = t.query,
                selected = t.id == selectedId,
                onClick = { vm.repo.selectTab(t.id) },
                busy = t.active,
                badge = 0,
                closeLabel = res.getString(R.string.search_close_tab),
                onClose = { vm.repo.closeTab(t.id); vm.onTabClosed(t.id) },
            )
        }
        if (tabs.size > 1) {
            DeskIconButton(DeskIcons.Delete, stringResource(R.string.search_close_all), {
                tabs.forEach { vm.onTabClosed(it.id) }
                vm.repo.closeAllTabs()
            })
        }
    }
}

// ---------------------------------------------------------------------- sekme içeriği

@Composable
private fun TabContent(tab: SearchTabState, vm: SearchViewModel, container: AppContainer, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val ui = vm.tabUi(tab.id)
    val prefs by container.settings.appPrefs.collectAsStateWithLifecycle()
    val userActions = rememberUserActions(container, actions)

    Column(modifier.fillMaxSize()) {
        // Hızlı süzgeçler ve gruplama
        ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs)) {
            ToggleChip(res.getString(R.string.chip_free_slots), QuickFilter.isActive(tab.filter, QuickFilter.FREE_SLOTS), { vm.toggleQuick(tab.id, QuickFilter.FREE_SLOTS) }, icon = SoulIcons.SlotFree)
            ToggleChip(res.getString(R.string.chip_hide_locked), QuickFilter.isActive(tab.filter, QuickFilter.HIDE_LOCKED), { vm.toggleQuick(tab.id, QuickFilter.HIDE_LOCKED) }, icon = SoulIcons.LockClosed)
            ToggleChip(res.getString(R.string.chip_lossless), QuickFilter.isActive(tab.filter, QuickFilter.LOSSLESS), { vm.toggleQuick(tab.id, QuickFilter.LOSSLESS) }, icon = SoulIcons.Lossless)
            ToggleChip(res.getString(R.string.chip_min_320), QuickFilter.isActive(tab.filter, QuickFilter.MIN_320), { vm.toggleQuick(tab.id, QuickFilter.MIN_320) }, icon = DeskIcons.Equalizer)
            SegmentedChoice(
                options = GroupMode.entries.map { it to res.groupModeText(it) },
                selected = tab.groupMode,
                onSelect = { vm.setGroupMode(tab.id, it) },
            )
            ToggleChip(res.getString(R.string.action_select), ui.selectMode, { vm.setSelectMode(tab.id, !ui.selectMode) }, icon = DeskIcons.Check)
        }
        if (vm.filtersOpen) {
            FilterBar(tab, ui.filterReset, vm)
            HorizontalRule()
        }
        if (tab.active) DeskProgressLine()

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
            else -> ResultTable(tab, ui, prefs.expandGroups, vm, actions, userActions, Modifier.weight(1f))
        }

        if (ui.selected.isNotEmpty()) SelectionBar(tab, ui, vm, actions)
    }
}

@Composable
private fun DeskProgressLine() {
    com.soulseekplus.ui.DeskProgressBar(null, Modifier.fillMaxWidth(), height = 2.dp)
}

// ---------------------------------------------------------------------- süzgeç çubuğu

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(tab: SearchTabState, reset: Int, vm: SearchViewModel) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val filter = tab.filter
    val issues = remember(filter) { SearchFilterEngine.compile(filter).issues }
    val includeBad = issues.any { it.field == FilterField.INCLUDE }
    val excludeBad = issues.any { it.field == FilterField.EXCLUDE }
    val resetKey = tab.id * 1000 + reset
    val update: ((SearchFilter) -> SearchFilter) -> Unit = { transform -> vm.repo.updateFilter(tab.id, transform) }

    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        DebouncedField(
            initial = filter.include,
            resetKey = resetKey,
            hint = res.getString(if (filter.useRegex) R.string.f_include_regex else R.string.f_include),
            error = includeBad,
            modifier = Modifier.width(220.dp),
        ) { text -> update { it.copy(include = text) } }
        DebouncedField(
            initial = filter.exclude,
            resetKey = resetKey,
            hint = res.getString(if (filter.useRegex) R.string.f_exclude_regex else R.string.f_exclude),
            error = excludeBad,
            modifier = Modifier.width(220.dp),
        ) { text -> update { it.copy(exclude = text) } }
        ToggleChip(res.getString(R.string.f_regex), filter.useRegex, { update { it.copy(useRegex = !it.useRegex) } })
        DebouncedField(filter.extensions, resetKey, res.getString(R.string.f_extensions), false, Modifier.width(190.dp)) { text -> update { it.copy(extensions = text) } }
        DebouncedField(filter.countries, resetKey, res.getString(R.string.f_countries), false, Modifier.width(150.dp)) { text -> update { it.copy(countries = text) } }
        OptionalNumberField(filter.minBitrate?.toLong(), { v -> update { it.copy(minBitrate = v?.toInt()) } }, res.getString(R.string.f_min_bitrate), Modifier.width(110.dp), resetKey)
        OptionalNumberField(filter.minSizeBytes?.div(MIB), { v -> update { it.copy(minSizeBytes = v?.times(MIB)) } }, res.getString(R.string.f_min_size), Modifier.width(110.dp), resetKey)
        OptionalNumberField(filter.maxSizeBytes?.div(MIB), { v -> update { it.copy(maxSizeBytes = v?.times(MIB)) } }, res.getString(R.string.f_max_size), Modifier.width(110.dp), resetKey)
        OptionalNumberField(filter.maxQueueLength, { v -> update { it.copy(maxQueueLength = v) } }, res.getString(R.string.f_max_queue), Modifier.width(110.dp), resetKey)
        OptionalNumberField(filter.minSpeedBytes?.toLong()?.div(1024L), { v -> update { it.copy(minSpeedBytes = v?.times(1024L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()) } }, res.getString(R.string.f_min_speed), Modifier.width(120.dp), resetKey)
        DeskButton(res.getString(R.string.search_clear_filters), { vm.clearFilter(tab.id) }, icon = DeskIcons.Close, enabled = filter != SearchFilter(hideLocked = false))
        if (issues.isNotEmpty()) {
            Text(res.getString(R.string.f_invalid_regex), style = DeskTheme.type.caption, color = DeskTheme.colors.danger, modifier = Modifier.padding(top = dimens.gapS))
        }
    }
}

/** Yazdıkça değil, kısa bir duraksamadan sonra uygulanan süzgeç metin alanı (imleç sıçramasın diye yerel durumlu). */
@Composable
private fun DebouncedField(
    initial: String,
    resetKey: Int,
    hint: String,
    error: Boolean,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(initial) }
    var committed by remember(resetKey) { mutableStateOf(initial) }
    val latest by rememberUpdatedState(onCommit)
    LaunchedEffect(text, resetKey) {
        if (text == committed) return@LaunchedEffect
        delay(250)
        committed = text
        latest(text)
    }
    DeskTextField(value = text, onValueChange = { text = it }, modifier = modifier, hint = hint, error = error)
}

// ---------------------------------------------------------------------- sonuç tablosu

private class Col(
    val column: ResultColumn,
    val title: String,
    val width: ColumnWidth,
    val alignEnd: Boolean = false,
    val mono: Boolean = false,
    val cell: (ResultRow) -> String,
)

@Composable
private fun ResultTable(
    tab: SearchTabState,
    ui: TabUi,
    expandByDefault: Boolean,
    vm: SearchViewModel,
    actions: AppActions,
    userActions: UserActions,
    modifier: Modifier,
) {
    val res = appResources()
    val widthClass = currentWidthClass()
    val rows = rememberRows(tab, ui, expandByDefault)
    val cols = remember(res, widthClass, tab.groupMode) { columnsFor(res, widthClass, tab.groupMode) }
    val tableColumns = remember(cols) {
        cols.map { c -> TableColumn<ResultRow>(c.title, c.width, alignEnd = c.alignEnd, mono = c.mono) { row -> c.cell(row) } }
    }
    val sortIndex = cols.indexOfFirst { it.column == ui.sort.column }
    val tableSort = if (sortIndex >= 0) TableSort(sortIndex, ui.sort.ascending) else null
    val tabId = tab.id

    DenseTable(
        columns = tableColumns,
        rows = rows,
        key = { it.key },
        onRowClick = { row, _ -> if (row is GroupHeaderRow) vm.toggleGroup(tabId, row.key) },
        modifier = modifier.fillMaxWidth(),
        selectedKeys = ui.selected,
        onSelectionChange = { vm.setSelection(tabId, it) },
        toggleOnClick = ui.selectMode,
        sort = tableSort,
        onSortChange = { s -> cols.getOrNull(s.column)?.let { vm.setSort(tabId, ResultSort(it.column, s.ascending)) } },
        rowMenu = { row, _ -> buildMenu(row, ui, tab, vm, actions, userActions, res, expandByDefault) },
        rowMenuLabel = res.getString(R.string.action_more),
    )
}

@Composable
private fun rememberRows(tab: SearchTabState, ui: TabUi, expandByDefault: Boolean): List<ResultRow> {
    val compute: () -> List<ResultRow> = {
        SearchGrouping.rows(tab.visibleHits, tab.groupMode, ui.sort) { key ->
            if (expandByDefault) key !in ui.toggledGroups else key in ui.toggledGroups
        }
    }
    return if (tab.visibleHits.size <= SYNC_ROW_LIMIT) {
        remember(tab.visibleHits, tab.groupMode, ui.sort, ui.toggledGroups, expandByDefault) { compute() }
    } else {
        val state: State<List<ResultRow>> = produceState<List<ResultRow>>(
            emptyList(),
            tab.visibleHits, tab.groupMode, ui.sort, ui.toggledGroups, expandByDefault,
        ) {
            value = withContext(Dispatchers.Default) { compute() }
        }
        state.value
    }
}

private const val SYNC_ROW_LIMIT = 1500

private fun columnsFor(res: android.content.res.Resources, width: WidthClass, mode: GroupMode): List<Col> {
    val slotFree = res.getString(R.string.search_slot_free)
    val file = Col(ResultColumn.FILE, res.getString(R.string.col_file), ColumnWidth.Weight(1f)) { row -> fileCell(row, mode, res) }
    val user = Col(ResultColumn.USER, res.getString(R.string.col_user), ColumnWidth.Fixed(120.dp)) { row ->
        when (row) {
            is GroupHeaderRow -> if (row.kind == GroupKind.FOLDER) row.username else res.getString(R.string.label_files_count, row.fileCount)
            is FileResultRow -> if (mode == GroupMode.FLAT) row.hit.username else ""
        }
    }
    val size = Col(ResultColumn.SIZE, res.getString(R.string.col_size), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { row ->
        when (row) {
            is GroupHeaderRow -> Formatting.size(row.totalSize)
            is FileResultRow -> Formatting.size(row.hit.file.size)
        }
    }
    val quality = Col(ResultColumn.BITRATE, res.getString(R.string.col_quality), ColumnWidth.Fixed(168.dp)) { row ->
        when (row) {
            is GroupHeaderRow -> buildString {
                if (row.hasLossless) append("◆ ")
                row.bestBitrate?.let { append("≤ $it kbps") }
            }
            is FileResultRow -> {
                val f = row.hit.file
                val a = f.attributes
                (if (f.isLossless) "◆ " else "") + com.soulseekplus.ui.qualityText(f.extension, a.bitrate, a.sampleRate, a.bitDepth, a.vbr)
            }
        }
    }
    val time = Col(ResultColumn.DURATION, res.getString(R.string.col_time), ColumnWidth.Fixed(60.dp), alignEnd = true, mono = true) { row ->
        if (row is FileResultRow) Formatting.duration(row.hit.file.attributes.durationSeconds) else ""
    }
    val speed = Col(ResultColumn.SPEED, res.getString(R.string.col_speed), ColumnWidth.Fixed(88.dp), alignEnd = true, mono = true) { row ->
        val avg = when (row) {
            is GroupHeaderRow -> row.averageSpeed
            is FileResultRow -> row.hit.averageSpeed
        }
        Formatting.speed(avg.toLong())
    }
    val slots = Col(ResultColumn.QUEUE, res.getString(R.string.col_slots), ColumnWidth.Fixed(72.dp)) { row ->
        val (free, queue) = when (row) {
            is GroupHeaderRow -> row.freeSlots to row.queueLength
            is FileResultRow -> row.hit.freeSlots to row.hit.queueLength
        }
        if (free) slotFree else res.getString(R.string.search_slot_queue, queue)
    }
    val country = Col(ResultColumn.COUNTRY, res.getString(R.string.col_country), ColumnWidth.Fixed(58.dp)) { row ->
        val code = when (row) {
            is GroupHeaderRow -> row.countryCode
            is FileResultRow -> row.hit.countryCode
        }
        if (code.isNullOrBlank()) "" else Formatting.flag(code) + " " + code.uppercase()
    }
    return when (width) {
        WidthClass.Compact -> listOf(file, size, slots)
        WidthClass.Medium -> listOf(file, user, size, quality, slots)
        else -> listOf(file, user, size, quality, time, speed, slots, country)
    }
}

private fun fileCell(row: ResultRow, mode: GroupMode, res: android.content.res.Resources): String = when (row) {
    is GroupHeaderRow -> {
        val caret = if (row.expanded) "▾ " else "▸ "
        val lock = if (row.lockedCount > 0 && row.lockedCount == row.fileCount) "🔒 " else ""
        caret + lock + row.title + "  ·  " + res.getString(R.string.label_files_count, row.fileCount)
    }
    is FileResultRow -> {
        val hit = row.hit
        val prefix = if (hit.locked) "🔒 " else ""
        if (mode == GroupMode.FLAT) {
            val folder = hit.file.folder.substringAfterLast('\\')
            prefix + (if (folder.isNotEmpty()) "$folder\\" else "") + hit.file.name
        } else {
            "      " + prefix + hit.file.name
        }
    }
}

// ---------------------------------------------------------------------- bağlam menüsü ve seçim çubuğu

private fun buildMenu(
    row: ResultRow,
    ui: TabUi,
    tab: SearchTabState,
    vm: SearchViewModel,
    actions: AppActions,
    ua: UserActions,
    res: android.content.res.Resources,
    expandByDefault: Boolean,
): List<MenuEntry> {
    val keys: Set<Any> = if (row.key in ui.selected) ui.selected else setOf(row.key)
    val hits = vm.resolveSelection(tab, keys)
    val downloadable = hits.count { !it.locked }
    val single = keys.size == 1
    val out = ArrayList<MenuEntry>()

    val downloadLabel = if (single && row is FileResultRow) {
        res.getString(R.string.action_download)
    } else {
        res.getString(R.string.menu_download_n, downloadable)
    }
    out += MenuEntry.Item(
        downloadLabel,
        { vm.download(hits, actions) },
        shortcut = AppShortcuts.label(AppShortcuts.DOWNLOAD_SELECTED, res),
        icon = SoulIcons.Download,
        enabled = downloadable > 0,
    )

    if (single) {
        when (row) {
            is GroupHeaderRow -> {
                val folderPath = row.folderPath
                if (row.kind == GroupKind.FOLDER && folderPath != null) {
                    out += MenuEntry.Item(res.getString(R.string.menu_download_folder), { vm.downloadFolder(row.username, folderPath, actions) }, icon = SoulIcons.FolderDownload)
                    out += MenuEntry.Item(res.getString(R.string.menu_browse_folder), { actions.browseUser(row.username, folderPath) }, icon = SoulIcons.BrowseUser)
                    out += ua.copyFolderLink(row.username, folderPath)
                }
                out += MenuEntry.Item(
                    res.getString(if (row.expanded) R.string.menu_collapse else R.string.menu_expand),
                    { vm.toggleGroup(tab.id, row.key) },
                    icon = if (row.expanded) DeskIcons.ChevronUp else DeskIcons.ChevronDown,
                )
            }
            is FileResultRow -> {
                val hit = row.hit
                val folder = hit.file.folder
                if (folder.isNotEmpty()) {
                    out += MenuEntry.Item(res.getString(R.string.menu_download_folder), { vm.downloadFolder(hit.username, folder, actions) }, icon = SoulIcons.FolderDownload)
                    out += MenuEntry.Item(res.getString(R.string.menu_browse_folder), { actions.browseUser(hit.username, folder) }, icon = SoulIcons.BrowseUser)
                }
                out += MenuEntry.Separator
                out += ua.copyFileName(hit.file.name)
                out += ua.copyFileLink(hit.username, hit.file.path)
                if (folder.isNotEmpty()) out += ua.copyFolderLink(hit.username, folder)
            }
        }
    }
    if (tab.groupMode != GroupMode.FLAT) {
        out += MenuEntry.Separator
        out += MenuEntry.Item(res.getString(R.string.menu_expand_all), { vm.setAllGroups(tab.id, expandByDefault, expand = true) }, icon = DeskIcons.ChevronDown)
        out += MenuEntry.Item(res.getString(R.string.menu_collapse_all), { vm.setAllGroups(tab.id, expandByDefault, expand = false) }, icon = DeskIcons.ChevronUp)
    }
    val users = hits.map { it.username }.distinct()
    if (users.size == 1) {
        out += MenuEntry.Separator
        out += ua.userEntries(users[0])
    }
    return out
}

@Composable
private fun SelectionBar(tab: SearchTabState, ui: TabUi, vm: SearchViewModel, actions: AppActions) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    Column {
        HorizontalRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.label_selected_count, ui.selected.size),
                style = DeskTheme.type.bodyStrong,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            DeskButton(stringResource(R.string.action_download), { vm.download(vm.resolveSelection(tab, ui.selected), actions) }, primary = true, icon = SoulIcons.Download)
            DeskButton(stringResource(R.string.action_clear), { vm.clearSelection(tab.id) })
        }
    }
}
