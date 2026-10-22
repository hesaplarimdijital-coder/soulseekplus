package com.soulseekplus.core.model

/**
 * Motorun okuduğu tüm ayarlar. Uygulama katmanı kalıcı olarak saklar ve [kotlinx.coroutines.flow.StateFlow] olarak verir;
 * motor değişiklikleri canlı uygular (ör. yükleme yuvası sayısı, hız sınırları, dinleme portu yeniden bağlanmayla).
 */
data class ClientSettings(
    // --- Hesap / sunucu
    val username: String = "",
    val password: String = "",
    val serverHost: String = DEFAULT_SERVER_HOST,
    val serverPort: Int = DEFAULT_SERVER_PORT,
    /** Uygulama açılınca otomatik bağlan. */
    val autoConnect: Boolean = true,

    // --- Ağ
    /** Gelen eş bağlantıları için dinleme portu. */
    val listenPort: Int = DEFAULT_LISTEN_PORT,
    /** UPnP IGD / NAT-PMP ile yönlendiricide port açma. */
    val portMappingEnabled: Boolean = true,
    /** Yalnızca Wi-Fi/Ethernet'te transfer yap (mobil veride bağlı kal ama transfer başlatma). */
    val transfersOnWifiOnly: Boolean = false,

    // --- Yükleme
    val uploadSlots: Int = 2,
    val uploadQueueMode: UploadQueueMode = UploadQueueMode.ROUND_ROBIN,
    /** Toplam yükleme hız sınırı (KiB/s, 0 = sınırsız). */
    val uploadSpeedLimitKiB: Int = 0,
    /** Arkadaşlara öncelikli yuva. */
    val prioritizeBuddies: Boolean = true,
    /** Yalnızca arkadaşlara yükleme. */
    val uploadToBuddiesOnly: Boolean = false,
    /** Kullanıcı başına kuyruğa alınabilecek en fazla dosya (0 = sınırsız). */
    val maxQueuedFilesPerUser: Int = 500,
    /** Kullanıcı başına kuyruğa alınabilecek en fazla boyut (MiB, 0 = sınırsız). */
    val maxQueuedMiBPerUser: Int = 0,

    // --- İndirme
    /** Toplam indirme hız sınırı (KiB/s, 0 = sınırsız). */
    val downloadSpeedLimitKiB: Int = 0,
    /** Kullanıcı tekrar çevrimiçi olunca / hata sonrası otomatik yeniden dene. */
    val autoRetryDownloads: Boolean = true,
    /** Aynı anda en fazla aktif indirme (0 = sınırsız). */
    val maxActiveDownloads: Int = 0,
    /** İndirmeleri kullanıcı adına göre alt klasöre koy. */
    val downloadsIntoUserFolder: Boolean = false,

    // --- Arama
    /** Gelen aramalara verilecek en fazla sonuç. */
    val maxSearchResultsToSend: Int = 1500,
    /** Kendi aramalarımızda tutulacak en fazla sonuç (sekme başına). */
    val maxSearchResultsKept: Int = 5000,
    /** Gelen aramaları yanıtla (paylaşımlar etkin). */
    val respondToSearches: Boolean = true,

    // --- Profil
    val userDescription: String = "",
    /** Profil resmi: SAF content:// URI ya da dosya yolu (PNG/JPEG), yoksa null. Baytları uygulama katmanı okur. */
    val userPictureUri: String? = null,

    // --- Sohbet
    /** Bağlanınca otomatik katılınacak odalar. */
    val autoJoinRooms: List<String> = emptyList(),
    /** Komut takma adları: `/alias ad komut`. */
    val commandAliases: Map<String, String> = emptyMap(),
) {
    val isConfigured: Boolean get() = username.isNotBlank() && password.isNotEmpty()

    companion object {
        const val DEFAULT_SERVER_HOST = "server.slsknet.org"
        const val DEFAULT_SERVER_PORT = 2242
        const val DEFAULT_LISTEN_PORT = 2234
    }
}
