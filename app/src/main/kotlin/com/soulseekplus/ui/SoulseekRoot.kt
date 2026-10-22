package com.soulseekplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.soulseekplus.AppContainer
import com.soulseekplus.R
import com.soulseekplus.core.chat.UiRequest
import com.soulseekplus.core.links.SlskLink
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.ui.about.AboutScreen
import com.soulseekplus.ui.account.AccountScreen
import com.soulseekplus.ui.browse.BrowseScreen
import com.soulseekplus.ui.browse.BrowseViewModel
import com.soulseekplus.ui.chat.ChatScreen
import com.soulseekplus.ui.chat.ChatViewModel
import com.soulseekplus.ui.icons.SoulIcons
import com.soulseekplus.ui.profile.ProfileScreen
import com.soulseekplus.ui.profile.ProfileViewModel
import com.soulseekplus.ui.search.SearchScreen
import com.soulseekplus.ui.search.SearchViewModel
import com.soulseekplus.ui.settings.SettingsScreen
import com.soulseekplus.ui.shares.SharesScreen
import com.soulseekplus.ui.status.StatusStrip
import com.soulseekplus.ui.transfers.TransfersScreen
import com.soulseekplus.ui.transfers.TransfersViewModel
import com.soulseekplus.ui.users.UsersScreen
import com.soulseekplus.ui.wishlist.WishlistScreen
import com.soulseekplus.ui.wishlist.WishlistViewModel
import desktopkit.DesktopKitRoot
import desktopkit.theme.InputMode
import desktopkit.theme.DeskType
import desktopkit.theme.DeskFonts
import androidx.compose.ui.platform.LocalConfiguration
import desktopkit.components.DeskIcons
import desktopkit.input.CommonShortcuts
import desktopkit.input.ShortcutScope
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellNavigator
import desktopkit.shell.ShellSpec
import desktopkit.shell.WidthClass
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Kompozisyon ↔ etkinlik köprüsü: klavye kısayolları `Activity.dispatchKeyEvent` içinde eşleşir (odak nerede
 * olursa olsun) ve burada çalıştırılır.
 */
interface ShortcutHost {
    /** Kısayolu çalıştırır; işlendiyse true (tuş tüketilir). */
    fun perform(id: String): Boolean

    /** Şu an geçerli kısayol kapsamları. */
    fun activeScopes(): Set<ShortcutScope>
}

/**
 * Uygulama kökü: tema + yoğunluk ([DesktopKitRoot]), hesap yoksa karşılama ekranı, varsa gezinme kabuğu.
 * [onHostChanged] kısayol köprüsünü etkinliğe bildirir; [onAccountSaved] hesap kaydedilince çağrılır.
 */
@Composable
fun SoulseekRoot(appViewModel: AppViewModel, onHostChanged: (ShortcutHost?) -> Unit, onAccountSaved: () -> Unit) {
    val prefs = rememberDeskPreferences()
    // Tablet / DeX (≥ 600 dp) için tüm yazı stilleri 1 sp büyük; telefon ölçüleri aynen kalır.
    // Düzen ve yoğunluk değişmez, yalnızca yazı boyutu.
    val wide = LocalConfiguration.current.screenWidthDp >= WidthClass.Medium.minWidthDp
    val typeStep = if (wide) WIDE_TYPE_STEP_SP else 0f
    val type = remember(typeStep) {
        { mode: InputMode, fonts: DeskFonts -> DeskType.forMode(mode, fonts, pointerStep = typeStep, touchStep = typeStep) }
    }
    DesktopKitRoot(prefs, type = type) { widthClass ->
        val container = appViewModel.container
        val client by container.settings.clientSettings.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(DeskTheme.colors.background)) {
            if (client.isConfigured) {
                AppShell(appViewModel, prefs, widthClass, onHostChanged, onAccountSaved)
            } else {
                AccountScreen(container, onSaved = onAccountSaved)
            }
        }
    }
}

/** Geniş pencerelerde (tablet, DeX) yazı stillerine eklenen boyut. */
private const val WIDE_TYPE_STEP_SP = 1f

/** [AppActions]'ın kök gerçekleştirmesi: her şey [AppViewModel.request] üzerinden tek toplayıcıya gider. */
private class RootActions(private val app: AppViewModel) : AppActions {
    override fun navigate(route: String) = app.request(NavRequest.Route(route))
    override fun browseUser(username: String, path: String?) = app.request(NavRequest.Browse(username, path))
    override fun userInfo(username: String) = app.request(NavRequest.UserInfo(username))
    override fun privateChat(username: String) = app.request(NavRequest.PrivateChat(username))
    override fun openRoom(room: String) = app.request(NavRequest.Room(room))
    override fun search(query: String, scope: SearchScope) = app.request(NavRequest.Search(query, scope))
    override fun openLink(link: SlskLink) = app.openLink(link)
    override fun toast(id: Int, vararg args: Any) = app.post(id, *args)
    override fun toastText(text: String) = app.postText(text)
}

@Composable
private fun AppShell(
    appViewModel: AppViewModel,
    prefs: DeskPreferences,
    widthClass: WidthClass,
    onHostChanged: (ShortcutHost?) -> Unit,
    onAccountSaved: () -> Unit,
) {
    val container = appViewModel.container
    val resources = appResources()
    val navController = rememberNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.SEARCH
    val routeState = rememberUpdatedState(route)
    val actions = remember(appViewModel) { RootActions(appViewModel) }

    val searchVm: SearchViewModel = viewModel()
    val transfersVm: TransfersViewModel = viewModel()
    val wishlistVm: WishlistViewModel = viewModel()
    val chatVm: ChatViewModel = viewModel()
    val browseVm: BrowseViewModel = viewModel()
    val profileVm: ProfileViewModel = viewModel()

    var linkDownload by remember { mutableStateOf<NavRequest.DownloadLink?>(null) }

    // ------------------------------------------------------------------ gezinme istekleri
    LaunchedEffect(appViewModel, navigator) {
        fun handle(request: NavRequest) {
            when (request) {
                is NavRequest.Route -> navigator.navigate(request.route)
                is NavRequest.Browse -> {
                    browseVm.open(request.username, request.path)
                    navigator.navigate(Routes.BROWSE)
                }
                is NavRequest.UserInfo -> {
                    profileVm.open(request.username)
                    navigator.navigate(Routes.PROFILE)
                }
                is NavRequest.PrivateChat -> {
                    chatVm.openPrivate(request.username)
                    navigator.navigate(Routes.CHAT)
                }
                is NavRequest.Room -> {
                    chatVm.openRoom(request.room)
                    navigator.navigate(Routes.CHAT)
                }
                is NavRequest.Search -> {
                    searchVm.start(request.query, request.scope)
                    navigator.navigate(Routes.SEARCH)
                }
                is NavRequest.OpenSearchTab -> {
                    container.searches.selectTab(request.tabId)
                    navigator.navigate(Routes.SEARCH)
                }
                is NavRequest.DownloadLink -> {
                    val folder = if ('\\' in request.path) request.path.substringBeforeLast('\\') else null
                    browseVm.open(request.username, folder)
                    navigator.navigate(Routes.BROWSE)
                    linkDownload = request
                }
            }
        }
        coroutineScope {
            // Önce abone ol, sonra bekleyen (soğuk başlatma) istekleri boşalt: yayın abonesiz kaybolur.
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { appViewModel.navRequests.collect { handle(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                container.chat.uiRequests.collect { request ->
                    appViewModel.request(
                        when (request) {
                            is UiRequest.OpenBrowse -> NavRequest.Browse(request.username, request.path)
                            is UiRequest.OpenUserInfo -> NavRequest.UserInfo(request.username)
                            is UiRequest.OpenSearch -> NavRequest.OpenSearchTab(request.tabId)
                            is UiRequest.NewSearch -> NavRequest.Search(request.query, request.scope)
                            is UiRequest.OpenPrivateChat -> NavRequest.PrivateChat(request.username)
                            is UiRequest.OpenRoom -> NavRequest.Room(request.room)
                        },
                    )
                }
            }
            appViewModel.onCollectorReady()
            try {
                collector.join()
            } finally {
                appViewModel.onCollectorGone()
            }
        }
    }

    // ------------------------------------------------------------------ kısayollar
    val host = remember(navigator, prefs, appViewModel) {
        object : ShortcutHost {
            override fun perform(id: String): Boolean = performShortcut(
                id, container, appViewModel, prefs, navigator, searchVm, transfersVm, actions,
            )

            override fun activeScopes(): Set<ShortcutScope> = when (routeState.value) {
                Routes.SEARCH -> setOf(ShortcutScope.Global, AppShortcuts.SearchScope)
                Routes.TRANSFERS -> setOf(ShortcutScope.Global, AppShortcuts.TransfersScope)
                else -> setOf(ShortcutScope.Global)
            }
        }
    }
    DisposableEffect(host) {
        onHostChanged(host)
        onDispose { onHostChanged(null) }
    }

    // ------------------------------------------------------------------ kabuk tanımı
    val labels = listOf(
        stringResource(R.string.nav_search), stringResource(R.string.nav_transfers), stringResource(R.string.nav_wishlist),
        stringResource(R.string.nav_chat), stringResource(R.string.nav_users), stringResource(R.string.nav_browse),
        stringResource(R.string.nav_profile), stringResource(R.string.nav_shares), stringResource(R.string.nav_about),
        stringResource(R.string.nav_settings), stringResource(R.string.app_name), stringResource(R.string.nav_section_people),
        stringResource(R.string.nav_section_app),
    )
    val spec = remember(labels) {
        ShellSpec(
            appTitle = labels[10],
            sections = listOf(
                NavSection(
                    title = null,
                    items = listOf(
                        NavItem(Routes.SEARCH, labels[0], DeskIcons.Search),
                        NavItem(Routes.TRANSFERS, labels[1], SoulIcons.Updown),
                        NavItem(Routes.WISHLIST, labels[2], SoulIcons.WishlistStar),
                        NavItem(Routes.CHAT, labels[3], SoulIcons.Chat),
                    ),
                ),
                NavSection(
                    title = labels[11],
                    items = listOf(
                        NavItem(Routes.USERS, labels[4], SoulIcons.Group),
                        NavItem(Routes.BROWSE, labels[5], DeskIcons.Folder),
                        NavItem(Routes.PROFILE, labels[6], SoulIcons.User),
                    ),
                ),
                NavSection(
                    title = labels[12],
                    items = listOf(
                        NavItem(Routes.SHARES, labels[7], SoulIcons.ShareFolder),
                        NavItem(Routes.ABOUT, labels[8], DeskIcons.Info),
                    ),
                ),
            ),
            footer = NavItem(Routes.SETTINGS, labels[9], DeskIcons.Settings),
            bottomBar = listOf(Routes.SEARCH, Routes.TRANSFERS, Routes.WISHLIST, Routes.CHAT),
            rail = listOf(Routes.SEARCH, Routes.TRANSFERS, Routes.WISHLIST, Routes.CHAT, Routes.USERS, Routes.BROWSE, Routes.SHARES),
            startRoute = Routes.SEARCH,
        )
    }

    val menus = remember(resources, host, container) {
        AppShortcuts.registry.menus(
            resources = resources,
            perform = { id -> host.perform(id) },
            enabled = { id -> isShortcutEnabled(id, container, routeState.value) },
        )
    }

    val transferStats by container.client.transferStats.collectAsStateWithLifecycle()
    val wishlistNew by container.wishlist.totalNewHits.collectAsStateWithLifecycle()
    val chatUnread by container.chat.totalUnread.collectAsStateWithLifecycle()
    val badges = buildMap<String, String> {
        val active = transferStats.activeDownloads + transferStats.activeUploads
        if (active > 0) put(Routes.TRANSFERS, active.toString())
        if (wishlistNew > 0) put(Routes.WISHLIST, if (wishlistNew > 99) "99+" else wishlistNew.toString())
        if (chatUnread > 0) put(Routes.CHAT, if (chatUnread > 99) "99+" else chatUnread.toString())
    }

    DesktopShell(
        spec = spec,
        widthClass = widthClass,
        navController = navController,
        sidebarCollapsed = prefs.sidebarCollapsed,
        onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
        badges = badges,
        menus = menus,
        onSearch = { host.perform(CommonShortcuts.SEARCH) },
        searchHint = stringResource(R.string.search_hint_global),
        bottomBar = { StatusStrip(container) },
    ) { currentRoute, _ ->
        Box(Modifier.fillMaxSize()) {
            when (currentRoute) {
                Routes.SEARCH -> SearchScreen(searchVm, container, actions)
                Routes.TRANSFERS -> TransfersScreen(transfersVm, container, actions)
                Routes.WISHLIST -> WishlistScreen(wishlistVm, container, actions)
                Routes.CHAT -> ChatScreen(chatVm, container, actions)
                Routes.USERS -> UsersScreen(container, actions)
                Routes.BROWSE -> BrowseScreen(browseVm, container, actions)
                Routes.PROFILE -> ProfileScreen(profileVm, container, actions)
                Routes.SHARES -> SharesScreen(container, actions)
                Routes.ABOUT -> AboutScreen()
                else -> SettingsScreen(
                    container = container,
                    prefs = prefs,
                    actions = actions,
                    onAccountSaved = onAccountSaved,
                    onOpenAbout = { navigator.navigate(Routes.ABOUT) },
                )
            }
            ToastHost(appViewModel, Modifier.align(Alignment.BottomCenter))
        }
    }

    linkDownload?.let { link ->
        val name = link.path.substringAfterLast('\\')
        DeskDialog(
            title = stringResource(R.string.link_download_title),
            text = stringResource(R.string.link_download_text, name, link.username),
            confirmLabel = stringResource(R.string.action_download),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                container.client.download(link.username, SharedFile(path = link.path, size = 0L))
                container.ensureServiceRunning()
                actions.toast(R.string.toast_queued_downloads, 1)
                linkDownload = null
            },
            onDismiss = { linkDownload = null },
        )
    }
}

/** Kısayolu çalıştırır; işlendiyse true. Menü ve klavye aynı yolu kullanır. */
private fun performShortcut(
    id: String,
    container: AppContainer,
    app: AppViewModel,
    prefs: DeskPreferences,
    navigator: ShellNavigator,
    search: SearchViewModel,
    transfers: TransfersViewModel,
    actions: AppActions,
): Boolean {
    when (id) {
        AppShortcuts.GO_SEARCH -> navigator.navigate(Routes.SEARCH)
        AppShortcuts.GO_TRANSFERS -> navigator.navigate(Routes.TRANSFERS)
        AppShortcuts.GO_WISHLIST -> navigator.navigate(Routes.WISHLIST)
        AppShortcuts.GO_CHAT -> navigator.navigate(Routes.CHAT)
        AppShortcuts.GO_USERS -> navigator.navigate(Routes.USERS)
        AppShortcuts.GO_SHARES -> navigator.navigate(Routes.SHARES)
        AppShortcuts.GO_ABOUT -> navigator.navigate(Routes.ABOUT)
        CommonShortcuts.SETTINGS, CommonShortcuts.SHORTCUT_LIST -> navigator.navigate(Routes.SETTINGS)
        CommonShortcuts.TOGGLE_SIDEBAR -> prefs.updateSidebarCollapsed(!prefs.sidebarCollapsed)

        AppShortcuts.CONNECT -> app.connect()
        AppShortcuts.DISCONNECT -> app.disconnect()
        AppShortcuts.TOGGLE_AWAY -> {
            val state = container.client.connection.value as? ConnectionState.LoggedIn ?: return true
            container.client.setAway(!state.away)
        }
        AppShortcuts.RESCAN_SHARES -> container.shares.rescan()

        CommonShortcuts.SEARCH, AppShortcuts.NEW_SEARCH -> {
            navigator.navigate(Routes.SEARCH)
            search.requestFocus()
        }
        AppShortcuts.CLOSE_TAB -> search.closeSelectedTab()
        AppShortcuts.NEXT_TAB, AppShortcuts.PREV_TAB -> {
            val tabs = container.searches.tabs.value
            val index = tabs.indexOfFirst { it.id == container.searches.selectedTabId.value }
            if (tabs.size > 1 && index >= 0) {
                val step = if (id == AppShortcuts.NEXT_TAB) 1 else -1
                container.searches.selectTab(tabs[Math.floorMod(index + step, tabs.size)].id)
            }
        }
        AppShortcuts.RERUN -> search.rerunSelectedTab()
        AppShortcuts.TOGGLE_FILTERS -> search.filtersOpen = !search.filtersOpen
        AppShortcuts.DOWNLOAD_SELECTED -> {
            val tabId = container.searches.selectedTabId.value ?: return true
            val tab = container.searches.tab(tabId) ?: return true
            search.download(search.resolveSelection(tab, search.tabUi(tabId).selected), actions)
        }

        AppShortcuts.PAUSE_SELECTED -> transfers.pause(transfers.resolve(transfers.selected))
        AppShortcuts.RESUME_SELECTED -> transfers.resume(transfers.resolve(transfers.selected))
        AppShortcuts.CLEAR_FINISHED -> transfers.clearFinished()
        else -> return false
    }
    return true
}

/** Menü maddelerinin etkinliği; her menü açılışında güncel durumdan okunur. */
private fun isShortcutEnabled(id: String, container: AppContainer, route: String): Boolean {
    val connection = container.client.connection.value
    return when (id) {
        AppShortcuts.CONNECT -> connection is ConnectionState.Disconnected
        AppShortcuts.DISCONNECT -> connection !is ConnectionState.Disconnected
        AppShortcuts.TOGGLE_AWAY -> connection is ConnectionState.LoggedIn
        AppShortcuts.RESCAN_SHARES -> !container.shares.progress.value.active && container.shares.roots.value.isNotEmpty()
        AppShortcuts.CLOSE_TAB, AppShortcuts.NEXT_TAB, AppShortcuts.PREV_TAB, AppShortcuts.RERUN,
        AppShortcuts.TOGGLE_FILTERS, AppShortcuts.DOWNLOAD_SELECTED,
        -> route == Routes.SEARCH && container.searches.tabs.value.isNotEmpty()
        AppShortcuts.PAUSE_SELECTED, AppShortcuts.RESUME_SELECTED, AppShortcuts.CLEAR_FINISHED -> route == Routes.TRANSFERS
        else -> true
    }
}

/** Alttan beliren geçici mesaj; dokununca kapanır. */
@Composable
private fun ToastHost(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val message by appViewModel.message.collectAsStateWithLifecycle()
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val current = message ?: return
    val shape = RoundedCornerShape(dimens.radiusControl)
    Row(
        modifier
            .padding(horizontal = dimens.pagePadding, vertical = dimens.gapL)
            .widthIn(max = 520.dp)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.borderStrong, shape)
            .clickable(onClick = appViewModel::dismissMessage)
            .padding(horizontal = dimens.gapL, vertical = dimens.gapM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapM),
    ) {
        Text(current.text, style = DeskTheme.type.body, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
