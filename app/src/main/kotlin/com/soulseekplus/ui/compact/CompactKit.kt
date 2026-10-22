package com.soulseekplus.ui.compact

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.soulseekplus.ui.DeskCheckbox
import com.soulseekplus.ui.DeskProgressBar
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.components.MenuEntry
import desktopkit.components.MenuPopup
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

/*
 * Telefon (dar pencere) düzeni için yapı taşları. Geniş pencerelerde masaüstü tabloları kullanılır; bunlar yalnızca
 * WidthClass.Compact'ta devreye girer. Amaç: kontrolleri tek satırda toplamak, listeye ekranın çoğunu bırakmak.
 */

/** Dar pencere (telefon dikey) mi. */
@Composable
fun isCompactWidth(): Boolean = currentWidthClass() == WidthClass.Compact

/**
 * İki satırlı liste satırı (telefon). Dokunma [onClick]'i çalıştırır ya da [openMenuOnTap] ise eylem menüsünü açar;
 * uzun basma [onLongClick]'i (genellikle seçim kipini başlatır) ya da yoksa menüyü açar.
 *
 * Sağ tarafta [trailing] (tek aralıklı, ör. boyut veya yüzde) ve altında [trailingSub] (ör. yuva durumu) gösterilir.
 * [progress] doluysa alt satırın altında ince bir ilerleme çubuğu çizilir.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = DeskTheme.colors.textTertiary,
    leading: ImageVector? = null,
    leadingTint: Color = DeskTheme.colors.textSecondary,
    trailing: String? = null,
    trailingColor: Color = DeskTheme.colors.textSecondary,
    trailingSub: String? = null,
    trailingSubColor: Color = DeskTheme.colors.textTertiary,
    strong: Boolean = false,
    indent: Dp = 0.dp,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    progress: Float? = null,
    progressColor: Color = DeskTheme.colors.accent,
    openMenuOnTap: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    menu: (() -> List<MenuEntry>)? = null,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    var menuOpen by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxWidth().onSizeChanged { rowHeight = it.height }) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.selection else Color.Transparent)
                .combinedClickable(
                    onClick = {
                        if (!selectionMode && openMenuOnTap && menu != null) menuOpen = true else onClick()
                    },
                    onLongClick = {
                        when {
                            onLongClick != null -> onLongClick()
                            menu != null -> menuOpen = true
                        }
                    },
                )
                .heightIn(min = 56.dp)
                .padding(start = dimens.pagePadding + indent, end = dimens.pagePadding, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) DeskCheckbox(selected, null)
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = leadingTint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = if (strong) DeskTheme.type.bodyStrong else DeskTheme.type.body,
                    color = if (selected) colors.onSelection else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = DeskTheme.type.caption, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) {
                    Spacer(Modifier.height(2.dp))
                    DeskProgressBar(progress.coerceIn(0f, 1f), Modifier.fillMaxWidth(), color = progressColor, height = 3.dp)
                }
            }
            if (trailing != null || trailingSub != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.widthIn(max = 120.dp)) {
                    if (trailing != null) {
                        Text(trailing, style = DeskTheme.type.mono, color = trailingColor, maxLines = 1)
                    }
                    if (trailingSub != null) {
                        Text(trailingSub, style = DeskTheme.type.caption, color = trailingSubColor, maxLines = 1)
                    }
                }
            }
        }
        HorizontalRule(Modifier.align(Alignment.BottomStart).padding(start = dimens.pagePadding + indent))
        if (menu != null) {
            MenuPopup(
                visible = menuOpen,
                onDismiss = { menuOpen = false },
                entries = if (menuOpen) menu() else emptyList(),
                offset = IntOffset(0, rowHeight),
            )
        }
    }
}

/**
 * Telefonda seçim çubuğu: solda kapat + seçili sayısı, sağda simge eylemler. Metinli düğmeler dar ekrana sığmadığı için
 * eylemler simge olarak verilir (uzun basınca ipucu kit tarafından gösterilir).
 */
@Composable
fun CompactSelectionBar(
    countText: String,
    closeLabel: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    Column(Modifier.fillMaxWidth().background(colors.surfaceRaised)) {
        HorizontalRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.gapS, vertical = dimens.gapXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
        ) {
            DeskIconButton(DeskIcons.Close, closeLabel, onClose)
            Text(countText, style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

/** Liste üstünde ince özet satırı (ör. "4591 / 5000 sonuç · 59 kullanıcı"). */
@Composable
fun CompactCaption(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val dimens = DeskTheme.dimens
    Row(
        modifier.fillMaxWidth().padding(start = dimens.pagePadding, end = dimens.gapS, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = DeskTheme.type.caption, color = DeskTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}
