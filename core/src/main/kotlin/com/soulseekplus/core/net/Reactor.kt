package com.soulseekplus.core.net

import com.soulseekplus.core.api.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tek iş parçacıklı NIO seçici (java.nio.channels.Selector) ve coroutine köprüsü.
 *
 * Seçici iş parçacığı yalnızca hazır olma olaylarını bekler ve bekleyen coroutine'leri uyandırır; okuma/yazma çağrıları
 * (engellemeyen) çağıran coroutine'in iş parçacığında yapılır. Seçici durumuna (anahtarlar, ilgi kümeleri) yalnızca
 * seçici iş parçacığı dokunur: diğer iş parçacıkları [post] ile görev bırakır.
 */
class Reactor(private val logger: Logger = Logger.NONE, threadName: String = "slsk-selector") : AutoCloseable {

    internal val selector: Selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    private var running = true
    private val thread: Thread = Thread({ loop() }, threadName).apply {
        isDaemon = true
        start()
    }

    val isClosed: Boolean get() = !running

    /** Görevi seçici iş parçacığında çalıştırır. Kapalıysa çağıranın iş parçacığında çalıştırılır (bekleyenleri düşürmek için). */
    internal fun post(task: Runnable) {
        tasks.add(task)
        if (!running) {
            drainTasks()
            return
        }
        try {
            selector.wakeup()
        } catch (_: ClosedSelectorException) {
            drainTasks()
        }
    }

    private fun drainTasks() {
        while (true) {
            val t = tasks.poll() ?: return
            try {
                t.run()
            } catch (e: Throwable) {
                logger.warn(TAG, "Seçici görevi başarısız", e)
            }
        }
    }

    private fun loop() {
        try {
            while (running) {
                try {
                    selector.select()
                } catch (e: IOException) {
                    logger.warn(TAG, "select başarısız", e)
                    continue
                } catch (_: ClosedSelectorException) {
                    break
                }
                drainTasks()
                val iterator = try {
                    selector.selectedKeys().iterator()
                } catch (_: ClosedSelectorException) {
                    break
                }
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    dispatch(key)
                }
            }
        } catch (e: Throwable) {
            logger.error(TAG, "Seçici döngüsü beklenmedik şekilde bitti", e)
        } finally {
            running = false
            shutdownAll()
        }
    }

    private fun dispatch(key: SelectionKey) {
        val attachment = key.attachment()
        try {
            when (attachment) {
                is AsyncSocket -> attachment.onReady(key)
                is Listener -> attachment.onAcceptable()
            }
        } catch (_: CancelledKeyException) {
            (attachment as? AsyncSocket)?.close()
        } catch (e: Throwable) {
            logger.warn(TAG, "Seçici olayı işlenemedi", e)
            (attachment as? AsyncSocket)?.close()
        }
    }

    private fun shutdownAll() {
        try {
            for (key in selector.keys().toList()) {
                when (val a = key.attachment()) {
                    is AsyncSocket -> a.close()
                    is Listener -> a.close()
                }
            }
        } catch (_: Exception) {
        }
        try {
            selector.close()
        } catch (_: IOException) {
        }
        drainTasks()
    }

    /**
     * [address]'e bağlanır. Çözülmüş (IP) adres gerekir: DNS çözümlemesi seçiciyi engellememesi için çağıranda yapılır.
     * @throws IOException bağlantı reddedildi / ağa ulaşılamadı; [SocketTimeoutException] zaman aşımı.
     */
    suspend fun connect(address: InetSocketAddress, timeoutMillis: Long, keepAlive: Boolean = false): AsyncSocket {
        if (address.isUnresolved) throw IOException("Çözümlenmemiş adres: $address")
        val channel = SocketChannel.open()
        var success = false
        try {
            channel.configureBlocking(false)
            channel.socket().tcpNoDelay = true
            if (keepAlive) channel.socket().keepAlive = true
            val socket = AsyncSocket(this, channel)
            try {
                withTimeout(timeoutMillis) { socket.connectTo(address) }
            } catch (e: TimeoutCancellationException) {
                throw SocketTimeoutException("Bağlantı zaman aşımı: $address")
            }
            success = true
            return socket
        } finally {
            if (!success) {
                try {
                    channel.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /**
     * [port] üzerinde dinler (0: boş port). [onAccept] seçici iş parçacığında çağrılır; hızlı dönmeli (ör. coroutine başlat).
     * @throws IOException port bağlanamadı.
     */
    fun listen(port: Int, onAccept: (AsyncSocket) -> Unit): Listener {
        if (!running) throw IOException("Reactor kapalı")
        val channel = ServerSocketChannel.open()
        try {
            channel.socket().reuseAddress = true
            channel.bind(InetSocketAddress(port), 128)
            channel.configureBlocking(false)
        } catch (e: IOException) {
            try {
                channel.close()
            } catch (_: IOException) {
            }
            throw e
        }
        val listener = Listener(this, channel, onAccept, logger)
        post(Runnable { listener.register() })
        return listener
    }

    override fun close() {
        if (!running && !thread.isAlive) return
        running = false
        try {
            selector.wakeup()
        } catch (_: ClosedSelectorException) {
        }
        if (Thread.currentThread() !== thread) {
            try {
                thread.join(3000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        private const val TAG = "Reactor"
    }
}

/** Dinleyici soket. [close] dinlemeyi bırakır; kabul edilmiş bağlantılar etkilenmez. */
class Listener internal constructor(
    private val reactor: Reactor,
    private val channel: ServerSocketChannel,
    private val onAccept: (AsyncSocket) -> Unit,
    private val logger: Logger,
) {
    val port: Int = channel.socket().localPort
    private val closed = AtomicBoolean(false)

    internal fun register() {
        try {
            if (closed.get()) return
            channel.register(reactor.selector, SelectionKey.OP_ACCEPT, this)
        } catch (e: Exception) {
            logger.warn("Listener", "Dinleyici kaydedilemedi", e)
            close()
        }
    }

    internal fun onAcceptable() {
        while (!closed.get()) {
            val accepted = try {
                channel.accept()
            } catch (e: IOException) {
                logger.warn("Listener", "accept başarısız", e)
                null
            } ?: return
            try {
                accepted.configureBlocking(false)
                accepted.socket().tcpNoDelay = true
                onAccept(AsyncSocket(reactor, accepted))
            } catch (e: Exception) {
                logger.warn("Listener", "Kabul edilen bağlantı kurulamadı", e)
                try {
                    accepted.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            channel.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * Engellemeyen bir [SocketChannel] üzerinde askıya alan okuma/yazma. Aynı anda en çok bir okuyucu ve bir yazıcı olabilir.
 */
class AsyncSocket internal constructor(
    private val reactor: Reactor,
    internal val channel: SocketChannel,
) {
    // Aşağıdaki alanlara yalnızca seçici iş parçacığı dokunur.
    private var key: SelectionKey? = null
    private var readCont: CancellableContinuation<Unit>? = null
    private var writeCont: CancellableContinuation<Unit>? = null
    private var connectCont: CancellableContinuation<Unit>? = null

    private val closedFlag = AtomicBoolean(false)
    val isClosed: Boolean get() = closedFlag.get()

    val remoteAddress: InetSocketAddress?
        get() = try {
            channel.remoteAddress as? InetSocketAddress
        } catch (_: IOException) {
            null
        }

    /** Karşı tarafın IPv4/IPv6 metin adresi ("" bilinmiyorsa). */
    val remoteIp: String get() = remoteAddress?.address?.hostAddress.orEmpty()
    val remotePort: Int get() = remoteAddress?.port ?: 0

    // ------------------------------------------------------------------------------------------------- bağlanma

    internal suspend fun connectTo(address: InetSocketAddress) {
        suspendCancellableCoroutine<Unit> { cont ->
            reactor.post(Runnable { startConnect(address, cont) })
            cont.invokeOnCancellation { reactor.post(Runnable { if (connectCont === cont) connectCont = null; close() }) }
        }
    }

    private fun startConnect(address: InetSocketAddress, cont: CancellableContinuation<Unit>) {
        if (closedFlag.get() || reactor.isClosed) {
            cont.resumeWithException(ClosedChannelException())
            return
        }
        try {
            if (channel.connect(address)) {
                cont.resume(Unit)
                return
            }
            val k = channel.register(reactor.selector, SelectionKey.OP_CONNECT, this)
            key = k
            connectCont = cont
        } catch (e: UnresolvedAddressException) {
            cont.resumeWithException(IOException("Çözümlenmemiş adres: $address", e))
        } catch (e: IOException) {
            cont.resumeWithException(e)
        } catch (e: RuntimeException) {
            cont.resumeWithException(IOException(e))
        }
    }

    // ------------------------------------------------------------------------------------------------- okuma / yazma

    /** En az 1 bayt okur; -1: karşı taraf kapattı. [dst] doluysa 0. */
    suspend fun read(dst: ByteBuffer): Int {
        if (!dst.hasRemaining()) return 0
        while (true) {
            val n = channel.read(dst)
            if (n != 0) return n
            await(SelectionKey.OP_READ)
        }
    }

    /** [src] tamamen yazılana kadar askıya alır. */
    suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            val n = channel.write(src)
            if (n == 0) await(SelectionKey.OP_WRITE)
        }
    }

    private suspend fun await(op: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            reactor.post(Runnable { arm(op, cont) })
            cont.invokeOnCancellation { reactor.post(Runnable { disarm(op, cont) }) }
        }
    }

    private fun arm(op: Int, cont: CancellableContinuation<Unit>) {
        if (closedFlag.get() || reactor.isClosed) {
            cont.resumeWithException(ClosedChannelException())
            return
        }
        try {
            val k = key ?: channel.register(reactor.selector, 0, this).also { key = it }
            if (op == SelectionKey.OP_READ) {
                check(readCont == null) { "Aynı anda iki okuyucu" }
                readCont = cont
            } else {
                check(writeCont == null) { "Aynı anda iki yazıcı" }
                writeCont = cont
            }
            k.interestOps(k.interestOps() or op)
        } catch (e: CancelledKeyException) {
            cont.resumeWithException(ClosedChannelException())
        } catch (e: IOException) {
            cont.resumeWithException(e)
        } catch (e: RuntimeException) {
            cont.resumeWithException(IOException(e))
        }
    }

    private fun disarm(op: Int, cont: CancellableContinuation<Unit>) {
        val k = key
        if (op == SelectionKey.OP_READ) {
            if (readCont === cont) readCont = null else return
        } else {
            if (writeCont === cont) writeCont = null else return
        }
        try {
            if (k != null && k.isValid) k.interestOps(k.interestOps() and op.inv())
        } catch (_: CancelledKeyException) {
        }
    }

    /** Seçici iş parçacığında: anahtar hazır. */
    internal fun onReady(k: SelectionKey) {
        val ready = k.readyOps()
        if (ready and SelectionKey.OP_CONNECT != 0) {
            val cont = connectCont
            try {
                if (channel.finishConnect()) {
                    k.interestOps(k.interestOps() and SelectionKey.OP_CONNECT.inv())
                    connectCont = null
                    cont?.resume(Unit)
                }
            } catch (e: IOException) {
                connectCont = null
                cont?.resumeWithException(e)
                close()
                return
            }
        }
        if (ready and SelectionKey.OP_READ != 0) {
            k.interestOps(k.interestOps() and SelectionKey.OP_READ.inv())
            val cont = readCont
            readCont = null
            cont?.resume(Unit)
        }
        if (ready and SelectionKey.OP_WRITE != 0) {
            k.interestOps(k.interestOps() and SelectionKey.OP_WRITE.inv())
            val cont = writeCont
            writeCont = null
            cont?.resume(Unit)
        }
    }

    /** Soketi kapatır ve bekleyen okuyucu/yazıcıları [ClosedChannelException] ile uyandırır. Her iş parçacığından çağrılabilir. */
    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        try {
            channel.close()
        } catch (_: IOException) {
        }
        reactor.post(Runnable { failAll() })
    }

    private fun failAll() {
        try {
            key?.cancel()
        } catch (_: Exception) {
        }
        val r = readCont
        val w = writeCont
        val c = connectCont
        readCont = null
        writeCont = null
        connectCont = null
        val error = ClosedChannelException()
        r?.resumeWithException(error)
        w?.resumeWithException(error)
        c?.resumeWithException(error)
    }
}
