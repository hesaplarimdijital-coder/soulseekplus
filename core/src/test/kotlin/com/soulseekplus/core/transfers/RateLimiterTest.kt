package com.soulseekplus.core.transfers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var now = 0L
    private val limiter = RateLimiter { now }

    private fun advanceMillis(ms: Long) {
        now += ms * 1_000_000
    }

    @Test
    fun unlimitedNeverWaits() {
        assertTrue(limiter.isUnlimited)
        assertEquals(0L, limiter.reserve(10_000_000))
        assertEquals(65536, limiter.chunkSize(65536))
    }

    @Test
    fun reservationsAreSpacedAtTheConfiguredRate() {
        limiter.bytesPerSecond = 10_000
        assertEquals(500_000_000L, limiter.reserve(5_000))
        advanceMillis(500)
        // Borç ödendi; yeni 1000 bayt 100 ms bekletir.
        assertEquals(100_000_000L, limiter.reserve(1_000))
    }

    @Test
    fun idleTimeBuildsOnlyABoundedBurst() {
        limiter.bytesPerSecond = 10_000
        advanceMillis(60_000)
        // Kapasite = max(rate * 0.25, 16 KiB) = 16384.
        assertEquals(0L, limiter.reserve(16_384))
        assertTrue(limiter.reserve(10_000) > 0)
    }

    @Test
    fun limitCanBeChangedAndRemovedLive() {
        limiter.bytesPerSecond = 1_000
        assertTrue(limiter.reserve(2_000) > 0)
        limiter.bytesPerSecond = 0
        assertTrue(limiter.isUnlimited)
        assertEquals(0L, limiter.reserve(1_000_000))
        limiter.bytesPerSecond = -5
        assertEquals(0L, limiter.bytesPerSecond)
    }

    @Test
    fun chunkSizeShrinksWithTheLimit() {
        limiter.bytesPerSecond = 10_000
        assertEquals(1_024, limiter.chunkSize(65_536))
        limiter.bytesPerSecond = 400_000
        assertEquals(40_000, limiter.chunkSize(65_536))
        limiter.bytesPerSecond = 50_000_000
        assertEquals(65_536, limiter.chunkSize(65_536))
    }

    @Test
    fun acquireReturnsImmediatelyWhenTokensAreAvailable() = runBlocking {
        limiter.bytesPerSecond = 1_000_000
        advanceMillis(1_000)
        val start = System.nanoTime()
        limiter.acquire(1_000)
        assertTrue(System.nanoTime() - start < 200_000_000L)
    }

    @Test
    fun speedMeterAveragesOverTheWindow() {
        val meter = SpeedMeter(windowMillis = 3_000)
        assertEquals(0L, meter.bytesPerSecond())
        meter.sample(0, 0)
        assertEquals(0L, meter.bytesPerSecond())
        meter.sample(3_000, 1_000)
        assertEquals(3_000L, meter.bytesPerSecond())
        // Pencere dışına çıkan eski örnekler atılır: son 3 sn içinde 6000 bayt / 3 sn = 2000.
        meter.sample(6_000, 4_000)
        meter.sample(9_000, 7_000)
        assertEquals(1_000L, meter.bytesPerSecond())
        meter.reset()
        assertEquals(0L, meter.bytesPerSecond())
    }

    @Test
    fun pathSanitizerBlocksTraversalAndIllegalCharacters() {
        assertEquals("a/b/c", PathSanitizer.subfolder("a\\b/c"))
        assertEquals("b", PathSanitizer.subfolder("../../b"))
        assertEquals(null, PathSanitizer.subfolder("..\\.."))
        assertEquals(null, PathSanitizer.subfolder(null))
        assertEquals("_", PathSanitizer.segment(".."))
        assertEquals("a_b", PathSanitizer.segment("a:b"))
        assertEquals("_", PathSanitizer.segment("   "))
        assertTrue(PathSanitizer.segment("x".repeat(500) + ".flac").endsWith(".flac"))
        assertTrue(PathSanitizer.segment("x".repeat(500) + ".flac").toByteArray().size <= 200)
    }
}
