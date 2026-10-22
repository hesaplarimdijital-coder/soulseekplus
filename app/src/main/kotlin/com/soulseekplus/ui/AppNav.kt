package com.soulseekplus.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import com.soulseekplus.core.links.SlskLink
import com.soulseekplus.core.model.SearchScope

/** Kabuk rotaları. */
object Routes {
    const val SEARCH = "search"
    const val TRANSFERS = "transfers"
    const val WISHLIST = "wishlist"
    const val CHAT = "chat"
    const val USERS = "users"
    const val BROWSE = "browse"
    const val PROFILE = "profile"
    const val SHARES = "shares"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

/**
 * Bildirimlerin ve dış uygulamaların `MainActivity`'yi doğru ekranda açması için kullanılan intent anahtarları.
 * `AppNotifications` PendingIntent'leri bu anahtarlarla kurar; `AppViewModel.handleIntent` okur.
 */
object LaunchTargets {
    const val EXTRA_TARGET = "com.soulseekplus.extra.TARGET"
    const val EXTRA_USER = "com.soulseekplus.extra.USER"
    const val EXTRA_ROOM = "com.soulseekplus.extra.ROOM"

    const val TARGET_PRIVATE_CHAT = "private_chat"
    const val TARGET_ROOM = "room"
    const val TARGET_WISHLIST = "wishlist"
    const val TARGET_TRANSFERS = "transfers"

    /** `MainActivity`'yi açan, belirtilen hedefe götüren bir intent (PendingIntent için). */
    fun intent(context: Context, target: String, user: String? = null, room: String? = null): Intent =
        Intent().setClassName(context.packageName, "com.soulseekplus.ui.MainActivity")
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TARGET, target)
            .apply {
                if (user != null) putExtra(EXTRA_USER, user)
                if (room != null) putExtra(EXTRA_ROOM, room)
            }
}

/** Ekranlar arası gezinme istekleri (slsk:// bağlantıları, bildirimler, sohbet komutları, bağlam menüleri). */
sealed interface NavRequest {
    data class Route(val route: String) : NavRequest
    data class Browse(val username: String, val path: String?) : NavRequest
    data class UserInfo(val username: String) : NavRequest
    data class PrivateChat(val username: String) : NavRequest
    data class Room(val room: String) : NavRequest
    data class Search(val query: String, val scope: SearchScope) : NavRequest
    data class OpenSearchTab(val tabId: Int) : NavRequest

    /** `slsk://` dosya bağlantısı: ilgili kullanıcının klasörüne gidilir ve dosya indirme için önerilir. */
    data class DownloadLink(val username: String, val path: String) : NavRequest
}

/** Ekranların kök bileşenden çağırdığı ortak eylemler. */
@Stable
interface AppActions {
    fun navigate(route: String)
    fun browseUser(username: String, path: String? = null)
    fun userInfo(username: String)
    fun privateChat(username: String)
    fun openRoom(room: String)
    fun search(query: String, scope: SearchScope = SearchScope.Network)

    /** `slsk://` bağlantısını açar (kullanıcı, klasör ya da dosya). */
    fun openLink(link: SlskLink)

    /** Kısa süreli bilgi mesajı (alt şerit). */
    fun toast(@StringRes id: Int, vararg args: Any)
    fun toastText(text: String)
}
