package com.soulseekplus.core.transfers

import kotlinx.coroutines.delay

/**
 * Jeton kovası hız sınırlayıcı; tüm transferler tarafından paylaşılan genel sınır için. Sınır canlı değiştirilebilir.
 * İş parçacığı güvenlidir (kısa senkron bölümler, bekleme kilit dışında yapılır).
 *
 * @param clockNanos zaman kaynağı (testlerde sahte saat).
 */
class RateLimiter(private val clockNanos: () -> Long = System::nanoTime) {

    private val lock = Any()
    private var rate = 0L
    private var tokens = 0.0
    private var last = clockNanos()

    /** Saniyedeki bayt sınırı (0 = sınırsız). */
    var bytesPerSecond: Long
        get() = synchronized(lock) { rate }
        set(value) {
            synchronized(lock) {
                val newRate = value.coerceAtLeast(0)
                refill()
                rate = newRate
                tokens = minOf(tokens, capacity())
            }
        }

    val isUnlimited: Boolean get() = bytesPerSecond <= 0

    private fun capacity(): Double = maxOf(rate * BURST_SECONDS, MIN_CAPACITY.toDouble())

    private fun refill() {
        val now = clockNanos()
        val elapsed = now - last
        last = now
        if (rate > 0 && elapsed > 0) tokens = minOf(capacity(), tokens + elapsed * rate / 1e9)
    }

    /**
     * [bytes] bayt için rezervasyon yapar ve beklenmesi gereken süreyi (ns) döndürür. Çağıran bu süre kadar bekler.
     * Sınırsızsa 0.
     */
    fun reserve(bytes: Int): Long {
        if (bytes <= 0) return 0
        synchronized(lock) {
            if (rate <= 0) return 0
            refill()
            tokens -= bytes
            return if (tokens >= 0) 0 else (-tokens * 1e9 / rate).toLong()
        }
    }

    /** [bytes] bayta izin verilene kadar askıya alır. */
    suspend fun acquire(bytes: Int) {
        val waitNanos = reserve(bytes)
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }

    /** Sınır varken tek seferde işlenecek parça boyutu: kısa aralıklarla akıcı akış için sınırlanır. */
    fun chunkSize(max: Int): Int {
        val r = bytesPerSecond
        if (r <= 0) return max
        return (r / 10).coerceIn(1024L, max.toLong()).toInt()
    }

    companion object {
        private const val BURST_SECONDS = 0.25
        private const val MIN_CAPACITY = 16 * 1024
    }
}

/**
 * Hareketli ortalama hız ölçer: örnekler (toplam bayt, zaman) eklenir; son [windowMillis] içindeki değişimden hız hesaplanır.
 * Tek iş parçacığından kullanılır.
 */
class SpeedMeter(private val windowMillis: Long = 3_000) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    fun reset() {
        times.clear()
        totals.clear()
    }

    /** [totalBytes] o ana kadarki toplam bayttır. */
    fun sample(totalBytes: Long, nowMillis: Long) {
        times.addLast(nowMillis)
        totals.addLast(totalBytes)
        while (times.size > 2 && nowMillis - times.first() > windowMillis) {
            times.removeFirst()
            totals.removeFirst()
        }
    }

    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0
        val dt = times.last() - times.first()
        if (dt <= 0) return 0
        val delta = totals.last() - totals.first()
        return if (delta <= 0) 0 else delta * 1000 / dt
    }
}
