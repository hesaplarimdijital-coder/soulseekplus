package com.soulseekplus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desktopkit.theme.DeskTheme

// Ortak bileşenlerin hepsi desktop-kit'ten gelir (MIGRATION.md 2. adım); burada yalnızca kitte karşılığı olmayan
// küçük bir yardımcı kaldı.

/** Sayfa gövdesi yan boşlukları. */
@Composable
fun Modifier.pagePadding(): Modifier = this.padding(horizontal = DeskTheme.dimens.pagePadding)
