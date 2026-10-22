package com.soulseekplus.core.transfers

import com.soulseekplus.core.api.ClientEvent
import com.soulseekplus.core.api.DownloadStore
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.ShareProvider
import com.soulseekplus.core.api.TransferPersistence
import com.soulseekplus.core.api.UploadResolution
import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.model.TransferStats
import com.soulseekplus.core.model.UploadQueueMode
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.model.UserStatus
import com.soulseekplus.core.net.Connection
import com.soulseekplus.core.net.PeerFailure
import com.soulseekplus.core.protocol.PeerMessage
import com.soulseekplus.core.protocol.PlaceInQueueRequest
import com.soulseekplus.core.protocol.PlaceInQueueResponse
import com.soulseekplus.core.protocol.QueueUpload
import com.soulseekplus.core.protocol.SendUploadSpeed
import com.soulseekplus.core.protocol.ServerMessage
import com.soulseekplus.core.protocol.TransferReason
import com.soulseekplus.core.protocol.TransferRequest
import com.soulseekplus.core.protocol.TransferResponse
import com.soulseekplus.core.protocol.UploadDenied
import com.soulseekplus.core.protocol.UploadFailed
import com.soulseekplus.core.protocol.tokenToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** [TransferManager]'ın ağ katmanıyla sözleşmesi (motor uygular). Hepsi mantık iş parçacığında çağrılır. */
interface TransferNetwork {
    val isLoggedIn: Boolean

    /** Bir eşe "P" mesajı gönderir; bağlantı kurulamazsa [onFailure] çağrılır. */
    fun sendPeer(username: String, message: PeerMessage, onFailure: ((PeerFailure) -> Unit)? = null)

    /** Eşe yeni bir "F" bağlantısı açar (doğrudan + dolaylı). @throws IOException */
    suspend fun openFileConnection(username: String): Connection

    fun sendServer(message: ServerMessage)

    /** Kullanıcıyı izlemeye alır (durum değişimleri [TransferManager.onUserStatus]'a gelir). */
    fun watchUser(username: String)

    fun unwatchUser(username: String)

    /** Bilinen son durum, bilinmiyorsa null. */
    fun userStatus(username: String): UserStatus?

    /** Sunucu ayrıcalığı olan kullanıcı mı. */
    fun isPrivileged(username: String): Boolean
}

/** Zaman ayarları (testlerde kısaltılabilir). Varsayılanlar Nicotine+ değerleridir. */
class TransferTiming(
    val startTimeoutMillis: Long = 45_000,
    val connectionRetryMillis: Long = 180_000,
    val localErrorRetryMillis: Long = 900_000,
    val queueLimitRetryMillis: Long = 300_000,
    val queueRecheckMillis: Long = 10_000,
    val positionRefreshMillis: Long = 300_000,
    val persistDebounceMillis: Long = 1_500,
    val tickMillis: Long = 250,
    val streamIdleMillis: Long = 60_000,
    val uploadRetryMax: Int = 3,
)

/** Transfer durum metinleri (Nicotine+ sabitleri; UI bunları eşler). */
object TransferStatus {
    const val CONNECTION_TIMEOUT = "Connection timeout"
    const val CONNECTION_CLOSED = "Connection closed"
    const val LOCAL_FILE_ERROR = "Local file error"
    const val DOWNLOAD_FOLDER_ERROR = "Download folder error"
    const val USER_LOGGED_OFF = "User logged off"
    const val CANCELLED = "Cancelled"
}

/**
 * İndirme ve yükleme akışlarının sahibi (bkz. docs/slsk-protocol.md §7, §8).
 *
 * İş parçacığı: tüm durum [scope]'un tek iş parçacıklı (mantık) dağıtıcısında yaşar; genel metotlar o iş parçacığından
 * çağrılmalıdır. Engelleyen G/Ç (dosya yazma/okuma, paylaşım çözümleme, kalıcılık) [io] dağıtıcısında yürür.
 */
class TransferManager(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val settings: StateFlow<ClientSettings>,
    private val userLists: StateFlow<UserLists>,
    private val shares: ShareProvider,
    private val store: DownloadStore,
    private val persistence: TransferPersistence,
    private val net: TransferNetwork,
    private val emit: (ClientEvent) -> Unit,
    private val logger: Logger,
    private val timing: TransferTiming = TransferTiming(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private class Rec(var t: Transfer) {
        /** Etkin transfer jetonu (indirmede yükleyicinin, yüklemede bizim jetonumuz). */
        var token: Int = 0
        var hasToken = false

        /** Her iptal/yeniden başlatmada artar; eski coroutine'lerin sonuçları yok sayılır. */
        var gen = 0
        var legacyRetried = false
        var sizeChanged = false
        var job: Job? = null
        var startTimer: Job? = null
        var conn: Connection? = null
        val done = AtomicLong()
        var lastDone = -1L
        var lastProgressMillis = 0L
        val meter = SpeedMeter()
        var retryAtMillis = 0L
        var lastPositionAskMillis = 0L
        var responseWaiter: CompletableDeferred<TransferResponse>? = null
        var uploadStartMillis = 0L
        var uploadRetries = 0
    }

    private class LocalIoException(cause: Throwable) : Exception(cause.message, cause)

    private val records = LinkedHashMap<String, Rec>()
    private val downloadTokens = HashMap<Int, Rec>()
    private val uploadTokens = HashMap<Int, Rec>()
    private val queue = UploadQueue()
    private var uploadToken: Int = Random.nextInt(0, Int.MAX_VALUE / 1000 + 1)

    private var ready = false
    private val pendingOps = ArrayList<() -> Unit>()
    private var metered = false
    private var running = false

    private var publishDirty = false
    private var queueDirty = false
    private val persistDirty = LinkedHashSet<String>()
    private val persistDeleted = LinkedHashSet<String>()
    private var persistJob: Job? = null
    private val watched = HashSet<String>()
    private val schedulerSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastQueueCheckMillis = 0L
    private var lastRetryScanMillis = 0L

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    private val _stats = MutableStateFlow(TransferStats())
    val stats: StateFlow<TransferStats> = _stats

    val downloadLimiter = RateLimiter()
    val uploadLimiter = RateLimiter()

    // ------------------------------------------------------------------------------------------------- yaşam döngüsü

    /** Kalıcı transferleri yükler ve arka plan döngülerini başlatır. Bir kez çağrılır. */
    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Kalıcı transferler yüklenemedi", e)
            }
            ready = true
            val ops = pendingOps.toList()
            pendingOps.clear()
            for (op in ops) runGuarded(op)
            if (net.isLoggedIn) onLoggedIn()
        }
        scope.launch {
            settings.map { it.uploadSpeedLimitKiB to it.downloadSpeedLimitKiB }.distinctUntilChanged().collect { (up, down) ->
                uploadLimiter.bytesPerSecond = up.toLong().coerceAtLeast(0) * 1024
                downloadLimiter.bytesPerSecond = down.toLong().coerceAtLeast(0) * 1024
                requestSchedule()
            }
        }
        scope.launch {
            for (signal in schedulerSignal) {
                try {
                    runScheduler()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, "Yükleme zamanlayıcısı hatası", e)
                }
            }
        }
        scope.launch { housekeeping() }
    }

    /** Her şeyi durdurur; bekleyen kalıcılık yazmaları [flushPersistence] ile ayrıca boşaltılır. */
    fun shutdown() {
        running = false
        for (rec in records.values.toList()) {
            rec.gen++
            rec.startTimer?.cancel()
            rec.job?.cancel()
            rec.conn?.close()
            rec.responseWaiter?.cancel()
        }
        schedulerSignal.close()
    }

    private fun runGuarded(op: () -> Unit) {
        try {
            op()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Transfer işlemi başarısız", e)
        }
    }

    private fun whenReady(block: () -> Unit) {
        if (ready) runGuarded(block) else pendingOps.add(block)
    }

    private suspend fun restore() {
        val loaded = withContext(io) { persistence.loadAll() }
        val drop = ArrayList<String>()
        val now = clockMillis()
        for (t in loaded) {
            if (t.direction == TransferDirection.UPLOAD) {
                // Nicotine+ gibi: önceki oturumun yalnızca bitmiş yüklemeleri korunur.
                if (t.state == TransferState.FINISHED) records[t.id] = Rec(t) else drop.add(t.id)
                continue
            }
            val state = when (t.state) {
                TransferState.CONNECTING, TransferState.TRANSFERRING, TransferState.QUEUED_REMOTE,
                TransferState.USER_OFFLINE, TransferState.QUEUED,
                -> TransferState.QUEUED
                else -> t.state
            }
            val restored = t.copy(state = state, speedBytesPerSecond = 0, queuePosition = null)
            val rec = Rec(restored)
            if (restored.state == TransferState.FAILED) rec.retryAtMillis = now + 30_000
            records[t.id] = rec
        }
        if (drop.isNotEmpty()) persistDeleted.addAll(drop)
        publishDirty = true
        schedulePersist()
    }

    // ------------------------------------------------------------------------------------------------- yardımcılar

    private fun idOf(direction: TransferDirection, user: String, path: String) = Transfer.idOf(direction, user, path)

    private val blocked: Boolean get() = settings.value.transfersOnWifiOnly && metered

    private fun canRun(): Boolean = net.isLoggedIn && !blocked

    private fun isBuddy(user: String) = userLists.value.isBuddy(user)

    private fun isPrivileged(user: String): Boolean =
        net.isPrivileged(user) || (settings.value.prioritizeBuddies && isBuddy(user))

    private fun fifo(): Boolean = settings.value.uploadQueueMode == UploadQueueMode.FIFO

    private fun update(rec: Rec, change: (Transfer) -> Transfer) {
        rec.t = change(rec.t)
        markDirty(rec)
    }

    private fun markDirty(rec: Rec) {
        publishDirty = true
        persistIfNeeded(rec)
    }

    private fun persistIfNeeded(rec: Rec) {
        val t = rec.t
        if (t.direction == TransferDirection.UPLOAD && t.state != TransferState.FINISHED) return
        persistDirty.add(t.id)
        persistDeleted.remove(t.id)
        schedulePersist()
    }

    private fun setState(rec: Rec, state: TransferState, reason: String? = null) {
        val now = clockMillis()
        update(rec) { t ->
            t.copy(
                state = state,
                failReason = reason,
                speedBytesPerSecond = if (state.isActive) t.speedBytesPerSecond else 0,
                queuePosition = if (state == TransferState.QUEUED_REMOTE || (state == TransferState.QUEUED && t.direction == TransferDirection.UPLOAD)) t.queuePosition else null,
                finishedAtMillis = if (state.isTerminal) now else t.finishedAtMillis,
            )
        }
        if (state != TransferState.TRANSFERRING) rec.meter.reset()
    }

    private fun removeRecord(rec: Rec) {
        records.remove(rec.t.id)
        persistDirty.remove(rec.t.id)
        persistDeleted.add(rec.t.id)
        publishDirty = true
        schedulePersist()
    }

    // ------------------------------------------------------------------------------------------------- istemci API

    fun enqueueDownload(username: String, file: SharedFile, destinationSubfolder: String?) = whenReady {
        val s = settings.value
        val sub = PathSanitizer.subfolder(destinationSubfolder)
            ?: if (s.downloadsIntoUserFolder) PathSanitizer.subfolder(username) else null
        val tid = idOf(TransferDirection.DOWNLOAD, username, file.path)
        val existing = records[tid]
        if (existing != null) {
            when (existing.t.state) {
                TransferState.QUEUED, TransferState.QUEUED_REMOTE, TransferState.CONNECTING, TransferState.TRANSFERRING -> return@whenReady
                else -> {
                    abortActive(existing)
                    existing.legacyRetried = false
                    existing.retryAtMillis = 0
                    update(existing) {
                        it.copy(
                            size = file.size, bytesTransferred = 0, state = TransferState.QUEUED, failReason = null,
                            queuePosition = null, speedBytesPerSecond = 0, attributes = file.attributes,
                            destinationSubfolder = sub, localUri = null, addedAtMillis = clockMillis(),
                            startedAtMillis = null, finishedAtMillis = null, retryCount = 0,
                        )
                    }
                    requestDownload(existing)
                }
            }
            return@whenReady
        }
        val t = Transfer(
            id = tid, direction = TransferDirection.DOWNLOAD, username = username, virtualPath = file.path,
            size = file.size, state = TransferState.QUEUED, attributes = file.attributes,
            destinationSubfolder = sub, addedAtMillis = clockMillis(),
        )
        val rec = Rec(t)
        records[tid] = rec
        markDirty(rec)
        if (!store.isReady()) {
            emit(ClientEvent.Notice("Download folder is not set or not writable.", isError = true))
        }
        requestDownload(rec)
    }

    fun pause(ids: Collection<String>) = whenReady {
        for (id in ids) {
            val rec = records[id] ?: continue
            val st = rec.t.state
            if (st.isTerminal || st == TransferState.PAUSED) continue
            if (rec.t.direction == TransferDirection.UPLOAD) removeUploadFromQueue(rec)
            abortActive(rec)
            setState(rec, TransferState.PAUSED)
        }
        requestSchedule()
    }

    fun resume(ids: Collection<String>) = whenReady {
        for (id in ids) {
            val rec = records[id] ?: continue
            val st = rec.t.state
            if (rec.t.direction == TransferDirection.DOWNLOAD) {
                if (st == TransferState.PAUSED || st == TransferState.FAILED || st == TransferState.USER_OFFLINE ||
                    st == TransferState.DENIED || st == TransferState.CANCELLED
                ) {
                    abortActive(rec)
                    rec.legacyRetried = false
                    rec.retryAtMillis = 0
                    setState(rec, TransferState.QUEUED)
                    requestDownload(rec)
                }
            } else if (st == TransferState.PAUSED || st == TransferState.FAILED || st == TransferState.USER_OFFLINE ||
                st == TransferState.CANCELLED
            ) {
                requeueUpload(rec)
            }
        }
    }

    fun cancel(ids: Collection<String>, deleteFiles: Boolean) = whenReady {
        for (id in ids) {
            val rec = records[id] ?: continue
            cancelRecord(rec, deleteFiles)
        }
        requestSchedule()
    }

    private fun cancelRecord(rec: Rec, deleteFiles: Boolean) {
        val t = rec.t
        if (t.state == TransferState.FINISHED) return
        if (t.direction == TransferDirection.UPLOAD) {
            val wasQueuedOrActive = queue.isQueued(t.username, t.virtualPath) || rec.hasToken || t.state.isActive
            removeUploadFromQueue(rec)
            abortActive(rec)
            if (wasQueuedOrActive && net.isLoggedIn) {
                net.sendPeer(t.username, UploadDenied(t.virtualPath, TransferReason.CANCELLED))
            }
        } else {
            abortActive(rec)
        }
        setState(rec, TransferState.CANCELLED, TransferStatus.CANCELLED)
        if (deleteFiles && t.direction == TransferDirection.DOWNLOAD) discardPartial(rec.t)
    }

    fun remove(ids: Collection<String>) = whenReady {
        for (id in ids) {
            val rec = records[id] ?: continue
            if (rec.t.state != TransferState.FINISHED) {
                val isDownload = rec.t.direction == TransferDirection.DOWNLOAD
                cancelRecord(rec, deleteFiles = isDownload)
            }
            removeRecord(rec)
        }
        requestSchedule()
    }

    fun clearFinished(direction: TransferDirection) = whenReady {
        for (rec in records.values.toList()) {
            if (rec.t.direction != direction) continue
            if (rec.t.state == TransferState.FINISHED || rec.t.state == TransferState.CANCELLED) removeRecord(rec)
        }
    }

    fun refreshQueuePosition(id: String) = whenReady {
        val rec = records[id] ?: return@whenReady
        val t = rec.t
        if (t.direction != TransferDirection.DOWNLOAD || t.state != TransferState.QUEUED_REMOTE || !net.isLoggedIn) return@whenReady
        rec.lastPositionAskMillis = clockMillis()
        net.sendPeer(t.username, PlaceInQueueRequest(t.virtualPath, rec.legacyRetried))
    }

    private fun discardPartial(t: Transfer) {
        scope.launch {
            try {
                withContext(io + NonCancellable) { store.discard(t) }
            } catch (e: Exception) {
                logger.warn(TAG, "Kısmi dosya silinemedi: ${t.virtualPath}", e)
            }
        }
    }

    /** Dışarıdan: listedeki yükleme kuyruğu boyutu (arama yanıtı ve kullanıcı bilgisi için). */
    fun queueSizeFor(username: String): Int = queue.queueSizeFor(isPrivileged(username), ::isPrivileged)

    /** Yeni bir yükleme şu an kabul edilir mi (boş yuva var mı). */
    fun hasFreeUploadSlot(): Boolean = canRun() && queue.activeUserCount < slotLimit()

    fun totalUploadSlots(): Int = slotLimit()

    private fun slotLimit(): Int = settings.value.uploadSlots.coerceAtLeast(1)

    fun activeUploadCount(): Int = queue.activeUserCount

    // ------------------------------------------------------------------------------------------------- oturum olayları

    /** Sunucuya giriş yapıldı: kuyruktaki indirmeler yeniden talep edilir. */
    fun onLoggedIn() = whenReady {
        watched.clear()
        for (rec in records.values) {
            if (rec.t.direction == TransferDirection.DOWNLOAD && rec.t.state == TransferState.QUEUED) rec.retryAtMillis = 0
        }
        retryScan(clockMillis())
        requestSchedule()
    }

    /** Sunucu bağlantısı koptu ya da kapatıldı: aktif transferler durdurulur, indirme kuyruğu korunur. */
    fun onDisconnected() = whenReady {
        for (rec in records.values.toList()) {
            val t = rec.t
            if (t.direction == TransferDirection.DOWNLOAD) {
                when (t.state) {
                    TransferState.CONNECTING, TransferState.TRANSFERRING, TransferState.QUEUED_REMOTE -> {
                        abortActive(rec)
                        setState(rec, TransferState.QUEUED)
                    }
                    else -> Unit
                }
            } else if (queue.isQueued(t.username, t.virtualPath) || queue.isActive(t.username, t.virtualPath) || t.state.isActive) {
                removeUploadFromQueue(rec)
                abortActive(rec)
                rec.retryAtMillis = 0
                setState(rec, TransferState.FAILED, TransferStatus.CONNECTION_CLOSED)
            }
        }
        downloadTokens.clear()
        uploadTokens.clear()
        queue.clear()
        queueDirty = true
        watched.clear()
    }

    /** Ağ değişti (ölçülü bağlantı bilgisi). Yalnızca Wi-Fi kuralı açıkken transferler durdurulur/sürdürülür. */
    fun onNetworkChanged(onMetered: Boolean) = whenReady {
        val wasBlocked = blocked
        metered = onMetered
        val nowBlocked = blocked
        if (nowBlocked && !wasBlocked) {
            for (rec in records.values.toList()) {
                val t = rec.t
                if (!t.state.isActive) continue
                if (t.direction == TransferDirection.DOWNLOAD) {
                    abortActive(rec)
                    setState(rec, TransferState.QUEUED)
                } else {
                    abortActive(rec)
                    queue.enqueue(t.username, t.virtualPath, t.size)
                    setState(rec, TransferState.QUEUED)
                    queueDirty = true
                }
            }
        } else if (!nowBlocked && wasBlocked) {
            retryScan(clockMillis())
            requestSchedule()
        }
    }

    /** İzlenen bir kullanıcının durumu değişti. */
    fun onUserStatus(username: String, status: UserStatus) = whenReady {
        if (status == UserStatus.OFFLINE) {
            for (rec in records.values.toList()) {
                val t = rec.t
                if (t.username != username) continue
                if (t.direction == TransferDirection.DOWNLOAD) {
                    when (t.state) {
                        TransferState.QUEUED, TransferState.QUEUED_REMOTE, TransferState.CONNECTING, TransferState.TRANSFERRING -> {
                            abortActive(rec)
                            setState(rec, TransferState.USER_OFFLINE, TransferStatus.USER_LOGGED_OFF)
                        }
                        else -> Unit
                    }
                } else if (queue.isQueued(username, t.virtualPath) || queue.isActive(username, t.virtualPath) || t.state.isActive) {
                    removeUploadFromQueue(rec)
                    abortActive(rec)
                    rec.retryAtMillis = 0
                    setState(rec, TransferState.USER_OFFLINE, TransferStatus.USER_LOGGED_OFF)
                }
            }
            requestSchedule()
        } else {
            for (rec in records.values.toList()) {
                val t = rec.t
                if (t.username != username || t.direction != TransferDirection.DOWNLOAD) continue
                if (t.state == TransferState.USER_OFFLINE && settings.value.autoRetryDownloads) {
                    setState(rec, TransferState.QUEUED)
                    rec.retryAtMillis = 0
                    if (canRun()) requestDownload(rec)
                } else if (t.state == TransferState.FAILED && settings.value.autoRetryDownloads && rec.retryAtMillis > 0) {
                    rec.retryAtMillis = clockMillis()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- indirme: istek

    private fun requestDownload(rec: Rec) {
        val t = rec.t
        if (t.direction != TransferDirection.DOWNLOAD || !canRun()) return
        val gen = rec.gen
        setState(rec, TransferState.QUEUED_REMOTE)
        rec.lastPositionAskMillis = clockMillis()
        net.sendPeer(t.username, QueueUpload(t.virtualPath, rec.legacyRetried)) { failure ->
            if (rec.gen == gen && rec.t.state == TransferState.QUEUED_REMOTE) onQueueRequestFailed(rec, failure)
        }
    }

    private fun onQueueRequestFailed(rec: Rec, failure: PeerFailure) {
        if (!net.isLoggedIn) {
            setState(rec, TransferState.QUEUED)
            return
        }
        when (failure) {
            PeerFailure.OFFLINE -> setState(rec, TransferState.USER_OFFLINE, TransferStatus.USER_LOGGED_OFF)
            PeerFailure.UNREACHABLE -> failDownload(rec, TransferStatus.CONNECTION_TIMEOUT, timing.connectionRetryMillis)
        }
    }

    private fun findDownload(user: String, path: String): Rec? {
        val exact = records[idOf(TransferDirection.DOWNLOAD, user, path)]
        if (exact != null) return exact
        for (rec in records.values) {
            val t = rec.t
            if (t.direction == TransferDirection.DOWNLOAD && t.username == user && t.virtualPath.equals(path, ignoreCase = true)) return rec
        }
        return null
    }

    private fun failDownload(rec: Rec, reason: String, retryAfterMillis: Long?) {
        abortActive(rec)
        setState(rec, TransferState.FAILED, reason)
        val autoRetry = settings.value.autoRetryDownloads && retryAfterMillis != null
        rec.retryAtMillis = if (autoRetry) clockMillis() + retryAfterMillis!! else 0
        if (!autoRetry) emit(ClientEvent.TransferFailed(rec.t))
    }

    private fun sanitizeRemoteReason(reason: String): String =
        if (reason in TransferReason.INTERNAL_STATUSES) TransferReason.CANCELLED else reason

    // ------------------------------------------------------------------------------------------------- indirme: eş mesajları

    fun onTransferRequest(user: String, msg: TransferRequest) = whenReady {
        if (msg.direction == TransferRequest.DIRECTION_UPLOAD) {
            onUploadOffer(user, msg)
        } else {
            // Eski akış: karşı taraf bizden indirmek istiyor. Kuyruğa al, "Queued" yanıtla.
            scope.launch {
                val reply = when (val r = admitUpload(user, msg.path)) {
                    is Admission.Rejected -> TransferResponse(msg.token, false, r.reason)
                    else -> TransferResponse(msg.token, false, TransferReason.QUEUED)
                }
                net.sendPeer(user, reply)
            }
        }
    }

    /** Karşı taraf bize dosya göndermeye hazır (modern akış). */
    private fun onUploadOffer(user: String, msg: TransferRequest) {
        fun reject(reason: String) = net.sendPeer(user, TransferResponse(msg.token, false, reason))
        val rec = findDownload(user, msg.path)
        if (rec == null) {
            reject(TransferReason.CANCELLED)
            return
        }
        when (rec.t.state) {
            TransferState.FINISHED -> return reject(TransferReason.COMPLETE)
            TransferState.CANCELLED, TransferState.PAUSED -> return reject(TransferReason.CANCELLED)
            else -> Unit
        }
        val s = settings.value
        val active = records.values.count {
            it !== rec && it.t.direction == TransferDirection.DOWNLOAD && it.t.state.isActive
        }
        if (!canRun() || (s.maxActiveDownloads > 0 && active >= s.maxActiveDownloads)) {
            if (rec.t.state != TransferState.QUEUED_REMOTE && rec.t.state != TransferState.CONNECTING && rec.t.state != TransferState.TRANSFERRING) {
                setState(rec, TransferState.QUEUED_REMOTE)
            }
            return reject(TransferReason.QUEUED)
        }
        if (!store.isReady()) {
            reject(TransferReason.CANCELLED)
            emit(ClientEvent.Notice("Download folder is not set or not writable.", isError = true))
            failDownload(rec, TransferStatus.DOWNLOAD_FOLDER_ERROR, timing.localErrorRetryMillis)
            return
        }
        // Önceki bir deneme (eski jeton/bağlantı) varsa bırak.
        abortActive(rec)
        rec.token = msg.token
        rec.hasToken = true
        downloadTokens[msg.token] = rec
        if (msg.size > 0) {
            if (rec.t.size > 0 && rec.t.size != msg.size) rec.sizeChanged = true
            update(rec) { it.copy(size = msg.size) }
        }
        val now = clockMillis()
        rec.done.set(rec.t.bytesTransferred)
        rec.lastDone = -1
        rec.lastProgressMillis = now
        setState(rec, TransferState.CONNECTING)
        update(rec) { it.copy(startedAtMillis = now) }
        net.sendPeer(user, TransferResponse(msg.token, true))
        val gen = rec.gen
        rec.startTimer = scope.launch {
            delay(timing.startTimeoutMillis)
            if (rec.gen == gen && rec.t.state == TransferState.CONNECTING && rec.conn == null) {
                failDownload(rec, TransferStatus.CONNECTION_TIMEOUT, timing.connectionRetryMillis)
            }
        }
    }

    fun onUploadDenied(user: String, msg: UploadDenied) = whenReady {
        val rec = findDownload(user, msg.path) ?: return@whenReady
        val st = rec.t.state
        if (st.isTerminal || st == TransferState.PAUSED) return@whenReady
        val reason = sanitizeRemoteReason(msg.reason)
        when {
            reason == TransferReason.FILE_NOT_SHARED && !rec.legacyRetried -> {
                rec.legacyRetried = true
                abortActive(rec)
                setState(rec, TransferState.QUEUED)
                requestDownload(rec)
            }
            reason == TransferReason.TOO_MANY_FILES || reason == TransferReason.TOO_MANY_MEGABYTES ||
                reason.startsWith(TransferReason.USER_LIMIT_PREFIX) -> {
                abortActive(rec)
                setState(rec, TransferState.QUEUED, reason)
                rec.retryAtMillis = clockMillis() + timing.queueLimitRetryMillis
            }
            else -> {
                abortActive(rec)
                setState(rec, TransferState.DENIED, reason)
                emit(ClientEvent.TransferFailed(rec.t))
            }
        }
    }

    fun onUploadFailed(user: String, msg: UploadFailed) = whenReady {
        val rec = findDownload(user, msg.path) ?: return@whenReady
        val st = rec.t.state
        if (st.isTerminal || st == TransferState.PAUSED) return@whenReady
        abortActive(rec)
        if (!rec.legacyRetried) {
            rec.legacyRetried = true
            setState(rec, TransferState.QUEUED)
            requestDownload(rec)
        } else {
            failDownload(rec, TransferStatus.CONNECTION_CLOSED, timing.connectionRetryMillis)
        }
    }

    fun onPlaceInQueueResponse(user: String, msg: PlaceInQueueResponse) = whenReady {
        val rec = findDownload(user, msg.path) ?: return@whenReady
        if (rec.t.state == TransferState.QUEUED_REMOTE && msg.place > 0) {
            update(rec) { it.copy(queuePosition = msg.place.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
    }

    // ------------------------------------------------------------------------------------------------- indirme: dosya akışı

    /** Bir "F" bağlantısı kuruldu (yükleyici bağlandı ya da dolaylı geri bağlandık): ilk 4 bayt jetondur. */
    fun onFileConnection(user: String, conn: Connection) {
        scope.launch {
            var handedOver = false
            try {
                val token = withTimeoutOrNull(timing.startTimeoutMillis) { conn.readInt32() }
                if (token == null) return@launch
                val rec = downloadTokens[token]
                if (rec == null || rec.t.username != user || rec.conn != null || rec.t.state != TransferState.CONNECTING) {
                    logger.debug(TAG, "$user: bilinmeyen/yinelenen dosya bağlantısı jetonu ${tokenToString(token)}")
                    return@launch
                }
                handedOver = true
                rec.startTimer?.cancel()
                rec.startTimer = null
                rec.conn = conn
                rec.job = coroutineContextJob()
                streamDownload(rec, conn, rec.gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.debug(TAG, "$user dosya bağlantısı koptu: ${e.message}")
            } catch (e: Exception) {
                logger.warn(TAG, "Dosya bağlantısı işlenemedi", e)
            } finally {
                if (!handedOver) conn.close()
            }
        }
    }

    private suspend fun coroutineContextJob(): Job? = kotlin.coroutines.coroutineContext[Job]

    private suspend fun streamDownload(rec: Rec, conn: Connection, gen: Int) {
        val snapshot = rec.t
        var out: java.io.OutputStream? = null
        var received = 0L
        var remaining = 0L
        var offset = 0L
        var completed = false
        var failure: String? = null
        var localError = false
        try {
            offset = withContext(io) {
                try {
                    if (rec.sizeChanged) {
                        store.discard(snapshot)
                        0L
                    } else {
                        store.partialSize(snapshot)
                    }
                } catch (e: IOException) {
                    throw LocalIoException(e)
                }
            }
            rec.sizeChanged = false
            val size = snapshot.size
            if (offset > size) {
                withContext(io) { store.discard(snapshot) }
                offset = 0
            }
            if (rec.gen != gen) return
            setState(rec, TransferState.TRANSFERRING)
            update(rec) { it.copy(bytesTransferred = offset, startedAtMillis = it.startedAtMillis ?: clockMillis()) }
            rec.done.set(offset)
            rec.lastProgressMillis = clockMillis()
            if (offset >= size) {
                // Her şey zaten diskte ya da dosya 0 bayt (Nicotine+: size > offset değilse ofset gönderilmez, bağlantı
                // kapatılır, tamamlanmış sayılır). 0 baytlık dosya için boş `.part` oluşturulur ki complete() taşısın;
                // aksi hâlde iki taraf da birbirini bekler ve 60 sn sonra "Connection timeout" ile sonsuz yeniden deneme olur.
                if (size == 0L) {
                    withContext(io) {
                        try {
                            store.openPartial(snapshot, 0).close()
                        } catch (e: IOException) {
                            throw LocalIoException(e)
                        }
                    }
                }
                conn.close()
                completed = true
            } else {
                val offsetBytes = ByteArray(8)
                var v = offset
                for (i in 0 until 8) {
                    offsetBytes[i] = v.toByte()
                    v = v ushr 8
                }
                conn.write(offsetBytes)
                out = withContext(io) {
                    try {
                        store.openPartial(snapshot, offset)
                    } catch (e: IOException) {
                        throw LocalIoException(e)
                    }
                }
                remaining = if (size > 0) size - offset else Long.MAX_VALUE
                val sink = out
                received = withContext(io) { copyToPartial(conn, sink, remaining, rec) }
                completed = if (size > 0) received >= remaining else received > 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalIoException) {
            localError = true
            failure = TransferStatus.LOCAL_FILE_ERROR
            logger.warn(TAG, "Yerel dosya hatası: ${e.message}")
        } catch (e: IOException) {
            failure = TransferStatus.CONNECTION_CLOSED
        } finally {
            val stream = out
            if (stream != null) {
                withContext(NonCancellable + io) {
                    try {
                        stream.flush()
                    } catch (_: IOException) {
                    }
                    try {
                        stream.close()
                    } catch (e: IOException) {
                        logger.warn(TAG, "Kısmi dosya kapatılamadı", e)
                    }
                }
            }
        }
        if (rec.gen != gen) return
        if (completed) {
            val uri = try {
                withContext(io) { store.complete(rec.t) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "İndirme tamamlanamadı: ${e.message}")
                if (rec.gen == gen) {
                    emit(ClientEvent.Notice("Could not save ${rec.t.fileName}: ${e.message}", isError = true))
                    failDownload(rec, TransferStatus.LOCAL_FILE_ERROR, timing.localErrorRetryMillis)
                }
                return
            }
            if (rec.gen != gen) return
            finishDownload(rec, uri, offset + received)
        } else {
            val reason = failure ?: TransferStatus.CONNECTION_CLOSED
            if (localError) {
                emit(ClientEvent.Notice("Could not write ${rec.t.fileName}: ${reason}", isError = true))
                failDownload(rec, reason, timing.localErrorRetryMillis)
            } else {
                failDownload(rec, reason, timing.connectionRetryMillis)
            }
        }
    }

    private suspend fun copyToPartial(conn: Connection, out: java.io.OutputStream, remaining: Long, rec: Rec): Long {
        val buf = ByteArray(STREAM_CHUNK)
        var received = 0L
        while (received < remaining) {
            val want = minOf(downloadLimiter.chunkSize(buf.size).toLong(), remaining - received).toInt()
            val n = conn.readSome(buf, 0, want)
            if (n < 0) break
            try {
                out.write(buf, 0, n)
            } catch (e: IOException) {
                throw LocalIoException(e)
            }
            received += n
            rec.done.addAndGet(n.toLong())
            downloadLimiter.acquire(n)
        }
        return received
    }

    private fun finishDownload(rec: Rec, uri: String, totalBytes: Long) {
        val now = clockMillis()
        releaseDownload(rec)
        update(rec) { t ->
            t.copy(
                state = TransferState.FINISHED, size = if (t.size > 0) t.size else totalBytes,
                bytesTransferred = if (t.size > 0) t.size else totalBytes, localUri = uri, failReason = null,
                speedBytesPerSecond = 0, queuePosition = null, finishedAtMillis = now,
            )
        }
        rec.meter.reset()
        emit(ClientEvent.TransferFinished(rec.t))
    }

    /** Jeton/bağlantı/iş referanslarını bırakır (iş kendi kendine biter, iptal edilmez). */
    private fun releaseDownload(rec: Rec) {
        rec.startTimer?.cancel()
        rec.startTimer = null
        if (rec.hasToken) {
            if (downloadTokens[rec.token] === rec) downloadTokens.remove(rec.token)
            rec.hasToken = false
        }
        rec.conn?.close()
        rec.conn = null
        rec.job = null
    }

    /** Etkin bir deneme varsa iptal eder; eski coroutine sonuçları [Rec.gen] sayesinde yok sayılır. */
    private fun abortActive(rec: Rec) {
        rec.gen++
        val job = rec.job
        rec.job = null
        rec.startTimer?.cancel()
        rec.startTimer = null
        rec.responseWaiter?.cancel()
        rec.responseWaiter = null
        job?.cancel()
        rec.conn?.close()
        rec.conn = null
        if (rec.hasToken) {
            if (rec.t.direction == TransferDirection.DOWNLOAD) {
                if (downloadTokens[rec.token] === rec) downloadTokens.remove(rec.token)
            } else if (uploadTokens[rec.token] === rec) {
                uploadTokens.remove(rec.token)
            }
            rec.hasToken = false
        }
        if (rec.t.direction == TransferDirection.UPLOAD && queue.isActive(rec.t.username, rec.t.virtualPath)) {
            queue.deactivate(rec.t.username, rec.t.virtualPath)
            queueDirty = true
            requestSchedule()
        }
    }

    // ------------------------------------------------------------------------------------------------- yükleme: kabul

    private sealed interface Admission {
        data object Accepted : Admission
        data object AlreadyQueued : Admission
        class Rejected(val reason: String) : Admission
    }

    fun onQueueUpload(user: String, msg: QueueUpload) = whenReady {
        scope.launch {
            try {
                val r = admitUpload(user, msg.path)
                if (r is Admission.Rejected) net.sendPeer(user, UploadDenied(msg.path, r.reason))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "QueueUpload işlenemedi ($user)", e)
            }
        }
    }

    fun onPlaceInQueueRequest(user: String, msg: PlaceInQueueRequest) = whenReady {
        val pos = queue.position(user, msg.path, fifo(), ::isPrivileged)
        if (pos != null && pos > 0) net.sendPeer(user, PlaceInQueueResponse(msg.path, pos.toLong()))
    }

    fun onTransferResponse(user: String, msg: TransferResponse) = whenReady {
        val rec = uploadTokens[msg.token]
        if (rec == null || rec.t.username != user) {
            logger.debug(TAG, "$user: bilinmeyen TransferResponse jetonu ${tokenToString(msg.token)}")
            return@whenReady
        }
        rec.responseWaiter?.complete(msg)
    }

    private suspend fun admitUpload(user: String, path: String): Admission {
        val lists = userLists.value
        if (lists.isBanned(user)) return Admission.Rejected(TransferReason.BANNED)
        if (settings.value.uploadToBuddiesOnly && !lists.isBuddy(user)) return Admission.Rejected("${TransferReason.BANNED} (buddies only)")
        if (queue.isQueued(user, path) || queue.isActive(user, path)) return Admission.AlreadyQueued
        val resolution = withContext(io) { shares.resolveUpload(user, path) }
        if (queue.isQueued(user, path) || queue.isActive(user, path)) return Admission.AlreadyQueued
        return when (resolution) {
            UploadResolution.NotShared -> Admission.Rejected(TransferReason.FILE_NOT_SHARED)
            is UploadResolution.Denied -> Admission.Rejected(resolution.reason)
            is UploadResolution.Allowed -> {
                val size = withContext(io) {
                    try {
                        resolution.source.size
                    } catch (e: Exception) {
                        -1L
                    }
                }
                if (size < 0) return Admission.Rejected(TransferReason.FILE_READ_ERROR)
                if (!isBuddy(user)) {
                    val s = settings.value
                    if (s.maxQueuedFilesPerUser > 0 && queue.queuedCount(user) >= s.maxQueuedFilesPerUser) {
                        return Admission.Rejected(TransferReason.TOO_MANY_FILES)
                    }
                    if (s.maxQueuedMiBPerUser > 0 && queue.queuedBytes(user) >= s.maxQueuedMiBPerUser.toLong() * 1024 * 1024) {
                        return Admission.Rejected(TransferReason.TOO_MANY_MEGABYTES)
                    }
                }
                if (queue.isQueued(user, path) || queue.isActive(user, path)) return Admission.AlreadyQueued
                enqueueUploadRecord(user, path, resolution.file, size)
                Admission.Accepted
            }
        }
    }

    private fun enqueueUploadRecord(user: String, path: String, file: SharedFile, size: Long) {
        val tid = idOf(TransferDirection.UPLOAD, user, path)
        queue.enqueue(user, path, size)
        queueDirty = true
        val now = clockMillis()
        val existing = records[tid]
        if (existing == null) {
            val rec = Rec(
                Transfer(
                    id = tid, direction = TransferDirection.UPLOAD, username = user, virtualPath = path, size = size,
                    state = TransferState.QUEUED, attributes = file.attributes, addedAtMillis = now,
                ),
            )
            records[tid] = rec
            markDirty(rec)
        } else {
            abortActive(existing)
            existing.retryAtMillis = 0
            update(existing) {
                it.copy(
                    size = size, bytesTransferred = 0, state = TransferState.QUEUED, failReason = null,
                    speedBytesPerSecond = 0, queuePosition = null, attributes = file.attributes, addedAtMillis = now,
                    startedAtMillis = null, finishedAtMillis = null,
                )
            }
        }
        requestSchedule()
    }

    private fun removeUploadFromQueue(rec: Rec) {
        if (queue.remove(rec.t.username, rec.t.virtualPath)) queueDirty = true
    }

    private fun requeueUpload(rec: Rec) {
        val t = rec.t
        rec.retryAtMillis = 0
        scope.launch {
            try {
                val r = admitUpload(t.username, t.virtualPath)
                if (r is Admission.Rejected) {
                    update(rec) { it.copy(state = TransferState.FAILED, failReason = r.reason) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Yükleme yeniden kuyruğa alınamadı", e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- yükleme: zamanlayıcı

    private fun requestSchedule() {
        schedulerSignal.trySend(Unit)
    }

    private suspend fun runScheduler() {
        var guard = 0
        while (running && canRun() && queue.activeUserCount < slotLimit() && guard++ < 1000) {
            val cand = queue.selectCandidate(fifo(), ::isPrivileged) ?: return
            val user = cand.username
            val rec = records[idOf(TransferDirection.UPLOAD, user, cand.path)]
            if (rec == null) {
                queue.remove(user, cand.path)
                queueDirty = true
                continue
            }
            if (userLists.value.isBanned(user)) {
                denyQueuedUpload(rec, TransferReason.BANNED)
                continue
            }
            if (net.userStatus(user) == UserStatus.OFFLINE) {
                queue.remove(user, cand.path)
                queueDirty = true
                setState(rec, TransferState.USER_OFFLINE, TransferStatus.USER_LOGGED_OFF)
                continue
            }
            val resolution = withContext(io) { shares.resolveUpload(user, cand.path) }
            if (!queue.isQueued(user, cand.path)) continue
            when (resolution) {
                UploadResolution.NotShared -> denyQueuedUpload(rec, TransferReason.FILE_NOT_SHARED)
                is UploadResolution.Denied -> denyQueuedUpload(rec, resolution.reason)
                is UploadResolution.Allowed -> {
                    val size = withContext(io) {
                        try {
                            resolution.source.size
                        } catch (e: Exception) {
                            -1L
                        }
                    }
                    if (!queue.isQueued(user, cand.path)) continue
                    if (size < 0) {
                        queue.remove(user, cand.path)
                        queueDirty = true
                        net.sendPeer(user, UploadDenied(cand.path, TransferReason.FILE_READ_ERROR))
                        setState(rec, TransferState.FAILED, TransferStatus.LOCAL_FILE_ERROR)
                        continue
                    }
                    if (!running || !canRun() || queue.activeUserCount >= slotLimit()) return
                    startUpload(rec, cand, resolution.source, size)
                }
            }
        }
    }

    private fun denyQueuedUpload(rec: Rec, reason: String) {
        val t = rec.t
        removeUploadFromQueue(rec)
        net.sendPeer(t.username, UploadDenied(t.virtualPath, reason))
        removeRecord(rec)
    }

    private fun nextUploadToken(): Int {
        var t = uploadToken
        do {
            t = if (t == -1) 1 else t + 1
        } while (uploadTokens.containsKey(t) || t == 0)
        uploadToken = t
        return t
    }

    private fun startUpload(rec: Rec, entry: UploadQueue.Entry, source: UploadSource, size: Long) {
        if (!queue.activate(entry)) return
        queueDirty = true
        val token = nextUploadToken()
        val now = clockMillis()
        rec.token = token
        rec.hasToken = true
        uploadTokens[token] = rec
        rec.done.set(0)
        rec.lastDone = -1
        rec.lastProgressMillis = now
        rec.uploadStartMillis = now
        setState(rec, TransferState.CONNECTING)
        update(rec) { it.copy(size = size, bytesTransferred = 0, startedAtMillis = now, queuePosition = null) }
        val gen = rec.gen
        rec.job = scope.launch { runUpload(rec, gen, token, source, size) }
    }

    private class UploadPeerFailed(val failure: PeerFailure) : Exception("peer: $failure")

    private class UploadTimeout : Exception("timeout")

    private suspend fun runUpload(rec: Rec, gen: Int, token: Int, source: UploadSource, size: Long) {
        val user = rec.t.username
        val path = rec.t.virtualPath
        val deadline = clockMillis() + timing.startTimeoutMillis
        fun remaining(): Long = (deadline - clockMillis()).coerceAtLeast(1)
        var conn: Connection? = null
        var offset = 0L
        val waiter = CompletableDeferred<TransferResponse>()
        rec.responseWaiter = waiter
        try {
            net.sendPeer(user, TransferRequest(TransferRequest.DIRECTION_UPLOAD, token, path, size)) { failure ->
                waiter.completeExceptionally(UploadPeerFailed(failure))
            }
            val response = withTimeoutOrNull(remaining()) { waiter.await() } ?: throw UploadTimeout()
            if (rec.gen != gen) return
            if (!response.allowed) {
                handleUploadRejection(rec, gen, response.reason ?: TransferReason.CANCELLED)
                return
            }
            withTimeoutOrNull(remaining()) { conn = net.openFileConnection(user) } ?: throw UploadTimeout()
            val c = conn ?: throw UploadTimeout()
            if (rec.gen != gen) return
            rec.conn = c
            c.write(
                byteArrayOf(token.toByte(), (token ushr 8).toByte(), (token ushr 16).toByte(), (token ushr 24).toByte()),
            )
            if (size == 0L) {
                // 0 baytlık dosya: indiren ofset göndermeden kapatır (Nicotine+ da ofset beklemeden bitirir).
                finishUpload(rec, size, 0)
                return
            }
            val rawOffset = withTimeoutOrNull(remaining()) { c.readUInt64() } ?: throw UploadTimeout()
            // Soulseek NS hatası: 2 GiB üstü ofsetler 0xFFFFFFFFFFFFFFFF gelebilir; baştan başla.
            offset = if (rawOffset < 0) 0 else rawOffset
            if (offset > size) throw IOException("Invalid offset $offset for size $size")
            if (rec.gen != gen) return
            val startedStream = clockMillis()
            rec.uploadStartMillis = startedStream
            rec.done.set(offset)
            rec.lastProgressMillis = startedStream
            setState(rec, TransferState.TRANSFERRING)
            update(rec) { it.copy(bytesTransferred = offset) }
            val sent = withContext(io) { streamUpload(rec, c, source, offset, size) }
            if (rec.gen != gen) return
            finishUpload(rec, size, sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadTimeout) {
            endUpload(rec, gen, TransferState.FAILED, TransferStatus.CONNECTION_TIMEOUT, timing.connectionRetryMillis)
        } catch (e: UploadPeerFailed) {
            when (e.failure) {
                PeerFailure.OFFLINE -> endUpload(rec, gen, TransferState.USER_OFFLINE, TransferStatus.USER_LOGGED_OFF, null)
                PeerFailure.UNREACHABLE -> endUpload(rec, gen, TransferState.FAILED, TransferStatus.CONNECTION_TIMEOUT, timing.connectionRetryMillis)
            }
        } catch (e: LocalIoException) {
            logger.warn(TAG, "Yükleme için dosya okunamadı: ${e.message}")
            if (rec.gen == gen) net.sendPeer(user, UploadFailed(path))
            endUpload(rec, gen, TransferState.FAILED, TransferStatus.LOCAL_FILE_ERROR, null)
        } catch (e: IOException) {
            logger.debug(TAG, "$user yükleme bağlantısı koptu: ${e.message}")
            if (rec.gen == gen && net.userStatus(user) != UserStatus.OFFLINE) net.sendPeer(user, UploadFailed(path))
            endUpload(rec, gen, TransferState.FAILED, TransferStatus.CONNECTION_CLOSED, null)
        } finally {
            if (rec.gen != gen) conn?.close()
        }
    }

    /** Dosyayı [offset]'ten başlayarak gönderir; gönderilen bayt sayısını döndürür. IO dağıtıcısında çalışır. */
    private suspend fun streamUpload(rec: Rec, conn: Connection, source: UploadSource, offset: Long, size: Long): Long {
        val input = try {
            source.open(offset)
        } catch (e: IOException) {
            throw LocalIoException(e)
        }
        input.use { stream ->
            val buf = ByteArray(STREAM_CHUNK)
            var total = 0L
            while (offset + total < size) {
                val want = minOf(uploadLimiter.chunkSize(buf.size).toLong(), size - offset - total).toInt()
                val n = try {
                    stream.read(buf, 0, want)
                } catch (e: IOException) {
                    throw LocalIoException(e)
                }
                if (n < 0) throw LocalIoException(EOFException("File is shorter than announced"))
                if (n == 0) continue
                uploadLimiter.acquire(n)
                conn.write(buf, 0, n)
                total += n
                rec.done.addAndGet(n.toLong())
            }
            return total
        }
    }

    private fun handleUploadRejection(rec: Rec, gen: Int, rawReason: String) {
        when (rawReason) {
            TransferReason.QUEUED -> endUpload(rec, gen, TransferState.FAILED, TransferReason.QUEUED, timing.connectionRetryMillis)
            TransferReason.COMPLETE -> {
                if (rec.gen != gen) return
                releaseUpload(rec)
                update(rec) { it.copy(state = TransferState.FINISHED, bytesTransferred = it.size, failReason = null, finishedAtMillis = clockMillis(), speedBytesPerSecond = 0) }
                emit(ClientEvent.TransferFinished(rec.t))
            }
            else -> {
                val reason = sanitizeRemoteReason(rawReason)
                if (reason == TransferReason.CANCELLED) {
                    endUpload(rec, gen, TransferState.CANCELLED, TransferStatus.CANCELLED, null)
                } else {
                    endUpload(rec, gen, TransferState.FAILED, reason, null)
                }
            }
        }
    }

    private fun finishUpload(rec: Rec, size: Long, sent: Long) {
        val now = clockMillis()
        val elapsedSeconds = maxOf(1L, (now - rec.uploadStartMillis) / 1000)
        releaseUpload(rec)
        update(rec) {
            it.copy(
                state = TransferState.FINISHED, bytesTransferred = size, failReason = null, speedBytesPerSecond = 0,
                queuePosition = null, finishedAtMillis = now,
            )
        }
        rec.meter.reset()
        if (sent > 0) {
            val avg = (sent / elapsedSeconds).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            net.sendServer(SendUploadSpeed(avg))
        }
        emit(ClientEvent.TransferFinished(rec.t))
    }

    private fun endUpload(rec: Rec, gen: Int, state: TransferState, reason: String?, retryAfterMillis: Long?) {
        if (rec.gen != gen) return
        releaseUpload(rec)
        setState(rec, state, reason)
        val retry = retryAfterMillis != null && rec.uploadRetries < timing.uploadRetryMax && state == TransferState.FAILED
        rec.retryAtMillis = if (retry) clockMillis() + retryAfterMillis!! else 0
        if (state == TransferState.FAILED && !retry) emit(ClientEvent.TransferFailed(rec.t))
    }

    private fun releaseUpload(rec: Rec) {
        if (rec.hasToken) {
            if (uploadTokens[rec.token] === rec) uploadTokens.remove(rec.token)
            rec.hasToken = false
        }
        if (queue.isActive(rec.t.username, rec.t.virtualPath)) {
            queue.deactivate(rec.t.username, rec.t.virtualPath)
            queueDirty = true
        }
        rec.conn?.close()
        rec.conn = null
        rec.job = null
        rec.responseWaiter = null
        requestSchedule()
    }

    // ------------------------------------------------------------------------------------------------- bakım döngüsü

    private suspend fun housekeeping() {
        while (running && scope.isActive) {
            delay(timing.tickMillis)
            try {
                val now = clockMillis()
                tickProgress(now)
                if (now - lastQueueCheckMillis >= timing.queueRecheckMillis) {
                    lastQueueCheckMillis = now
                    requestSchedule()
                }
                if (now - lastRetryScanMillis >= RETRY_SCAN_MILLIS) {
                    lastRetryScanMillis = now
                    retryScan(now)
                    refreshPositions(now)
                }
                refreshWatches()
                if (publishDirty || queueDirty) publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Transfer bakım döngüsü hatası", e)
            }
        }
    }

    private fun tickProgress(now: Long) {
        for (rec in records.values) {
            val st = rec.t.state
            if (st != TransferState.TRANSFERRING) continue
            val done = rec.done.get()
            if (done != rec.lastDone) {
                rec.lastDone = done
                rec.lastProgressMillis = now
                rec.meter.sample(done, now)
                val speed = rec.meter.bytesPerSecond()
                rec.t = rec.t.copy(bytesTransferred = done, speedBytesPerSecond = speed)
                publishDirty = true
            } else if (now - rec.lastProgressMillis > timing.streamIdleMillis) {
                if (rec.t.direction == TransferDirection.DOWNLOAD) {
                    failDownload(rec, TransferStatus.CONNECTION_TIMEOUT, timing.connectionRetryMillis)
                } else {
                    val t = rec.t
                    abortActive(rec)
                    net.sendPeer(t.username, UploadFailed(t.virtualPath))
                    setState(rec, TransferState.FAILED, TransferStatus.CONNECTION_TIMEOUT)
                    emit(ClientEvent.TransferFailed(rec.t))
                }
            }
        }
    }

    private fun retryScan(now: Long) {
        if (!canRun()) return
        var budget = RETRY_BUDGET
        val autoRetry = settings.value.autoRetryDownloads
        for (rec in records.values.toList()) {
            val t = rec.t
            if (t.direction == TransferDirection.DOWNLOAD) {
                when (t.state) {
                    TransferState.QUEUED -> if (now >= rec.retryAtMillis && budget > 0) {
                        budget--
                        rec.retryAtMillis = 0
                        requestDownload(rec)
                    }
                    TransferState.FAILED -> if (autoRetry && rec.retryAtMillis > 0 && now >= rec.retryAtMillis && budget > 0) {
                        budget--
                        rec.retryAtMillis = 0
                        update(rec) { it.copy(retryCount = it.retryCount + 1) }
                        setState(rec, TransferState.QUEUED)
                        requestDownload(rec)
                    }
                    TransferState.USER_OFFLINE -> {
                        val status = net.userStatus(t.username)
                        if (autoRetry && (status == UserStatus.ONLINE || status == UserStatus.AWAY) && budget > 0) {
                            budget--
                            setState(rec, TransferState.QUEUED)
                            requestDownload(rec)
                        }
                    }
                    else -> Unit
                }
            } else if (t.state == TransferState.FAILED && rec.retryAtMillis > 0 && now >= rec.retryAtMillis) {
                rec.uploadRetries++
                if (net.userStatus(t.username) == UserStatus.OFFLINE) {
                    rec.retryAtMillis = 0
                } else {
                    requeueUpload(rec)
                }
            }
        }
    }

    private fun refreshPositions(now: Long) {
        if (!net.isLoggedIn) return
        for (rec in records.values) {
            val t = rec.t
            if (t.direction == TransferDirection.DOWNLOAD && t.state == TransferState.QUEUED_REMOTE &&
                now - rec.lastPositionAskMillis >= timing.positionRefreshMillis
            ) {
                rec.lastPositionAskMillis = now
                net.sendPeer(t.username, PlaceInQueueRequest(t.virtualPath, rec.legacyRetried))
            }
        }
    }

    private fun refreshWatches() {
        if (!net.isLoggedIn) return
        val wanted = HashSet<String>()
        for (rec in records.values) {
            val t = rec.t
            val relevant = when (t.state) {
                TransferState.QUEUED, TransferState.QUEUED_REMOTE, TransferState.CONNECTING, TransferState.TRANSFERRING,
                TransferState.USER_OFFLINE, TransferState.FAILED,
                -> true
                else -> false
            }
            if (relevant) wanted.add(t.username)
        }
        for (u in wanted) if (watched.add(u)) net.watchUser(u)
        val stale = watched.filter { it !in wanted }
        for (u in stale) {
            watched.remove(u)
            net.unwatchUser(u)
        }
    }

    // ------------------------------------------------------------------------------------------------- yayımlama

    private fun publish() {
        if (queueDirty) {
            queueDirty = false
            val positions = queue.positions(fifo(), ::isPrivileged)
            for (rec in records.values) {
                val t = rec.t
                if (t.direction != TransferDirection.UPLOAD) continue
                val pos = if (t.state == TransferState.QUEUED) positions[UploadQueue.key(t.username, t.virtualPath)] else null
                if (pos != t.queuePosition) rec.t = t.copy(queuePosition = pos)
            }
        }
        publishDirty = false
        val list = ArrayList<Transfer>(records.size)
        var downSpeed = 0L
        var upSpeed = 0L
        var activeDown = 0
        var activeUp = 0
        for (rec in records.values) {
            val t = rec.t
            list.add(t)
            if (t.state.isActive) {
                if (t.direction == TransferDirection.DOWNLOAD) {
                    activeDown++
                    downSpeed += t.speedBytesPerSecond
                } else {
                    activeUp++
                    upSpeed += t.speedBytesPerSecond
                }
            }
        }
        _transfers.value = list
        _stats.value = TransferStats(
            downloadSpeed = downSpeed,
            uploadSpeed = upSpeed,
            activeDownloads = activeDown,
            activeUploads = activeUp,
            queuedUploads = queue.queuedCount,
            freeUploadSlots = (slotLimit() - queue.activeUserCount).coerceAtLeast(0),
        )
    }

    // ------------------------------------------------------------------------------------------------- kalıcılık

    private fun schedulePersist() {
        if (persistJob != null || !running) return
        persistJob = scope.launch {
            delay(timing.persistDebounceMillis)
            persistJob = null
            flushPersistence()
        }
    }

    /** Bekleyen değişiklikleri hemen kalıcı depoya yazar. */
    suspend fun flushPersistence() {
        val upserts = persistDirty.mapNotNull { records[it]?.t }
        val deletes = persistDeleted.toList()
        persistDirty.clear()
        persistDeleted.clear()
        if (upserts.isEmpty() && deletes.isEmpty()) return
        try {
            withContext(io + NonCancellable) {
                if (deletes.isNotEmpty()) persistence.delete(deletes)
                if (upserts.isNotEmpty()) persistence.upsert(upserts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Transferler kaydedilemedi", e)
            for (t in upserts) persistDirty.add(t.id)
            persistDeleted.addAll(deletes)
            if (running) {
                persistJob = scope.launch {
                    delay(timing.persistDebounceMillis * 5)
                    persistJob = null
                    flushPersistence()
                }
            }
        }
    }

    /** Yayımlanan anlık görüntüyü hemen yeniler (testler ve kapatma için). */
    fun publishNow() {
        publish()
    }

    companion object {
        private const val TAG = "Transfers"
        private const val STREAM_CHUNK = 64 * 1024
        private const val RETRY_SCAN_MILLIS = 2_000L
        private const val RETRY_BUDGET = 200
    }
}
