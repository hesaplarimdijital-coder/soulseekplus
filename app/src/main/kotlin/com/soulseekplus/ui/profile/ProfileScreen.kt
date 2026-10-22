package com.soulseekplus.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskProgressBar
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.KeyValueRow
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.SurfaceCard
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.common.rememberUserActions
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.userStatusText
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.SectionCaption
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.theme.DeskTheme

@Composable
fun ProfileScreen(vm: ProfileViewModel, container: AppContainer, actions: AppActions, modifier: Modifier = Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val state = vm.state

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.nav_profile),
            subtitle = (state as? ProfileState.Loaded)?.username,
            actions = { DeskIconButton(SoulIcons.Retry, stringResource(R.string.action_refresh), { vm.reload() }, enabled = vm.usernameInput.isNotBlank()) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskTextField(
                value = vm.usernameInput,
                onValueChange = { vm.usernameInput = it },
                modifier = Modifier.weight(1f),
                hint = stringResource(R.string.profile_hint_user),
                leading = SoulIcons.User,
                imeAction = ImeAction.Go,
                onImeAction = { vm.open(vm.usernameInput) },
            )
            DeskButton(stringResource(R.string.profile_lookup), { vm.open(vm.usernameInput) }, primary = true, enabled = vm.usernameInput.isNotBlank())
        }
        when (state) {
            ProfileState.Idle -> MessageScreen(
                title = stringResource(R.string.profile_idle_title),
                icon = SoulIcons.User,
                modifier = Modifier.weight(1f),
                description = stringResource(R.string.profile_idle_desc),
            )
            is ProfileState.Loading -> Column(Modifier.weight(1f)) {
                DeskProgressBar(null, Modifier.fillMaxWidth(), height = 2.dp)
                MessageScreen(title = res.getString(R.string.profile_loading, state.username), icon = SoulIcons.User)
            }
            is ProfileState.Failed -> Column(Modifier.weight(1f)) {
                StatusBanner(
                    tone = BannerTone.Error,
                    text = res.getString(R.string.profile_failed, state.username, state.message ?: res.getString(R.string.err_generic)),
                    modifier = Modifier.padding(dimens.pagePadding),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { vm.reload() },
                )
            }
            is ProfileState.Loaded -> LoadedProfile(state, container, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoadedProfile(state: ProfileState.Loaded, container: AppContainer, actions: AppActions, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val presenceMap by container.client.users.collectAsStateWithLifecycle()
    val lists by container.userLists.lists.collectAsStateWithLifecycle()
    val ua = rememberUserActions(container, actions)
    val name = state.username
    val info = state.info
    val presence = presenceMap[name]
    val picture = remember(state.picture) { state.picture?.asImageBitmap() }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
        verticalArrangement = Arrangement.spacedBy(dimens.gapM),
    ) {
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapL)) {
                Box(
                    Modifier.size(112.dp).clip(RoundedCornerShape(dimens.radiusCard)).background(colors.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picture != null) {
                        Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        androidx.compose.material3.Icon(SoulIcons.User, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(48.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    Text(name, style = DeskTheme.type.title, color = colors.text)
                    val status = presence?.status
                    if (status != null) {
                        val tone = when (status) {
                            UserStatus.ONLINE -> colors.success
                            UserStatus.AWAY -> colors.warning
                            UserStatus.OFFLINE -> colors.textTertiary
                        }
                        Text(res.userStatusText(status) + if (presence?.privileged == true) " · " + res.getString(R.string.profile_privileged) else "", style = DeskTheme.type.body, color = tone)
                    }
                    val code = presence?.countryCode
                    if (!code.isNullOrBlank()) Text(Formatting.flag(code) + " " + code.uppercase(), style = DeskTheme.type.body, color = colors.textSecondary)
                    if (name in lists.buddies) Text(res.getString(R.string.profile_is_buddy), style = DeskTheme.type.label, color = colors.accentText)
                    if (name in lists.banned) Text(res.getString(R.string.profile_is_banned), style = DeskTheme.type.label, color = colors.danger)
                    if (name in lists.ignored) Text(res.getString(R.string.profile_is_ignored), style = DeskTheme.type.label, color = colors.warning)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                DeskButton(stringResource(R.string.menu_user_browse), { actions.browseUser(name) }, icon = SoulIcons.BrowseUser, primary = true)
                DeskButton(stringResource(R.string.menu_user_message), { actions.privateChat(name) }, icon = SoulIcons.Chat)
            }
        }

        SurfaceCard(Modifier.fillMaxWidth()) {
            SectionCaption(stringResource(R.string.profile_about))
            Text(
                text = info.description.ifBlank { res.getString(R.string.profile_no_description) },
                style = DeskTheme.type.body,
                color = if (info.description.isBlank()) colors.textTertiary else colors.text,
            )
        }

        SurfaceCard(Modifier.fillMaxWidth()) {
            SectionCaption(stringResource(R.string.profile_sharing))
            KeyValueRow(res.getString(R.string.profile_slots), info.uploadSlots.toString(), mono = true)
            KeyValueRow(
                res.getString(R.string.profile_slots_free),
                res.getString(if (info.slotsAvailable) R.string.label_yes else R.string.label_no),
                valueColor = if (info.slotsAvailable) colors.success else colors.textSecondary,
            )
            KeyValueRow(res.getString(R.string.profile_queue), info.queueSize.toString(), mono = true)
            info.uploadPermission?.let { KeyValueRow(res.getString(R.string.profile_permission), permissionText(res, it)) }
            presence?.stats?.let { stats ->
                KeyValueRow(res.getString(R.string.profile_avg_speed), Formatting.speed(stats.averageSpeed.toLong()), mono = true)
                KeyValueRow(res.getString(R.string.profile_uploads), stats.uploadCount.toString(), mono = true)
                KeyValueRow(res.getString(R.string.profile_files), stats.files.toString(), mono = true)
                KeyValueRow(res.getString(R.string.profile_folders), stats.folders.toString(), mono = true)
            }
            state.address?.let { (ip, port) -> KeyValueRow(res.getString(R.string.profile_address), "$ip:$port", mono = true) }
        }

        SurfaceCard(Modifier.fillMaxWidth()) {
            SectionCaption(stringResource(R.string.profile_lists))
            val buddy = name in lists.buddies
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
                DeskButton(
                    stringResource(if (buddy) R.string.menu_user_remove_buddy else R.string.menu_user_add_buddy),
                    { if (buddy) container.userLists.removeBuddy(name) else container.userLists.addBuddy(name) },
                    icon = SoulIcons.Buddy,
                )
                DeskButton(
                    stringResource(if (name in lists.banned) R.string.menu_user_unban else R.string.menu_user_ban),
                    { if (name in lists.banned) container.userLists.unban(name) else container.userLists.ban(name) },
                    icon = SoulIcons.Ban,
                )
                DeskButton(
                    stringResource(if (name in lists.ignored) R.string.menu_user_unignore else R.string.menu_user_ignore),
                    { if (name in lists.ignored) container.userLists.unignore(name) else container.userLists.ignore(name) },
                    icon = SoulIcons.Ignore,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                DeskButton(stringResource(R.string.menu_copy_username), { ua.copy(name) }, icon = SoulIcons.Copy)
                DeskButton(stringResource(R.string.menu_copy_link), { ua.copy(com.soulseekplus.core.links.SlskUri.forUser(name)) }, icon = SoulIcons.Link)
            }
        }
    }
}

private fun permissionText(res: android.content.res.Resources, code: Int): String = when (code) {
    0 -> res.getString(R.string.perm_nobody)
    1 -> res.getString(R.string.perm_everyone)
    2 -> res.getString(R.string.perm_buddies)
    3 -> res.getString(R.string.perm_recognized)
    else -> res.getString(R.string.label_unknown)
}
