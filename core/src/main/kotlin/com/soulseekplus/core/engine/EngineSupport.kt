package com.soulseekplus.core.engine

import com.soulseekplus.core.net.NetworkTimeouts
import com.soulseekplus.core.transfers.TransferTiming
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Motorun zaman aşımı ve aralık ayarları. Varsayılanlar gerçek ağ içindir; testler kısaltılmış değerlerle
 * [SoulseekEngine.Companion.withTuning] üzerinden motor kurar.
 */
class EngineTuning(
    val serverConnectTimeoutMillis: Long = 15_000,
    val loginTimeoutMillis: Long = 30_000,
    val pingIntervalMillis: Long = 60_000,
    /** İlk yeniden bağlanma gecikmesi bu aralıkta rastgele seçilir; sonraki denemeler ikiye katlanır. */
    val reconnectMinMillis: Long = 5_000,
    val reconnectFirstMaxMillis: Long = 15_000,
    val reconnectCapMillis: Long = 300_000,
    /** Ağ değişiminden sonra sunucudan yanıt beklenen süre; gelmezse bağlantı ölü sayılır. */
    val probeTimeoutMillis: Long = 10_000,
    val userInfoTimeoutMillis: Long = 30_000,
    val browseTimeoutMillis: Long = 240_000,
    val folderTimeoutMillis: Long = 60_000,
    val statusIntervalMillis: Long = 2_000,
    val minSearchChars: Int = 3,
    val networkTimeouts: NetworkTimeouts = NetworkTimeouts(),
    val transferTiming: TransferTiming = TransferTiming(),
)

/** Anahtar başına bekleyen çağıranlar listesi. Yalnızca mantık iş parçacığından kullanılır. */
internal class WaiterTable<K : Any, T> {
    private val map = HashMap<K, ArrayList<CompletableDeferred<T>>>()

    /** Bekleyen ekler; anahtar için ilk bekleyense true (istek gönderilmelidir). */
    fun add(key: K, waiter: CompletableDeferred<T>): Boolean {
        val list = map.getOrPut(key) { ArrayList(2) }
        list.add(waiter)
        return list.size == 1
    }

    /** Bekleyeni çıkarır; anahtar için kimse kalmadıysa true. Anahtar zaten yoksa false. */
    fun remove(key: K, waiter: CompletableDeferred<T>): Boolean {
        val list = map[key] ?: return false
        list.remove(waiter)
        if (list.isEmpty()) {
            map.remove(key)
            return true
        }
        return false
    }

    fun complete(key: K, value: T): Boolean {
        val list = map.remove(key) ?: return false
        for (w in list) w.complete(value)
        return true
    }

    fun fail(key: K, error: Throwable): Boolean {
        val list = map.remove(key) ?: return false
        for (w in list) w.completeExceptionally(error)
        return true
    }

    fun failAll(error: Throwable) {
        val all = map.values.toList()
        map.clear()
        for (list in all) for (w in list) w.completeExceptionally(error)
    }

    fun hasWaiters(key: K): Boolean = map.containsKey(key)

    fun keys(): List<K> = map.keys.toList()
}

/** Sınırlı büyüklükte, iş parçacığı güvenli token kümesi (en eski girdi atılır). */
internal class BoundedTokenSet(private val capacity: Int) {
    private val set = LinkedHashSet<Int>()

    fun add(token: Int) {
        synchronized(set) {
            set.remove(token)
            set.add(token)
            while (set.size > capacity) {
                val it = set.iterator()
                it.next()
                it.remove()
            }
        }
    }

    fun remove(token: Int) {
        synchronized(set) { set.remove(token) }
    }

    fun contains(token: Int): Boolean = synchronized(set) { set.contains(token) }
}

/** İstediğimiz büyük eş yanıtları (paylaşım listesi, kullanıcı bilgisi); istenmemiş olanlar okuyucuda reddedilir. */
internal class SolicitedTracker {
    private val map = ConcurrentHashMap<String, Long>()

    private fun key(user: String, code: Int) = "$code\u0000$user"

    fun expect(user: String, code: Int, ttlMillis: Long) {
        map[key(user, code)] = System.nanoTime() + ttlMillis * 1_000_000
    }

    fun isSolicited(user: String, code: Int): Boolean {
        val expiry = map[key(user, code)] ?: return false
        return expiry - System.nanoTime() > 0
    }

    fun clear(user: String, code: Int) {
        map.remove(key(user, code))
    }

    fun clearAll() {
        map.clear()
    }
}

/** Sınırlı büyüklükte en-son-kullanılan değil, ekleme sırasına göre atan sözlük (yalnızca tek iş parçacığı). */
internal class BoundedMap<K, V>(private val capacity: Int) : LinkedHashMap<K, V>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
}
