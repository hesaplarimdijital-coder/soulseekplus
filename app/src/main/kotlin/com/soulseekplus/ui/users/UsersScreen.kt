package com.soulseekplus.ui.users

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
import com.soulseekplus.core.model.UserGroup
import com.soulseekplus.core.model.UserPresence
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.DeskDialog
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.SurfaceCard
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.userStatusText
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
import desktopkit.theme.DeskTheme

private enum class UsersTab { BUDDIES, GROUPS, BANNED, IGNORED }

/** Arkadaşlar, gruplar, yasaklılar ve yoksayılanlar. Veri [com.soulseekplus.data.settings.UserListsRepository]'de. */
@Composable
fun UsersScreen(container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val repo = container.userLists
    val lists by repo.lists.collectAsStateWithLifecycle()
    val presence by container.client.users.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(UsersTab.BUDDIES) }
    var addText by rememberSaveable(tab) { mutableStateOf("") }

    // Arkadaşların çevrimiçi durumunu izle (zaten izleniyorsa istemci yok sayar).
    LaunchedEffect(lists.buddies) {
        for (name in lists.buddies) container.client.watchUser(name)
    }

    fun add() {
        val name = addText.trim()
        if (name.isEmpty()) return
        when (tab) {
            UsersTab.BUDDIES -> repo.addBuddy(name)
            UsersTab.BANNED -> repo.ban(name)
            UsersTab.IGNORED -> repo.ignore(name)
            UsersTab.GROUPS -> return
        }
        addText = ""
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_users),
            subtitle = res.getString(R.string.users_summary, lists.buddies.size, lists.banned.size, lists.ignored.size),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding)) {
            SegmentedChoice(
                options = listOf(
                    UsersTab.BUDDIES to res.getString(R.string.users_tab_buddies),
                    UsersTab.GROUPS to res.getString(R.string.users_tab_groups),
                    UsersTab.BANNED to res.getString(R.string.users_tab_banned),
                    UsersTab.IGNORED to res.getString(R.string.users_tab_ignored),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
        }
        if (tab != UsersTab.GROUPS) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeskTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    modifier = Modifier.weight(1f),
                    hint = res.getString(
                        when (tab) {
                            UsersTab.BUDDIES -> R.string.users_add_buddy_hint
                            UsersTab.BANNED -> R.string.users_add_banned_hint
                            else -> R.string.users_add_ignored_hint
                        },
                    ),
                    leading = SoulIcons.User,
                    imeAction = ImeAction.Done,
                    onImeAction = ::add,
                )
                DeskButton(res.getString(R.string.action_add), ::add, primary = true, enabled = addText.isNotBlank())
            }
        }
        HorizontalRule()
        when (tab) {
            UsersTab.BUDDIES -> UserTable(
                names = lists.buddies.toList(),
                presence = presence,
                container = container,
                actions = actions,
                empty = R.string.users_empty_buddies,
                emptyDesc = R.string.users_empty_buddies_desc,
                buddyList = true,
                notes = lists.buddyNotes,
                groups = lists.groups,
                modifier = Modifier.weight(1f),
            )
            UsersTab.BANNED -> UserTable(
                names = lists.banned.toList(),
                presence = presence,
                container = container,
                actions = actions,
                empty = R.string.users_empty_banned,
                emptyDesc = R.string.users_empty_banned_desc,
                modifier = Modifier.weight(1f),
            )
            UsersTab.IGNORED -> UserTable(
                names = lists.ignored.toList(),
                presence = presence,
                container = container,
                actions = actions,
                empty = R.string.users_empty_ignored,
                emptyDesc = R.string.users_empty_ignored_desc,
                modifier = Modifier.weight(1f),
            )
            UsersTab.GROUPS -> GroupsPane(container, lists.groups, presence, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun UserTable(
    names: List<String>,
    presence: Map<String, UserPresence>,
    container: AppContainer,
    actions: AppActions,
    empty: Int,
    emptyDesc: Int,
    modifier: Modifier,
    buddyList: Boolean = false,
    notes: Map<String, String> = emptyMap(),
    groups: List<UserGroup> = emptyList(),
) {
    val res = appResources()
    val ua = rememberUserActions(container, actions)
    val repo = container.userLists
    val showBuddyColumns = buddyList
    var sort by rememberSaveable(empty) { mutableStateOf(0 to true) }
    var noteFor by remember { mutableStateOf<String?>(null) }

    if (names.isEmpty()) {
        MessageScreen(
            title = res.getString(empty),
            icon = SoulIcons.Group,
            modifier = modifier,
            description = res.getString(emptyDesc),
        )
        return
    }

    val columns = remember(res, showBuddyColumns, notes, groups, presence) {
        buildList {
            add(
                TableColumn<String>(res.getString(R.string.col_user), ColumnWidth.Weight(1.2f)) { name ->
                    val dot = when (presence[name]?.status) {
                        UserStatus.ONLINE -> "● "
                        UserStatus.AWAY -> "◐ "
                        else -> "○ "
                    }
                    dot + name
                },
            )
            add(
                TableColumn<String>(res.getString(R.string.col_status), ColumnWidth.Fixed(96.dp)) { name ->
                    presence[name]?.status?.let { res.userStatusText(it) }.orEmpty()
                },
            )
            add(
                TableColumn<String>(res.getString(R.string.col_speed), ColumnWidth.Fixed(88.dp), alignEnd = true, mono = true) { name ->
                    presence[name]?.stats?.let { Formatting.speed(it.averageSpeed.toLong()) }.orEmpty()
                },
            )
            if (showBuddyColumns) {
                add(
                    TableColumn<String>(res.getString(R.string.col_groups), ColumnWidth.Weight(1f)) { name ->
                        groups.filter { name in it.members }.joinToString(", ") { it.name }
                    },
                )
                add(TableColumn<String>(res.getString(R.string.col_note), ColumnWidth.Weight(1.2f)) { name -> notes[name].orEmpty() })
            }
        }
    }
    val sorted = remember(names, sort, presence) {
        val (column, ascending) = sort
        val base = when (column) {
            1 -> names.sortedBy { presence[it]?.status?.code ?: -1 }
            2 -> names.sortedBy { presence[it]?.stats?.averageSpeed ?: 0 }
            else -> names.sortedBy { it.lowercase() }
        }
        if (ascending) base else base.reversed()
    }

    DenseTable(
        columns = columns,
        rows = sorted,
        key = { it },
        onRowClick = { name, _ -> actions.userInfo(name) },
        modifier = modifier.fillMaxWidth(),
        sort = TableSort(sort.first, sort.second),
        onSortChange = { sort = it.column to it.ascending },
        rowMenuLabel = res.getString(R.string.action_more),
        rowMenu = { name, _ ->
            buildList {
                addAll(ua.userEntries(name))
                if (showBuddyColumns) {
                    add(MenuEntry.Separator)
                    add(MenuEntry.Item(res.getString(R.string.users_edit_note), { noteFor = name }, icon = SoulIcons.Edit))
                    for (group in groups) {
                        if (name in group.members) {
                            add(MenuEntry.Item(res.getString(R.string.users_remove_from_group, group.name), { repo.removeFromGroup(group.name, name) }, icon = DeskIcons.Close))
                        } else {
                            add(MenuEntry.Item(res.getString(R.string.users_add_to_group, group.name), { repo.addToGroup(group.name, name) }, icon = SoulIcons.Group))
                        }
                    }
                }
            }
        },
    )

    noteFor?.let { name ->
        var text by remember(name) { mutableStateOf(notes[name].orEmpty()) }
        DeskFormDialog(
            title = res.getString(R.string.users_note_title, name),
            confirmLabel = res.getString(R.string.action_save),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { repo.setBuddyNote(name, text); noteFor = null },
            onDismiss = { noteFor = null },
        ) {
            DeskTextField(text, { text = it.take(200) }, Modifier.fillMaxWidth(), hint = res.getString(R.string.users_note_hint), singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun GroupsPane(container: AppContainer, groups: List<UserGroup>, presence: Map<String, UserPresence>, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val repo = container.userLists
    val ua = rememberUserActions(container, actions)
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var renameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteName by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(res.getString(R.string.users_groups_desc), style = DeskTheme.type.caption, color = colors.textSecondary, modifier = Modifier.weight(1f))
            DeskButton(res.getString(R.string.users_new_group), { createDialog = true }, icon = DeskIcons.Plus, primary = true)
        }
        if (groups.isEmpty()) {
            MessageScreen(
                title = res.getString(R.string.users_empty_groups),
                icon = SoulIcons.Group,
                modifier = Modifier.weight(1f),
                description = res.getString(R.string.users_empty_groups_desc),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dimens.pagePadding, vertical = dimens.gapS),
                verticalArrangement = Arrangement.spacedBy(dimens.gapM),
            ) {
                items(groups, key = { it.name }) { group ->
                    GroupCard(group, presence, repo, ua, actions, onRename = { renameFrom = group.name }, onDelete = { deleteName = group.name })
                }
            }
        }
    }

    if (createDialog) {
        var name by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        DeskFormDialog(
            title = res.getString(R.string.users_new_group),
            confirmLabel = res.getString(R.string.action_create),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { if (repo.createGroup(name)) createDialog = false else failed = true },
            onDismiss = { createDialog = false },
            confirmEnabled = name.isNotBlank(),
        ) {
            DeskTextField(name, { name = it; failed = false }, Modifier.fillMaxWidth(), hint = res.getString(R.string.users_group_name), error = failed)
            if (failed) Text(res.getString(R.string.users_group_exists), style = DeskTheme.type.caption, color = colors.danger)
        }
    }
    renameFrom?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        var failed by remember(old) { mutableStateOf(false) }
        DeskFormDialog(
            title = res.getString(R.string.users_rename_group),
            confirmLabel = res.getString(R.string.action_save),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { if (repo.renameGroup(old, name)) renameFrom = null else failed = true },
            onDismiss = { renameFrom = null },
            confirmEnabled = name.isNotBlank(),
        ) {
            DeskTextField(name, { name = it; failed = false }, Modifier.fillMaxWidth(), hint = res.getString(R.string.users_group_name), error = failed)
            if (failed) Text(res.getString(R.string.users_group_exists), style = DeskTheme.type.caption, color = colors.danger)
        }
    }
    deleteName?.let { name ->
        DeskDialog(
            title = res.getString(R.string.users_delete_group_title),
            text = res.getString(R.string.users_delete_group_text, name),
            confirmLabel = res.getString(R.string.action_delete),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { repo.deleteGroup(name); deleteName = null },
            onDismiss = { deleteName = null },
            destructive = true,
        )
    }
}

@Composable
private fun GroupCard(
    group: UserGroup,
    presence: Map<String, UserPresence>,
    repo: com.soulseekplus.data.settings.UserListsRepository,
    ua: com.soulseekplus.ui.common.UserActions,
    actions: AppActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    var addText by remember(group.name) { mutableStateOf("") }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = DeskTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(res.getString(R.string.label_members_count, group.members.size), style = DeskTheme.type.caption, color = colors.textTertiary)
            }
            DeskIconButton(SoulIcons.Edit, res.getString(R.string.users_rename_group), onRename)
            DeskIconButton(DeskIcons.Delete, res.getString(R.string.users_delete_group_title), onDelete)
        }
        for (member in group.members.sortedBy { it.lowercase() }) {
            val status = presence[member]?.status
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            ) {
                Text(
                    when (status) {
                        UserStatus.ONLINE -> "●"
                        UserStatus.AWAY -> "◐"
                        else -> "○"
                    },
                    color = when (status) {
                        UserStatus.ONLINE -> colors.success
                        UserStatus.AWAY -> colors.warning
                        else -> colors.textTertiary
                    },
                    style = DeskTheme.type.body,
                )
                Text(member, style = DeskTheme.type.body, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                com.soulseekplus.ui.IconMenuButton(DeskIcons.MoreVertical, res.getString(R.string.action_more), { ua.userEntries(member) })
                DeskIconButton(DeskIcons.Close, res.getString(R.string.users_remove_member), { repo.removeFromGroup(group.name, member) })
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskTextField(
                addText,
                { addText = it },
                Modifier.weight(1f),
                hint = res.getString(R.string.users_add_member_hint),
                leading = SoulIcons.User,
                imeAction = ImeAction.Done,
                onImeAction = { repo.addToGroup(group.name, addText); addText = "" },
            )
            DeskButton(res.getString(R.string.action_add), { repo.addToGroup(group.name, addText); addText = "" }, enabled = addText.isNotBlank())
        }
    }
}
