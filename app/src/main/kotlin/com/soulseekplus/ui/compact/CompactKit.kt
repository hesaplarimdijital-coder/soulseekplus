package com.soulseekplus.ui.compact

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import desktopkit.theme.DeskTheme

/*
 * Telefon (dar pencere) düzeni için kitte karşılığı olmayan parça. Liste satırı (DeskListRow) ve seçim çubuğu
 * (SelectionBar) kitten gelir (MIGRATION.md 2. adım).
 */

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
