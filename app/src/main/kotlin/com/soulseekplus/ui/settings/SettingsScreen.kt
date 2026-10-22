package com.soulseekplus.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.UploadQueueMode
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.AppShortcuts
import com.soulseekplus.ui.BannerTone
import com.soulseekplus.ui.DeskFormDialog
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.NumberField
import com.soulseekplus.ui.StatusBanner
import com.soulseekplus.ui.SwitchRow
import com.soulseekplus.ui.account.AccountForm
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.connectionText
import com.soulseekplus.ui.groupModeText
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.portMappingText
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.SegmentedChoice
import desktopkit.components.SettingRow
import desktopkit.input.ShortcutScope
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.SettingsSection
import desktopkit.settings.ShortcutListSection
import desktopkit.theme.DeskTheme

@Composable
fun SettingsScreen(
    container: AppContainer,
    prefs: DeskPreferences,
    actions: AppActions,
    onAccountSaved: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val settings = container.settings
    val client by settings.clientSettings.collectAsStateWithLifecycle()
    val app by settings.appPrefs.collectAsStateWithLifecycle()
    val downloadRoot by settings.downloadRootUri.collectAsStateWithLifecycle()
    val connection by container.client.connection.collectAsStateWithLifecycle()
    val portMapping by container.client.portMapping.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                settings.setDownloadRoot(uri.toString())
            } catch (e: SecurityException) {
                actions.toast(R.string.settings_download_permission_failed)
            }
        }
    }
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                settings.update { it.copy(userPictureUri = uri.toString()) }
            } catch (e: SecurityException) {
                actions.toast(R.string.settings_picture_permission_failed)
            }
        }
    }

    var description by rememberSaveable { mutableStateOf(client.userDescription) }
    var roomsText by rememberSaveable { mutableStateOf(client.autoJoinRooms.joinToString("\n")) }
    var aliasDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.nav_settings))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.gapM),
        ) {
            // ------------------------------------------------------------ hesap
            SettingsSection(stringResource(R.string.settings_account)) {
                Text(res.connectionText(connection), style = DeskTheme.type.caption, color = DeskTheme.colors.textSecondary)
                AccountForm(container, onAccountSaved, Modifier.fillMaxWidth().widthIn(max = 520.dp))
                SwitchRow(
                    stringResource(R.string.settings_auto_connect),
                    client.autoConnect,
                    { v -> settings.update { it.copy(autoConnect = v) } },
                    subtitle = stringResource(R.string.settings_auto_connect_desc),
                )
            }

            // ------------------------------------------------------------ ağ
            SettingsSection(stringResource(R.string.settings_network)) {
                SettingRow(stringResource(R.string.settings_listen_port), subtitle = stringResource(R.string.settings_listen_port_desc)) {
                    NumberField(client.listenPort, { v -> settings.update { it.copy(listenPort = v) } }, min = 1024, max = 65535)
                }
                SwitchRow(
                    stringResource(R.string.settings_port_mapping),
                    client.portMappingEnabled,
                    { v -> settings.update { it.copy(portMappingEnabled = v) } },
                    subtitle = res.portMappingText(portMapping),
                )
                SwitchRow(
                    stringResource(R.string.settings_wifi_only),
                    client.transfersOnWifiOnly,
                    { v -> settings.update { it.copy(transfersOnWifiOnly = v) } },
                    subtitle = stringResource(R.string.settings_wifi_only_desc),
                )
            }

            // ------------------------------------------------------------ yüklemeler
            SettingsSection(stringResource(R.string.settings_uploads)) {
                SettingRow(stringResource(R.string.settings_upload_slots), subtitle = stringResource(R.string.settings_upload_slots_desc)) {
                    NumberField(client.uploadSlots, { v -> settings.update { it.copy(uploadSlots = v) } }, min = 1, max = 100)
                }
                SettingRow(stringResource(R.string.settings_queue_mode)) {
                    SegmentedChoice(
                        options = listOf(
                            UploadQueueMode.ROUND_ROBIN to res.getString(R.string.queue_round_robin),
                            UploadQueueMode.FIFO to res.getString(R.string.queue_fifo),
                        ),
                        selected = client.uploadQueueMode,
                        onSelect = { v -> settings.update { it.copy(uploadQueueMode = v) } },
                    )
                }
                SettingRow(stringResource(R.string.settings_upload_limit), subtitle = stringResource(R.string.settings_limit_desc)) {
                    NumberField(client.uploadSpeedLimitKiB, { v -> settings.update { it.copy(uploadSpeedLimitKiB = v) } }, max = 10_000_000)
                }
                SwitchRow(
                    stringResource(R.string.settings_prioritize_buddies),
                    client.prioritizeBuddies,
                    { v -> settings.update { it.copy(prioritizeBuddies = v) } },
                    subtitle = stringResource(R.string.settings_prioritize_buddies_desc),
                )
                SwitchRow(
                    stringResource(R.string.settings_buddies_only),
                    client.uploadToBuddiesOnly,
                    { v -> settings.update { it.copy(uploadToBuddiesOnly = v) } },
                    subtitle = stringResource(R.string.settings_buddies_only_desc),
                )
                SettingRow(stringResource(R.string.settings_max_queued_files), subtitle = stringResource(R.string.settings_zero_unlimited)) {
                    NumberField(client.maxQueuedFilesPerUser, { v -> settings.update { it.copy(maxQueuedFilesPerUser = v) } }, max = 1_000_000)
                }
                SettingRow(stringResource(R.string.settings_max_queued_mib), subtitle = stringResource(R.string.settings_zero_unlimited)) {
                    NumberField(client.maxQueuedMiBPerUser, { v -> settings.update { it.copy(maxQueuedMiBPerUser = v) } }, max = 10_000_000)
                }
            }

            // ------------------------------------------------------------ indirmeler
            SettingsSection(stringResource(R.string.settings_downloads)) {
                SettingRow(
                    stringResource(R.string.settings_download_folder),
                    subtitle = downloadRoot?.let { treeLabel(Uri.parse(it)) } ?: stringResource(R.string.settings_download_folder_unset),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                        DeskButton(stringResource(R.string.action_choose), { downloadPicker.launch(null) }, icon = DeskIcons.Folder)
                    }
                }
                if (downloadRoot == null) {
                    StatusBanner(BannerTone.Warning, stringResource(R.string.settings_download_folder_warning), Modifier.fillMaxWidth())
                }
                SettingRow(stringResource(R.string.settings_download_limit), subtitle = stringResource(R.string.settings_limit_desc)) {
                    NumberField(client.downloadSpeedLimitKiB, { v -> settings.update { it.copy(downloadSpeedLimitKiB = v) } }, max = 10_000_000)
                }
                SettingRow(stringResource(R.string.settings_max_active), subtitle = stringResource(R.string.settings_zero_unlimited)) {
                    NumberField(client.maxActiveDownloads, { v -> settings.update { it.copy(maxActiveDownloads = v) } }, max = 1000)
                }
                SwitchRow(
                    stringResource(R.string.settings_auto_retry),
                    client.autoRetryDownloads,
                    { v -> settings.update { it.copy(autoRetryDownloads = v) } },
                    subtitle = stringResource(R.string.settings_auto_retry_desc),
                )
                SwitchRow(
                    stringResource(R.string.settings_user_folder),
                    client.downloadsIntoUserFolder,
                    { v -> settings.update { it.copy(downloadsIntoUserFolder = v) } },
                    subtitle = stringResource(R.string.settings_user_folder_desc),
                )
            }

            // ------------------------------------------------------------ arama
            SettingsSection(stringResource(R.string.settings_search)) {
                SwitchRow(
                    stringResource(R.string.settings_respond_searches),
                    client.respondToSearches,
                    { v -> settings.update { it.copy(respondToSearches = v) } },
                    subtitle = stringResource(R.string.settings_respond_searches_desc),
                )
                SettingRow(stringResource(R.string.settings_max_results_sent), subtitle = stringResource(R.string.settings_max_results_sent_desc)) {
                    NumberField(client.maxSearchResultsToSend, { v -> settings.update { it.copy(maxSearchResultsToSend = v) } }, max = 100_000)
                }
                SettingRow(stringResource(R.string.settings_max_results_kept), subtitle = stringResource(R.string.settings_max_results_kept_desc)) {
                    NumberField(client.maxSearchResultsKept, { v -> settings.update { it.copy(maxSearchResultsKept = v) } }, min = 100, max = 200_000)
                }
                SettingRow(stringResource(R.string.settings_default_grouping)) {
                    SegmentedChoice(
                        options = GroupMode.entries.map { it to res.groupModeText(it) },
                        selected = app.defaultGroupMode,
                        onSelect = { v -> settings.updateApp { it.copy(defaultGroupMode = v) } },
                    )
                }
                SwitchRow(
                    stringResource(R.string.settings_expand_groups),
                    app.expandGroups,
                    { v -> settings.updateApp { it.copy(expandGroups = v) } },
                )
                SwitchRow(
                    stringResource(R.string.settings_read_tags),
                    app.readAudioAttributes,
                    { v -> settings.updateApp { it.copy(readAudioAttributes = v) } },
                    subtitle = stringResource(R.string.settings_read_tags_desc),
                )
            }

            // ------------------------------------------------------------ profil
            SettingsSection(stringResource(R.string.settings_profile)) {
                Text(stringResource(R.string.settings_profile_desc), style = DeskTheme.type.caption, color = DeskTheme.colors.textSecondary)
                DeskTextField(
                    description,
                    { description = it.take(2000) },
                    Modifier.fillMaxWidth(),
                    hint = stringResource(R.string.settings_profile_hint),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
                    DeskButton(
                        stringResource(R.string.action_save),
                        { settings.update { it.copy(userDescription = description.trim()) } },
                        primary = true,
                        enabled = description.trim() != client.userDescription,
                    )
                }
                SettingRow(
                    stringResource(R.string.settings_picture),
                    subtitle = if (client.userPictureUri != null) stringResource(R.string.settings_picture_set) else stringResource(R.string.settings_picture_none),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                        DeskButton(stringResource(R.string.action_choose), { picturePicker.launch(arrayOf("image/png", "image/jpeg")) }, icon = DeskIcons.Folder)
                        if (client.userPictureUri != null) {
                            DeskButton(stringResource(R.string.action_remove), { settings.update { it.copy(userPictureUri = null) } }, icon = DeskIcons.Delete)
                        }
                    }
                }
            }

            // ------------------------------------------------------------ sohbet
            SettingsSection(stringResource(R.string.settings_chat)) {
                Text(stringResource(R.string.settings_auto_join_desc), style = DeskTheme.type.caption, color = DeskTheme.colors.textSecondary)
                DeskTextField(
                    roomsText,
                    { roomsText = it },
                    Modifier.fillMaxWidth(),
                    hint = stringResource(R.string.settings_auto_join_hint),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                )
                val parsedRooms = remember(roomsText) { roomsText.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
                DeskButton(
                    stringResource(R.string.action_save),
                    { settings.update { it.copy(autoJoinRooms = parsedRooms) } },
                    primary = true,
                    enabled = parsedRooms != client.autoJoinRooms,
                )
                SettingRow(
                    stringResource(R.string.settings_aliases),
                    subtitle = res.getString(R.string.settings_aliases_count, client.commandAliases.size),
                ) {
                    DeskButton(stringResource(R.string.action_manage), { aliasDialog = true }, icon = SoulIcons.Edit)
                }
            }

            // ------------------------------------------------------------ bildirimler
            SettingsSection(stringResource(R.string.settings_notifications)) {
                SwitchRow(
                    stringResource(R.string.settings_notify_messages),
                    app.notifyMessages,
                    { v -> settings.updateApp { it.copy(notifyMessages = v) } },
                    subtitle = stringResource(R.string.settings_notify_messages_desc),
                )
                SwitchRow(
                    stringResource(R.string.settings_notify_wishlist),
                    app.notifyWishlist,
                    { v -> settings.updateApp { it.copy(notifyWishlist = v) } },
                    subtitle = stringResource(R.string.settings_notify_wishlist_desc),
                )
                SwitchRow(
                    stringResource(R.string.settings_notify_transfers),
                    app.notifyTransfers,
                    { v -> settings.updateApp { it.copy(notifyTransfers = v) } },
                    subtitle = stringResource(R.string.settings_notify_transfers_desc),
                )
            }

            // ------------------------------------------------------------ kit bölümleri
            AppearanceSection(prefs)
            InputSection(prefs)
            ShortcutListSection(AppShortcuts.registry, scopeLabel = { scope -> scopeTitle(scope) })

            SettingsSection(stringResource(R.string.settings_about)) {
                SettingRow(stringResource(R.string.about_title), subtitle = stringResource(R.string.about_tagline)) {
                    DeskButton(stringResource(R.string.action_open), onOpenAbout, icon = DeskIcons.Info)
                }
            }
            Spacer(Modifier.height(dimens.gapL))
        }
    }

    if (aliasDialog) {
        AliasManagerDialog(client.commandAliases, onRemove = { settings.setAlias(it, null) }, onDismiss = { aliasDialog = false })
    }
}

@Composable
private fun scopeTitle(scope: ShortcutScope): String? = when (scope) {
    AppShortcuts.SearchScope -> stringResource(R.string.scope_search_page)
    AppShortcuts.TransfersScope -> stringResource(R.string.scope_transfers_page)
    else -> null
}

@Composable
private fun AliasManagerDialog(aliases: Map<String, String>, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val res = appResources()
    DeskFormDialog(
        title = res.getString(R.string.chat_aliases_title),
        confirmLabel = res.getString(R.string.action_close),
        dismissLabel = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        if (aliases.isEmpty()) {
            Text(res.getString(R.string.chat_fb_no_aliases), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
        } else {
            Column(
                Modifier.fillMaxWidth().padding(end = 4.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS),
            ) {
                for ((name, expansion) in aliases.toSortedMap()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
                        Column(Modifier.weight(1f)) {
                            Text("/$name", style = DeskTheme.type.mono, color = DeskTheme.colors.accentText)
                            Text(expansion, style = DeskTheme.type.caption, color = DeskTheme.colors.textSecondary, maxLines = 2)
                        }
                        desktopkit.components.DeskIconButton(DeskIcons.Delete, res.getString(R.string.action_remove), { onRemove(name) })
                    }
                }
            }
        }
        Text(res.getString(R.string.chat_help_alias_tip), style = DeskTheme.type.caption, color = DeskTheme.colors.textTertiary)
    }
}

private fun treeLabel(uri: Uri): String {
    val id = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        return uri.lastPathSegment ?: uri.toString()
    }
    val path = id.substringAfter(':', id)
    return path.ifEmpty { id.substringBefore(':') }.trimEnd('/')
}
