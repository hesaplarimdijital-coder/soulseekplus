package com.soulseekplus.core.search

import com.soulseekplus.core.model.SearchFilter
import com.soulseekplus.core.testutil.hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterEngineTest {

    private val noLockFilter = SearchFilter(hideLocked = false)

    private fun matches(filter: SearchFilter, h: com.soulseekplus.core.model.SearchHit) = SearchFilterEngine.matches(h, filter)

    @Test
    fun defaultFilterHidesLockedOnly() {
        assertTrue(matches(SearchFilter(), hit()))
        assertFalse(matches(SearchFilter(), hit(locked = true)))
        assertTrue(matches(noLockFilter, hit(locked = true)))
        assertTrue(SearchFilterEngine.compile(noLockFilter).isPassThrough)
        assertFalse(SearchFilterEngine.compile(SearchFilter()).isPassThrough)
    }

    @Test
    fun wordIncludeRequiresEveryWordCaseInsensitive() {
        val h = hit(path = "Music\\Daft Punk\\Discovery\\One More Time.flac", bitrate = null)
        assertTrue(matches(noLockFilter.copy(include = "daft discovery"), h))
        assertTrue(matches(noLockFilter.copy(include = "DAFT   one more"), h))
        assertFalse(matches(noLockFilter.copy(include = "daft homework"), h))
    }

    @Test
    fun quotedPhraseIsOneTerm() {
        val h = hit(path = "Music\\Daft Punk\\One More Time.flac")
        assertTrue(matches(noLockFilter.copy(include = "\"one more time\""), h))
        assertFalse(matches(noLockFilter.copy(include = "\"more one\""), h))
    }

    @Test
    fun alternativesWithPipe() {
        val h = hit(path = "Music\\Foo\\Song.ogg")
        assertTrue(matches(noLockFilter.copy(include = "flac|ogg"), h))
        assertFalse(matches(noLockFilter.copy(include = "flac|wav"), h))
    }

    @Test
    fun wordExcludeRejectsAnyWord() {
        val h = hit(path = "Music\\Foo\\Song (live).mp3")
        assertFalse(matches(noLockFilter.copy(exclude = "live"), h))
        assertFalse(matches(noLockFilter.copy(exclude = "remix live"), h))
        assertTrue(matches(noLockFilter.copy(exclude = "remix"), h))
    }

    @Test
    fun includeAlsoMatchesUsername() {
        assertTrue(matches(noLockFilter.copy(include = "alice"), hit(user = "alice")))
        assertFalse(matches(noLockFilter.copy(include = "bob"), hit(user = "alice")))
    }

    @Test
    fun regexIncludeAndExclude() {
        val h = hit(path = "Music\\Foo\\Track 05.mp3")
        assertTrue(matches(noLockFilter.copy(useRegex = true, include = "track \\d+\\.mp3$"), h))
        assertFalse(matches(noLockFilter.copy(useRegex = true, include = "^track"), h))
        assertFalse(matches(noLockFilter.copy(useRegex = true, exclude = "track\\s0\\d"), h))
        assertTrue(matches(noLockFilter.copy(useRegex = true, exclude = "wav|flac"), h))
    }

    @Test
    fun invalidRegexIsReportedAndIgnored() {
        val f = noLockFilter.copy(useRegex = true, include = "([unclosed", exclude = "ok")
        val issues = SearchFilterEngine.validate(f)
        assertEquals(1, issues.size)
        assertEquals(FilterField.INCLUDE, issues[0].field)
        // Sorunlu alan uygulanmaz; sağlam alan uygulanır.
        assertTrue(matches(f, hit(path = "Music\\x\\y.mp3")))
        assertFalse(matches(f, hit(path = "Music\\ok\\y.mp3")))
        val outcome = SearchFilterEngine.apply(listOf(hit()), f)
        assertEquals(1, outcome.issues.size)
        assertEquals(1, outcome.hits.size)
    }

    @Test
    fun catastrophicRegexDoesNotHang() {
        val evil = hit(path = "a".repeat(60) + "!")
        val f = noLockFilter.copy(useRegex = true, include = "(a+)+$")
        val start = System.currentTimeMillis()
        matches(f, evil)
        assertTrue(System.currentTimeMillis() - start < 5000)
    }

    @Test
    fun catastrophicRegexOverManyRowsStaysFast() {
        // Her satırda adım bütçesini tüketen ifade (~1-2 ms/satır); 5000 satırda eskiden saniyeler sürüyordu.
        val rows = (1..5000).map { hit(user = "u$it", path = "a".repeat(40) + "!$it") }
        val f = noLockFilter.copy(useRegex = true, include = "(.*a){20}")
        val start = System.currentTimeMillis()
        val out = SearchFilterEngine.apply(rows, f)
        assertTrue("süre: ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 2000)
        assertTrue(out.hits.isEmpty())
    }

    @Test
    fun bitrateLetsLosslessPass() {
        val f = noLockFilter.copy(minBitrate = 320)
        assertTrue(matches(f, hit(path = "a\\b.mp3", bitrate = 320)))
        assertFalse(matches(f, hit(path = "a\\b.mp3", bitrate = 192)))
        assertFalse(matches(f, hit(path = "a\\b.mp3", bitrate = null)))
        assertTrue(matches(f, hit(path = "a\\b.flac", bitrate = null)))
        assertTrue(matches(f, hit(path = "a\\b.flac", bitrate = 900)))
    }

    @Test
    fun losslessOnly() {
        val f = noLockFilter.copy(losslessOnly = true)
        assertTrue(matches(f, hit(path = "a\\b.flac")))
        assertTrue(matches(f, hit(path = "a\\b.WAV")))
        assertFalse(matches(f, hit(path = "a\\b.mp3")))
    }

    @Test
    fun sampleRateAndBitDepth() {
        val hiRes = hit(path = "a\\b.flac", sampleRate = 96000, bitDepth = 24)
        val cd = hit(path = "a\\b.flac", sampleRate = 44100, bitDepth = 16)
        val unknown = hit(path = "a\\b.flac")
        val f = noLockFilter.copy(minSampleRate = 48000, minBitDepth = 24)
        assertTrue(matches(f, hiRes))
        assertFalse(matches(f, cd))
        assertFalse(matches(f, unknown))
        assertTrue(matches(noLockFilter.copy(minBitDepth = 16), cd))
    }

    @Test
    fun sizeRange() {
        val h = hit(size = 10_000)
        assertTrue(matches(noLockFilter.copy(minSizeBytes = 10_000, maxSizeBytes = 10_000), h))
        assertFalse(matches(noLockFilter.copy(minSizeBytes = 10_001), h))
        assertFalse(matches(noLockFilter.copy(maxSizeBytes = 9_999), h))
    }

    @Test
    fun extensionsIncludeExcludeAndGeneric() {
        val flac = hit(path = "a\\b.flac")
        val mp3 = hit(path = "a\\b.mp3")
        val jpg = hit(path = "a\\cover.jpg")
        assertTrue(matches(noLockFilter.copy(extensions = "flac, mp3"), mp3))
        assertFalse(matches(noLockFilter.copy(extensions = "flac"), mp3))
        assertTrue(matches(noLockFilter.copy(extensions = ".FLAC"), flac))
        assertFalse(matches(noLockFilter.copy(extensions = "!mp3"), mp3))
        assertTrue(matches(noLockFilter.copy(extensions = "!mp3"), flac))
        assertTrue(matches(noLockFilter.copy(extensions = "audio"), flac))
        assertFalse(matches(noLockFilter.copy(extensions = "audio"), jpg))
        assertTrue(matches(noLockFilter.copy(extensions = "lossless"), flac))
        assertFalse(matches(noLockFilter.copy(extensions = "lossless"), mp3))
    }

    @Test
    fun countriesWithExclusion() {
        val tr = hit(country = "TR")
        val us = hit(country = "US")
        val none = hit(country = null)
        assertTrue(matches(noLockFilter.copy(countries = "tr,de"), tr))
        assertFalse(matches(noLockFilter.copy(countries = "tr,de"), us))
        assertFalse(matches(noLockFilter.copy(countries = "!US"), us))
        assertTrue(matches(noLockFilter.copy(countries = "!US"), tr))
        assertTrue(matches(noLockFilter.copy(countries = "!US"), none))
        assertFalse(matches(noLockFilter.copy(countries = "TR"), none))
    }

    @Test
    fun slotsQueueSpeed() {
        val f = noLockFilter.copy(freeSlotsOnly = true)
        assertTrue(matches(f, hit(free = true)))
        assertFalse(matches(f, hit(free = false, queue = 3)))
        val q = noLockFilter.copy(maxQueueLength = 5)
        assertTrue(matches(q, hit(free = false, queue = 5)))
        assertFalse(matches(q, hit(free = false, queue = 6)))
        assertTrue(matches(q, hit(free = true, queue = 99)))
        val s = noLockFilter.copy(minSpeedBytes = 100_000)
        assertTrue(matches(s, hit(speed = 100_000)))
        assertFalse(matches(s, hit(speed = 99_999)))
    }

    @Test
    fun applyKeepsOrderAndFilters() {
        val hits = listOf(hit(user = "a", path = "x\\1.mp3"), hit(user = "b", path = "x\\2.flac"), hit(user = "c", path = "x\\3.mp3", locked = true))
        val out = SearchFilterEngine.apply(hits, SearchFilter(extensions = "mp3"))
        assertEquals(listOf("a"), out.hits.map { it.username })
    }

    @Test
    fun splitTermsHandlesQuotesAndEmpty() {
        assertEquals(listOf(listOf("a"), listOf("b c"), listOf("d", "e")), SearchFilterEngine.splitTerms("a \"b c\" d|e"))
        assertTrue(SearchFilterEngine.splitTerms("   ").isEmpty())
    }
}
