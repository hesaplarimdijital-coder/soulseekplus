package com.soulseekplus.core.search

import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.testutil.hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGroupingTest {

    private val hits = listOf(
        hit(user = "bob", path = "Music\\A\\Album1\\02.mp3", size = 2_000, speed = 10),
        hit(user = "alice", path = "Music\\A\\Album1\\01.flac", size = 30_000, speed = 500, bitrate = null),
        hit(user = "alice", path = "Music\\A\\Album1\\02.flac", size = 40_000, speed = 500, bitrate = null),
        hit(user = "alice", path = "Music\\B\\Album2\\01.mp3", size = 5_000, speed = 500, bitrate = 192),
    )

    @Test
    fun flatSortsBySpeedDescendingByDefault() {
        val rows = SearchGrouping.rows(hits, GroupMode.FLAT)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it is FileResultRow && it.depth == 0 })
        assertEquals("bob", (rows.last() as FileResultRow).hit.username)
    }

    @Test
    fun userGroupingProducesHeadersAndAggregates() {
        val rows = SearchGrouping.rows(hits, GroupMode.USER, ResultSort(ResultColumn.USER, true))
        assertEquals(6, rows.size)
        val h0 = rows[0] as GroupHeaderRow
        assertEquals("alice", h0.username)
        assertEquals(3, h0.fileCount)
        assertEquals(75_000L, h0.totalSize)
        assertEquals(192, h0.bestBitrate)
        assertTrue(h0.hasLossless)
        assertEquals(1, (rows[1] as FileResultRow).depth)
        assertEquals("bob", (rows[4] as GroupHeaderRow).username)
    }

    @Test
    fun folderGroupingGroupsPerUserAndFolder() {
        val rows = SearchGrouping.rows(hits, GroupMode.FOLDER, ResultSort(ResultColumn.FOLDER, true))
        val headers = rows.filterIsInstance<GroupHeaderRow>()
        assertEquals(3, headers.size)
        assertEquals(listOf("Music\\A\\Album1", "Music\\A\\Album1", "Music\\B\\Album2"), headers.map { it.folderPath })
        assertEquals(listOf("alice", "bob", "alice"), headers.map { it.username })
    }

    @Test
    fun collapsedGroupsHideChildrenButKeepTotals() {
        val closed = SearchGrouping.groupKey(GroupKind.USER, hits[1])
        val rows = SearchGrouping.rows(hits, GroupMode.USER, ResultSort(ResultColumn.USER, true)) { it != closed }
        assertEquals(3, rows.size)
        val header = rows[0] as GroupHeaderRow
        assertEquals(3, header.fileCount)
        assertEquals(false, header.expanded)
        assertEquals(3, SearchGrouping.membersOf(header, hits).size)
    }

    @Test
    fun groupsOrderedBySizeUsingTotals() {
        val rows = SearchGrouping.rows(hits, GroupMode.USER, ResultSort(ResultColumn.SIZE, false)) { false }
        val names = rows.filterIsInstance<GroupHeaderRow>().map { it.username }
        assertEquals(listOf("alice", "bob"), names)
        val asc = SearchGrouping.rows(hits, GroupMode.USER, ResultSort(ResultColumn.SIZE, true)) { false }
        assertEquals(listOf("bob", "alice"), asc.filterIsInstance<GroupHeaderRow>().map { it.username })
    }

    @Test
    fun rowKeysAreUnique() {
        val rows = SearchGrouping.rows(hits, GroupMode.FOLDER)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
