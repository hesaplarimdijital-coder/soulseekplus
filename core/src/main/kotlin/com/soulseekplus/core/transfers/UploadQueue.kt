package com.soulseekplus.core.transfers

/**
 * Yükleme kuyruğu — saf veri yapısı (G/Ç ve zaman yok), Nicotine+ `uploads.py` mantığının birebir karşılığı
 * (bkz. docs/slsk-protocol.md §8).
 *
 *  - Yuva sayımı ayrık kullanıcılar üzerindendir (bir kullanıcının aynı anda en çok bir aktif yüklemesi vardır).
 *  - ROUND_ROBIN: kullanıcılar, sırada en uzun bekleyen önce olacak şekilde sayaçla sıralanır; bir yüklemesi biten kullanıcı
 *    sıranın sonuna geçer. FIFO: genel ekleme sırası.
 *  - Ayrıcalıklı kullanıcılar (sunucu ayrıcalığı ya da öncelikli arkadaş) varsa yalnızca onlar değerlendirilir.
 *
 * İş parçacığı güvenli değildir; çağıran tek iş parçacığından kullanır.
 */
class UploadQueue {

    /** Kuyruktaki tek bir dosya. */
    data class Entry(val username: String, val path: String, val size: Long)

    private val queuedByUser = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
    private val globalOrder = LinkedHashMap<String, Entry>()
    private val queuedBytes = HashMap<String, Long>()
    private val counters = LinkedHashMap<String, Long>()
    private val active = HashMap<String, MutableSet<String>>()
    private var counterSeq = 0L

    val queuedCount: Int get() = globalOrder.size

    /** Aktif yüklemesi olan ayrık kullanıcı sayısı (yuva sayımı). */
    val activeUserCount: Int get() = active.size

    val activeCount: Int get() = active.values.sumOf { it.size }

    fun queuedCount(username: String): Int = queuedByUser[username]?.size ?: 0

    fun queuedBytes(username: String): Long = queuedBytes[username] ?: 0L

    fun isQueued(username: String, path: String): Boolean = queuedByUser[username]?.containsKey(path) == true

    fun isActive(username: String, path: String): Boolean = active[username]?.contains(path) == true

    fun hasActive(username: String): Boolean = active.containsKey(username)

    fun queuedUsers(): List<String> = queuedByUser.keys.toList()

    fun entries(): List<Entry> = globalOrder.values.toList()

    /**
     * Dosyayı kuyruğa ekler. Zaten kuyrukta ya da aktifse false.
     * Kullanıcının sayacı yoksa ve aktif yüklemesi yoksa kullanıcı sıranın sonuna alınır (var olan sayaç korunur:
     * yeni dosya eklemek kullanıcıyı geri itmez).
     */
    fun enqueue(username: String, path: String, size: Long): Boolean {
        if (isQueued(username, path) || isActive(username, path)) return false
        val entry = Entry(username, path, size)
        queuedByUser.getOrPut(username) { LinkedHashMap() }[path] = entry
        globalOrder[key(username, path)] = entry
        queuedBytes[username] = (queuedBytes[username] ?: 0L) + size
        if (username !in counters) assignCounter(username)
        return true
    }

    /** Kuyruktan çıkarır (iptal, paylaşım kalktı, kullanıcı çevrimdışı). */
    fun remove(username: String, path: String): Boolean {
        val perUser = queuedByUser[username] ?: return false
        val entry = perUser.remove(path) ?: return false
        globalOrder.remove(key(username, path))
        val left = (queuedBytes[username] ?: 0L) - entry.size
        if (perUser.isEmpty()) {
            queuedByUser.remove(username)
            queuedBytes.remove(username)
            counters.remove(username)
        } else {
            queuedBytes[username] = left.coerceAtLeast(0)
        }
        return true
    }

    /** Kullanıcının tüm kuyruğunu temizler; çıkarılan girdileri döndürür. */
    fun removeUser(username: String): List<Entry> {
        val perUser = queuedByUser[username] ?: return emptyList()
        val removed = perUser.values.toList()
        for (e in removed) remove(username, e.path)
        return removed
    }

    /**
     * Bir sonraki yüklenecek dosyayı seçer (kuyruktan ÇIKARMAZ; çıkarmak için [activate]).
     * @param fifo true: genel ekleme sırası; false: Round Robin.
     * @param isPrivileged ayrıcalıklı kullanıcı mı (sunucu ayrıcalığı ya da öncelikli arkadaş).
     */
    fun selectCandidate(fifo: Boolean, isPrivileged: (String) -> Boolean): Entry? {
        if (counters.isEmpty()) return null
        val privileged = HashSet<String>()
        for (user in counters.keys) if (isPrivileged(user)) privileged.add(user)
        val restrict = privileged.isNotEmpty()
        if (fifo) {
            for (entry in globalOrder.values) {
                val u = entry.username
                if (restrict && u !in privileged) continue
                if (u !in counters) continue
                return entry
            }
            return null
        }
        var best: String? = null
        var bestCounter = Long.MAX_VALUE
        for ((user, counter) in counters) {
            if (restrict && user !in privileged) continue
            if (counter < bestCounter) {
                best = user
                bestCounter = counter
            }
        }
        val user = best ?: return null
        return queuedByUser[user]?.values?.firstOrNull()
    }

    /** Girdiyi kuyruktan çıkarıp aktif yapar (kullanıcının sayacı silinir). */
    fun activate(entry: Entry): Boolean {
        if (!remove(entry.username, entry.path)) return false
        active.getOrPut(entry.username) { HashSet() }.add(entry.path)
        counters.remove(entry.username)
        return true
    }

    /** Aktif yükleme bitti/başarısız/iptal: slot boşalır; kullanıcının kuyruğu varsa sıranın sonuna geçer. */
    fun deactivate(username: String, path: String) {
        val set = active[username] ?: return
        set.remove(path)
        if (set.isEmpty()) active.remove(username)
        if (username in queuedByUser && username !in active) assignCounter(username)
    }

    private fun assignCounter(username: String) {
        if (username in queuedByUser && username !in active) {
            counterSeq++
            counters[username] = counterSeq
        }
    }

    /** Ayrıcalıklı olmayan kullanıcılara bildirilen kuyruk uzunluğu = tüm kuyruk; ayrıcalıklılara = ayrıcalıklıların kuyruğu. */
    fun queueSizeFor(requesterPrivileged: Boolean, isPrivileged: (String) -> Boolean): Int {
        if (!requesterPrivileged) return globalOrder.size
        var n = 0
        for ((user, files) in queuedByUser) if (isPrivileged(user)) n += files.size
        return n
    }

    /**
     * PlaceInQueueRequest yanıtı (1 tabanlı); dosya kuyrukta değilse null.
     */
    fun position(username: String, path: String, fifo: Boolean, isPrivileged: (String) -> Boolean): Int? {
        val perUser = queuedByUser[username] ?: return null
        if (!perUser.containsKey(path)) return null
        val requesterPrivileged = isPrivileged(username)
        if (fifo) {
            var index = 0
            for (entry in globalOrder.values) {
                if (requesterPrivileged && !isPrivileged(entry.username)) continue
                index++
                if (entry.username == username && entry.path == path) return index
            }
            return null
        }
        var own = 0
        for (p in perUser.keys) {
            own++
            if (p == path) break
        }
        return own + roundRobinOffset(requesterPrivileged, isPrivileged)
    }

    /** Tüm kuyruklu girdilerin yeri (anahtar: [key]); tek geçişte hesaplanır. */
    fun positions(fifo: Boolean, isPrivileged: (String) -> Boolean): Map<String, Int> {
        val result = HashMap<String, Int>(globalOrder.size * 2)
        if (fifo) {
            val privilegedIndex = HashMap<String, Int>()
            var all = 0
            var priv = 0
            for ((k, entry) in globalOrder) {
                all++
                if (isPrivileged(entry.username)) {
                    priv++
                    privilegedIndex[k] = priv
                }
                result[k] = all
            }
            // Ayrıcalıklı istekçiler kendi kuyruklarındaki sırayı görür.
            for ((k, idx) in privilegedIndex) result[k] = idx
            return result
        }
        val privOffset = roundRobinOffset(true, isPrivileged)
        val normalOffset = roundRobinOffset(false, isPrivileged)
        for ((user, files) in queuedByUser) {
            val offset = if (isPrivileged(user)) privOffset else normalOffset
            var i = 0
            for (p in files.keys) {
                i++
                result[key(user, p)] = i + offset
            }
        }
        return result
    }

    private fun roundRobinOffset(requesterPrivileged: Boolean, isPrivileged: (String) -> Boolean): Int {
        var privilegedUsers = 0
        var privilegedFiles = 0
        for ((user, files) in queuedByUser) {
            if (isPrivileged(user)) {
                privilegedUsers++
                privilegedFiles += files.size
            }
        }
        return if (requesterPrivileged) privilegedUsers else queuedByUser.size + privilegedFiles
    }

    fun clear() {
        queuedByUser.clear()
        globalOrder.clear()
        queuedBytes.clear()
        counters.clear()
        active.clear()
    }

    companion object {
        fun key(username: String, path: String): String = "$username\u0000$path"
    }
}
