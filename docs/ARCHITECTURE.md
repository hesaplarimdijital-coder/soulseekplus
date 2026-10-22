# Soulseek+ Mimarisi (sözleşme belgesi)

Bu belge modül sınırlarını, dosya sahipliğini ve kurucu imzalarını sabitler. Ajanlar birbirinin iç kodunu okumadan
paralel çalışır; buradaki imzalar **değiştirilmez**. Sözleşme dışında bir şeye ihtiyaç duyan ajan kendi tarafında
çözer ya da raporunda belirtir.

## Modüller

| Modül | Tür | Kural |
|---|---|---|
| `:core` | saf Kotlin/JVM | `android.*`/`androidx.*` importu YASAK. Coroutines 1.6.4'te olmayan API kullanılmaz (yerel doğrulama derleyicisi). |
| `:app` | Android (Compose, Room, SAF) | UI `desktop-kit` 0.1.3 üzerine kurulur. |

Paket kökleri: `com.soulseekplus.core.*` ve `com.soulseekplus.*` (app).

Yerel doğrulama: `tools/verify-core.sh` (Gradle'ın gömülü Kotlin 2.0.21 derleyicisi, coroutines 1.6.4, JUnit 4).
`:app` yalnızca CI'da derlenir (`.github/workflows/android.yml`).

## Sözleşme dosyaları (MİMAR — değiştirilmez)

- `core/model/*.kt` — tüm veri modelleri (Files, Users, Search, Transfers, Chat, Settings, Network)
- `core/api/SoulseekClient.kt` — motor cephesi; `core/api/ClientEvent.kt` — olaylar
- `core/api/Ports.kt` — `ShareProvider`, `UploadSource`, `DownloadStore`, `TransferPersistence`, `CountryResolver`, `Logger`
- `core/protocol/PacketSerializer.kt` — `MessageWriter`, `MessageReader`, `Frame` (çerçeve, zlib)
- `core/geo/IpCountryResolver.kt` — IP → ülke (varlık: `app/src/main/assets/ip_country.bin`)
- `app/.../SoulseekApp.kt`, `app/.../AppContainer.kt`, `app/.../AndroidLogger.kt`, `app/src/main/AndroidManifest.xml`

## Dosya sahipliği

### B1 — Protokol, P2P, UPnP, transfer, servis
- `core/protocol/` (PacketSerializer hariç): `Codes.kt`, `ServerMessages.kt`, `PeerMessages.kt`, `DistributedMessages.kt`, `SharesCodec.kt` (paylaşım listesi/arama yanıtı kodlama)
- `core/net/` — NIO (`java.nio.channels` Selector) bağlantı motoru: sunucu, eş "P", dağıtık "D", dosya "F", dinleyici, PierceFirewall/dolaylı bağlantı
- `core/distrib/` — dağıtık arama ağı (ebeveyn seçimi, çocuklara iletme, dal seviyesi/kökü)
- `core/transfers/` — `TransferManager`, `UploadQueue` (Round-Robin/FIFO, saf ve test edilir), `RateLimiter`
- `core/upnp/` — SSDP + IGD SOAP AddPortMapping, NAT-PMP, `PortMapper` (kira yenileme)
- `core/engine/SoulseekEngine.kt` — `SoulseekClient` uygulaması
- `core/src/test/.../{protocol,net,transfers,upnp,engine}/`
- `app/.../service/SoulseekService.kt` — ön plan servisi (`dataSync`), WifiLock, transfer sürerken kısmi WakeLock, ağ değişimi geri çağrısı, ilerleme bildirimi
- `app/.../storage/SafDownloadStore.kt` — `DownloadStore` (SAF, `.part`, devam, atomik ad değiştirme)
- `app/.../data/transfers/` — Room `TransfersDatabase` + `RoomTransferPersistence`

### B2 — Arama/filtre, istek listesi, paylaşım, sohbet, arayüz
- `core/search/` — `SearchFilterEngine` (saf), gruplama, `SearchRepository`, `WishlistRepository`, `WishlistStore` (port)
- `core/shares/` — `ShareIndex : ShareProvider` (kelime dizini, 3 kademe izin, sanal takma adlar), `ShareSnapshot` modelleri
- `core/chat/` — `ChatRepository`, `CommandParser` (`/join /leave /msg /me /browse /whois /info /search /rsearch /bsearch /usearch /ignore /ban /add /rem /away /clear /alias /unalias /help`)
- `core/src/test/.../{search,shares,chat}/`
- `app/.../data/settings/` — `SettingsRepository`, `UserListsRepository`, `PrefsWishlistStore`
- `app/.../data/shares/` — SAF `DocumentsContract` tarayıcısı, Room `SharesDatabase` önbelleği, `ShareRepository`
- `app/.../ui/` — tüm ekranlar, ViewModel'ler, `MainActivity`, ikonlar (`ui/icons/SoulIcons.kt`), mini oynatıcı, `slsk://` işleme
- `app/.../notify/AppNotifications.kt` — özel mesaj, istek listesi, transfer bitti bildirimleri
- `app/src/main/res/` (lead'in koyduğu ikon/tema dosyaları hariç), `values/strings.xml` (İngilizce) ve `values-tr/strings.xml` (Türkçe)

### Lead
- Kompozisyon kökü, manifest, CI, entegrasyon ve kalite denetimi.

## Kurucu imzaları (AppContainer bunları aynen çağırır)

```kotlin
// B1 — core
class SoulseekEngine(
    settings: StateFlow<ClientSettings>,
    userLists: StateFlow<UserLists>,
    shares: ShareProvider,
    downloadStore: DownloadStore,
    transferPersistence: TransferPersistence,
    countryResolver: CountryResolver,
    loadPicture: (uri: String) -> ByteArray?,
    logger: Logger,
) : SoulseekClient {
    /** Motoru başlatır: kalıcı transferleri yükler; autoConnect ve hesap varsa bağlanır. Bir kez çağrılır. */
    fun start()
    /** Her şeyi kapatır (soketler, port eşlemesi kaldırılır). */
    fun shutdown()
}

// B1 — app
class SafDownloadStore(context: Context, rootUri: () -> String?, logger: Logger) : DownloadStore
class RoomTransferPersistence(context: Context) : TransferPersistence
class SoulseekService : LifecycleService() {
    companion object {
        fun start(context: Context)   // startForegroundService
        fun stop(context: Context)
    }
}

// B2 — core
class ShareIndex(logger: Logger) : ShareProvider {
    fun update(snapshot: ShareSnapshot, userLists: UserLists)   // paylaşım ya da kullanıcı listesi değişince
}
class SearchRepository(client: SoulseekClient, settings: StateFlow<ClientSettings>, scope: CoroutineScope)
class WishlistRepository(client: SoulseekClient, store: WishlistStore, scope: CoroutineScope)
class ChatRepository(client: SoulseekClient, settings: StateFlow<ClientSettings>, userLists: StateFlow<UserLists>, scope: CoroutineScope)

// B2 — app
class SettingsRepository(context: Context) {
    val clientSettings: StateFlow<ClientSettings>
    val downloadRootUri: StateFlow<String?>
}
class UserListsRepository(context: Context) { val lists: StateFlow<UserLists> }
class PrefsWishlistStore(context: Context) : WishlistStore
class ShareRepository(context: Context, index: ShareIndex, userLists: StateFlow<UserLists>, scope: CoroutineScope, logger: Logger) {
    /** Room önbelleğinden yükler (disk taraması yapmaz); önbellek yoksa tarar. */
    fun start()
}
class MiniPlayerController(context: Context)
class AppNotifications(context: Context, client: SoulseekClient, chat: ChatRepository, wishlist: WishlistRepository, scope: CoroutineScope) {
    fun start()
}
```

UI, kompozisyon köküne `SoulseekApp.container(context)` ile erişir (bkz. `AppContainer.kt`).

## İş parçacığı ve yaşam döngüsü

- Motor kendi `CoroutineScope(SupervisorJob() + Dispatchers.IO)` kapsamında çalışır; NIO seçici tek bir iş parçacığında
  döner, mesaj işleme coroutine'lerde yapılır. Kilit sırası: hiçbir kilit tutulurken ağ G/Ç'si ya da başka bir modülün
  geri çağrısı yapılmaz (kilitlenme önleme). Paylaşılan durum `MutableStateFlow.update {}` ya da tek iş parçacıklı
  aktör (Channel) ile değiştirilir.
- `SoulseekApp.onCreate` kapsayıcıyı kurar ve `engine.start()` çağırır. `MainActivity.onStart`, girişte ve "Bağlan"
  eyleminde `SoulseekApp.container(ctx).ensureServiceRunning()` çağrılır (Android 12+ arka plandan ön plan servisi
  başlatmayı yasaklar). Servis bağlantı/transfer sürdükçe süreci canlı tutar; kullanıcı bağlantıyı kesip aktif transfer
  kalmayınca kendini durdurur. `SoulseekService.start` istisnaları yutar ve günlüğe yazar.
- Ana Activity sınıfı: `com.soulseekplus.ui.MainActivity` (manifestte kayıtlı).
- Depolar (`SearchRepository`, `ChatRepository`, ...) uygulama kapsamında yaşar (`AppContainer.appScope`).

## Dizgeler ve dil

- `:core` kullanıcıya dönük metin üretmez; durum/sebepleri sabit İngilizce dizgelerle (`"File not shared."`,
  `"User offline"`, `"Cancelled"` ...) ya da enum'larla verir. UI bunları kaynak dizgelerine eşler.
- Uygulama metinleri `values/strings.xml` (İngilizce) + `values-tr/strings.xml` (Türkçe).

## Kalite kuralları

- `// TODO`, yer tutucu, yarım fonksiyon yok. Her dosya ilk seferde derlenebilir olmalı.
- Her soket/kanal/akış `use {}` ya da `finally` ile kapatılır; zaman aşımları açıkça belirtilir.
- Gelen ağ verisi güvenilmezdir: uzunluk sınırları, zlib bombası sınırı, yol geçişi (`..`) kontrolü.
- SAF izinleri `takePersistableUriPermission` ile kalıcı alınır; dosya adları temizlenir.
