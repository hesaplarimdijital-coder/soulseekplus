package com.soulseekplus.core.model

enum class TransferDirection { DOWNLOAD, UPLOAD }

/** Transfer durum makinesi. */
enum class TransferState {
    /** Yerel kuyrukta, eşe henüz istek gönderilmedi (indirme) ya da yükleme sırası bekleniyor. */
    QUEUED,

    /** Uzak tarafın kuyruğunda bekliyor (indirme; [Transfer.queuePosition] dolu olabilir). */
    QUEUED_REMOTE,

    /** Eşe bağlanılıyor / el sıkışma. */
    CONNECTING,

    /** Veri akıyor. */
    TRANSFERRING,

    /** Kullanıcı duraklattı. */
    PAUSED,

    /** Tamamlandı. */
    FINISHED,

    /** Kullanıcı iptal etti. */
    CANCELLED,

    /** Hata ile durdu; [Transfer.failReason] dolu. Otomatik yeniden deneme uygun olabilir. */
    FAILED,

    /** Uzak taraf reddetti (UploadDenied / TransferResponse reddi). */
    DENIED,

    /** Kullanıcı çevrimdışı; çevrimiçi olunca otomatik devam (Auto-Retry). */
    USER_OFFLINE;

    val isTerminal: Boolean get() = this == FINISHED || this == CANCELLED || this == DENIED
    val isActive: Boolean get() = this == CONNECTING || this == TRANSFERRING
}

/** Bir indirme ya da yükleme. Değişmezdir; motor her güncellemede yeni kopya yayımlar. */
data class Transfer(
    /** Kalıcı benzersiz kimlik (yön + kullanıcı + yol'dan türetilir, bkz. [idOf]). */
    val id: String,
    val direction: TransferDirection,
    val username: String,
    /** Soulseek sanal yolu (`\` ayırıcı). */
    val virtualPath: String,
    /** Bilinen toplam boyut (bilinmiyorsa 0). */
    val size: Long,
    val bytesTransferred: Long = 0,
    val state: TransferState = TransferState.QUEUED,
    val failReason: String? = null,
    /** Uzak kuyruktaki yer (indirme) ya da bizim kuyruğumuzdaki yer (yükleme). */
    val queuePosition: Int? = null,
    /** Anlık hız, bayt/saniye. */
    val speedBytesPerSecond: Long = 0,
    val attributes: FileAttributes = FileAttributes.EMPTY,
    /** İndirmenin hedef alt klasörü (indirme kökü altında, `/` ayırıcı). null: kök. */
    val destinationSubfolder: String? = null,
    /** Tamamlanan indirmenin son konumu (SAF content:// URI ya da dosya yolu). */
    val localUri: String? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /** Bu transfer için yapılan otomatik yeniden deneme sayısı. */
    val retryCount: Int = 0,
) {
    val fileName: String get() = virtualPath.substringAfterLast('\\')
    val folder: String get() = if ('\\' in virtualPath) virtualPath.substringBeforeLast('\\') else ""
    val progress: Float get() = if (size <= 0) 0f else (bytesTransferred.toFloat() / size).coerceIn(0f, 1f)

    /** Kalan süre tahmini (saniye), hız bilinmiyorsa null. */
    val etaSeconds: Long?
        get() = if (speedBytesPerSecond <= 0 || size <= 0) null else (size - bytesTransferred).coerceAtLeast(0) / speedBytesPerSecond

    companion object {
        fun idOf(direction: TransferDirection, username: String, virtualPath: String): String =
            (if (direction == TransferDirection.DOWNLOAD) "d:" else "u:") + username + "\u0000" + virtualPath
    }
}

/** Yükleme kuyruğu stratejisi (Nicotine+). */
enum class UploadQueueMode {
    /** Bekleyen kullanıcılara sırayla hak verilir. */
    ROUND_ROBIN,

    /** İlk gelen ilk alır. */
    FIFO,
}

/** Anlık ağ hızları (bayt/saniye) ve sayaçlar. */
data class TransferStats(
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val activeDownloads: Int = 0,
    val activeUploads: Int = 0,
    val queuedUploads: Int = 0,
    val freeUploadSlots: Int = 0,
)
