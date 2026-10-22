package com.soulseekplus.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.view.BrowseTree
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskProgressBar
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.FilterTextField
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.UserActions
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
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.components.rememberSplitState
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

private data class TreeRow(val node: BrowseTree.Node, val depth: Int)

private data class FileRow(val file: SharedFile, val folderPath: String, val locked: Boolean)

/** Kullanıcının paylaşımlarına göz atma: sol klasör ağacı, sağ dosya listesi (bölünmüş bölme). */
@Composable
fun BrowseScreen(vm: BrowseViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val state = vm.state
    val loaded = state as? BrowseState.Loaded

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_browse),
            subtitle = loaded?.let { res.getString(R.string.browse_subtitle, it.username, it.tree.fileCount, it.tree.folderCount) },
            actions = {
                if (loaded != null) {
                    DeskIconButton(DeskIcons.ChevronDown, stringResource(R.string.menu_expand_all), { vm.expandAll(loaded.tree) })
                    DeskIconButton(DeskIcons.ChevronUp, stringResource(R.string.menu_collapse_all), { vm.collapseAll() })
                }
                DeskIconButton(SoulIcons.Retry, stringResource(R.string.action_refresh), { vm.reload() }, enabled = vm.usernameInput.isNotBlank())
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskTextField(
                value = vm.usernameInput,
                onValueChange = { vm.usernameInput = it },
                modifier = Modifier.weight(1f),
                hint = stringResource(R.string.browse_hint_user),
                leading = SoulIcons.User,
                imeAction = ImeAction.Go,
                onImeAction = { vm.open(vm.usernameInput) },
            )
            DeskButton(stringResource(R.string.browse_open), { vm.open(vm.usernameInput) }, primary = true, enabled = vm.usernameInput.isNotBlank())
        }
        Row(Modifier.fillMaxWidth().padding(dimens.pagePadding, dimens.gapS)) {
            if (loaded != null) {
                FilterTextField(vm.filter, { vm.filter = it }, stringResource(R.string.browse_filter_hint), Modifier.weight(1f))
            }
        }
        when (state) {
            BrowseState.Idle -> MessageScreen(
                title = stringResource(R.string.browse_idle_title),
                icon = SoulIcons.BrowseUser,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.browse_idle_desc),
            )
            is BrowseState.Loading -> Column(Modifier.weight(1f)) {
                DeskProgressBar(null, Modifier.fillMaxWidth(), height = 2.dp)
                MessageScreen(
                    title = res.getString(R.string.browse_loading, state.username),
                    icon = SoulIcons.BrowseUser,
                    description = stringResource(R.string.browse_loading_desc),
                )
            }
            is BrowseState.Failed -> Column(Modifier.weight(1f)) {
                StatusBanner(
                    tone = BannerTone.Error,
                    text = res.getString(R.string.browse_failed, state.username, state.message ?: res.getString(R.string.err_generic)),
                    modifier = Modifier.padding(dimens.pagePadding),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { vm.reload() },
                )
            }
            is BrowseState.Loaded -> LoadedContent(vm, state, container, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoadedContent(vm: BrowseViewModel, state: BrowseState.Loaded, container: AppContainer, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val compact = currentWidthClass() == WidthClass.Compact
    val ua = rememberUserActions(container, actions)
    val tree = state.tree

    if (tree.roots.isEmpty()) {
        MessageScreen(
            title = stringResource(R.string.browse_empty_title),
            icon = DeskIcons.Folder,
            modifier = modifier,
            description = res.getString(R.string.browse_empty_desc, state.username),
        )
        return
    }
    if (compact) {
        Column(modifier.fillMaxSize()) {
            if (vm.filter.isNotBlank() || (vm.showFilesOnCompact && vm.selectedPath != null)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = DeskTheme.dimens.pagePadding)) {
                    DeskButton(stringResource(R.string.browse_folders), { vm.showFilesOnCompact = false; vm.filter = "" }, icon = DeskIcons.ArrowLeft)
                }
                FilesPane(vm, state, ua, actions, Modifier.weight(1f))
            } else {
                TreePane(vm, state, ua, actions, Modifier.weight(1f))
            }
        }
    } else {
        HorizontalSplitPane(
            state = rememberSplitState(340.dp),
            start = { TreePane(vm, state, ua, actions, Modifier.fillMaxSize()) },
            end = { FilesPane(vm, state, ua, actions, Modifier.fillMaxSize()) },
            modifier = modifier.fillMaxWidth(),
            minStart = 220.dp,
            minEnd = 320.dp,
            dividerLabel = res.getString(R.string.browse_resize_panes),
        )
    }
}

// ---------------------------------------------------------------------- klasör ağacı

@Composable
private fun TreePane(vm: BrowseViewModel, state: BrowseState.Loaded, ua: UserActions, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val tree = state.tree
    val rows = remember(tree, vm.expanded) { flatten(tree, vm.expanded) }
    val columns = remember(res) {
        listOf(
            TableColumn<TreeRow>(res.getString(R.string.label_folder), ColumnWidth.Weight(1f), sortable = false) { row ->
                val node = row.node
                val caret = if (node.children.isEmpty()) "   " else if (node.path in vm.expanded) "▾ " else "▸ "
                "  ".repeat(row.depth) + caret + (if (node.locked) "🔒 " else "") + node.name
            },
            TableColumn<TreeRow>(res.getString(R.string.col_files), ColumnWidth.Fixed(56.dp), alignEnd = true, mono = true, sortable = false) { row ->
                row.node.totalFiles.toString()
            },
        )
    }
    DenseTable(
        columns = columns,
        rows = rows,
        key = { it.node.path },
        onRowClick = { row, _ ->
            val node = row.node
            if (node.children.isNotEmpty() && vm.selectedPath == node.path) vm.toggleExpanded(node.path)
            else if (node.children.isNotEmpty() && node.path !in vm.expanded) vm.toggleExpanded(node.path)
            vm.selectedPath = node.path
            vm.filter = ""
            vm.fileSelection = emptySet()
            vm.showFilesOnCompact = true
        },
        modifier = modifier.fillMaxWidth(),
        selectedKey = vm.selectedPath,
        rowMenuLabel = res.getString(R.string.action_more),
        rowMenu = { row, _ ->
            val node = row.node
            listOf(
                MenuEntry.Item(
                    res.getString(R.string.browse_download_folder, node.totalFiles, Formatting.size(node.totalSize)),
                    { vm.downloadFolder(state.username, node, tree, actions) },
                    icon = SoulIcons.FolderDownload,
                    enabled = node.totalFiles > 0 && !tree.isLocked(node.path),
                ),
                MenuEntry.Item(
                    res.getString(if (node.path in vm.expanded) R.string.menu_collapse else R.string.menu_expand),
                    { vm.toggleExpanded(node.path) },
                    icon = if (node.path in vm.expanded) DeskIcons.ChevronUp else DeskIcons.ChevronDown,
                    enabled = node.children.isNotEmpty(),
                ),
                ua.copyFolderLink(state.username, node.path),
            )
        },
    )
}

private fun flatten(tree: BrowseTree, expanded: Set<String>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun walk(node: BrowseTree.Node, depth: Int) {
        out += TreeRow(node, depth)
        if (node.path in expanded) node.children.forEach { walk(it, depth + 1) }
    }
    tree.roots.forEach { walk(it, 0) }
    return out
}

// ---------------------------------------------------------------------- dosya listesi

@Composable
private fun FilesPane(vm: BrowseViewModel, state: BrowseState.Loaded, ua: UserActions, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val compact = currentWidthClass() == WidthClass.Compact
    val tree = state.tree
    val path = vm.selectedPath
    val node = path?.let { tree.node(it) }
    val filtering = vm.filter.isNotBlank()

    val rows: List<FileRow> = remember(tree, path, vm.includeSubfolders, vm.filter, vm.sortColumn, vm.sortAscending) {
        val base: List<FileRow> = when {
            filtering -> tree.search(vm.filter).map { FileRow(it.file, it.folderPath, it.locked) }
            node == null -> emptyList()
            vm.includeSubfolders -> collectRecursive(tree, node)
            else -> node.files.map { FileRow(it, node.path, tree.isLocked(node.path)) }
        }
        sortRows(base, vm.sortColumn, vm.sortAscending)
    }
    val columns = remember(res, compact, filtering) { fileColumns(res, compact, filtering) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (filtering) res.getString(R.string.browse_matches, rows.size) else node?.path.orEmpty(),
                style = DeskTheme.type.bodyStrong,
                color = DeskTheme.colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!filtering && node != null && node.children.isNotEmpty()) {
                ToggleChip(res.getString(R.string.browse_subfolders), vm.includeSubfolders, { vm.includeSubfolders = !vm.includeSubfolders })
            }
        }
        HorizontalRule()
        if (rows.isEmpty()) {
            MessageScreen(
                title = stringResource(if (filtering) R.string.browse_no_matches else R.string.browse_no_files),
                icon = DeskIcons.Folder,
                modifier = Modifier.weight(1f),
            )
        } else {
            DenseTable(
                columns = columns,
                rows = rows,
                key = { it.file.path },
                onRowClick = { _, _ -> },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                sort = TableSort(vm.sortColumn.coerceIn(0, columns.lastIndex), vm.sortAscending),
                onSortChange = { vm.sortColumn = it.column; vm.sortAscending = it.ascending },
                selectedKeys = vm.fileSelection,
                onSelectionChange = { vm.fileSelection = it },
                rowMenuLabel = res.getString(R.string.action_more),
                rowMenu = { row, _ ->
                    val keys: Set<Any> = if (row.file.path in vm.fileSelection) vm.fileSelection else setOf(row.file.path)
                    val files = rows.filter { it.file.path in keys }.map { it.file }
                    val out = ArrayList<MenuEntry>()
                    out += MenuEntry.Item(
                        if (files.size > 1) res.getString(R.string.menu_download_n, files.size) else res.getString(R.string.action_download),
                        { vm.downloadFiles(state.username, files, tree, actions) },
                        icon = SoulIcons.Download,
                        enabled = files.isNotEmpty() && files.any { !tree.isLocked(it.folder) },
                    )
                    if (files.size == 1) {
                        out += ua.copyFileName(files[0].name)
                        out += ua.copyFileLink(state.username, files[0].path)
                    }
                    out
                },
            )
        }
        if (vm.fileSelection.isNotEmpty()) {
            HorizontalRule()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.label_selected_count, vm.fileSelection.size),
                    style = DeskTheme.type.bodyStrong,
                    color = DeskTheme.colors.text,
                    modifier = Modifier.weight(1f),
                )
                DeskButton(
                    stringResource(R.string.action_download),
                    {
                        val files = rows.filter { it.file.path in vm.fileSelection }.map { it.file }
                        vm.downloadFiles(state.username, files, tree, actions)
                    },
                    primary = true,
                    icon = SoulIcons.Download,
                )
                DeskButton(stringResource(R.string.action_clear), { vm.fileSelection = emptySet() })
            }
        }
    }
}

private fun collectRecursive(tree: BrowseTree, node: BrowseTree.Node): List<FileRow> {
    val out = ArrayList<FileRow>(node.totalFiles)
    fun walk(n: BrowseTree.Node) {
        val locked = tree.isLocked(n.path)
        n.files.forEach { out += FileRow(it, n.path, locked) }
        n.children.forEach { walk(it) }
    }
    walk(node)
    return out
}

private fun sortRows(rows: List<FileRow>, column: Int, ascending: Boolean): List<FileRow> {
    val comparator: Comparator<FileRow> = when (column) {
        1 -> compareBy<FileRow> { it.file.size }
        2 -> compareBy<FileRow> { it.file.attributes.bitrate ?: -1 }
        3 -> compareBy<FileRow> { it.file.attributes.durationSeconds ?: -1 }
        else -> compareBy<FileRow, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
    }
    val sorted = rows.sortedWith(comparator.thenComparing(compareBy<FileRow, String>(String.CASE_INSENSITIVE_ORDER) { it.file.path }))
    return if (ascending) sorted else sorted.reversed()
}

private fun fileColumns(res: android.content.res.Resources, compact: Boolean, filtering: Boolean): List<TableColumn<FileRow>> {
    val name = TableColumn<FileRow>(res.getString(R.string.col_file), ColumnWidth.Weight(1f)) { row ->
        val lock = if (row.locked) "🔒 " else ""
        if (filtering) lock + row.folderPath.substringAfterLast('\\') + "\\" + row.file.name else lock + row.file.name
    }
    val size = TableColumn<FileRow>(res.getString(R.string.col_size), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { Formatting.size(it.file.size) }
    val quality = TableColumn<FileRow>(res.getString(R.string.col_quality), ColumnWidth.Fixed(168.dp)) { row ->
        val f = row.file
        val a = f.attributes
        (if (f.isLossless) "◆ " else "") + qualityText(f.extension, a.bitrate, a.sampleRate, a.bitDepth, a.vbr)
    }
    val time = TableColumn<FileRow>(res.getString(R.string.col_time), ColumnWidth.Fixed(60.dp), alignEnd = true, mono = true) {
        Formatting.duration(it.file.attributes.durationSeconds)
    }
    return if (compact) listOf(name, size) else listOf(name, size, quality, time)
}
