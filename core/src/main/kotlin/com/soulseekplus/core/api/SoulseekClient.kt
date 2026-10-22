package com.soulseekplus.core.api

import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.DistributedState
import com.soulseekplus.core.model.NetworkStatus
import com.soulseekplus.core.model.PortMappingState
import com.soulseekplus.core.model.SearchResponse
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferStats
import com.soulseekplus.core.model.UserInfo
import com.soulseekplus.core.model.UserPresence
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Soulseek motorunun kullanıcı arayüzüne ve depolara açılan tek cephesi.
 * Uygulayan: core.engine.SoulseekEngine (B1). Tüketen: core.search / core.chat depoları ve app ekranları (B2).
 *
 * İş parçacığı modeli: bütün metotlar herhangi bir iş parçacığından çağrılabilir ve hemen döner (ağ işi motorun kendi
 * kapsamında yürür). `suspend` olanlar yanıtı bekler ve zaman aşımında [SoulseekException] fırlatır.
 * Bağlı değilken çağrılan gönderim metotları sessizce yok sayılır (arama/indirme kuyruğa alınır, bkz. ayrıntılar).
 */
interface SoulseekClient {

    // ---------------------------------------------------------------- durum akışları

    val connection: StateFlow<ConnectionState>

    /** Olay akışı (sıcak, replay yok, yavaş aboneler için tamponlu). */
    val events: SharedFlow<ClientEvent>

    /** İzlenen kullanıcıların durumu (watchUser ile eklenenler + oda üyeleri + transfer eşleri). */
    val users: StateFlow<Map<String, UserPresence>>

    /** Tüm indirmeler ve yüklemeler (eklenme sırasına göre). */
    val transfers: StateFlow<List<Transfer>>

    val transferStats: StateFlow<TransferStats>

    val portMapping: StateFlow<PortMappingState>

    val distributed: StateFlow<DistributedState>

    val network: StateFlow<NetworkStatus>

    /** Kendi aramalarımıza gelen yanıtlar (tüm sekmeler; token ile ayrılır). */
    val searchResponses: SharedFlow<SearchResponse>

    /** Sunucunun bildirdiği istek listesi arama aralığı (saniye; bilinmiyorsa varsayılan 720). */
    val wishlistIntervalSeconds: StateFlow<Int>

    // ---------------------------------------------------------------- oturum

    /** Ayarlardaki hesapla bağlan (bağlıysa no-op). Kopunca motor üstel geri çekilmeyle kendisi yeniden bağlanır. */
    fun connect()

    /** Bağlantıyı kapat ve yeniden bağlanmayı durdur. Transferler duraklatılmış sayılır, kuyruk korunur. */
    fun disconnect()

    /** Ağ değişti (Wi-Fi ↔ mobil): soketleri kontrol et, gerekirse hemen yeniden bağlan, portu yeniden eşle. */
    fun onNetworkChanged(onMetered: Boolean)

    /** Durum: uzakta (true) / çevrimiçi (false). */
    fun setAway(away: Boolean)

    // ---------------------------------------------------------------- kullanıcılar

    fun watchUser(username: String)

    fun unwatchUser(username: String)

    suspend fun getUserInfo(username: String): UserInfo

    /** Kullanıcının tüm paylaşımlarını getir (SharedFileListRequest). Büyük listeler için uzun zaman aşımı. */
    suspend fun browseUser(username: String): BrowseResult

    /** Tek klasörün içeriği (FolderContentsRequest). */
    suspend fun getFolderContents(username: String, folderPath: String): List<SharedFolder>

    /** Kullanıcının IP adresi ve portu (GetPeerAddress); çevrimdışıysa null. */
    suspend fun getUserAddress(username: String): Pair<String, Int>?

    // ---------------------------------------------------------------- arama

    /**
     * Arama başlatır ve token döndürür. Yanıtlar [searchResponses]'a token ile düşer.
     * [SearchScope.Wishlist] WishlistSearch gönderir. Bağlı değilse token yine döner ama gönderim yapılmaz.
     */
    fun search(query: String, scope: SearchScope): Int

    /** Bu token'a artık yanıt kabul etme (sekme kapandı). */
    fun cancelSearch(token: Int)

    // ---------------------------------------------------------------- sohbet

    fun requestRoomList()

    fun joinRoom(room: String, private: Boolean = false)

    fun leaveRoom(room: String)

    fun sayInRoom(room: String, text: String)

    fun sendPrivateMessage(username: String, text: String)

    /** Odadaki kendi duvar yazımızı ayarla (boş metin kaldırır). */
    fun setRoomTicker(room: String, text: String)

    // ---------------------------------------------------------------- transferler

    /**
     * Dosyayı indirme kuyruğuna ekler. Aynı kullanıcı+yol zaten varsa ve bitmemişse yeniden eklenmez.
     * [destinationSubfolder] indirme kökü altındaki alt klasör (`/` ayırıcı; null = kök, ayara göre kullanıcı klasörü).
     */
    fun download(username: String, file: SharedFile, destinationSubfolder: String? = null)

    /**
     * Uzak klasörü (ve alt klasörlerini) indirir: önce FolderContentsRequest ile içeriği alır, sonra her dosyayı ekler.
     * Hedef alt klasör: uzak klasörün adı (ve alt klasör yapısı korunur).
     */
    suspend fun downloadFolder(username: String, folderPath: String): Int

    fun pauseTransfers(ids: Collection<String>)

    /** Duraklatılmış/başarısız/çevrimdışı transferleri yeniden kuyruğa alır. */
    fun resumeTransfers(ids: Collection<String>)

    /** İptal eder; indirmelerde `.part` dosyası [deleteFiles] ise silinir. */
    fun cancelTransfers(ids: Collection<String>, deleteFiles: Boolean = true)

    /** Listeden kaldırır (bitmiş/iptal olanlar; aktifler önce iptal edilir). */
    fun removeTransfers(ids: Collection<String>)

    /** Bitmiş transferleri temizle. */
    fun clearFinished(direction: com.soulseekplus.core.model.TransferDirection)

    /** Uzak kuyruktaki yeri sor (PlaceInQueueRequest). */
    fun refreshQueuePosition(id: String)
}

/** Motor isteği başarısız oldu (zaman aşımı, kullanıcı çevrimdışı, bağlantı reddi...). [message] kullanıcıya gösterilebilir. */
class SoulseekException(message: String, cause: Throwable? = null) : Exception(message, cause)
