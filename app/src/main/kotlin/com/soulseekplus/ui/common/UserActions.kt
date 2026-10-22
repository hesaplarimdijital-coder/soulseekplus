package com.soulseekplus.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.links.SlskUri
import com.soulseekplus.ui.AppActions
import com.soulseekplus.ui.appResources
import com.soulseekplus.ui.icons.SoulIcons
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry

/**
 * Kullanıcı ve dosya bağlam menüsü girdileri; arama, transfer, gözatma ve sohbet ekranları aynı menüyü paylaşır.
 * Girdiler menü açılırken üretilir, bu yüzden arkadaş/yasak durumu güncel okunur.
 */
class UserActions(
    private val container: AppContainer,
    private val actions: AppActions,
    private val res: Resources,
    private val copyText: (String) -> Unit,
) {

    /** Kullanıcıya özel girdiler: gözat, bilgi, mesaj, arkadaş/yasak/yoksay, adı kopyala. */
    fun userEntries(username: String, browse: Boolean = true, info: Boolean = true, message: Boolean = true): List<MenuEntry> {
        val lists = container.userLists.lists.value
        val out = ArrayList<MenuEntry>()
        if (browse) out += MenuEntry.Item(res.getString(R.string.menu_user_browse), { actions.browseUser(username) }, icon = SoulIcons.BrowseUser)
        if (info) out += MenuEntry.Item(res.getString(R.string.menu_user_info), { actions.userInfo(username) }, icon = DeskIcons.Info)
        if (message) out += MenuEntry.Item(res.getString(R.string.menu_user_message), { actions.privateChat(username) }, icon = SoulIcons.Chat)
        if (out.isNotEmpty()) out += MenuEntry.Separator
        out += if (username in lists.buddies) {
            MenuEntry.Item(res.getString(R.string.menu_user_remove_buddy), { container.userLists.removeBuddy(username) }, icon = SoulIcons.Buddy)
        } else {
            MenuEntry.Item(res.getString(R.string.menu_user_add_buddy), { container.userLists.addBuddy(username) }, icon = SoulIcons.Buddy)
        }
        out += if (username in lists.banned) {
            MenuEntry.Item(res.getString(R.string.menu_user_unban), { container.userLists.unban(username) }, icon = SoulIcons.Ban)
        } else {
            MenuEntry.Item(res.getString(R.string.menu_user_ban), { container.userLists.ban(username) }, icon = SoulIcons.Ban, destructive = true)
        }
        out += if (username in lists.ignored) {
            MenuEntry.Item(res.getString(R.string.menu_user_unignore), { container.userLists.unignore(username) }, icon = SoulIcons.Ignore)
        } else {
            MenuEntry.Item(res.getString(R.string.menu_user_ignore), { container.userLists.ignore(username) }, icon = SoulIcons.Ignore)
        }
        out += MenuEntry.Item(res.getString(R.string.menu_copy_username), { copyText(username) }, icon = SoulIcons.Copy)
        return out
    }

    fun copyFileName(name: String): MenuEntry =
        MenuEntry.Item(res.getString(R.string.menu_copy_name), { copyText(name) }, icon = SoulIcons.Copy)

    fun copyFileLink(username: String, path: String): MenuEntry =
        MenuEntry.Item(res.getString(R.string.menu_copy_link), { copyText(SlskUri.forFile(username, path)) }, icon = SoulIcons.Link)

    fun copyFolderLink(username: String, path: String): MenuEntry =
        MenuEntry.Item(res.getString(R.string.menu_copy_folder_link), { copyText(SlskUri.forFolder(username, path)) }, icon = SoulIcons.Link)

    fun copy(text: String) = copyText(text)
}

@Composable
fun rememberUserActions(container: AppContainer, actions: AppActions): UserActions {
    val res = appResources()
    val clipboard = LocalClipboardManager.current
    return remember(container, actions, res, clipboard) {
        UserActions(container, actions, res) { clipboard.setText(AnnotatedString(it)) }
    }
}
