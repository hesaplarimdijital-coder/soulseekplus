package com.soulseekplus.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.ui.DeskTextField
import com.soulseekplus.ui.SurfaceCard
import com.soulseekplus.ui.SwitchRow
import com.soulseekplus.ui.icons.SoulIcons
import desktopkit.components.DeskButton
import desktopkit.theme.DeskTheme

/**
 * Hesap alanları: kullanıcı adı, parola ve (isteğe bağlı) sunucu. Kaydedince ayarlar yazılır ve bağlantı yenilenir.
 * İlk açılış ekranında ve ayarlarda aynı form kullanılır.
 */
@Composable
fun AccountForm(
    container: AppContainer,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    showServer: Boolean = true,
    saveLabel: String = stringResource(R.string.account_save),
) {
    val current by container.settings.clientSettings.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf(current.username) }
    var password by rememberSaveable { mutableStateOf(current.password) }
    var host by rememberSaveable { mutableStateOf(current.serverHost) }
    var port by rememberSaveable { mutableStateOf(current.serverPort.toString()) }
    var advanced by rememberSaveable { mutableStateOf(showServer) }

    val parsedPort = port.toIntOrNull()
    val portValid = parsedPort != null && parsedPort in 1..65535
    val valid = username.isNotBlank() && password.isNotEmpty() && host.isNotBlank() && portValid
    val unchanged = username.trim() == current.username && password == current.password &&
        host.trim() == current.serverHost && parsedPort == current.serverPort

    fun save() {
        if (!valid) return
        container.settings.setAccount(username.trim(), password, host.trim(), parsedPort ?: ClientSettings.DEFAULT_SERVER_PORT)
        container.client.disconnect()
        container.client.connect()
        container.ensureServiceRunning()
        onSaved()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapM)) {
        DeskTextField(
            username,
            { username = it },
            Modifier.fillMaxWidth(),
            hint = stringResource(R.string.label_username),
            leading = SoulIcons.User,
            imeAction = ImeAction.Next,
        )
        DeskTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            hint = stringResource(R.string.label_password),
            leading = SoulIcons.LockClosed,
            password = true,
            keyboardType = KeyboardType.Password,
            imeAction = if (advanced) ImeAction.Next else ImeAction.Done,
            onImeAction = { if (!advanced) save() },
        )
        if (advanced) {
            Row(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
                DeskTextField(host, { host = it.trim() }, Modifier.weight(1f), hint = stringResource(R.string.account_server), leading = SoulIcons.Globe, imeAction = ImeAction.Next)
                DeskTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    Modifier.widthIn(max = 96.dp),
                    hint = stringResource(R.string.account_port),
                    error = !portValid,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    onImeAction = ::save,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
            DeskButton(saveLabel, ::save, primary = true, enabled = valid && !unchanged)
            if (!showServer) {
                DeskButton(
                    stringResource(if (advanced) R.string.account_hide_server else R.string.account_show_server),
                    { advanced = !advanced },
                )
            }
        }
    }
}

/** Hesap girilmemişse gösterilen karşılama ekranı. */
@Composable
fun AccountScreen(container: AppContainer, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dimens.pagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SurfaceCard(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            Icon(SoulIcons.P2pConnected, contentDescription = null, tint = colors.accentText, modifier = Modifier.padding(bottom = dimens.gapXs))
            Text(stringResource(R.string.account_welcome_title), style = DeskTheme.type.headline, color = colors.text)
            Text(stringResource(R.string.account_welcome_text), style = DeskTheme.type.body, color = colors.textSecondary)
            Text(stringResource(R.string.account_welcome_new), style = DeskTheme.type.caption, color = colors.textTertiary)
            AccountForm(
                container = container,
                onSaved = onSaved,
                modifier = Modifier.padding(top = dimens.gapS),
                showServer = false,
                saveLabel = stringResource(R.string.account_connect),
            )
        }
    }
}
