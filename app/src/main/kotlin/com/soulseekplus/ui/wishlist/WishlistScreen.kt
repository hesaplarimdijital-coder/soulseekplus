package com.soulseekplus.ui.wishlist

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.WishlistItem
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.CountBadge
import com.soulseekplus.ui.DeskSwitch
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.agoText
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.qualityText
import desktopkit.components.ColumnWidth
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DenseTable
import desktopkit.components.HorizontalRule
import desktopkit.components.HorizontalSplitPane
import desktopkit.components.MenuEntry
import desktopkit.components.SectionCaption
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.components.collectState
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.components.rememberSplitState
import desktopkit.components.stateBackground
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

@Composable
fun WishlistScreen(vm: WishlistViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val compact = currentWidthClass() == WidthClass.Compact
    val items by vm.repo.items.collectAsStateWithLifecycle()
    val results by vm.repo.results.collectAsStateWithLifecycle()
    val interval by vm.repo.intervalSeconds.collectAsStateWithLifecycle()
    val totalNew by vm.repo.totalNewHits.collectAsStateWithLifecycle()
    val res = appResources()
    val dimens = DeskTheme.dimens
    val selected = vm.selectedQuery?.takeIf { q -> items.any { it.query == q } }

    // Seçili öğenin sonuçları ekrandayken gelen yeni sonuçlar rozet olarak birikmesin.
    val selectedNew = selected?.let { q -> items.firstOrNull { it.query == q }?.newHitCount } ?: 0
    LaunchedEffect(selected, selectedNew) {
        if (selected != null && selectedNew > 0) vm.repo.clearBadge(selected)
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_wishlist),
            subtitle = if (interval > 0) res.getString(R.string.wishlist_interval, Formatting.duration(interval)) else res.getString(R.string.wishlist_interval_unknown),
            actions = {
                if (totalNew > 0) DeskButton(stringResource(R.string.wishlist_clear_badges), { vm.repo.clearAllBadges() }, icon = DeskIcons.Check)
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskTextField(
                value = vm.newItemText,
                onValueChange = { vm.newItemText = it },
                modifier = Modifier.weight(1f),
                hint = stringResource(R.string.wishlist_add_hint),
                leading = SoulIcons.WishlistStar,
                imeAction = ImeAction.Done,
                onImeAction = { vm.add(actions) },
            )
            DeskButton(stringResource(R.string.action_add), { vm.add(actions) }, primary = true, enabled = vm.newItemText.isNotBlank())
        }
        HorizontalRule()
        if (items.isEmpty()) {
            MessageScreen(
                title = stringResource(R.string.wishlist_empty_title),
                icon = SoulIcons.WishlistRadar,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.wishlist_empty_desc),
            )
            return@Column
        }
        val list: @Composable (Modifier) -> Unit = { m -> ItemList(items, selected, vm, actions, m) }
        val detail: @Composable (Modifier) -> Unit = { m ->
            if (selected == null) {
                MessageScreen(
                    title = stringResource(R.string.wishlist_pick_title),
                    icon = SoulIcons.WishlistStar,
                    modifier = m,
                    description = stringResource(R.string.wishlist_pick_desc),
                )
            } else {
                ResultsPane(selected, results[selected].orEmpty(), vm, container, actions, compact, m)
            }
        }
        if (compact) {
            if (selected == null) list(Modifier.weight(1f)) else detail(Modifier.weight(1f))
        } else {
            HorizontalSplitPane(
                state = rememberSplitState(320.dp),
                start = { list(Modifier.fillMaxSize()) },
                end = { detail(Modifier.fillMaxSize()) },
                modifier = Modifier.weight(1f),
                minStart = 240.dp,
                minEnd = 360.dp,
                dividerLabel = stringResource(R.string.wishlist_resize_panes),
            )
        }
    }
}

@Composable
private fun ItemList(items: List<WishlistItem>, selected: String?, vm: WishlistViewModel, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = dimens.gapS)) {
        item { SectionCaption(stringResource(R.string.wishlist_items)) }
        items(items, key = { it.query }) { item ->
            val interaction = remember { MutableInteractionSource() }
            val state = interaction.collectState()
            val shape = RoundedCornerShape(dimens.radiusControl)
            val isSelected = item.query == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.gapS, vertical = 1.dp)
                    .background(stateBackground(state, selected = isSelected), shape)
                    .focusRing(state, shape)
                    .deskClickable(interaction, { vm.select(item.query) })
                    .padding(start = dimens.gapM, end = dimens.gapXs, top = dimens.gapXs, bottom = dimens.gapXs)
                    .heightIn(min = dimens.rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            ) {
                Icon(
                    SoulIcons.WishlistStar,
                    contentDescription = null,
                    tint = if (isSelected) colors.onSelection else if (item.enabled) colors.accentText else colors.textTertiary,
                    modifier = Modifier.size(dimens.icon),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.query,
                        style = if (item.newHitCount > 0) DeskTheme.type.bodyStrong else DeskTheme.type.body,
                        color = if (isSelected) colors.onSelection else if (item.enabled) colors.text else colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.lastSearchedAtMillis?.let { res.getString(R.string.wishlist_last_searched, res.agoText(it)) }
                            ?: res.getString(R.string.wishlist_never_searched),
                        style = DeskTheme.type.caption,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CountBadge(item.newHitCount)
                DeskSwitch(item.enabled, { vm.repo.setEnabled(item.query, it) })
                DeskIconButton(SoulIcons.Retry, stringResource(R.string.wishlist_search_now), { vm.searchNow(item.query, actions) }, size = 36.dp)
                DeskIconButton(DeskIcons.Delete, stringResource(R.string.action_remove), { confirmRemove = item.query }, size = 36.dp)
            }
        }
    }
    confirmRemove?.let { query ->
        com.soulseekplus.ui.DeskDialog(
            title = res.getString(R.string.wishlist_remove_title),
            text = res.getString(R.string.wishlist_remove_text, query),
            confirmLabel = res.getString(R.string.action_remove),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { vm.remove(query); confirmRemove = null },
            onDismiss = { confirmRemove = null },
            destructive = true,
        )
    }
}

@Composable
private fun ResultsPane(
    query: String,
    hits: List<SearchHit>,
    vm: WishlistViewModel,
    container: AppContainer,
    actions: AppActions,
    compact: Boolean,
    modifier: Modifier,
) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val ua = rememberUserActions(container, actions)
    var sort by rememberSaveable(query) { mutableStateOf(0 to false) }
    val columns = remember(res, compact) {
        buildList {
            add(TableColumn<SearchHit>(res.getString(R.string.col_file), ColumnWidth.Weight(2f)) { (if (it.isNew) "● " else "") + it.file.name })
            add(TableColumn<SearchHit>(res.getString(R.string.col_user), ColumnWidth.Weight(1f)) { it.username })
            add(TableColumn<SearchHit>(res.getString(R.string.col_size), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { Formatting.size(it.file.size) })
            if (!compact) {
                add(
                    TableColumn<SearchHit>(res.getString(R.string.col_quality), ColumnWidth.Fixed(120.dp)) {
                        val a = it.file.attributes
                        qualityText(it.file.extension, a.bitrate, a.sampleRate, a.bitDepth, a.vbr)
                    },
                )
                add(TableColumn<SearchHit>(res.getString(R.string.col_speed), ColumnWidth.Fixed(88.dp), alignEnd = true, mono = true) { Formatting.speed(it.averageSpeed.toLong()) })
            }
        }
    }
    val sorted = remember(hits, sort, columns) {
        val (column, ascending) = sort
        val comparator: Comparator<SearchHit> = when (column) {
            1 -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.username }
            2 -> compareBy<SearchHit> { it.file.size }
            3 -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }
            4 -> compareBy<SearchHit> { it.averageSpeed }
            else -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        }
        hits.sortedWith(if (ascending) comparator else comparator.reversed())
    }
    val selectedHits = remember(sorted, vm.selectedHits) { sorted.filter { it.key in vm.selectedHits } }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) DeskIconButton(DeskIcons.ArrowLeft, stringResource(R.string.action_back), { vm.select(null) })
            Column(Modifier.weight(1f)) {
                Text(query, style = DeskTheme.type.title, color = DeskTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(res.getString(R.string.wishlist_result_count, hits.size), style = DeskTheme.type.caption, color = DeskTheme.colors.textTertiary)
            }
            DeskButton(
                stringResource(R.string.action_download),
                { vm.download(selectedHits, actions) },
                icon = SoulIcons.Download,
                primary = true,
                enabled = selectedHits.isNotEmpty(),
            )
            DeskIconButton(DeskIcons.Delete, stringResource(R.string.wishlist_clear_results), { vm.repo.clearResults(query); vm.selectedHits = emptySet() }, enabled = hits.isNotEmpty())
        }
        HorizontalRule()
        if (hits.isEmpty()) {
            MessageScreen(
                title = stringResource(R.string.wishlist_no_results),
                icon = SoulIcons.WishlistRadar,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.wishlist_no_results_desc),
                actionLabel = stringResource(R.string.wishlist_search_now),
                onAction = { vm.searchNow(query, actions) },
            )
        } else {
            DenseTable(
                columns = columns,
                rows = sorted,
                key = { it.key },
                onRowClick = { _, _ -> },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                sort = TableSort(sort.first, sort.second),
                onSortChange = { sort = it.column to it.ascending },
                selectedKeys = vm.selectedHits,
                onSelectionChange = { vm.selectedHits = it },
                toggleOnClick = compact,
                rowMenuLabel = res.getString(R.string.action_more),
                rowMenu = { hit, _ ->
                    buildList {
                        add(MenuEntry.Item(res.getString(R.string.action_download), { vm.download(listOf(hit), actions) }, icon = SoulIcons.Download, enabled = !hit.locked))
                        add(MenuEntry.Item(res.getString(R.string.menu_download_folder), { vm.downloadFolder(hit, actions) }, icon = SoulIcons.FolderDownload, enabled = !hit.locked && hit.file.folder.isNotEmpty()))
                        add(ua.copyFileName(hit.file.name))
                        add(ua.copyFileLink(hit.username, hit.file.path))
                        add(MenuEntry.Separator)
                        addAll(ua.userEntries(hit.username))
                    }
                },
            )
        }
    }
}
