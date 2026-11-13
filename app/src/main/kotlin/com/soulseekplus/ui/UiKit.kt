package com.soulseekplus.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desktopkit.components.verticalScrollbar
import desktopkit.theme.DeskTheme

// Ortak bileşenlerin hepsi desktop-kit'ten gelir (MIGRATION.md 2. adım); burada yalnızca kitte karşılığı olmayan
// küçük bir yardımcı kaldı.

/** Sayfa gövdesi yan boşlukları. */
@Composable
fun Modifier.pagePadding(): Modifier = this.padding(horizontal = DeskTheme.dimens.pagePadding)

/**
 * Kaydırılan sütun, kitin kaydırma çubuğuyla (2.8.0): fare ya da dokunmatik yüzeyle (DeX, klavyeli kapak) sonda ince
 * bir çubuk, kayarken ve imleç üstündeyken görünür, sürüklenir; dokunmatikte yok. `verticalScroll(rememberScrollState())`
 * yerine (kitin tabloları ve ızgaraları çubuğu kendileri taşır).
 */
@Composable
fun Modifier.scrollableColumn(state: ScrollState = rememberScrollState()): Modifier = verticalScrollbar(state).verticalScroll(state)

