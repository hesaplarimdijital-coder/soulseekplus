package com.soulseekplus.core.search

import com.soulseekplus.core.model.ClientSettings
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.SearchFilter
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.testutil.FakeSoulseekClient
import com.soulseekplus.core.testutil.awaitUntil
import com.soulseekplus.core.testutil.close
import com.soulseekplus.core.testutil.file
import com.soulseekplus.core.testutil.response
import com.soulseekplus.core.testutil.testScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {

    private val client = FakeSoulseekClient()
    private val settings = MutableStateFlow(ClientSettings(maxSearchResultsKept = 5000))
    private val scope = testScope()
    private fun repo(timeout: Long = 60_000L) = SearchRepository(client, settings, scope, searchTimeoutMillis = timeout, publishIntervalMillis = 5L)

    @After
    fun tearDown() = scope.close()

    @Test
    fun newSearchCreatesSelectedTabAndStartsClientSearch() {
        val repo = repo()
        val id = repo.newSearch("  daft punk ", SearchScope.Network)
        assertEquals(listOf("daft punk" to SearchScope.Network), client.searches.toList())
        assertEquals(id, repo.selectedTabId.value)
        val tab = repo.tabs.value.single()
        assertEquals("daft punk", tab.query)
        assertTrue(tab.active)
        assertEquals(GroupMode.FOLDER, tab.groupMode)
        assertEquals(-1, repo.newSearch("   "))
        assertEquals(listOf("daft punk"), repo.recentQueries.value)
    }

    @Test
    fun wishlistScopeIsCoercedToNetwork() {
        val repo = repo()
        repo.newSearch("x", SearchScope.Wishlist)
        assertEquals(SearchScope.Network, client.searches.single().second)
    }

    @Test
    fun responsesAreRoutedByTokenAndDeduplicated() {
        val repo = repo()
        val a = repo.newSearch("one")
        val b = repo.newSearch("two")
        val (tokA, tokB) = client.tokens.toList()
        client.emitResponse(response("alice", tokA, listOf(file("M\\x\\1.mp3"), file("M\\x\\2.mp3"))))
        client.emitResponse(response("alice", tokA, listOf(file("M\\x\\1.mp3"))))
        client.emitResponse(response("bob", tokB, listOf(file("M\\y\\1.flac")), locked = listOf(file("M\\y\\secret.flac"))))
        client.emitResponse(response("zed", 424242, listOf(file("M\\z.mp3"))))
        repo.flushNow()
        val ta = repo.tab(a)!!
        val tb = repo.tab(b)!!
        assertEquals(2, ta.allHits.size)
        assertEquals(1, ta.userCount)
        assertEquals(2, tb.allHits.size)
        assertEquals(1, tb.visibleHits.size) // kilitli gizli (varsayılan)
        assertTrue(tb.allHits.any { it.locked })
    }

    @Test
    fun resultCapIsHonoured() {
        settings.value = ClientSettings(maxSearchResultsKept = 3)
        val repo = repo()
        val id = repo.newSearch("q")
        client.emitResponse(response("u", client.tokens[0], (1..10).map { file("M\\f$it.mp3") }))
        repo.flushNow()
        assertEquals(3, repo.tab(id)!!.allHits.size)
    }

    @Test
    fun filterAppliesToExistingAndFutureHits() {
        val repo = repo()
        val id = repo.newSearch("q")
        val token = client.tokens[0]
        client.emitResponse(response("u", token, listOf(file("M\\a.mp3", bitrate = 128), file("M\\b.mp3", bitrate = 320))))
        repo.setFilter(id, SearchFilter(minBitrate = 256))
        assertEquals(1, repo.tab(id)!!.visibleHits.size)
        assertEquals(2, repo.tab(id)!!.allHits.size)
        client.emitResponse(response("v", token, listOf(file("M\\c.mp3", bitrate = 192), file("M\\d.mp3", bitrate = 256))))
        repo.flushNow()
        assertEquals(listOf("b.mp3", "d.mp3"), repo.tab(id)!!.visibleHits.map { it.file.name })
        repo.clearFilter(id)
        assertEquals(4, repo.tab(id)!!.visibleHits.size)
    }

    @Test
    fun quickFiltersToggle() {
        val repo = repo()
        val id = repo.newSearch("q")
        repo.toggleQuickFilter(id, QuickFilter.FREE_SLOTS)
        repo.toggleQuickFilter(id, QuickFilter.LOSSLESS)
        repo.toggleQuickFilter(id, QuickFilter.MIN_320)
        repo.toggleQuickFilter(id, QuickFilter.HIDE_LOCKED) // varsayılan açıktı → kapanır
        val f = repo.tab(id)!!.filter
        assertTrue(f.freeSlotsOnly && f.losslessOnly && f.minBitrate == 320 && !f.hideLocked)
        assertTrue(QuickFilter.isActive(f, QuickFilter.MIN_320))
        repo.toggleQuickFilter(id, QuickFilter.MIN_320)
        assertNull(repo.tab(id)!!.filter.minBitrate)
    }

    @Test
    fun groupModeAndClear() {
        val repo = repo()
        val id = repo.newSearch("q")
        client.emitResponse(response("u", client.tokens[0], listOf(file("M\\a.mp3"))))
        repo.flushNow()
        repo.setGroupMode(id, GroupMode.USER)
        assertEquals(GroupMode.USER, repo.tab(id)!!.groupMode)
        repo.clearResults(id)
        assertEquals(0, repo.tab(id)!!.allHits.size)
        // Temizlendikten sonra aynı dosya yeniden eklenebilir.
        client.emitResponse(response("u", client.tokens[0], listOf(file("M\\a.mp3"))))
        repo.flushNow()
        assertEquals(1, repo.tab(id)!!.allHits.size)
    }

    @Test
    fun closeTabCancelsTokenAndSelectsNeighbour() {
        val repo = repo()
        val a = repo.newSearch("a")
        val b = repo.newSearch("b")
        val c = repo.newSearch("c")
        repo.closeTab(c)
        assertEquals(listOf(client.tokens[2]), client.cancelledTokens.toList())
        assertEquals(b, repo.selectedTabId.value)
        // Kapanmış sekmenin token'ına gelen yanıt yok sayılır.
        client.emitResponse(response("u", client.tokens[2], listOf(file("M\\a.mp3"))))
        repo.closeTab(a)
        repo.closeTab(b)
        assertNull(repo.selectedTabId.value)
        assertTrue(repo.tabs.value.isEmpty())
    }

    @Test
    fun tabBecomesInactiveAfterTimeout() {
        val repo = repo(timeout = 40L)
        val id = repo.newSearch("q")
        assertTrue(repo.tab(id)!!.active)
        awaitUntil { !repo.tab(id)!!.active }
        // Etkin olmayan sekme yine de yanıt kabul eder.
        client.emitResponse(response("u", client.tokens[0], listOf(file("M\\a.mp3"))))
        repo.flushNow()
        assertEquals(1, repo.tab(id)!!.allHits.size)
    }

    @Test
    fun rerunGetsNewTokenAndClearsResults() {
        val repo = repo()
        val id = repo.newSearch("q", SearchScope.User("bob"))
        client.emitResponse(response("bob", client.tokens[0], listOf(file("M\\a.mp3"))))
        repo.flushNow()
        assertTrue(repo.rerun(id))
        assertEquals(2, client.searches.size)
        assertEquals(SearchScope.User("bob"), client.searches[1].second)
        assertEquals(listOf(client.tokens[0]), client.cancelledTokens.toList())
        assertEquals(0, repo.tab(id)!!.allHits.size)
        client.emitResponse(response("bob", client.tokens[0], listOf(file("M\\late.mp3")))) // eski token
        client.emitResponse(response("bob", client.tokens[1], listOf(file("M\\new.mp3"))))
        repo.flushNow()
        assertEquals(listOf("new.mp3"), repo.tab(id)!!.allHits.map { it.file.name })
        assertFalse(repo.rerun(999))
    }

    @Test
    fun tabNavigation() {
        val repo = repo()
        val a = repo.newSearch("a")
        val b = repo.newSearch("b")
        val c = repo.newSearch("c")
        repo.selectRelative(1)
        assertEquals(a, repo.selectedTabId.value)
        repo.selectRelative(-1)
        assertEquals(c, repo.selectedTabId.value)
        repo.selectIndex(1)
        assertEquals(b, repo.selectedTabId.value)
        repo.selectIndex(99)
        assertEquals(c, repo.selectedTabId.value)
    }
}
