package com.soulseekplus.core.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlskUriTest {

    @Test
    fun parsesUserFileAndFolder() {
        assertEquals(SlskLink.User("alice"), SlskUri.parse("slsk://alice"))
        assertEquals(SlskLink.User("alice"), SlskUri.parse("slsk://alice/"))
        assertEquals(SlskLink.File("alice", "Music\\Album\\01 Song.mp3"), SlskUri.parse("slsk://alice/Music/Album/01%20Song.mp3"))
        assertEquals(SlskLink.Folder("alice", "Music\\Album"), SlskUri.parse("slsk://alice/Music/Album/"))
        assertEquals(SlskLink.File("alice", "x"), SlskUri.parse("  SLSK://alice/x "))
    }

    @Test
    fun decodesUtf8AndKeepsPlus() {
        assertEquals(SlskLink.File("müller", "Café\\a+b.mp3"), SlskUri.parse("slsk://m%C3%BCller/Caf%C3%A9/a+b.mp3"))
        assertEquals(SlskLink.File("u", "100%.mp3"), SlskUri.parse("slsk://u/100%.mp3")) // geçersiz % olduğu gibi
    }

    @Test
    fun rejectsInvalid() {
        assertNull(SlskUri.parse("http://alice"))
        assertNull(SlskUri.parse("slsk://"))
        assertNull(SlskUri.parse("slsk:///path"))
        assertNull(SlskUri.parse("slsk://alice/a/../b"))
        assertNull(SlskUri.parse("alice/x"))
    }

    @Test
    fun buildsNicotineCompatibleLinks() {
        assertEquals("slsk://alice", SlskUri.forUser("alice"))
        assertEquals("slsk://alice/Music/Album/01%20Song.mp3", SlskUri.forFile("alice", "Music\\Album\\01 Song.mp3"))
        assertEquals("slsk://alice/Music/Album/", SlskUri.forFolder("alice", "Music\\Album"))
        assertEquals("slsk://m%C3%BCller/Caf%C3%A9/a%2Bb.mp3", SlskUri.forFile("müller", "Café\\a+b.mp3"))
        assertEquals("slsk://alice", SlskUri.forFile("alice", ""))
    }

    @Test
    fun roundTrips() {
        val links = listOf(
            SlskLink.User("a b"),
            SlskLink.File("Üser", "Música\\Ünïcode ♪\\x #1 [live].flac"),
            SlskLink.Folder("bob", "Root\\Sub folder\\Deep"),
        )
        for (link in links) assertEquals(link, SlskUri.parse(link.toUriString()))
    }

    @Test
    fun findsLinksInText() {
        val text = "grab slsk://alice/Music/Album/ and (slsk://bob/x%20y.mp3), thanks."
        val found = SlskUri.findLinks(text)
        assertEquals(2, found.size)
        assertEquals("slsk://alice/Music/Album/", text.substring(found[0].first))
        assertEquals(SlskLink.Folder("alice", "Music\\Album"), found[0].second)
        assertEquals("slsk://bob/x%20y.mp3", text.substring(found[1].first))
        assertEquals(SlskLink.File("bob", "x y.mp3"), found[1].second)
    }
}
