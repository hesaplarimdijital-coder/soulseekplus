package com.soulseekplus.core.view

import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTreeTest {

    private fun f(path: String, size: Long = 10) = SharedFile(path, size)
    private fun folder(path: String, vararg names: String) = SharedFolder(path, names.map { f("$path\\$it") })

    private val tree = BrowseTree(
        BrowseResult(
            "alice",
            folders = listOf(
                folder("Music\\Artist B\\Album", "02.flac", "01.flac"),
                folder("Music\\Artist A\\Album 1", "x.mp3"),
                folder("Music", "readme.txt"),
                folder("Docs", "a.pdf"),
            ),
            lockedFolders = listOf(folder("Secret\\Stuff", "s.flac"), folder("Music\\Artist A\\Album 1", "extra.mp3")),
        ),
    )

    @Test
    fun buildsHierarchyWithSyntheticParents() {
        assertEquals(listOf("Docs", "Music", "Secret"), tree.roots.map { it.name })
        val music = tree.node("Music")!!
        assertEquals(listOf("Artist A", "Artist B"), music.children.map { it.name })
        assertEquals(listOf("readme.txt"), music.files.map { it.name })
        assertEquals(5, music.totalFiles)
        assertEquals(listOf("Stuff"), tree.node("Secret")!!.children.map { it.name })
        assertTrue(tree.node("Secret")!!.files.isEmpty())
        assertEquals(8, tree.folderCount)
        assertEquals("alice", tree.username)
    }

    @Test
    fun filesSortedAndLockedFlags() {
        assertEquals(listOf("01.flac", "02.flac"), tree.node("Music\\Artist B\\Album")!!.files.map { it.name })
        assertTrue(tree.node("Secret\\Stuff")!!.locked)
        assertFalse(tree.node("Secret")!!.locked) // yalnızca kilitli çocuk taşıyan ara klasör açıktır... dosyası yok
        assertTrue(tree.isLocked("Secret\\Stuff"))
        assertTrue(tree.isLocked("Secret\\Stuff\\Deeper"))
        assertFalse(tree.isLocked("Music"))
        // hem açık hem kilitli içerik taşıyan klasör açık sayılır, dosyalar birleşir
        val mixed = tree.node("Music\\Artist A\\Album 1")!!
        assertFalse(mixed.locked)
        assertEquals(listOf("extra.mp3", "x.mp3"), mixed.files.map { it.name })
    }

    @Test
    fun recursiveFilesAndLookup() {
        assertEquals(5, tree.filesRecursive("Music").size)
        assertTrue(tree.filesRecursive("Nope").isEmpty())
        assertNotNull(tree.node("\\Music\\"))
        assertNull(tree.node("music")) // büyük/küçük harf duyarlı
    }

    @Test
    fun searchMatchesFolderAndFileWords() {
        val r = tree.search("artist b flac")
        assertEquals(listOf("01.flac", "02.flac"), r.map { it.file.name })
        assertEquals(1, tree.search("SECRET s.flac").size)
        assertTrue(tree.search("SECRET s.flac").single().locked)
        assertTrue(tree.search("   ").isEmpty())
        assertEquals(2, tree.search("flac", limit = 2).size)
    }

    @Test
    fun absurdlyDeepRemotePathDoesNotOverflowTheStack() {
        val deep = (1..50_000).joinToString("\\") { "d" }
        val t = BrowseTree(BrowseResult("evil", folders = listOf(folder(deep, "x.mp3"), folder("Ok", "y.mp3"))))
        assertEquals(listOf("Ok"), t.roots.map { it.path })
        assertNotNull(t.node("Ok"))
    }
}
