package com.soulseekplus.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.format.Formatting
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.PortMappingState
import com.soulseekplus.ui.DeskProgressBar
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.connectionText
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.portMappingText
import com.soulseekplus.ui.shortConnectionText
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskSlider
import desktopkit.components.HorizontalRule
import desktopkit.components.TooltipArea
import desktopkit.components.collectState
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.components.stateBackground
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

/**
 * Kabuğun alt şeridi: (varsa) mini oynatıcı ve ağ durumu — bağlantı, hızlar, dağıtık ağ konumu, port eşlemesi.
 * Bağlantı öğesine dokunmak bağlı değilken bağlanır.
 */
@Composable
fun StatusStrip(container: AppContainer, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    Column(modifier.fillMaxWidth().background(colors.chrome)) {
        HorizontalRule()
        MiniPlayerRow(container)
        NetworkRow(container)
    }
}

@Composable
private fun NetworkRow(container: AppContainer) {
    val res = appResources()
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val compact = currentWidthClass() == WidthClass.Compact
    val connection by container.client.connection.collectAsStateWithLifecycle()
    val stats by container.client.transferStats.collectAsStateWithLifecycle()
    val distributed by container.client.distributed.collectAsStateWithLifecycle()
    val portMapping by container.client.portMapping.collectAsStateWithLifecycle()
    val network by container.client.network.collectAsStateWithLifecycle()

    val connectionIcon: ImageVector
    val connectionTint: Color
    when (connection) {
        is ConnectionState.LoggedIn -> {
            connectionIcon = SoulIcons.P2pConnected
            connectionTint = colors.success
        }
        ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
            connectionIcon = SoulIcons.P2pConnecting
            connectionTint = colors.warning
        }
        is ConnectionState.Disconnected -> {
            connectionIcon = SoulIcons.P2pOffline
            connectionTint = colors.textTertiary
        }
    }
    val canConnect = connection is ConnectionState.Disconnected

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs)
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapL),
    ) {
        // Bağlantı
        val interaction = remember { MutableInteractionSource() }
        val state = interaction.collectState()
        val shape = RoundedCornerShape(dimens.radiusSmall)
        TooltipArea(res.connectionText(connection)) {
            Row(
                Modifier
                    .background(if (canConnect) stateBackground(state) else Color.Transparent, shape)
                    .focusRing(state, shape)
                    .deskClickable(
                        interaction,
                        { container.client.connect(); container.ensureServiceRunning() },
                        enabled = canConnect,
                        onClickLabel = res.getString(R.string.action_connect),
                    )
                    .padding(horizontal = dimens.gapXs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            ) {
                Icon(connectionIcon, contentDescription = null, tint = connectionTint, modifier = Modifier.size(dimens.icon))
                Text(
                    res.shortConnectionText(connection),
                    style = DeskTheme.type.label,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Hızlar
        SpeedItem(SoulIcons.Download, Formatting.speed(stats.downloadSpeed), stats.activeDownloads, res.getString(R.string.status_download_speed))
        SpeedItem(SoulIcons.Upload, Formatting.speed(stats.uploadSpeed), stats.activeUploads, res.getString(R.string.status_upload_speed))

        if (!compact) {
            // Yükleme yuvaları
            val slotsFree = stats.freeUploadSlots > 0
            TooltipArea(res.getString(R.string.status_slots, stats.freeUploadSlots, stats.queuedUploads)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    Icon(
                        if (slotsFree) SoulIcons.SlotFree else SoulIcons.SlotBusy,
                        contentDescription = null,
                        tint = if (slotsFree) colors.success else colors.warning,
                        modifier = Modifier.size(dimens.icon),
                    )
                    Text(
                        res.getString(R.string.status_slots_short, stats.freeUploadSlots, stats.queuedUploads),
                        style = DeskTheme.type.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            // Dağıtık ağ
            val parent = distributed.parentUsername
            TooltipArea(res.getString(R.string.status_distributed_tip)) {
                Text(
                    if (parent == null) res.getString(R.string.status_no_parent)
                    else res.getString(R.string.status_parent, parent, distributed.branchLevel, distributed.childCount),
                    style = DeskTheme.type.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        // Port eşlemesi / dinleme
        when (portMapping) {
            PortMappingState.Disabled -> if (network.listening) {
                PortItem(SoulIcons.PortMapped, colors.textSecondary, res.getString(R.string.status_listening, network.listenPort))
            } else {
                PortItem(SoulIcons.PortFailed, colors.textTertiary, res.getString(R.string.status_not_listening))
            }
            PortMappingState.Discovering -> PortItem(SoulIcons.PortMapped, colors.textTertiary, res.portMappingText(portMapping))
            is PortMappingState.Mapped -> PortItem(SoulIcons.PortMapped, colors.success, res.portMappingText(portMapping))
            is PortMappingState.Failed -> PortItem(SoulIcons.PortFailed, colors.warning, res.portMappingText(portMapping))
        }
    }
}

@Composable
private fun SpeedItem(icon: ImageVector, speed: String, active: Int, description: String) {
    val colors = DeskTheme.colors
    TooltipArea(description) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapXs)) {
            Icon(icon, contentDescription = description, tint = if (active > 0) colors.accentText else colors.textTertiary, modifier = Modifier.size(DeskTheme.dimens.icon))
            Text(speed, style = DeskTheme.type.mono, color = if (active > 0) colors.text else colors.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun PortItem(icon: ImageVector, tint: Color, description: String) {
    TooltipArea(description) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(DeskTheme.dimens.icon))
    }
}

@Composable
private fun MiniPlayerRow(container: AppContainer) {
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    if (!state.hasMedia) return
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    // Sürüklerken konum yerel tutulur; bırakılınca oynatıcıya gider.
    var dragging by remember { mutableStateOf<Float?>(null) }

    HorizontalRule()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        DeskIconButton(
            if (state.playing) DeskIcons.Pause else DeskIcons.Play,
            stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
            { player.toggle() },
            enabled = !state.preparing && !state.failed,
        )
        Column(Modifier.weight(1f)) {
            Text(
                state.title,
                style = DeskTheme.type.label,
                color = if (state.failed) colors.danger else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                state.failed -> Text(res.getString(R.string.player_failed), style = DeskTheme.type.caption, color = colors.danger)
                state.preparing -> DeskProgressBar(null, Modifier.fillMaxWidth())
                else -> {
                    val shown = dragging ?: state.fraction
                    DeskSlider(
                        value = shown,
                        onValueChange = { dragging = it },
                        modifier = Modifier.fillMaxWidth(),
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo((it * state.durationMs).toInt()) }
                            dragging = null
                        },
                    )
                }
            }
        }
        if (!state.preparing && !state.failed && state.durationMs > 0) {
            Text(
                Formatting.duration(state.positionMs / 1000) + " / " + Formatting.duration(state.durationMs / 1000),
                style = DeskTheme.type.mono,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        DeskIconButton(DeskIcons.Close, stringResource(R.string.player_stop), { player.stop() })
    }
}
