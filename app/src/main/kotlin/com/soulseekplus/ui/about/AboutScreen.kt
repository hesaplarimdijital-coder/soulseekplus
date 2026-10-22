package com.soulseekplus.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soulseekplus.BuildConfig
import com.soulseekplus.R
import com.soulseekplus.ui.KeyValueRow
import com.soulseekplus.ui.SurfaceCard
import com.soulseekplus.ui.icons.SoulIcons
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.screens.PageHeader
import desktopkit.theme.DeskTheme

private const val IP2LOCATION_URL = "https://lite.ip2location.com"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val CC_BY_SA_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
private const val OFL_URL = "https://openfontlicense.org"
private const val PROTOCOL_URL = "https://nicotine-plus.org/doc/SLSKPROTOCOL.html"

/** Sürüm, lisanslar ve üçüncü taraf atıfları. */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val uriHandler = LocalUriHandler.current

    fun open(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (e: IllegalArgumentException) {
            // Bağlantıyı açacak uygulama yok; sessizce geç.
        }
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.about_title))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(dimens.gapM),
        ) {
            SurfaceCard(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Icon(SoulIcons.P2pConnected, contentDescription = null, tint = colors.accentText)
                Text(stringResource(R.string.app_name), style = DeskTheme.type.headline, color = colors.text)
                Text(stringResource(R.string.about_tagline), style = DeskTheme.type.body, color = colors.textSecondary)
                KeyValueRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME, mono = true)
                Text(stringResource(R.string.about_description), style = DeskTheme.type.body, color = colors.textSecondary)
            }
            SurfaceCard(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(stringResource(R.string.about_license_title), style = DeskTheme.type.title, color = colors.text)
                Text(stringResource(R.string.about_license_text), style = DeskTheme.type.body, color = colors.textSecondary)
                DeskButton(stringResource(R.string.about_open_gpl), { open(GPL_URL) }, icon = DeskIcons.Info)
            }
            SurfaceCard(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(stringResource(R.string.about_third_party), style = DeskTheme.type.title, color = colors.text)
                Text(stringResource(R.string.about_ip2location), style = DeskTheme.type.body, color = colors.text)
                Text(stringResource(R.string.about_ip2location_license), style = DeskTheme.type.caption, color = colors.textSecondary)
                Row2(
                    first = stringResource(R.string.about_open_ip2location) to { open(IP2LOCATION_URL) },
                    second = stringResource(R.string.about_open_cc) to { open(CC_BY_SA_URL) },
                )
                Spacer(Modifier.height(dimens.gapS))
                Text(stringResource(R.string.about_fonts), style = DeskTheme.type.body, color = colors.text)
                DeskButton(stringResource(R.string.about_open_ofl), { open(OFL_URL) }, icon = DeskIcons.Info)
                Spacer(Modifier.height(dimens.gapS))
                Text(stringResource(R.string.about_protocol), style = DeskTheme.type.body, color = colors.text)
                DeskButton(stringResource(R.string.about_open_protocol), { open(PROTOCOL_URL) }, icon = DeskIcons.Info)
            }
            Spacer(Modifier.height(dimens.gapL))
        }
    }
}

@Composable
private fun Row2(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
        DeskButton(first.first, first.second, icon = SoulIcons.Link)
        DeskButton(second.first, second.second, icon = SoulIcons.Link)
    }
}
