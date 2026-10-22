package com.soulseekplus

import android.content.Context
import com.soulseekplus.core.api.CountryResolver
import com.soulseekplus.core.api.SoulseekClient
import com.soulseekplus.core.chat.ChatRepository
import com.soulseekplus.core.engine.SoulseekEngine
import com.soulseekplus.core.geo.IpCountryResolver
import com.soulseekplus.core.search.SearchRepository
import com.soulseekplus.core.search.WishlistRepository
import com.soulseekplus.core.shares.ShareIndex
import com.soulseekplus.data.settings.PrefsWishlistStore
import com.soulseekplus.data.settings.SettingsRepository
import com.soulseekplus.data.settings.UserListsRepository
import com.soulseekplus.data.shares.ShareRepository
import com.soulseekplus.data.transfers.RoomTransferPersistence
import com.soulseekplus.notify.AppNotifications
import com.soulseekplus.service.SoulseekService
import com.soulseekplus.storage.SafDownloadStore
import com.soulseekplus.ui.player.MiniPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Kompozisyon kökü (sözleşme: docs/ARCHITECTURE.md). Tüm uzun ömürlü nesneler burada bir kez kurulur.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Uygulama ömrü boyunca yaşayan kapsam (depolar, gözlemciler). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val userLists = UserListsRepository(appContext)

    /** IP → ülke (bayrak ve ülke filtresi). Varlık tembel yüklenir; okunamazsa çözümleyici null döner. */
    val countries: CountryResolver = LazyCountryResolver(appContext)

    val shareIndex = ShareIndex(AndroidLogger)
    val shares = ShareRepository(appContext, shareIndex, userLists.lists, appScope, AndroidLogger)

    val engine = SoulseekEngine(
        settings = settings.clientSettings,
        userLists = userLists.lists,
        shares = shareIndex,
        downloadStore = SafDownloadStore(appContext, { settings.downloadRootUri.value }, AndroidLogger),
        transferPersistence = RoomTransferPersistence(appContext),
        countryResolver = countries,
        loadPicture = ::readPicture,
        logger = AndroidLogger,
    )

    /** UI'nin kullandığı cephe. */
    val client: SoulseekClient get() = engine

    val searches = SearchRepository(engine, settings.clientSettings, appScope)
    val wishlist = WishlistRepository(engine, PrefsWishlistStore(appContext), appScope)
    val chat = ChatRepository(engine, settings.clientSettings, userLists.lists, appScope)
    val player = MiniPlayerController(appContext)
    private val notifications = AppNotifications(appContext, engine, chat, wishlist, appScope)

    fun start() {
        shares.start()
        engine.start()
        notifications.start()
    }

    /**
     * Ön plan servisini başlatır (bağlantı ve transferler ekran kapalıyken de sürsün).
     * Android 12+ arka plandan ön plan servisi başlatmayı yasakladığı için yalnızca görünür bir Activity'den
     * (MainActivity.onStart, giriş yapıldığında, bağlan düğmesi) çağrılır.
     */
    fun ensureServiceRunning() {
        if (settings.clientSettings.value.isConfigured) SoulseekService.start(appContext)
    }

    fun stopService() = SoulseekService.stop(appContext)

    private fun readPicture(uri: String): ByteArray? = runCatching {
        appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            val bytes = input.readBytes()
            // Soulseek istemcileri büyük resimleri reddedebilir; 1 MiB üstünü göndermeyiz.
            bytes.takeIf { it.size <= 1024 * 1024 }
        }
    }.getOrNull()

    private class LazyCountryResolver(private val context: Context) : CountryResolver {
        private val delegate: IpCountryResolver? by lazy {
            runCatching { context.assets.open("ip_country.bin").use { IpCountryResolver.load(it) } }
                .onFailure { AndroidLogger.warn("Geo", "ip_country.bin yüklenemedi", it) }
                .getOrNull()
        }

        override fun countryOf(ipv4: String): String? = delegate?.countryOf(ipv4)
    }
}
