package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [AsyncSocket] üzerinde arabellekli okuma, çerçeveli okuma, sıralı yazma kuyruğu ve etkinlik izleme.
 *
 * Okuma: tek bir coroutine okur (suspend). Yazma: [send] kuyruğa ekler (yazıcı coroutine sırayla boşaltır);
 * [write] doğrudan yazar (dosya akışı). Aynı bağlantıda ikisi karıştırılırsa sıra bir [Mutex] ile korunur.
 */
class Connection(
    val socket: AsyncSocket,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.NONE,
    /** Dışarıdan konulan etiket (günlük için). */
    var label: String = "",
) {
    val id: Int = ids.incrementAndGet()

    /** Karşı tarafın IP'si (bağlandığımız ya da bize bağlanan). */
    val remoteIp: String = socket.remoteIp
    val remotePort: Int = socket.remotePort

    @Volatile
    var lastActivityNanos: Long = System.nanoTime()
        private set

    /** Başlangıç mesajından sonra herhangi bir mesaj gönderildi ya da alındı mı ("hayalet" bağlantı tespiti). */
    @Volatile
    var hasPostInitActivity: Boolean = false

    /** Boşta kalma sayacının dışında tutulan uzun ömürlü bağlantı (sunucu, dağıtık ebeveyn/çocuk gibi). */
    @Volatile
    var keepOpen: Boolean = false

    val bytesRead = AtomicLong()
    val bytesWritten = AtomicLong()

    private val readBuffer: ByteBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE).also { it.limit(0) }
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val queuedBytes = AtomicLong()
    private val writerStarted = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val closedFlag = AtomicBoolean(false)
    private val closeListeners = ConcurrentLinkedQueue<(Connection) -> Unit>()

    val isClosed: Boolean get() = closedFlag.get()

    /** [closeWhenFlushed] çağrıldı (kuyruk boşalınca kapanacak). */
    @Volatile
    var closeRequested: Boolean = false
        private set

    /** Bağlantı kapanınca (bir kez) çağrılır; herhangi bir iş parçacığında olabilir. Zaten kapalıysa hemen çağrılır. */
    fun onClose(listener: (Connection) -> Unit) {
        closeListeners.add(listener)
        if (isClosed && closeListeners.remove(listener)) listener(this)
    }

    // ------------------------------------------------------------------------------------------------- okuma

    private fun touch() {
        lastActivityNanos = System.nanoTime()
    }

    private suspend fun fill(): Boolean {
        readBuffer.clear()
        val n = socket.read(readBuffer)
        readBuffer.flip()
        if (n > 0) {
            bytesRead.addAndGet(n.toLong())
            touch()
        }
        return n > 0
    }

    /** En az 1 bayt okur; -1: akış sonu. */
    suspend fun readSome(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int {
        if (length == 0) return 0
        if (!readBuffer.hasRemaining()) {
            if (length >= READ_BUFFER_SIZE) {
                // Büyük okuma: ara kopya olmadan doğrudan hedefe.
                val wrapped = ByteBuffer.wrap(dst, offset, length)
                val n = socket.read(wrapped)
                if (n > 0) {
                    bytesRead.addAndGet(n.toLong())
                    touch()
                }
                return n
            }
            if (!fill()) return -1
        }
        val n = minOf(length, readBuffer.remaining())
        readBuffer.get(dst, offset, n)
        return n
    }

    /** Tam [length] bayt okur; akış erken biterse [EOFException]. */
    suspend fun readFully(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset) {
        var done = 0
        while (done < length) {
            val n = readSome(dst, offset + done, length - done)
            if (n < 0) throw EOFException("Bağlantı kapandı")
            done += n
        }
    }

    suspend fun readUInt8(): Int {
        val b = ByteArray(1)
        readFully(b)
        return b[0].toInt() and 0xFF
    }

    suspend fun readInt32(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    suspend fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    suspend fun readUInt64(): Long {
        val b = ByteArray(8)
        readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /**
     * [length] baytlık yükü okur. Büyük yükler için arabellek veri geldikçe büyür; saldırgan sahte uzunlukla belleği
     * ayıramaz.
     */
    suspend fun readBytes(length: Int): ByteArray {
        require(length >= 0)
        if (length <= INITIAL_CHUNK) {
            val b = ByteArray(length)
            readFully(b)
            return b
        }
        var buf = ByteArray(INITIAL_CHUNK)
        var have = 0
        while (have < length) {
            if (have == buf.size) buf = buf.copyOf(minOf(length, buf.size * 2))
            val n = readSome(buf, have, buf.size - have)
            if (n < 0) throw EOFException("Bağlantı kapandı")
            have += n
        }
        return buf
    }

    /** [count] baytı belleğe almadan atar. */
    suspend fun skip(count: Long) {
        var left = count
        val sink = ByteArray(8192)
        while (left > 0) {
            val n = readSome(sink, 0, minOf(left, sink.size.toLong()).toInt())
            if (n < 0) throw EOFException("Bağlantı kapandı")
            left -= n
        }
    }

    // ------------------------------------------------------------------------------------------------- yazma

    /**
     * Çerçevelenmiş mesajı gönderim kuyruğuna ekler. Kuyruk sınırı aşılırsa (yavaş/ölü eş) bağlantı kapatılır ve false döner.
     */
    fun send(bytes: ByteArray): Boolean {
        if (isClosed) return false
        if (queuedBytes.addAndGet(bytes.size.toLong()) > MAX_QUEUED_BYTES) {
            logger.warn(TAG, "Gönderim kuyruğu sınırı aşıldı, bağlantı kapatılıyor ($label)")
            close()
            return false
        }
        if (outbox.trySend(bytes).isFailure) {
            queuedBytes.addAndGet(-bytes.size.toLong())
            return false
        }
        if (writerStarted.compareAndSet(false, true)) scope.launch { writerLoop() }
        return true
    }

    private suspend fun writerLoop() {
        try {
            for (frame in outbox) {
                writeMutex.withLock { writeInternal(frame, 0, frame.size) }
                queuedBytes.addAndGet(-frame.size.toLong())
            }
            // Kuyruk kapatıldı ve boşaltıldı (closeWhenFlushed).
            close()
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
        } catch (e: Exception) {
            logger.warn(TAG, "Yazıcı beklenmedik şekilde durdu ($label)", e)
            close()
        }
    }

    private suspend fun writeInternal(bytes: ByteArray, offset: Int, length: Int) {
        socket.write(ByteBuffer.wrap(bytes, offset, length))
        bytesWritten.addAndGet(length.toLong())
        touch()
    }

    /** Doğrudan yazar (dosya akışı). Kuyruklu [send] ile aynı bağlantıda eşzamanlı kullanılabilir (sıra korunur). */
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        writeMutex.withLock { writeInternal(bytes, offset, length) }
    }

    /** Kuyruktaki her şey yazıldıktan sonra kapatır. Yeni gönderim kabul edilmez. */
    fun closeWhenFlushed() {
        if (isClosed) return
        closeRequested = true
        if (!writerStarted.get()) {
            close()
            return
        }
        outbox.close()
    }

    /** Hemen kapatır; kuyruktaki veri atılır. Birden çok kez çağrılabilir. */
    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        outbox.cancel()
        socket.close()
        while (true) {
            val l = closeListeners.poll() ?: break
            try {
                l(this)
            } catch (e: Exception) {
                logger.warn(TAG, "onClose dinleyicisi başarısız", e)
            }
        }
    }

    override fun toString(): String = "Connection#$id($label $remoteIp:$remotePort)"

    companion object {
        private const val TAG = "Connection"
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val INITIAL_CHUNK = 64 * 1024

        /** Bir bağlantının bekleyen giden veri sınırı. */
        const val MAX_QUEUED_BYTES = 64L * 1024 * 1024
        private val ids = AtomicInteger()
    }
}
