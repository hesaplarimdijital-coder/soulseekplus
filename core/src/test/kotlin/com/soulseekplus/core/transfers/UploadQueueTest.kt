package com.soulseekplus.core.transfers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQueueTest {

    private val nobody: (String) -> Boolean = { false }

    private fun UploadQueue.next(fifo: Boolean, privileged: (String) -> Boolean = nobody): UploadQueue.Entry? =
        selectCandidate(fifo, privileged)

    /** Round Robin: A (2 dosya), B, C; sıra sayaca göre, kullanıcı başına tek aktif yükleme. */
    @Test
    fun roundRobinServesEachWaitingUserOnceBeforeRepeating() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 10)
        q.enqueue("B", "b1", 10)
        q.enqueue("A", "a2", 10)
        q.enqueue("C", "c1", 10)

        val first = q.next(fifo = false)!!
        assertEquals("a1", first.path)
        assertTrue(q.activate(first))
        // A aktifken ikinci dosyası seçilmez.
        val second = q.next(fifo = false)!!
        assertEquals("b1", second.path)
        q.activate(second)
        val third = q.next(fifo = false)!!
        assertEquals("c1", third.path)
        q.activate(third)
        assertNull(q.next(fifo = false))
        assertEquals(3, q.activeUserCount)

        // A'nın yüklemesi bitince kalan dosyası yeniden seçilebilir hâle gelir.
        q.deactivate("A", "a1")
        assertEquals("a2", q.next(fifo = false)!!.path)
    }

    @Test
    fun roundRobinCounterIsKeptWhenUserAddsMoreFiles() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("B", "b1", 1)
        // A yeni dosya ekleyince geri itilmez.
        q.enqueue("A", "a2", 1)
        assertEquals("A", q.next(fifo = false)!!.username)
        // Sıradaki kullanıcı bittikten sonra sona geçer.
        val a1 = q.next(fifo = false)!!
        q.activate(a1)
        q.deactivate("A", "a1")
        assertEquals("B", q.next(fifo = false)!!.username)
    }

    @Test
    fun fifoFollowsGlobalOrderAndSkipsActiveUsers() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("B", "b1", 1)
        q.enqueue("A", "a2", 1)
        val e1 = q.next(fifo = true)!!
        assertEquals("a1", e1.path)
        q.activate(e1)
        // A etkin: sıradaki a2 atlanır.
        assertEquals("b1", q.next(fifo = true)!!.path)
        q.activate(q.next(fifo = true)!!)
        assertNull(q.next(fifo = true))
        q.deactivate("A", "a1")
        assertEquals("a2", q.next(fifo = true)!!.path)
    }

    @Test
    fun privilegedUsersAreServedFirstInBothModes() {
        for (fifo in listOf(true, false)) {
            val q = UploadQueue()
            q.enqueue("A", "a1", 1)
            q.enqueue("B", "b1", 1)
            q.enqueue("C", "c1", 1)
            val vip: (String) -> Boolean = { it == "C" }
            val chosen = q.next(fifo, vip)!!
            assertEquals("fifo=$fifo", "c1", chosen.path)
            q.activate(chosen)
            // Ayrıcalıklı kuyruk boşalınca diğerleri sırayla alınır.
            assertEquals("a1", q.next(fifo, vip)!!.path)
        }
    }

    @Test
    fun slotsAreCountedPerDistinctUser() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("A", "a2", 1)
        q.activate(q.next(false)!!)
        assertEquals(1, q.activeUserCount)
        assertEquals(1, q.activeCount)
        assertTrue(q.hasActive("A"))
        assertTrue(q.isActive("A", "a1"))
        assertTrue(q.isQueued("A", "a2"))
    }

    @Test
    fun duplicatesAreRejectedAndRemovalCleansUp() {
        val q = UploadQueue()
        assertTrue(q.enqueue("A", "a1", 100))
        assertFalse(q.enqueue("A", "a1", 100))
        assertTrue(q.enqueue("A", "a2", 50))
        assertEquals(2, q.queuedCount("A"))
        assertEquals(150L, q.queuedBytes("A"))
        assertTrue(q.remove("A", "a1"))
        assertFalse(q.remove("A", "a1"))
        assertEquals(50L, q.queuedBytes("A"))
        val removed = q.removeUser("A")
        assertEquals(listOf("a2"), removed.map { it.path })
        assertEquals(0, q.queuedCount)
        assertNull(q.next(false))
        assertEquals(0L, q.queuedBytes("A"))
    }

    @Test
    fun activeEntryCannotBeQueuedAgain() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.activate(q.next(false)!!)
        assertFalse(q.enqueue("A", "a1", 1))
        q.deactivate("A", "a1")
        assertTrue(q.enqueue("A", "a1", 1))
    }

    @Test
    fun reportedQueueSizeDependsOnPrivilege() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("B", "b1", 1)
        q.enqueue("B", "b2", 1)
        val vip: (String) -> Boolean = { it == "B" }
        assertEquals(3, q.queueSizeFor(false, vip))
        assertEquals(2, q.queueSizeFor(true, vip))
    }

    @Test
    fun fifoPositionsAreOneBasedAndPrivilegedUsersSeeTheirOwnLine() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("B", "b1", 1)
        q.enqueue("A", "a2", 1)
        assertEquals(1, q.position("A", "a1", fifo = true, isPrivileged = nobody))
        assertEquals(2, q.position("B", "b1", fifo = true, isPrivileged = nobody))
        assertEquals(3, q.position("A", "a2", fifo = true, isPrivileged = nobody))
        assertNull(q.position("A", "zzz", fifo = true, isPrivileged = nobody))
        val vip: (String) -> Boolean = { it == "B" }
        assertEquals(1, q.position("B", "b1", fifo = true, isPrivileged = vip))
        val all = q.positions(fifo = true, isPrivileged = nobody)
        assertEquals(3, all[UploadQueue.key("A", "a2")])
    }

    @Test
    fun roundRobinPositionGrowsWithOwnQueueAndRespectsPrivilege() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.enqueue("A", "a2", 1)
        q.enqueue("B", "b1", 1)
        val first = q.position("A", "a1", fifo = false, isPrivileged = nobody)!!
        val second = q.position("A", "a2", fifo = false, isPrivileged = nobody)!!
        assertEquals(first + 1, second)
        val vip: (String) -> Boolean = { it == "B" }
        val vipPos = q.position("B", "b1", fifo = false, isPrivileged = vip)!!
        val plainPos = q.position("A", "a1", fifo = false, isPrivileged = vip)!!
        assertTrue(vipPos < plainPos)
        val all = q.positions(fifo = false, isPrivileged = nobody)
        assertEquals(first, all[UploadQueue.key("A", "a1")])
        assertEquals(second, all[UploadQueue.key("A", "a2")])
    }

    @Test
    fun clearResetsEverything() {
        val q = UploadQueue()
        q.enqueue("A", "a1", 1)
        q.activate(q.next(false)!!)
        q.enqueue("B", "b1", 1)
        q.clear()
        assertEquals(0, q.queuedCount)
        assertEquals(0, q.activeUserCount)
        assertNull(q.next(false))
    }
}
