package com.soulseekplus.core.model

/** Bir aramanın hedefi. */
sealed interface SearchScope {
    /** Tüm ağ (sunucu FileSearch). */
    data object Network : SearchScope

    /** Bir sohbet odasının üyeleri (RoomSearch). */
    data class Room(val room: String) : SearchScope

    /** Arkadaş listesindeki herkes (her arkadaşa UserSearch). */
    data object Buddies : SearchScope

    /** Tek kullanıcı (UserSearch). */
    data class User(val username: String) : SearchScope

    /** İstek listesi araması (WishlistSearch). Kullanıcı arayüzünde ayrı sekmede gösterilir. */
    data object Wishlist : SearchScope
}

/** Eşten gelen ham arama yanıtı (FileSearchResponse). */
data class SearchResponse(
    val username: String,
    val token: Int,
    val files: List<SharedFile>,
    /** Yanıtlayanın, isteyene kilitli gösterdiği (arkadaşlara özel vb.) dosyalar. */
    val lockedFiles: List<SharedFile>,
    val freeSlots: Boolean,
    /** Ortalama yükleme hızı, bayt/saniye. */
    val averageSpeed: Int,
    val queueLength: Long,
    /** Yanıt geldiğinde bilinen ülke kodu (çözülemediyse null). */
    val countryCode: String? = null,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

/** Arama tablosundaki tek satır: bir yanıttaki tek dosya. */
data class SearchHit(
    val username: String,
    val file: SharedFile,
    val locked: Boolean,
    val freeSlots: Boolean,
    val averageSpeed: Int,
    val queueLength: Long,
    val countryCode: String?,
    /** İstek listesinde, daha önce görülmemiş (yeni düşen) sonuç. */
    val isNew: Boolean = false,
) {
    /** Kullanıcı + yol, sonuçların tekilliği için anahtar. */
    val key: String get() = "$username\u0000${file.path}"
}

/** Sonuç tablosunun gruplama kipi (Nicotine+). */
enum class GroupMode { FOLDER, USER, FLAT }

/**
 * Arama sonucu filtreleri (Nicotine+ filtreleri + Seeker hızlı çipleri).
 *
 * Kelime filtreleri: [include] içindeki her kelime dosya yolunda geçmeli; [exclude] içindeki hiçbir kelime geçmemeli.
 * [useRegex] açıkken [include]/[exclude] birer düzenli ifadedir (büyük/küçük harf duyarsız).
 */
data class SearchFilter(
    val include: String = "",
    val exclude: String = "",
    val useRegex: Boolean = false,
    /** Asgari bitrate (kbps). Kayıpsız dosyalar her zaman geçer. */
    val minBitrate: Int? = null,
    val losslessOnly: Boolean = false,
    val minSampleRate: Int? = null,
    val minBitDepth: Int? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    /** Virgülle ayrılmış uzantılar, ör. "flac,mp3" (boş: hepsi). */
    val extensions: String = "",
    /** ISO ülke kodları, ör. "TR,DE" (boş: hepsi). Başına '!' konursa hariç tutar: "!US". */
    val countries: String = "",
    val freeSlotsOnly: Boolean = false,
    val hideLocked: Boolean = true,
    val maxQueueLength: Long? = null,
    val minSpeedBytes: Int? = null,
) {
    val isEmpty: Boolean get() = this == SearchFilter()
}

/** Kullanıcı arayüzündeki bir arama sekmesinin durumu. */
data class SearchTabState(
    val id: Int,
    val query: String,
    val scope: SearchScope,
    val startedAtMillis: Long,
    /** Filtre uygulanmadan önceki tüm satırlar. */
    val allHits: List<SearchHit>,
    /** Filtrelenmiş satırlar (tabloya giden). */
    val visibleHits: List<SearchHit>,
    val filter: SearchFilter,
    val groupMode: GroupMode,
    /** Yanıt veren benzersiz kullanıcı sayısı. */
    val userCount: Int,
    /** Arama hâlâ yanıt topluyor mu. */
    val active: Boolean,
)

/** İstek listesi girdisi. */
data class WishlistItem(
    val query: String,
    val addedAtMillis: Long,
    val lastSearchedAtMillis: Long? = null,
    /** Son taramada yeni düşen sonuç sayısı (rozet). */
    val newHitCount: Int = 0,
    val enabled: Boolean = true,
)
