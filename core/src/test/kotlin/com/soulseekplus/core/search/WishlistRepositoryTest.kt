package com.soulseekplus.core.search

import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.WishlistItem
import com.soulseekplus.core.testutil.FakeSoulseekClient
import com.soulseekplus.core.testutil.awaitUntil
import com.soulseekplus.core.testutil.close
import com.soulseekplus.core.testutil.file
import com.soulseekplus.core.testutil.response
import com.soulseekplus.core.testutil.testScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistRepositoryTest {

    private class MemoryStore(var items: List<WishlistItem> = emptyList()) : WishlistStore {
        val seen = HashMap<String, List<String>>()
        override fun loadItems() = items
        override fun saveItems(items: List<WishlistItem>) {
            this.items = items
        }

        override fun loadSeenKeys(query: String) = seen[query].orEmpty()
        override fun saveSeenKeys(query: String, keys: List<String>) {
            seen[query] = keys
        }

        override fun deleteSeenKeys(query: String) {
            seen.remove(query)
        }
    }

    private val client = FakeSoulseekClient()
    private val store = MemoryStore()
    private val scope = testScope()
    private fun repo(notifyDelay: Long = 20L, maxSeen: Int = 3000) =
        WishlistRepository(client, store, scope, notifyDelayMillis = notifyDelay, maxSeenKeysPerQuery = maxSeen)

    @After
    fun tearDown() = scope.close()

    @Test
    fun addRemoveToggleAndPersist() {
        val repo = repo()
        assertTrue(repo.add("  flac album "))
        assertFalse(repo.add("flac album"))
        assertFalse(repo.add("   "))
        assertEquals(listOf("flac album"), store.items.map { it.query })
        repo.toggle("flac album")
        assertFalse(repo.items.value.single().enabled)
        assertFalse(store.items.single().enabled)
        repo.remove("flac album")
        assertTrue(repo.items.value.isEmpty())
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun loadsExistingItems() {
        store.items = listOf(WishlistItem("a", 1L), WishlistItem("a", 2L), WishlistItem(" ", 3L), WishlistItem("b", 4L, newHitCount = 2))
        val repo = repo()
        assertEquals(listOf("a", "b"), repo.items.value.map { it.query })
        assertEquals(2, repo.totalNewHits.value)
    }

    @Test
    fun addSearchesImmediatelyWhenLoggedIn() {
        val repo = repo()
        repo.add("x")
        assertEquals(listOf("x" to SearchScope.Wishlist), client.searches.toList())
        assertNotNull(repo.items.value.single().lastSearchedAtMillis)
    }

    @Test
    fun addDoesNotSearchWhenOffline() {
        client.connection.value = ConnectionState.Disconnected()
        val repo = repo()
        repo.add("x")
        assertTrue(client.searches.isEmpty())
        assertFalse(repo.searchNext())
    }

    @Test
    fun searchNextRotatesThroughEnabledItems() {
        client.connection.value = ConnectionState.Disconnected()
        val repo = repo()
        repo.add("a"); repo.add("b"); repo.add("c")
        repo.setEnabled("b", false)
        client.connection.value = ConnectionState.LoggedIn("me", null, false, "")
        repo.searchNext(); repo.searchNext(); repo.searchNext(); repo.searchNext()
        assertEquals(listOf("a", "c", "a", "c"), client.searches.map { it.first })
        assertTrue(client.searches.all { it.second == SearchScope.Wishlist })
    }

    @Test
    fun noEnabledItemsMeansNoSearch() {
        val repo = repo()
        client.searches.clear()
        assertFalse(repo.searchNext())
        repo.add("a")
        repo.setEnabled("a", false)
        client.searches.clear()
        assertFalse(repo.searchNext())
        assertTrue(client.searches.isEmpty())
    }

    @Test
    fun intervalDrivesTimer() {
        val repo = repo()
        client.connection.value = ConnectionState.Disconnected()
        repo.add("a")
        client.connection.value = ConnectionState.LoggedIn("me", null, false, "")
        client.wishlistIntervalSeconds.value = 1
        awaitUntil(3000) { client.searches.isNotEmpty() }
        assertEquals("a", client.searches[0].first)
    }

    @Test
    fun onlyNewHitsAreCountedAndEventIsCoalesced() {
        val repo = repo()
        repo.add("album")
        val token = client.tokens[0]
        val received = ArrayList<WishlistHitEvent>()
        scope.launch { repo.events.collect { received.add(it) } }
        client.emitResponse(response("u1", token, listOf(file("M\\1.flac"), file("M\\2.flac"))))
        client.emitResponse(response("u2", token, listOf(file("M\\1.flac"))))
        client.emitResponse(response("u1", token, listOf(file("M\\1.flac")))) // yinelenen
        assertEquals(3, repo.items.value.single().newHitCount)
        assertEquals(3, repo.totalNewHits.value)
        val shown = repo.results.value["album"]!!
        assertEquals(3, shown.size)
        assertTrue(shown.all { it.isNew })
        awaitUntil { received.isNotEmpty() }
        assertEquals(1, received.size)
        assertEquals(3, received[0].newCount)
        assertEquals(3, received[0].sample.size)
        assertEquals("album", received[0].query)

        repo.clearBadge("album")
        assertEquals(0, repo.totalNewHits.value)
        assertTrue(repo.results.value["album"]!!.none { it.isNew })
    }

    @Test
    fun seenKeysSurviveRestartSoOnlyNewHitsCountAgain() {
        val first = repo()
        first.add("album")
        client.emitResponse(response("u1", client.tokens[0], listOf(file("M\\1.flac"))))
        assertEquals(1, first.items.value.single().newHitCount)
        assertEquals(1, store.seen["album"]!!.size)

        // "Yeniden başlatma": aynı depo, yeni depo nesnesi.
        val client2 = FakeSoulseekClient()
        val second = WishlistRepository(client2, store, scope, notifyDelayMillis = 20L)
        second.searchNow("album")
        client2.emitResponse(response("u1", client2.tokens[0], listOf(file("M\\1.flac"), file("M\\2.flac"))))
        // 1 eski + 1 yeni; rozet önceki sayıya eklenir.
        assertEquals(2, second.items.value.single().newHitCount)
        val shown = second.results.value["album"]!!
        assertEquals(2, shown.size)
        assertEquals(1, shown.count { it.isNew })
        assertTrue(shown.first().isNew)
    }

    @Test
    fun seenKeysAreBounded() {
        val repo = repo(maxSeen = 5)
        repo.add("q")
        client.emitResponse(response("u", client.tokens[0], (1..20).map { file("M\\$it.flac") }))
        assertEquals(5, store.seen["q"]!!.size)
    }

    @Test
    fun newSearchCancelsPreviousTokenAndIgnoresStaleResponses() {
        val repo = repo()
        repo.add("q")
        repo.searchNow("q")
        assertEquals(listOf(client.tokens[0]), client.cancelledTokens.toList())
        client.emitResponse(response("u", client.tokens[0], listOf(file("M\\stale.flac"))))
        assertEquals(0, repo.items.value.single().newHitCount)
        client.emitResponse(response("u", client.tokens[1], listOf(file("M\\fresh.flac"))))
        assertEquals(1, repo.items.value.single().newHitCount)
    }

    @Test
    fun removeCancelsSearchAndForgetsSeen() {
        val repo = repo()
        repo.add("q")
        client.emitResponse(response("u", client.tokens[0], listOf(file("M\\a.flac"))))
        repo.remove("q")
        assertEquals(listOf(client.tokens[0]), client.cancelledTokens.toList())
        assertTrue(store.seen.isEmpty())
        assertTrue(repo.results.value.isEmpty())
    }

    @Test
    fun compactKeyIsStable() {
        assertEquals(WishlistRepository.compactKey("a"), WishlistRepository.compactKey("a"))
        assertTrue(WishlistRepository.compactKey("a") != WishlistRepository.compactKey("b"))
        assertEquals("af63dc4c8601ec8c", WishlistRepository.compactKey("a"))
    }
}
