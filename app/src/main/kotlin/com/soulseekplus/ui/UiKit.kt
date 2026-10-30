package com.soulseekplus.ui

import desktopkit.components.CountBadge
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry
import desktopkit.components.MenuPopup
import desktopkit.components.collectState
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.components.stateBackground
import desktopkit.theme.DeskTheme

// desktop-kit'te olmayan ortak bileşenler: Material3 yalnızca onay kutusu, anahtar, ilerleme çubuğu ve iletişim
// kutusu için; renkler DeskTheme belirteçlerinden gelir.

/** Onay penceresi. */
@Composable
fun DeskDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    DeskFormDialog(
        title = title,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = destructive,
    ) {
        Text(text, style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
    }
}

/** Serbest içerikli pencere (form alanları vb.). */
@Composable
fun DeskFormDialog(
    title: String,
    confirmLabel: String,
    dismissLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DeskTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        confirmButton = { DeskButton(confirmLabel, onConfirm, primary = !destructive, enabled = confirmEnabled) },
        dismissButton = dismissLabel?.let { label -> { DeskButton(label, onDismiss) } },
        title = { Text(title, style = DeskTheme.type.title, color = if (destructive) colors.danger else colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapM), content = content)
        },
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
    )
}

/** Tek satır (ya da çok satırlı) metin alanı; kit renkleriyle. */
@Composable
fun DeskTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    password: Boolean = false,
    error: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusControl)
    val borderColor = if (error) colors.danger else colors.borderStrong
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = DeskTheme.type.body.copy(color = if (enabled) colors.text else colors.textTertiary),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
        modifier = modifier,
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = dimens.controlHeight.coerceAtLeast(dimens.minTarget))
                    .background(colors.surface, shape)
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = dimens.gapM, vertical = if (singleLine) 0.dp else dimens.gapS),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (leading != null) {
                    Icon(leading, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(dimens.icon))
                    Spacer(Modifier.width(dimens.gapS))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && hint.isNotEmpty()) {
                        Text(hint, style = DeskTheme.type.body, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(dimens.gapS))
                    trailing()
                }
            }
        },
    )
}

/** Arama kutusu biçiminde süzgeç alanı. */
@Composable
fun FilterTextField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    DeskTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        hint = hint,
        leading = DeskIcons.Search,
        trailing = if (value.isNotEmpty()) {
            { DeskIconButton(DeskIcons.Close, hint, { onValueChange("") }, size = 24.dp, iconSize = 12.dp) }
        } else {
            null
        },
    )
}

/** Sayısal ayar alanı: geçerli bir tam sayı yazılınca [onValue] çağrılır; boş/geçersizken kırmızı. */
@Composable
fun NumberField(value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier, min: Int = 0, max: Int = Int.MAX_VALUE) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in min..max
    DeskTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }.take(9)
            text = filtered
            val n = filtered.toIntOrNull()
            if (n != null && n in min..max) onValue(n)
        },
        modifier = modifier.width(112.dp),
        error = !valid,
        keyboardType = KeyboardType.Number,
    )
}

/** İsteğe bağlı sayısal süzgeç alanı: boş = null. [reset] değişince metin [value]'dan yeniden okunur. */
@Composable
fun OptionalNumberField(
    value: Long?,
    onValue: (Long?) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    reset: Int = 0,
) {
    var text by remember(reset) { mutableStateOf(value?.toString().orEmpty()) }
    DeskTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }.take(12)
            text = filtered
            onValue(filtered.toLongOrNull())
        },
        modifier = modifier,
        hint = hint,
        keyboardType = KeyboardType.Number,
    )
}

/** Açılıp kapanan süzgeç çipi (hızlı süzgeçler). */
@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusPill)
    val background = if (selected) colors.selection else stateBackground(state, rest = Color.Transparent)
    val border = if (selected) colors.accent else colors.borderStrong
    Row(
        modifier = modifier
            .heightIn(min = dimens.minTarget)
            .background(background, shape)
            .border(1.dp, border, shape)
            .focusRing(state, shape)
            .deskClickable(interaction, onClick, role = androidx.compose.ui.semantics.Role.Checkbox)
            .padding(horizontal = dimens.gapM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapXs + 2.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onSelection else colors.textSecondary, modifier = Modifier.size(dimens.icon - 2.dp))
        }
        Text(label, style = DeskTheme.type.label, color = if (selected) colors.onSelection else colors.textSecondary, maxLines = 1)
    }
}

/** Sekme şeridindeki tek sekme: etiket, isteğe bağlı sayı rozeti ve kapatma düğmesi. */
@Composable
fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: Int = 0,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusControl)
    Row(
        modifier = modifier
            .heightIn(min = dimens.minTarget)
            .background(stateBackground(state, selected = selected, rest = Color.Transparent), shape)
            .focusRing(state, shape)
            .deskClickable(interaction, onClick, role = androidx.compose.ui.semantics.Role.Tab)
            .padding(start = dimens.gapM, end = if (onClose != null) dimens.gapXs else dimens.gapM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onSelection else colors.textSecondary, modifier = Modifier.size(dimens.icon))
        }
        Text(
            label,
            style = if (selected) DeskTheme.type.bodyStrong else DeskTheme.type.body,
            color = if (selected) colors.onSelection else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        if (busy) {
            Box(Modifier.size(6.dp).background(colors.accentText, RoundedCornerShape(dimens.radiusPill)))
        }
        CountBadge(badge)
        if (onClose != null && closeLabel != null) {
            DeskIconButton(DeskIcons.Close, closeLabel, onClose, size = 24.dp, iconSize = 12.dp)
        }
    }
}

/** Yatay kayan sekme/çip şeridi. */
@Composable
fun ScrollRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS),
        content = content,
    )
}

/** Sayfa gövdesi yan boşlukları. */
@Composable
fun Modifier.pagePadding(): Modifier = this.padding(horizontal = DeskTheme.dimens.pagePadding)
