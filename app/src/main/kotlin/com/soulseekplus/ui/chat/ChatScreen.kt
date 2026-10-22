package com.soulseekplus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.chat.ChatTarget
import com.soulseekplus.core.chat.CommandParser
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.links.SlskUri
import com.soulseekplus.core.model.ChatMessage
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomState
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.UserPresence
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.CountBadge
import com.soulseekplus.ui.DeskCheckbox
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.FilterTextField
import com.soulseekplus.ui.ScrollRow
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.ToggleChip
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.timeText
import com.soulseekplus.ui.userStatusText
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
import desktopkit.input.ContextMenuArea
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

private const val LINK_TAG = "slsk"

@Composable
fun ChatScreen(vm: ChatViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val compact = currentWidthClass() == WidthClass.Compact
    val rooms by vm.repo.rooms.collectAsStateWithLifecycle()
    val privates by vm.repo.privateChats.collectAsStateWithLifecycle()
    val presence by container.client.users.collectAsStateWithLifecycle()
    val connection by container.client.connection.collectAsStateWithLifecycle()
    val unread by vm.repo.totalUnread.collectAsStateWithLifecycle()

    // Açık sohbet kapanmışsa (odadan çıkıldı) seçimi bırak.
    val target = vm.selected?.takeIf { t ->
        when (t) {
            is ChatTarget.Room -> t.name in rooms
            is ChatTarget.Private -> t.username in privates
        }
    }
    // Ekranda açık sohbet: bildirim bastırma ve okundu sayma için depoya bildirilir.
    DisposableEffect(target) {
        vm.repo.setActiveTarget(target)
        onDispose { vm.repo.setActiveTarget(null) }
    }

    var newChatDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_chat),
            subtitle = if (unread > 0) appResources().getString(R.string.chat_unread_subtitle, unread) else null,
            actions = {
                DeskIconButton(SoulIcons.ChatRoom, stringResource(R.string.chat_browse_rooms), { vm.openRoomList() }, selected = vm.showRoomList)
                DeskIconButton(DeskIcons.Plus, stringResource(R.string.chat_new_message), { newChatDialog = true })
            },
        )
        if (connection !is ConnectionState.LoggedIn) {
            StatusBanner(
                tone = BannerTone.Warning,
                text = stringResource(R.string.chat_not_connected),
                modifier = Modifier.padding(horizontal = DeskTheme.dimens.pagePadding, vertical = DeskTheme.dimens.gapS),
                actionLabel = if (connection is ConnectionState.Disconnected) stringResource(R.string.action_connect) else null,
                onAction = { container.client.connect(); container.ensureServiceRunning() },
            )
        }
        val showDetail = vm.showRoomList || target != null
        val detail: @Composable (Modifier) -> Unit = { m ->
            when {
                vm.showRoomList -> RoomListPane(vm, container, m)
                target != null -> ConversationPane(target, vm, rooms, privates, presence, connection, container, actions, m)
                else -> MessageScreen(
                    title = stringResource(R.string.chat_empty_title),
                    icon = SoulIcons.ChatRoom,
                    modifier = m,
                    description = stringResource(R.string.chat_empty_desc),
                    actionLabel = stringResource(R.string.chat_browse_rooms),
                    onAction = { vm.openRoomList() },
                )
            }
        }
        if (compact) {
            if (showDetail) {
                Row(Modifier.fillMaxWidth().padding(horizontal = DeskTheme.dimens.pagePadding)) {
                    DeskButton(stringResource(R.string.chat_conversations), {
                        vm.select(null)
                        vm.closeRoomList()
                    }, icon = DeskIcons.ArrowLeft)
                }
                detail(Modifier.weight(1f))
            } else {
                ListPane(vm, rooms, privates, presence, Modifier.weight(1f))
            }
        } else {
            HorizontalSplitPane(
                state = rememberSplitState(272.dp),
                start = { ListPane(vm, rooms, privates, presence, Modifier.fillMaxSize()) },
                end = { detail(Modifier.fillMaxSize()) },
                modifier = Modifier.weight(1f),
                minStart = 200.dp,
                minEnd = 360.dp,
                dividerLabel = stringResource(R.string.chat_resize_panes),
            )
        }
    }

    if (newChatDialog) NewChatDialog(onDismiss = { newChatDialog = false }, onOpen = { vm.openPrivate(it); newChatDialog = false })
    vm.help?.let { HelpDialog(it.commands, onDismiss = { vm.help = null }) }
    vm.aliasList?.let { AliasDialog(it, onDismiss = { vm.aliasList = null }) }
}

// ---------------------------------------------------------------------- liste bölmesi

@Composable
private fun ListPane(
    vm: ChatViewModel,
    rooms: Map<String, RoomState>,
    privates: Map<String, com.soulseekplus.core.model.PrivateConversation>,
    presence: Map<String, UserPresence>,
    modifier: Modifier,
) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = dimens.gapS)) {
        item { SectionCaption(stringResource(R.string.chat_rooms), Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapS)) }
        if (rooms.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.chat_no_rooms),
                    style = DeskTheme.type.caption,
                    color = DeskTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs),
                )
            }
        }
        items(rooms.values.toList(), key = { "r:" + it.name }) { room ->
            ListRow(
                selected = (vm.selected as? ChatTarget.Room)?.name == room.name,
                icon = if (room.isPrivate) SoulIcons.LockClosed else SoulIcons.ChatRoom,
                title = room.name,
                subtitle = res.getString(R.string.label_users_count, room.users.size),
                badge = room.unread,
                onClick = { vm.select(ChatTarget.Room(room.name)) },
                closeLabel = res.getString(R.string.chat_leave_room),
                onClose = { vm.leaveRoom(room.name) },
            )
        }
        item { SectionCaption(stringResource(R.string.chat_private), Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapS)) }
        if (privates.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.chat_no_private),
                    style = DeskTheme.type.caption,
                    color = DeskTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs),
                )
            }
        }
        items(privates.values.toList(), key = { "p:" + it.username }) { chat ->
            val status = presence[chat.username]?.status
            ListRow(
                selected = (vm.selected as? ChatTarget.Private)?.username == chat.username,
                icon = SoulIcons.User,
                title = chat.username,
                subtitle = status?.let { res.userStatusText(it) },
                subtitleColor = when (status) {
                    UserStatus.ONLINE -> DeskTheme.colors.success
                    UserStatus.AWAY -> DeskTheme.colors.warning
                    else -> DeskTheme.colors.textTertiary
                },
                badge = chat.unread,
                onClick = { vm.select(ChatTarget.Private(chat.username)) },
                closeLabel = res.getString(R.string.chat_close_chat),
                onClose = { vm.closePrivate(chat.username) },
            )
        }
    }
}

@Composable
private fun ListRow(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    badge: Int,
    onClick: () -> Unit,
    closeLabel: String,
    onClose: () -> Unit,
    subtitleColor: Color = DeskTheme.colors.textTertiary,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusControl)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.gapS, vertical = 1.dp)
            .background(stateBackground(state, selected = selected), shape)
            .focusRing(state, shape)
            .deskClickable(interaction, onClick)
            .padding(start = dimens.gapM, end = dimens.gapXs, top = dimens.gapXs, bottom = dimens.gapXs)
            .heightIn(min = dimens.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.onSelection else colors.textSecondary, modifier = Modifier.size(dimens.icon))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (badge > 0) DeskTheme.type.bodyStrong else DeskTheme.type.body,
                color = if (selected) colors.onSelection else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = DeskTheme.type.caption, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CountBadge(badge)
        DeskIconButton(DeskIcons.Close, closeLabel, onClose, size = 28.dp, iconSize = 12.dp)
    }
}

// ---------------------------------------------------------------------- sohbet bölmesi

@Composable
private fun ConversationPane(
    target: ChatTarget,
    vm: ChatViewModel,
    rooms: Map<String, RoomState>,
    privates: Map<String, com.soulseekplus.core.model.PrivateConversation>,
    presence: Map<String, UserPresence>,
    connection: ConnectionState,
    container: AppContainer,
    actions: AppActions,
    modifier: Modifier,
) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val ownName = (connection as? ConnectionState.LoggedIn)?.username.orEmpty()
    val room = (target as? ChatTarget.Room)?.let { rooms[it.name] }
    val messages: List<ChatMessage> = when (target) {
        is ChatTarget.Room -> room?.messages.orEmpty()
        is ChatTarget.Private -> privates[target.username]?.messages.orEmpty()
    }
    val compact = currentWidthClass() == WidthClass.Compact
    var wallDialog by rememberSaveable(target) { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        // Başlık
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (target) {
                        is ChatTarget.Room -> target.name
                        is ChatTarget.Private -> target.username
                    },
                    style = DeskTheme.type.title,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when (target) {
                    is ChatTarget.Room -> room?.let { res.getString(R.string.label_users_count, it.users.size) }
                    is ChatTarget.Private -> presence[target.username]?.status?.let { res.userStatusText(it) }
                }
                if (sub != null) Text(sub, style = DeskTheme.type.caption, color = colors.textTertiary)
            }
            when (target) {
                is ChatTarget.Room -> {
                    DeskIconButton(SoulIcons.Group, stringResource(R.string.chat_users), { vm.showUsers = !vm.showUsers }, selected = vm.showUsers)
                    DeskIconButton(DeskIcons.Subtitle, stringResource(R.string.chat_wall), { wallDialog = true })
                    DeskIconButton(DeskIcons.Delete, stringResource(R.string.chat_clear), { vm.repo.clearMessages(target) })
                    DeskIconButton(DeskIcons.Close, stringResource(R.string.chat_leave_room), { vm.leaveRoom(target.name) })
                }
                is ChatTarget.Private -> {
                    DeskIconButton(DeskIcons.Folder, stringResource(R.string.menu_user_browse), { actions.browseUser(target.username) })
                    DeskIconButton(DeskIcons.Info, stringResource(R.string.menu_user_info), { actions.userInfo(target.username) })
                    DeskIconButton(DeskIcons.Delete, stringResource(R.string.chat_clear), { vm.repo.clearMessages(target) })
                    DeskIconButton(DeskIcons.Close, stringResource(R.string.chat_close_chat), { vm.closePrivate(target.username) })
                }
            }
        }
        HorizontalRule()
        if (room != null && room.tickers.isNotEmpty()) WallStrip(room.tickers)

        val showUsersPanel = target is ChatTarget.Room && vm.showUsers
        if (showUsersPanel && compact) {
            UsersPane(room?.users.orEmpty(), container, actions, Modifier.weight(1f))
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                MessageList(messages, target, container, actions, Modifier.weight(1f).fillMaxHeight())
                if (showUsersPanel) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                    UsersPane(room?.users.orEmpty(), container, actions, Modifier.width(300.dp).fillMaxHeight())
                }
            }
        }
        InputBar(target, vm, actions)
    }

    if (wallDialog && target is ChatTarget.Room) {
        var text by remember { mutableStateOf(room?.tickers?.get(ownName).orEmpty()) }
        DeskFormDialog(
            title = res.getString(R.string.chat_wall_title),
            confirmLabel = res.getString(R.string.action_save),
            dismissLabel = res.getString(R.string.action_cancel),
            onConfirm = { vm.repo.setTicker(target.name, text); wallDialog = false },
            onDismiss = { wallDialog = false },
        ) {
            Text(res.getString(R.string.chat_wall_desc), style = DeskTheme.type.body, color = colors.textSecondary)
            DeskTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth(), hint = res.getString(R.string.chat_wall_hint), singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun WallStrip(tickers: Map<String, String>) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val text = remember(tickers) { tickers.entries.joinToString("   •   ") { "${it.key}: ${it.value}" } }
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        Icon(DeskIcons.Subtitle, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(dimens.icon))
        Text(text, style = DeskTheme.type.caption, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalRule()
}

@Composable
private fun MessageList(messages: List<ChatMessage>, target: ChatTarget, container: AppContainer, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val ua = rememberUserActions(container, actions)
    val listState = rememberLazyListState()
    val count = messages.size
    val countState = rememberUpdatedState(count)

    // Alta yapışık kal: yeni mesaj geldiğinde kullanıcı en alttaysa (ya da sohbet değiştiyse) sona kaydır.
    LaunchedEffect(target) {
        if (countState.value > 0) listState.scrollToItem(countState.value - 1)
    }
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= count - 3) listState.animateScrollToItem(count - 1)
    }

    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_no_messages), style = DeskTheme.type.body, color = colors.textTertiary)
        }
        return
    }
    val palette = listOf(colors.accentText, colors.success, colors.warning, colors.brandSecondary)
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dimens.pagePadding, vertical = dimens.gapS),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(messages) { message ->
            val text = remember(message, res.configuration, colors) { buildMessage(message, res.timeText(message.timestampMillis), palette, colors.textTertiary, colors.textSecondary, colors.accentText, colors.text) }
            ContextMenuArea(
                entries = {
                    buildList {
                        add(MenuEntry.Item(res.getString(R.string.chat_copy_message), { ua.copy(message.text) }, icon = SoulIcons.Copy))
                        if (!message.isSystem && message.username.isNotEmpty() && !message.isOwn) {
                            add(MenuEntry.Separator)
                            addAll(ua.userEntries(message.username))
                        }
                    }
                },
            ) {
                ClickableText(
                    text = text,
                    style = DeskTheme.type.body.copy(color = colors.text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (message.mentionsMe) colors.accent.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(dimens.radiusSmall))
                        .padding(vertical = 1.dp),
                    onClick = { offset ->
                        text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let { annotation ->
                            SlskUri.parse(annotation.item)?.let(actions::openLink)
                        }
                    },
                )
            }
        }
    }
}

private fun buildMessage(
    message: ChatMessage,
    time: String,
    palette: List<Color>,
    timeColor: Color,
    systemColor: Color,
    linkColor: Color,
    textColor: Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = timeColor)) { append("$time  ") }
    if (message.isSystem) {
        withStyle(SpanStyle(color = systemColor, fontStyle = FontStyle.Italic)) { appendWithLinks(message.text, linkColor) }
        return@buildAnnotatedString
    }
    val nameColor = if (message.isOwn) linkColor else palette[Math.floorMod(message.username.hashCode(), palette.size)]
    if (message.isAction) {
        withStyle(SpanStyle(color = nameColor, fontStyle = FontStyle.Italic)) { append("* ${message.username} ") }
        withStyle(SpanStyle(color = textColor, fontStyle = FontStyle.Italic)) { appendWithLinks(message.text, linkColor) }
    } else {
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold)) { append(message.username) }
        withStyle(SpanStyle(color = textColor)) {
            append("  ")
            appendWithLinks(message.text, linkColor)
        }
    }
}

private fun AnnotatedString.Builder.appendWithLinks(text: String, linkColor: Color) {
    val links = SlskUri.findLinks(text)
    var cursor = 0
    for ((range, link) in links) {
        if (range.first > cursor) append(text.substring(cursor, range.first))
        val start = length
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(text.substring(range.first, range.last + 1)) }
        addStringAnnotation(LINK_TAG, link.toUriString(), start, length)
        cursor = range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

// ---------------------------------------------------------------------- üyeler

@Composable
private fun UsersPane(users: List<RoomUser>, container: AppContainer, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val ua = rememberUserActions(container, actions)
    var filter by rememberSaveable { mutableStateOf("") }
    var sortColumn by rememberSaveable { mutableStateOf(0) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    val rows = remember(users, filter, sortColumn, ascending) {
        val filtered = users.filter { filter.isBlank() || it.username.contains(filter.trim(), ignoreCase = true) }
        val sorted = when (sortColumn) {
            1 -> filtered.sortedBy { it.countryCode.orEmpty() }
            2 -> filtered.sortedBy { it.stats.averageSpeed }
            else -> filtered.sortedBy { it.username.lowercase() }
        }
        if (ascending) sorted else sorted.reversed()
    }
    val columns = remember(res) {
        listOf(
            TableColumn<RoomUser>(res.getString(R.string.col_user), ColumnWidth.Weight(1f)) { user ->
                val dot = when (user.status) {
                    UserStatus.ONLINE -> "● "
                    UserStatus.AWAY -> "◐ "
                    UserStatus.OFFLINE -> "○ "
                }
                dot + user.username
            },
            TableColumn<RoomUser>(res.getString(R.string.col_country), ColumnWidth.Fixed(58.dp)) { user ->
                user.countryCode?.let { Formatting.flag(it) + " " + it.uppercase() }.orEmpty()
            },
            TableColumn<RoomUser>(res.getString(R.string.col_speed), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { user ->
                Formatting.speed(user.stats.averageSpeed.toLong())
            },
        )
    }
    Column(modifier) {
        FilterTextField(filter, { filter = it }, stringResource(R.string.chat_filter_users), Modifier.fillMaxWidth().padding(DeskTheme.dimens.gapS))
        DenseTable(
            columns = columns,
            rows = rows,
            key = { it.username },
            onRowClick = { _, _ -> },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            sort = TableSort(sortColumn, ascending),
            onSortChange = { sortColumn = it.column; ascending = it.ascending },
            rowMenuLabel = res.getString(R.string.action_more),
            rowMenu = { user, _ -> ua.userEntries(user.username) },
        )
    }
}

// ---------------------------------------------------------------------- giriş çubuğu

@Composable
private fun InputBar(target: ChatTarget, vm: ChatViewModel, actions: AppActions) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val draft = vm.draft(target)
    val suggestions = remember(draft) { if (draft.startsWith("/") && !draft.contains(' ')) vm.repo.completeCommand(draft).take(8) else emptyList() }
    Column {
        HorizontalRule()
        vm.feedback?.let { text ->
            StatusBanner(
                tone = BannerTone.Info,
                text = text,
                modifier = Modifier.padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
                closeLabel = res.getString(R.string.action_dismiss),
                onClose = { vm.feedback = null },
            )
        }
        if (suggestions.isNotEmpty() && !(suggestions.size == 1 && suggestions[0] == draft)) {
            ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs)) {
                for (s in suggestions) ToggleChip(s, false, { vm.setDraft(target, "$s ") })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskTextField(
                value = draft,
                onValueChange = { vm.setDraft(target, it) },
                modifier = Modifier.weight(1f),
                hint = res.getString(if (target is ChatTarget.Room) R.string.chat_hint_room else R.string.chat_hint_private),
                imeAction = ImeAction.Send,
                onImeAction = { vm.submit(draft, actions) },
            )
            DeskIconButton(SoulIcons.Send, stringResource(R.string.chat_send), { vm.submit(draft, actions) }, enabled = draft.isNotBlank(), tint = DeskTheme.colors.accentText)
        }
    }
}

// ---------------------------------------------------------------------- oda listesi

@Composable
private fun RoomListPane(vm: ChatViewModel, container: AppContainer, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val list by vm.repo.roomList.collectAsStateWithLifecycle()
    val joined by vm.repo.rooms.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var newRoom by rememberSaveable { mutableStateOf("") }
    var privateRoom by rememberSaveable { mutableStateOf(false) }
    var sortColumn by rememberSaveable { mutableStateOf(1) }
    var ascending by rememberSaveable { mutableStateOf(false) }

    val rows = remember(list, filter, sortColumn, ascending) {
        val filtered = list.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
        val sorted = if (sortColumn == 0) filtered.sortedBy { it.name.lowercase() } else filtered.sortedBy { it.userCount }
        if (ascending) sorted else sorted.reversed()
    }
    val columns = remember(res, joined.keys) {
        listOf(
            TableColumn<RoomInfo>(res.getString(R.string.chat_col_room), ColumnWidth.Weight(1f)) { room ->
                (if (room.isPrivate) "🔒 " else "") + room.name + if (room.name in joined) "  ✓" else ""
            },
            TableColumn<RoomInfo>(res.getString(R.string.chat_col_users), ColumnWidth.Fixed(84.dp), alignEnd = true, mono = true) { it.userCount.toString() },
        )
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.chat_room_list), style = DeskTheme.type.title, color = DeskTheme.colors.text, modifier = Modifier.weight(1f))
            DeskIconButton(SoulIcons.Retry, stringResource(R.string.action_refresh), { vm.repo.refreshRoomList() })
            DeskIconButton(DeskIcons.Close, stringResource(R.string.action_close), { vm.closeRoomList() })
        }
        ScrollRow(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding)) {
            DeskTextField(
                value = newRoom,
                onValueChange = { newRoom = it },
                modifier = Modifier.width(220.dp),
                hint = res.getString(R.string.chat_join_hint),
                imeAction = ImeAction.Go,
                onImeAction = { if (newRoom.isNotBlank()) { vm.openRoom(newRoom, privateRoom); newRoom = "" } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeskCheckbox(privateRoom, { privateRoom = it })
                Text(res.getString(R.string.chat_private_room), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
            }
            DeskButton(res.getString(R.string.chat_join), { vm.openRoom(newRoom, privateRoom); newRoom = "" }, primary = true, enabled = newRoom.isNotBlank())
        }
        FilterTextField(filter, { filter = it }, stringResource(R.string.chat_filter_rooms), Modifier.fillMaxWidth().padding(dimens.pagePadding, dimens.gapS))
        if (rows.isEmpty()) {
            MessageScreen(
                title = stringResource(R.string.chat_no_room_list),
                icon = SoulIcons.ChatRoom,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.chat_no_room_list_desc),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = { vm.repo.refreshRoomList() },
            )
        } else {
            DenseTable(
                columns = columns,
                rows = rows,
                key = { it.name },
                onRowClick = { room, _ -> vm.openRoom(room.name) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                sort = TableSort(sortColumn, ascending),
                onSortChange = { sortColumn = it.column; ascending = it.ascending },
                rowMenuLabel = res.getString(R.string.action_more),
                rowMenu = { room, _ ->
                    listOf(
                        MenuEntry.Item(res.getString(R.string.chat_join), { vm.openRoom(room.name) }, icon = SoulIcons.ChatRoom, enabled = room.name !in joined),
                        MenuEntry.Item(res.getString(R.string.chat_leave_room), { vm.leaveRoom(room.name) }, icon = DeskIcons.Close, enabled = room.name in joined),
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- pencereler

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val res = appResources()
    var name by remember { mutableStateOf("") }
    DeskFormDialog(
        title = res.getString(R.string.chat_new_message),
        confirmLabel = res.getString(R.string.action_open),
        dismissLabel = res.getString(R.string.action_cancel),
        onConfirm = { onOpen(name) },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
    ) {
        DeskTextField(name, { name = it }, Modifier.fillMaxWidth(), hint = res.getString(R.string.label_username), leading = SoulIcons.User, imeAction = ImeAction.Go, onImeAction = { if (name.isNotBlank()) onOpen(name) })
    }
}

@Composable
private fun HelpDialog(commands: List<com.soulseekplus.core.chat.CommandInfo>, onDismiss: () -> Unit) {
    val res = appResources()
    val colors = DeskTheme.colors
    DeskFormDialog(
        title = res.getString(R.string.chat_help_title),
        confirmLabel = res.getString(R.string.action_close),
        dismissLabel = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
            for (c in commands) {
                Column {
                    val aliases = if (c.aliases.isEmpty()) "" else "  (" + c.aliases.joinToString(", ") { "/$it" } + ")"
                    Text("/${c.name} ${c.parameters}$aliases".trim(), style = DeskTheme.type.mono, color = colors.accentText)
                    Text(res.getString(commandDescription(c.name)), style = DeskTheme.type.caption, color = colors.textSecondary)
                }
            }
            Text(res.getString(R.string.chat_help_alias_tip), style = DeskTheme.type.caption, color = colors.textTertiary)
        }
    }
}

@Composable
private fun AliasDialog(aliases: Map<String, String>, onDismiss: () -> Unit) {
    val res = appResources()
    DeskFormDialog(
        title = res.getString(R.string.chat_aliases_title),
        confirmLabel = res.getString(R.string.action_close),
        dismissLabel = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for ((name, expansion) in aliases.toSortedMap()) {
                Text("/$name  →  $expansion", style = DeskTheme.type.mono, color = DeskTheme.colors.text)
            }
        }
    }
}

private fun commandDescription(name: String): Int = when (name) {
    "help" -> R.string.command_help_desc
    "away" -> R.string.command_away_desc
    "back" -> R.string.command_back_desc
    "clear" -> R.string.command_clear_desc
    "me" -> R.string.command_me_desc
    "join" -> R.string.command_join_desc
    "leave" -> R.string.command_leave_desc
    "say" -> R.string.command_say_desc
    "pm" -> R.string.command_pm_desc
    "close" -> R.string.command_close_desc
    "msg" -> R.string.command_msg_desc
    "add" -> R.string.command_add_desc
    "rem" -> R.string.command_rem_desc
    "browse" -> R.string.command_browse_desc
    "whois" -> R.string.command_whois_desc
    "ban" -> R.string.command_ban_desc
    "unban" -> R.string.command_unban_desc
    "ignore" -> R.string.command_ignore_desc
    "unignore" -> R.string.command_unignore_desc
    "search" -> R.string.command_search_desc
    "rsearch" -> R.string.command_rsearch_desc
    "bsearch" -> R.string.command_bsearch_desc
    "usearch" -> R.string.command_usearch_desc
    "alias" -> R.string.command_alias_desc
    "unalias" -> R.string.command_unalias_desc
    else -> R.string.chat_command_generic
}
