package com.soulseekplus.core.api

import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ShareStats
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.Transfer
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/*
 * Motorun (core.engine) dış dünyaya bağımlılıkları. Android katmanı (app) bunları SAF ve Room ile uygular;
 * testler bellek içi/dosya sistemi sürümleriyle uygular. Hiçbir port android.* tipi içermez.
 */

/** Paylaşımlarımızı motora sunan port. Uygulayan: core.shares.ShareIndex (B2). Tüm metotlar iş parçacığı güvenlidir. */
interface ShareProvider {

    /** Güncel istatistik; değişince motor sunucuya SharedFoldersFiles gönderir. */
    val stats: StateFlow<ShareStats>

    /**
     * Gelen bir aramayı (sunucu, dağıtık ağ ya da oda/kullanıcı araması) [requester] adına eşleştirir.
     * [requester]'ın göremediği (arkadaşa/gruba özel) eşleşmeler [ShareSearchResult.locked]'a konur.
     * Eşleşme yoksa ya da istek yasaklı kullanıcıdansa boş sonuç döner. En çok [maxResults] dosya (toplam).
     */
    fun search(query: String, requester: String, maxResults: Int): ShareSearchResult

    /** [requester]'ın göreceği paylaşım ağacı (SharedFileListResponse için). */
    fun browse(requester: String): BrowseResult

    /** Tek klasörün içeriği (FolderContentsResponse). Klasör ve alt klasörleri; erişim yoksa ya da yoksa boş liste. */
    fun folderContents(requester: String, folderPath: String): List<SharedFolder>

    /**
     * Yükleme isteğini çözer: sanal yol paylaşılıyor mu ve [requester]'ın erişimi var mı.
     */
    fun resolveUpload(requester: String, virtualPath: String): UploadResolution
}

data class ShareSearchResult(val files: List<SharedFile>, val locked: List<SharedFile>) {
    val isEmpty: Boolean get() = files.isEmpty() && locked.isEmpty()

    companion object {
        val EMPTY = ShareSearchResult(emptyList(), emptyList())
    }
}

sealed interface UploadResolution {
    /** İzinli. [source] ile dosya açılır. */
    data class Allowed(val file: SharedFile, val source: UploadSource) : UploadResolution

    /** Paylaşılıyor ama bu kullanıcıya kapalı. Protokol nedeni: "File not shared." ya da "Banned". */
    data class Denied(val reason: String) : UploadResolution

    /** Böyle bir paylaşılan dosya yok. */
    data object NotShared : UploadResolution
}

/** Yüklenecek bir dosyanın kaynağı. */
interface UploadSource {
    /** Gerçek (diskteki) boyut. */
    val size: Long

    /** Dosyayı [offset] baytından itibaren okumak için açar. Çağıran kapatır. Dosya artık yoksa IOException. */
    fun open(offset: Long): InputStream
}

/**
 * İndirilen dosyaların yazıldığı depo. Uygulayan: app.storage.SafDownloadStore (B1), testte dosya sistemi.
 * Dosya önce `<ad>.part` olarak yazılır, bitince atomik olarak son adına taşınır (çakışmada " (1)" eklenir).
 */
interface DownloadStore {
    /** İndirme kökü yapılandırılmış ve yazılabilir mi. */
    fun isReady(): Boolean

    /** Bu transfer için var olan `.part` dosyasının boyutu (devam ofseti); yoksa 0. */
    suspend fun partialSize(transfer: Transfer): Long

    /** `.part` dosyasını [offset]'ten itibaren yazmak için açar ([offset] = mevcut boyut ise ekler; 0 ise baştan yazar). */
    suspend fun openPartial(transfer: Transfer, offset: Long): OutputStream

    /** `.part` dosyasını son adına taşır ve son konumu (URI/yol) döndürür. */
    suspend fun complete(transfer: Transfer): String

    /** `.part` dosyasını siler (iptal). */
    suspend fun discard(transfer: Transfer)
}

/** Transferlerin uygulama yeniden başlatılınca geri yüklenmesi. Uygulayan: app (Room, B1). */
interface TransferPersistence {
    suspend fun loadAll(): List<Transfer>

    /** Ekle ya da güncelle (id'ye göre). Sık çağrılır; uygulama yazmaları toplu/geciktirilmiş yapabilir. */
    suspend fun upsert(transfers: List<Transfer>)

    suspend fun delete(ids: Collection<String>)
}

/** IPv4 → ülke kodu çözümleyici. Uygulayan: core.geo.IpCountryResolver. */
fun interface CountryResolver {
    /** "1.2.3.4" → "AU"; bilinmiyorsa null. */
    fun countryOf(ipv4: String): String?
}

/** Basit günlük arayüzü (Android'de Logcat'e, testte stdout'a). */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        /** Hiçbir şey yazmayan günlükçü. */
        val NONE: Logger = object : Logger {
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}
