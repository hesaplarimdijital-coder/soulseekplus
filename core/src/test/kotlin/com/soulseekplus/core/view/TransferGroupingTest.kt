package com.soulseekplus.core.view

import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferGroupingTest {

    private fun t(user: String, path: String, state: TransferState, size: Long = 100, done: Long = 0, speed: Long = 0, added: Long = 0) =
        Transfer(
            id = Transfer.idOf(TransferDirection.DOWNLOAD, user, path), direction = TransferDirection.DOWNLOAD,
            username = user, virtualPath = path, size = size, bytesTransferred = done, state = state,
            speedBytesPerSecond = speed, addedAtMillis = added,
        )

    private val items = listOf(
        t("bob", "M\\A\\1.mp3", TransferState.FINISHED, added = 1),
        t("bob", "M\\A\\2.mp3", TransferState.TRANSFERRING, done = 50, speed = 10, added = 2),
        t("alice", "M\\B\\3.flac", TransferState.QUEUED_REMOTE, size = 300, added = 3),
        t("alice", "M\\B\\4.flac", TransferState.FAILED, size = 300, added = 4),
        t("carol", "X\\5.ogg", TransferState.PAUSED, added = 5),
        t("carol", "X\\6.ogg", TransferState.CANCELLED, added = 6),
    )

    @Test
    fun categories() {
        fun ids(c: TransferCategory) = TransferGrouping.filter(items, c, "").map { it.fileName }
        assertEquals(6, ids(TransferCategory.ALL).size)
        assertEquals(listOf("2.mp3"), ids(TransferCategory.ACTIVE))
        assertEquals(listOf("3.flac", "5.ogg"), ids(TransferCategory.WAITING))
        assertEquals(listOf("1.mp3"), ids(TransferCategory.FINISHED))
        assertEquals(listOf("4.flac", "6.ogg"), ids(TransferCategory.FAILED))
        assertEquals(listOf("3.flac", "4.flac"), TransferGrouping.filter(items, TransferCategory.ALL, "ALICE").map { it.fileName })
        assertEquals(listOf("5.ogg"), TransferGrouping.filter(items, TransferCategory.ALL, "x\\5").map { it.fileName })
    }

    @Test
    fun flatSorting() {
        val byAdded = TransferGrouping.rows(items, TransferGroupMode.NONE, TransferSort(TransferColumn.ADDED, false))
        assertEquals("6.ogg", (byAdded.first() as TransferItemRow).transfer.fileName)
        val bySize = TransferGrouping.rows(items, TransferGroupMode.NONE, TransferSort(TransferColumn.SIZE, false))
        assertEquals(300L, (bySize.first() as TransferItemRow).transfer.size)
        val byUser = TransferGrouping.rows(items, TransferGroupMode.NONE, TransferSort(TransferColumn.USER, true))
        assertEquals("alice", (byUser.first() as TransferItemRow).transfer.username)
    }

    @Test
    fun groupingAggregates() {
        val rows = TransferGrouping.rows(items, TransferGroupMode.USER, TransferSort(TransferColumn.USER, true))
        val headers = rows.filterIsInstance<TransferGroupHeader>()
        assertEquals(listOf("alice", "bob", "carol"), headers.map { it.username })
        val bob = headers[1]
        assertEquals(2, bob.count)
        assertEquals(1, bob.finished)
        assertEquals(1, bob.active)
        assertEquals(200L, bob.totalBytes)
        assertEquals(150L, bob.transferredBytes)
        assertEquals(10L, bob.speed)
        assertEquals(0.75f, bob.progress, 0.001f)
        assertEquals(1, headers[0].failed)
        assertEquals(9, rows.size)
        assertEquals(1, (rows[1] as TransferItemRow).depth)
    }

    @Test
    fun folderGroupingAndCollapse() {
        val closed = TransferGrouping.groupKey(TransferGroupMode.FOLDER, items[0])
        val rows = TransferGrouping.rows(items, TransferGroupMode.FOLDER, TransferSort(TransferColumn.ADDED, true)) { it != closed }
        val headers = rows.filterIsInstance<TransferGroupHeader>()
        assertEquals(3, headers.size)
        assertEquals(listOf("M\\A", "M\\B", "X"), headers.map { it.folder })
        assertTrue(!headers[0].expanded)
        assertEquals(2, TransferGrouping.membersOf(headers[0], items).size)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(7, rows.size)
    }
}
