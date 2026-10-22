package com.soulseekplus.ui

import android.content.res.Resources
import androidx.compose.ui.input.key.Key
import com.soulseekplus.R
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.input.Shortcut
import desktopkit.input.ShortcutGroup
import desktopkit.input.ShortcutRegistry
import desktopkit.input.ShortcutScope

/** Uygulamanın klavye kısayolları: menü çubuğu, ipuçları ve Meta+/ listesi aynı tablodan beslenir. */
object AppShortcuts {

    const val GO_SEARCH = "ss.go_search"
    const val GO_TRANSFERS = "ss.go_transfers"
    const val GO_WISHLIST = "ss.go_wishlist"
    const val GO_CHAT = "ss.go_chat"
    const val GO_USERS = "ss.go_users"
    const val GO_SHARES = "ss.go_shares"
    const val GO_ABOUT = "ss.go_about"

    const val CONNECT = "ss.connect"
    const val DISCONNECT = "ss.disconnect"
    const val TOGGLE_AWAY = "ss.toggle_away"
    const val RESCAN_SHARES = "ss.rescan_shares"

    const val NEW_SEARCH = "ss.new_search"
    const val CLOSE_TAB = "ss.close_tab"
    const val NEXT_TAB = "ss.next_tab"
    const val PREV_TAB = "ss.prev_tab"
    const val RERUN = "ss.rerun"
    const val TOGGLE_FILTERS = "ss.toggle_filters"
    const val DOWNLOAD_SELECTED = "ss.download_selected"

    const val PAUSE_SELECTED = "ss.pause_selected"
    const val RESUME_SELECTED = "ss.resume_selected"
    const val CLEAR_FINISHED = "ss.clear_finished"

    /** Arama ekranındayken geçerli kısayollar. */
    val SearchScope = ShortcutScope("search")

    /** Transferler ekranındayken geçerli kısayollar. */
    val TransfersScope = ShortcutScope("transfers")

    val SessionGroup = ShortcutGroup("session", R.string.menu_session)
    val GoGroup = ShortcutGroup("go", R.string.menu_go)
    val SearchGroup = ShortcutGroup("search", R.string.menu_search)
    val TransfersGroup = ShortcutGroup("transfers", R.string.menu_transfers)

    val registry = ShortcutRegistry(
        groups = listOf(SessionGroup, GoGroup, SearchGroup, TransfersGroup, CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup),
        shortcuts = listOf(
            Shortcut(CONNECT, SessionGroup, R.string.sc_connect, KeyChord(Key.C, ctrl = true, shift = true)),
            Shortcut(DISCONNECT, SessionGroup, R.string.sc_disconnect, KeyChord(Key.D, ctrl = true, shift = true)),
            Shortcut(TOGGLE_AWAY, SessionGroup, R.string.sc_toggle_away, KeyChord(Key.A, ctrl = true, shift = true)),
            Shortcut(RESCAN_SHARES, SessionGroup, R.string.sc_rescan_shares, KeyChord(Key.R, ctrl = true, shift = true)),

            Shortcut(GO_SEARCH, GoGroup, R.string.sc_go_search, KeyChord(Key.One, ctrl = true)),
            Shortcut(GO_TRANSFERS, GoGroup, R.string.sc_go_transfers, KeyChord(Key.Two, ctrl = true)),
            Shortcut(GO_WISHLIST, GoGroup, R.string.sc_go_wishlist, KeyChord(Key.Three, ctrl = true)),
            Shortcut(GO_CHAT, GoGroup, R.string.sc_go_chat, KeyChord(Key.Four, ctrl = true)),
            Shortcut(GO_USERS, GoGroup, R.string.sc_go_users, KeyChord(Key.Five, ctrl = true)),
            Shortcut(GO_SHARES, GoGroup, R.string.sc_go_shares, KeyChord(Key.Six, ctrl = true)),

            Shortcut(NEW_SEARCH, SearchGroup, R.string.sc_new_search, KeyChord(Key.N, ctrl = true)),
            Shortcut(CLOSE_TAB, SearchGroup, R.string.sc_close_tab, KeyChord(Key.W, ctrl = true), SearchScope),
            Shortcut(NEXT_TAB, SearchGroup, R.string.sc_next_tab, KeyChord(Key.Tab, ctrl = true), SearchScope),
            Shortcut(PREV_TAB, SearchGroup, R.string.sc_prev_tab, KeyChord(Key.Tab, ctrl = true, shift = true), SearchScope),
            Shortcut(RERUN, SearchGroup, R.string.sc_rerun, KeyChord(Key.F5), SearchScope),
            Shortcut(TOGGLE_FILTERS, SearchGroup, R.string.sc_toggle_filters, KeyChord(Key.F, ctrl = true), SearchScope),
            Shortcut(DOWNLOAD_SELECTED, SearchGroup, R.string.sc_download_selected, KeyChord(Key.D, ctrl = true), SearchScope),

            Shortcut(PAUSE_SELECTED, TransfersGroup, R.string.sc_pause_selected, KeyChord(Key.P, ctrl = true), TransfersScope),
            Shortcut(RESUME_SELECTED, TransfersGroup, R.string.sc_resume_selected, KeyChord(Key.R, ctrl = true), TransfersScope),
            Shortcut(CLEAR_FINISHED, TransfersGroup, R.string.sc_clear_finished, KeyChord(Key.Backspace, ctrl = true), TransfersScope),

            CommonShortcuts.Search,
            CommonShortcuts.Settings,
            CommonShortcuts.ToggleSidebar,
            CommonShortcuts.ShortcutList,
        ),
    )

    fun label(id: String, resources: Resources): String? = registry.chordLabel(id, resources)
}
