package com.soulseekplus.core.protocol

import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Eş ("P") mesajları (bkz. docs/slsk-protocol.md §5). Çerçeve: uint32 uzunluk + uint32 kod + yük.
 * SharedFileListResponse, FileSearchResponse ve FolderContentsResponse yükü zlib ile sıkıştırılır.
 */
sealed interface PeerMessage {
    val code: Int

    /** Çerçevelenecek yük (gerekiyorsa sıkıştırılmış). */
    fun payload(): ByteArray

    fun encode(): ByteArray = Frame.code32(code, payload())
}

/** Sıkıştırılmamış, yalnızca [writePayload] ile tanımlanan mesajlar. */
sealed interface PlainPeerMessage : PeerMessage {
    fun writePayload(w: MessageWriter)
    override fun payload(): ByteArray {
        val w = MessageWriter()
        writePayload(w)
        return w.toByteArray()
    }
}

data object SharedFileListRequest : PlainPeerMessage {
    override val code get() = PeerCode.SHARED_FILE_LIST_REQUEST
    override fun writePayload(w: MessageWriter) = Unit
}

data class SharedFileListResponse(
    val folders: List<SharedFolder>,
    val lockedFolders: List<SharedFolder> = emptyList(),
) : PeerMessage {
    override val code get() = PeerCode.SHARED_FILE_LIST_RESPONSE
    override fun payload(): ByteArray = Frame.compress(SharesCodec.encodeSharedList(folders, lockedFolders), 4)
}

data class FileSearchResponseMessage(
    val username: String,
    val token: Int,
    val files: List<SharedFile>,
    val lockedFiles: List<SharedFile>,
    val freeSlots: Boolean,
    val uploadSpeed: Long,
    val queueLength: Long,
) : PeerMessage {
    override val code get() = PeerCode.FILE_SEARCH_RESPONSE

    override fun payload(): ByteArray {
        val w = MessageWriter(1024)
        w.string(username).int32(token)
        SharesCodec.writeResultFiles(w, files)
        w.bool(freeSlots).uint32(uploadSpeed and 0xFFFFFFFFL).uint32(queueLength and 0xFFFFFFFFL).uint32(0)
        if (lockedFiles.isNotEmpty()) SharesCodec.writeResultFiles(w, lockedFiles)
        return Frame.compress(w.toByteArray(), 4)
    }
}

data object UserInfoRequest : PlainPeerMessage {
    override val code get() = PeerCode.USER_INFO_REQUEST
    override fun writePayload(w: MessageWriter) = Unit
}

class UserInfoResponse(
    val description: String,
    val picture: ByteArray?,
    val totalSlots: Int,
    val queueSize: Int,
    val slotsFree: Boolean,
    /** 0 hiç kimse, 1 herkes, 2 arkadaşlar, 3 tanınan arkadaşlar; null: alan yok. */
    val uploadAllowed: Int?,
) : PlainPeerMessage {
    override val code get() = PeerCode.USER_INFO_RESPONSE
    override fun writePayload(w: MessageWriter) {
        w.string(description)
        if (picture != null) w.bool(true).bytes(picture) else w.bool(false)
        w.uint32(totalSlots.toLong().coerceAtLeast(0)).uint32(queueSize.toLong().coerceAtLeast(0)).bool(slotsFree)
        if (uploadAllowed != null) w.uint32(uploadAllowed.toLong())
    }

    override fun equals(other: Any?): Boolean =
        other is UserInfoResponse && other.description == description && other.picture.contentEquals(picture) &&
            other.totalSlots == totalSlots && other.queueSize == queueSize && other.slotsFree == slotsFree &&
            other.uploadAllowed == uploadAllowed

    override fun hashCode(): Int = description.hashCode() * 31 + (picture?.contentHashCode() ?: 0)
}

data class FolderContentsRequest(val token: Int, val folder: String, val legacy: Boolean = false) : PlainPeerMessage {
    override val code get() = PeerCode.FOLDER_CONTENTS_REQUEST
    override fun writePayload(w: MessageWriter) {
        w.int32(token)
        if (legacy) w.legacyString(folder) else w.string(folder)
    }
}

data class FolderContentsResponse(val token: Int, val folder: String, val folders: List<SharedFolder>) : PeerMessage {
    override val code get() = PeerCode.FOLDER_CONTENTS_RESPONSE
    override fun payload(): ByteArray {
        val w = MessageWriter(1024)
        w.int32(token).string(folder)
        SharesCodec.writeFolders(w, folders)
        return Frame.compress(w.toByteArray(), 4)
    }
}

/** direction 0: indirmek istiyorum (eski akış, boyut yok). 1: göndermeye hazırım (modern akış, boyut var). */
data class TransferRequest(val direction: Int, val token: Int, val path: String, val size: Long = 0) : PlainPeerMessage {
    override val code get() = PeerCode.TRANSFER_REQUEST
    override fun writePayload(w: MessageWriter) {
        w.uint32(direction.toLong()).int32(token).string(path)
        if (direction == DIRECTION_UPLOAD) w.uint64(size)
    }

    companion object {
        const val DIRECTION_DOWNLOAD = 0
        const val DIRECTION_UPLOAD = 1
    }
}

/** [allowed] ise [size] yalnızca eski akışta anında verilen yuva için bulunur. */
data class TransferResponse(
    val token: Int,
    val allowed: Boolean,
    val reason: String? = null,
    val size: Long? = null,
) : PlainPeerMessage {
    override val code get() = PeerCode.TRANSFER_RESPONSE
    override fun writePayload(w: MessageWriter) {
        w.int32(token).bool(allowed)
        if (allowed) {
            if (size != null) w.uint64(size)
        } else {
            w.string(reason ?: TransferReason.CANCELLED)
        }
    }
}

data class QueueUpload(val path: String, val legacy: Boolean = false) : PlainPeerMessage {
    override val code get() = PeerCode.QUEUE_UPLOAD
    override fun writePayload(w: MessageWriter) {
        if (legacy) w.legacyString(path) else w.string(path)
    }
}

data class PlaceInQueueResponse(val path: String, val place: Long) : PlainPeerMessage {
    override val code get() = PeerCode.PLACE_IN_QUEUE_RESPONSE
    override fun writePayload(w: MessageWriter) {
        w.string(path).uint32(place)
    }
}

data class PlaceInQueueRequest(val path: String, val legacy: Boolean = false) : PlainPeerMessage {
    override val code get() = PeerCode.PLACE_IN_QUEUE_REQUEST
    override fun writePayload(w: MessageWriter) {
        if (legacy) w.legacyString(path) else w.string(path)
    }
}

data class UploadFailed(val path: String) : PlainPeerMessage {
    override val code get() = PeerCode.UPLOAD_FAILED
    override fun writePayload(w: MessageWriter) {
        w.string(path)
    }
}

data class UploadDenied(val path: String, val reason: String) : PlainPeerMessage {
    override val code get() = PeerCode.UPLOAD_DENIED
    override fun writePayload(w: MessageWriter) {
        w.string(path).string(reason)
    }
}

/** FileSearchResponse başlığı (kullanıcı adı + token); yükü tamamen açmadan önce ilgilenip ilgilenmediğimizi anlamak için. */
data class SearchResponseHeader(val username: String, val token: Int)

object PeerMessages {

    /**
     * FileSearchResponse ve FolderContentsResponse için açılmış veri sınırı. Nicotine+ 128 MiB kullanır; mobil yığında
     * (ve istenmeden gelen, aynı anda birçok bağlantıdan akan arama yanıtlarında) bu OOM demektir. Gerçek bir yanıt
     * birkaç yüz KiB'tır; 32 MiB ~200 bin dosya girdisine yeter.
     */
    const val MAX_RESULT_INFLATED = 32 * 1024 * 1024

    /**
     * Paylaşım listesi için açılmış veri sınırı. Nicotine+ 2 GiB kabul eder; Android yığınında bu, kötü niyetli bir eşin
     * (zlib bombası) uygulamayı OOM ile düşürmesine izin verir. 256 MiB ~1-2 milyon dosyalık paylaşıma yeter.
     */
    const val MAX_SHARES_INFLATED = 256 * 1024 * 1024

    /** İzin verilen en büyük gelen "P" mesajı (kod dahil değil), koda göre (bkz. protokol §1). */
    fun maxIncomingSize(code: Int): Int = when (code) {
        PeerCode.SHARED_FILE_LIST_RESPONSE, PeerCode.USER_INFO_RESPONSE -> Frame.MAX_PEER_HUGE
        PeerCode.FILE_SEARCH_RESPONSE, PeerCode.FOLDER_CONTENTS_RESPONSE -> Frame.MAX_PEER_LARGE
        in KNOWN_SMALL_CODES -> Frame.MAX_PEER_DEFAULT
        else -> Frame.MAX_PEER_LARGE
    }

    /** Çözebildiğimiz kodlar; diğerlerinin gövdesi belleğe alınmadan atlanır. */
    fun isKnownCode(code: Int): Boolean = code in KNOWN_SMALL_CODES || code == PeerCode.SHARED_FILE_LIST_RESPONSE ||
        code == PeerCode.FILE_SEARCH_RESPONSE || code == PeerCode.USER_INFO_RESPONSE || code == PeerCode.FOLDER_CONTENTS_RESPONSE

    private val KNOWN_SMALL_CODES = setOf(
        PeerCode.SHARED_FILE_LIST_REQUEST, PeerCode.FILE_SEARCH_REQUEST, PeerCode.USER_INFO_REQUEST,
        PeerCode.FOLDER_CONTENTS_REQUEST, PeerCode.TRANSFER_REQUEST, PeerCode.TRANSFER_RESPONSE,
        PeerCode.PLACEHOLD_UPLOAD, PeerCode.QUEUE_UPLOAD, PeerCode.PLACE_IN_QUEUE_RESPONSE, PeerCode.UPLOAD_FAILED,
        PeerCode.UPLOAD_DENIED, PeerCode.PLACE_IN_QUEUE_REQUEST, PeerCode.UPLOAD_QUEUE_NOTIFICATION,
    )

    /**
     * Gelen bir eş mesajını çözer. [payload] kod alanından sonrasıdır. Bilinmeyen/yok sayılan kod için null;
     * bozuk gövde için [ProtocolException].
     */
    fun decode(code: Int, payload: ByteArray): PeerMessage? {
        return when (code) {
            PeerCode.SHARED_FILE_LIST_REQUEST -> SharedFileListRequest
            PeerCode.SHARED_FILE_LIST_RESPONSE -> {
                val body = Frame.decompress(payload, MAX_SHARES_INFLATED)
                val (folders, locked) = SharesCodec.decodeSharedList(body)
                SharedFileListResponse(folders, locked)
            }
            PeerCode.FILE_SEARCH_RESPONSE -> decodeSearchResponse(Frame.decompress(payload, MAX_RESULT_INFLATED))
            PeerCode.USER_INFO_REQUEST -> UserInfoRequest
            PeerCode.USER_INFO_RESPONSE -> decodeUserInfo(MessageReader(payload))
            PeerCode.FOLDER_CONTENTS_REQUEST -> {
                val r = MessageReader(payload)
                FolderContentsRequest(r.int32(), r.string())
            }
            PeerCode.FOLDER_CONTENTS_RESPONSE -> {
                val r = MessageReader(Frame.decompress(payload, MAX_RESULT_INFLATED))
                val token = r.int32()
                val folder = r.string()
                FolderContentsResponse(token, folder, SharesCodec.readFolders(r))
            }
            PeerCode.TRANSFER_REQUEST -> {
                val r = MessageReader(payload)
                val direction = r.uint32().toInt()
                val token = r.int32()
                val path = r.string()
                val size = if (direction == TransferRequest.DIRECTION_UPLOAD && r.remaining >= 8) r.uint64() else 0L
                TransferRequest(direction, token, path, size)
            }
            PeerCode.TRANSFER_RESPONSE -> {
                val r = MessageReader(payload)
                val token = r.int32()
                if (r.bool()) {
                    TransferResponse(token, true, null, if (r.remaining >= 8) r.uint64() else null)
                } else {
                    TransferResponse(token, false, if (r.hasRemaining()) r.string() else TransferReason.CANCELLED)
                }
            }
            PeerCode.QUEUE_UPLOAD -> QueueUpload(MessageReader(payload).string())
            PeerCode.PLACE_IN_QUEUE_RESPONSE -> {
                val r = MessageReader(payload)
                PlaceInQueueResponse(r.string(), r.uint32())
            }
            PeerCode.UPLOAD_FAILED -> UploadFailed(MessageReader(payload).string())
            PeerCode.UPLOAD_DENIED -> {
                val r = MessageReader(payload)
                UploadDenied(r.string(), r.string())
            }
            PeerCode.PLACE_IN_QUEUE_REQUEST -> PlaceInQueueRequest(MessageReader(payload).string())
            else -> null
        }
    }

    /** Sıkıştırılmamış FileSearchResponse gövdesini çözer. */
    fun decodeSearchResponse(body: ByteArray): FileSearchResponseMessage {
        val r = MessageReader(body)
        val username = r.string()
        val token = r.int32()
        val files = SharesCodec.readResultFiles(r)
        val freeSlots = r.bool()
        val speed = r.uint32()
        val queue = r.uint32()
        var locked: List<SharedFile> = emptyList()
        if (r.remaining >= 4) {
            r.uint32() // bilinmeyen
            if (r.remaining >= 4) locked = SharesCodec.readResultFiles(r)
        }
        return FileSearchResponseMessage(username, token, files, locked, freeSlots, speed, queue)
    }

    private fun decodeUserInfo(r: MessageReader): UserInfoResponse {
        val description = r.string()
        val picture = if (r.bool()) r.bytes() else null
        val slots = r.uint32().clampToInt()
        val queue = r.uint32().clampToInt()
        val free = r.bool()
        val allowed = if (r.remaining >= 4) r.uint32().clampToInt() else null
        return UserInfoResponse(description, picture, slots, queue, free, allowed)
    }

    /**
     * Sıkıştırılmış FileSearchResponse yükünün yalnızca başındaki kullanıcı adı ve token'ı okur (tamamını açmaz).
     * Okunamazsa null.
     */
    fun peekSearchResponse(payload: ByteArray): SearchResponseHeader? {
        val inflater = Inflater()
        try {
            inflater.setInput(payload)
            val buf = ByteArray(512)
            var n = 0
            while (n < buf.size && !inflater.finished()) {
                val got = try {
                    inflater.inflate(buf, n, buf.size - n)
                } catch (_: DataFormatException) {
                    return null
                }
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            val r = MessageReader(buf, 0, n)
            val username = r.string()
            return SearchResponseHeader(username, r.int32())
        } catch (_: ProtocolException) {
            return null
        } finally {
            inflater.end()
        }
    }
}

// =====================================================================================================================
// Bağlantı başlangıç mesajları (uint8 kod)
// =====================================================================================================================

sealed interface InitMessage {
    val code: Int
    fun writePayload(w: MessageWriter)
    fun encode(): ByteArray {
        val w = MessageWriter()
        writePayload(w)
        return Frame.code8(code, w.toByteArray())
    }
}

data class PierceFireWall(val token: Int) : InitMessage {
    override val code get() = InitCode.PIERCE_FIREWALL
    override fun writePayload(w: MessageWriter) {
        w.int32(token)
    }
}

data class PeerInit(val username: String, val type: String, val token: Int = 0) : InitMessage {
    override val code get() = InitCode.PEER_INIT
    override fun writePayload(w: MessageWriter) {
        w.string(username).string(type).int32(token)
    }
}

object InitMessages {
    /** [payload] kod baytından sonrasıdır. Bilinmeyen kod için null. */
    fun decode(code: Int, payload: ByteArray): InitMessage? {
        val r = MessageReader(payload)
        return when (code) {
            InitCode.PIERCE_FIREWALL -> PierceFireWall(r.int32())
            InitCode.PEER_INIT -> {
                val user = r.string()
                val type = r.string()
                val token = if (r.remaining >= 4) r.int32() else 0
                PeerInit(user, type, token)
            }
            else -> null
        }
    }
}
