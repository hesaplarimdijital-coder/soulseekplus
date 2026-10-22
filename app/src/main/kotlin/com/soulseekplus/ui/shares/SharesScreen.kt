package com.soulseekplus.ui.shares

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.ShareLevel
import com.soulseekplus.data.shares.ScanPhase
import com.soulseekplus.data.shares.ShareRootInfo
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskDialog
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.DeskProgressBar
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.IconMenuButton
import com.soulseekplus.ui.KeyValueRow
import com.soulseekplus.ui.MenuButton
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.SurfaceCard
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.dateTimeText
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.levelText
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry
import desktopkit.components.SectionCaption
import desktopkit.components.SegmentedChoice
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.theme.DeskTheme

/** Düzenlenen ya da eklenen kök: [id] null ise yeni ekleniyor ([uri] dolu). */
private data class RootDraft(val id: String?, val uri: Uri?, val alias: String, val level: ShareLevel, val group: String?)

@Composable
fun SharesScreen(container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val repo = container.shares
    val roots by repo.roots.collectAsStateWithLifecycle()
    val progress by repo.progress.collectAsStateWithLifecycle()
    val stats by repo.stats.collectAsStateWithLifecycle()
    val loaded by repo.cacheLoaded.collectAsStateWithLifecycle()
    val lists by container.userLists.lists.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<RootDraft?>(null) }
    var removing by remember { mutableStateOf<ShareRootInfo?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) draft = RootDraft(null, uri, folderLabel(uri), ShareLevel.PUBLIC, null)
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_shares),
            subtitle = res.getString(R.string.shares_summary, stats.files, stats.folders),
            actions = {
                if (progress.active) {
                    DeskButton(stringResource(R.string.shares_cancel_scan), { repo.cancelScan() }, icon = DeskIcons.Close)
                } else {
                    DeskButton(stringResource(R.string.shares_rescan), { repo.rescan() }, icon = SoulIcons.Retry, enabled = roots.isNotEmpty())
                }
                DeskButton(stringResource(R.string.shares_add_folder), { picker.launch(null) }, icon = DeskIcons.Plus, primary = true)
            },
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(dimens.gapM),
        ) {
            if (progress.active) {
                item(key = "progress") {
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        val text = when (progress.phase) {
                            ScanPhase.READING_TAGS -> res.getString(R.string.shares_reading_tags, progress.tagsDone, progress.tagsTotal)
                            else -> res.getString(R.string.shares_scanning, progress.rootAlias, progress.folders, progress.files)
                        }
                        Text(text, style = DeskTheme.type.bodyStrong, color = colors.text)
                        if (progress.phase == ScanPhase.LISTING && progress.currentFolder.isNotEmpty()) {
                            Text(progress.currentFolder, style = DeskTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DeskProgressBar(
                            if (progress.phase == ScanPhase.READING_TAGS && progress.tagsTotal > 0) progress.tagsDone.toFloat() / progress.tagsTotal else null,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (roots.any { !it.accessible }) {
                item(key = "inaccessible") {
                    StatusBanner(BannerTone.Warning, stringResource(R.string.shares_inaccessible_banner), Modifier.fillMaxWidth())
                }
            }
            if (roots.isEmpty()) {
                item(key = "empty") {
                    if (loaded) {
                        MessageScreen(
                            title = stringResource(R.string.shares_empty_title),
                            icon = SoulIcons.ShareFolder,
                            modifier = Modifier.padding(top = dimens.gapXl),
                            description = stringResource(R.string.shares_empty_desc),
                            actionLabel = stringResource(R.string.shares_add_folder),
                            onAction = { picker.launch(null) },
                        )
                    }
                }
            } else {
                item(key = "caption") { SectionCaption(stringResource(R.string.shares_folders)) }
                items(roots, key = { it.id }) { root ->
                    RootCard(
                        root = root,
                        scanning = progress.active,
                        onRescan = { repo.rescan(root.id) },
                        onEdit = { draft = RootDraft(root.id, null, root.alias, root.level, root.group) },
                        onRemove = { removing = root },
                    )
                }
            }
            item(key = "levels") {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shares_levels_title), style = DeskTheme.type.bodyStrong, color = colors.text)
                    Text(stringResource(R.string.shares_levels_public), style = DeskTheme.type.caption, color = colors.textSecondary)
                    Text(stringResource(R.string.shares_levels_buddies), style = DeskTheme.type.caption, color = colors.textSecondary)
                    Text(stringResource(R.string.shares_levels_group), style = DeskTheme.type.caption, color = colors.textSecondary)
                    Text(stringResource(R.string.shares_levels_note), style = DeskTheme.type.caption, color = colors.textTertiary)
                }
            }
        }
    }

    draft?.let { current ->
        RootDialog(
            initial = current,
            groups = lists.groups.map { it.name },
            onDismiss = { draft = null },
            onConfirm = { result ->
                if (result.id == null) {
                    val uri = result.uri
                    if (uri != null && !repo.addRoot(uri, result.level, result.group, result.alias)) actions.toast(R.string.shares_permission_failed)
                } else {
                    repo.updateRoot(result.id, result.alias, result.level, result.group)
                }
                draft = null
            },
        )
    }
    removing?.let { root ->
        DeskDialog(
            title = res.getString(R.string.shares_remove_title),
            text = res.getString(R.string.shares_remove_text, root.alias),
            confirmLabel = res.getString(R.string.action_remove),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { repo.removeRoot(root.id); removing = null },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

@Composable
private fun RootCard(root: ShareRootInfo, scanning: Boolean, onRescan: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val res = appResources()
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
            Column(Modifier.weight(1f)) {
                Text(root.alias, style = DeskTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(folderLabel(Uri.parse(root.treeUri)), style = DeskTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconMenuButton(
                DeskIcons.MoreVertical,
                res.getString(R.string.action_more),
                {
                    listOf(
                        MenuEntry.Item(res.getString(R.string.shares_rescan_folder), onRescan, icon = SoulIcons.Retry, enabled = !scanning),
                        MenuEntry.Item(res.getString(R.string.shares_edit_folder), onEdit, icon = SoulIcons.Edit),
                        MenuEntry.Separator,
                        MenuEntry.Item(res.getString(R.string.action_remove), onRemove, icon = DeskIcons.Delete, destructive = true),
                    )
                },
            )
        }
        KeyValueRow(res.getString(R.string.shares_visible_to), res.levelText(root.level, root.group))
        KeyValueRow(res.getString(R.string.shares_contents), res.getString(R.string.shares_contents_value, root.fileCount, root.folderCount, Formatting.size(root.totalBytes)))
        KeyValueRow(
            res.getString(R.string.shares_last_scan),
            if (root.scannedAtMillis > 0) res.dateTimeText(root.scannedAtMillis) else res.getString(R.string.shares_never_scanned),
        )
        if (!root.accessible) {
            Text(res.getString(R.string.shares_inaccessible), style = DeskTheme.type.caption, color = colors.warning)
        }
    }
}

@Composable
private fun RootDialog(initial: RootDraft, groups: List<String>, onDismiss: () -> Unit, onConfirm: (RootDraft) -> Unit) {
    val res = appResources()
    var alias by rememberSaveable(initial.id, initial.uri?.toString()) { mutableStateOf(initial.alias) }
    var level by rememberSaveable(initial.id, initial.uri?.toString()) { mutableStateOf(initial.level) }
    var group by rememberSaveable(initial.id, initial.uri?.toString()) { mutableStateOf(initial.group) }
    val valid = alias.isNotBlank() && (level != ShareLevel.GROUP || group != null)
    DeskFormDialog(
        title = res.getString(if (initial.id == null) R.string.shares_add_folder else R.string.shares_edit_folder),
        confirmLabel = res.getString(if (initial.id == null) R.string.action_add else R.string.action_save),
        dismissLabel = res.getString(R.string.action_cancel),
        onConfirm = { onConfirm(initial.copy(alias = alias.trim(), level = level, group = if (level == ShareLevel.GROUP) group else null)) },
        onDismiss = onDismiss,
        confirmEnabled = valid,
    ) {
        Text(res.getString(R.string.shares_alias), style = DeskTheme.type.label, color = DeskTheme.colors.textSecondary)
        DeskTextField(alias, { alias = it }, Modifier.fillMaxWidth(), hint = res.getString(R.string.shares_alias_hint))
        Text(res.getString(R.string.shares_visible_to), style = DeskTheme.type.label, color = DeskTheme.colors.textSecondary)
        SegmentedChoice(
            options = listOf(
                ShareLevel.PUBLIC to res.getString(R.string.level_public),
                ShareLevel.BUDDIES to res.getString(R.string.level_buddies),
                ShareLevel.GROUP to res.getString(R.string.level_group),
            ),
            selected = level,
            onSelect = { level = it },
        )
        if (level == ShareLevel.GROUP) {
            if (groups.isEmpty()) {
                Text(res.getString(R.string.shares_no_groups), style = DeskTheme.type.caption, color = DeskTheme.colors.warning)
            } else {
                MenuButton(
                    text = group ?: res.getString(R.string.shares_choose_group),
                    entries = { groups.map { name -> MenuEntry.Item(name, { group = name }, checked = name == group) } },
                    modifier = Modifier.fillMaxWidth(),
                    icon = SoulIcons.Group,
                )
            }
        }
    }
}

/** "Birincil depolama/Music" gibi okunur bir klasör etiketi. */
private fun folderLabel(uri: Uri): String {
    val id = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        return uri.lastPathSegment ?: uri.toString()
    }
    val path = id.substringAfter(':', id)
    return path.ifEmpty { id.substringBefore(':') }.trimEnd('/')
}
