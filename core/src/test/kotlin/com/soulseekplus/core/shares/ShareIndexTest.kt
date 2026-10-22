package com.soulseekplus.core.shares

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.UploadResolution
import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.ShareLevel
import com.soulseekplus.core.model.ShareStats
import com.soulseekplus.core.model.UserGroup
import com.soulseekplus.core.model.UserLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ShareIndexTest {

    private fun f(name: String, size: Long = 100, bitrate: Int? = null) =
        ShareFile(name, size, FileAttributes(bitrate = bitrate), ref = "doc:$name")

    private val opener = UploadOpener { path, _ ->
        object : UploadSource {
            override val size = path.length.toLong()
            override fun open(offset: Long): InputStream = ByteArrayInputStream(path.toByteArray().copyOfRange(offset.toInt(), path.length))
        }
    }

    private val music = ShareRoot(
        "r1", "Music", ShareLevel.PUBLIC, null,
        listOf(
            ShareFolder("Daft Punk\\Discovery", listOf(f("01 One More Time.flac", bitrate = 900), f("02 Aerodynamic.flac"), f("cover.jpg"))),
            ShareFolder("Boards of Canada", listOf(f("Roygbiv.mp3", bitrate = 320), f("Dayvan Cowboy (live).mp3"))),
            ShareFolder("", listOf(f("readme.txt"))),
        ),
    )
    private val secret = ShareRoot("r2", "Buddy Stuff", ShareLevel.BUDDIES, null, listOf(ShareFolder("Rare", listOf(f("Rare Track Hello.flac")))))
    private val club = ShareRoot("r3", "Club", ShareLevel.GROUP, "vip", listOf(ShareFolder("Mixes", listOf(f("Hello Mix.mp3")))))

    private fun index(lists: UserLists = UserLists.EMPTY, vararg roots: ShareRoot): ShareIndex {
        val idx = ShareIndex(Logger.NONE)
        idx.update(ShareSnapshot(roots.toList(), opener), lists)
        return idx
    }

    private val lists = UserLists(
        buddies = setOf("buddy"),
        banned = setOf("badguy"),
        groups = listOf(UserGroup("vip", setOf("vipuser"))),
    )

    private fun names(files: List<com.soulseekplus.core.model.SharedFile>) = files.map { it.name }

    @Test
    fun virtualPathsUseAliasAndBackslashes() {
        val idx = index(UserLists.EMPTY, music)
        val r = idx.search("aerodynamic", "anyone", 100)
        assertEquals("Music\\Daft Punk\\Discovery\\02 Aerodynamic.flac", r.files.single().path)
        // kök dosyası
        assertEquals("Music\\readme.txt", idx.search("readme", "anyone", 100).files.single().path)
    }

    @Test
    fun statsCountAllLevels() {
        val idx = index(UserLists.EMPTY, music, secret, club)
        assertEquals(ShareStats(folders = 5, files = 8), idx.stats.value)
    }

    @Test
    fun allWordsMustMatchExactly() {
        val idx = index(UserLists.EMPTY, music)
        assertEquals(1, idx.search("daft one time", "x", 100).files.size) // klasör + dosya adı kelimeleri
        assertTrue(idx.search("daft homework", "x", 100).isEmpty)
        assertTrue(idx.search("dis", "x", 100).isEmpty) // kısmi kelime yok
        assertEquals(2, idx.search("discovery flac", "x", 100).files.size)
    }

    @Test
    fun caseAndPunctuationInsensitive() {
        val idx = index(UserLists.EMPTY, music)
        assertEquals(1, idx.search("DAYVAN-cowboy (LIVE)", "x", 100).files.size)
        assertEquals(1, idx.search("one.more_time", "x", 100).files.size)
    }

    @Test
    fun minusExcludesWords() {
        val idx = index(UserLists.EMPTY, music)
        assertEquals(listOf("Roygbiv.mp3"), names(idx.search("canada mp3 -live", "x", 100).files))
        assertEquals(1, idx.search("canada -live", "x", 100).files.size)
        // yalnızca hariç tutma: sonuç yok
        assertTrue(idx.search("-live", "x", 100).isEmpty)
    }

    @Test
    fun starMatchesSuffixOfIndexedWords() {
        val idx = index(UserLists.EMPTY, music)
        assertEquals(1, idx.search("discovery *namic", "x", 100).files.size)
        assertEquals(1, idx.search("discovery *odynamic", "x", 100).files.size)
        assertTrue(idx.search("discovery *zzz", "x", 100).isEmpty)
        // yalnızca kısmi kelime: en az bir tam kelime gerekir
        assertTrue(idx.search("*namic", "x", 100).isEmpty)
    }

    @Test
    fun resultLimitCountsLockedToo() {
        val idx = index(UserLists.EMPTY, music, secret)
        assertEquals(2, idx.search("flac", "x", 2).let { it.files.size + it.locked.size })
        assertEquals(0, idx.search("flac", "x", 0).files.size)
    }

    @Test
    fun bannedUserGetsNothing() {
        val idx = index(lists, music)
        assertTrue(idx.search("flac", "badguy", 100).isEmpty)
        assertTrue(idx.browse("badguy").folders.isEmpty())
        assertTrue(idx.folderContents("badguy", "Music\\Boards of Canada").isEmpty())
        val up = idx.resolveUpload("badguy", "Music\\readme.txt")
        assertEquals(UploadResolution.Denied("Banned"), up)
    }

    @Test
    fun threeTierPermissionsInSearch() {
        val idx = index(lists, music, secret, club)
        val stranger = idx.search("hello", "stranger", 100)
        assertTrue(stranger.files.isEmpty())
        assertEquals(setOf("Rare Track Hello.flac", "Hello Mix.mp3"), names(stranger.locked).toSet())

        val buddy = idx.search("hello", "buddy", 100)
        assertEquals(listOf("Rare Track Hello.flac"), names(buddy.files))
        assertEquals(listOf("Hello Mix.mp3"), names(buddy.locked))

        val vip = idx.search("hello", "vipuser", 100)
        assertEquals(listOf("Hello Mix.mp3"), names(vip.files))
        assertEquals(listOf("Rare Track Hello.flac"), names(vip.locked))
    }

    @Test
    fun revealRestrictedOffHidesLockedLists() {
        val idx = index(lists, music, secret)
        idx.revealRestricted = false
        assertTrue(idx.search("hello", "stranger", 100).isEmpty)
        assertTrue(idx.browse("stranger").lockedFolders.isEmpty())
    }

    @Test
    fun listChangesApplyWithoutRebuildingIndex() {
        val snapshot = ShareSnapshot(listOf(music, secret), opener)
        val idx = ShareIndex(Logger.NONE)
        idx.update(snapshot, UserLists.EMPTY)
        assertTrue(idx.search("hello", "newbuddy", 10).files.isEmpty())
        idx.update(snapshot, UserLists(buddies = setOf("newbuddy")))
        assertEquals(1, idx.search("hello", "newbuddy", 10).files.size)
    }

    @Test
    fun browseSplitsOpenAndLockedFolders() {
        val idx = index(lists, music, secret, club)
        val stranger = idx.browse("stranger")
        assertEquals(listOf("Music", "Music\\Boards of Canada", "Music\\Daft Punk\\Discovery"), stranger.folders.map { it.path })
        assertEquals(setOf("Buddy Stuff\\Rare", "Club\\Mixes"), stranger.lockedFolders.map { it.path }.toSet())
        assertEquals(stranger.fileCount, 6 + 2)
        val buddy = idx.browse("buddy")
        assertEquals(4, buddy.folders.size)
        assertEquals(listOf("Club\\Mixes"), buddy.lockedFolders.map { it.path })
        // dosya adına göre sıralı
        assertEquals(listOf("01 One More Time.flac", "02 Aerodynamic.flac", "cover.jpg"), idx.browse("x").folders.last().files.map { it.name })
    }

    @Test
    fun folderContentsIncludesSubfoldersAndRespectsPermission() {
        val idx = index(lists, music, secret)
        assertEquals(1, idx.folderContents("x", "Music\\Daft Punk\\Discovery").size)
        assertEquals(3, idx.folderContents("x", "music\\daft punk\\discovery\\").flatMap { it.files }.size)
        assertEquals(listOf("Music", "Music\\Boards of Canada", "Music\\Daft Punk\\Discovery"), idx.folderContents("x", "Music").map { it.path })
        assertTrue(idx.folderContents("x", "Buddy Stuff\\Rare").isEmpty())
        assertEquals(1, idx.folderContents("buddy", "Buddy Stuff/Rare").size)
        assertTrue(idx.folderContents("x", "Nope").isEmpty())
        assertTrue(idx.folderContents("x", "").isEmpty())
        assertTrue(idx.folderContents("x", "Music\\..\\Buddy Stuff").isEmpty())
    }

    @Test
    fun resolveUploadNormalizesAndChecksPermission() {
        val idx = index(lists, music, secret)
        val ok = idx.resolveUpload("anyone", "Music\\Boards of Canada\\Roygbiv.mp3")
        assertTrue(ok is UploadResolution.Allowed)
        ok as UploadResolution.Allowed
        assertEquals("Roygbiv.mp3", ok.file.name)
        assertEquals(ok.file.path.length.toLong(), ok.source.size)
        assertEquals(ok.file.path.substring(3), ok.source.open(3).readBytes().toString(Charsets.UTF_8))

        assertTrue(idx.resolveUpload("anyone", "music/boards of canada//roygbiv.MP3") is UploadResolution.Allowed)
        assertEquals(UploadResolution.NotShared, idx.resolveUpload("anyone", "Music\\nothing.mp3"))
        assertEquals(UploadResolution.NotShared, idx.resolveUpload("anyone", "Music\\..\\Buddy Stuff\\Rare\\Rare Track Hello.flac"))
        assertEquals(UploadResolution.Denied("File not shared."), idx.resolveUpload("anyone", "Buddy Stuff\\Rare\\Rare Track Hello.flac"))
        assertTrue(idx.resolveUpload("buddy", "Buddy Stuff\\Rare\\Rare Track Hello.flac") is UploadResolution.Allowed)
    }

    @Test
    fun excludedPhrasesFilterResults() {
        val idx = index(UserLists.EMPTY, music)
        idx.excludedPhrases = listOf("dayvan")
        assertTrue(idx.search("canada live", "x", 10).isEmpty)
        assertEquals(1, idx.search("canada roygbiv", "x", 10).files.size)
    }

    @Test
    fun aliasSanitizingAndEmptySnapshot() {
        assertEquals("a_b", ShareSnapshot.sanitizeAlias(" a/b "))
        assertEquals("Shared", ShareSnapshot.sanitizeAlias("  "))
        assertEquals("__", ShareSnapshot.sanitizeAlias(".."))
        val idx = ShareIndex(Logger.NONE)
        assertTrue(idx.search("x", "y", 10).isEmpty)
        assertEquals(ShareStats.EMPTY, idx.stats.value)
        assertTrue(idx.browse("y").folders.isEmpty())
        val weird = ShareRoot("r", "My/Music", folders = listOf(ShareFolder("a", listOf(f("x.mp3")))))
        val i2 = index(UserLists.EMPTY, weird)
        assertEquals("My_Music\\a\\x.mp3", i2.search("x", "q", 5).files.single().path)
    }

    @Test
    fun searchWordsSplitUnicodePunctuation() {
        assertEquals(listOf("don", "t", "stop"), SearchWords.split("don't stop"))
        assertEquals(listOf("a", "b"), SearchWords.split("a—b"))
        assertEquals(listOf("café", "münchen"), SearchWords.split("café, münchen!"))
    }

    @Test
    fun largeIndexStaysFast() {
        val folders = (0 until 2000).map { d ->
            ShareFolder("Artist $d\\Album ${d % 50}", (0 until 50).map { n -> f("Track ${n + 1} song${n % 7} part$d.mp3", bitrate = 192) })
        }
        val root = ShareRoot("big", "Big", folders = folders)
        val started = System.nanoTime()
        val idx = index(UserLists.EMPTY, root)
        val buildMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(100_000, idx.stats.value.files)
        val t0 = System.nanoTime()
        val r = idx.search("mp3 song3", "x", 300)
        val searchMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(300, r.files.size)
        assertTrue("build took $buildMs ms", buildMs < 20_000)
        assertTrue("search took $searchMs ms", searchMs < 1_000)
    }
}
