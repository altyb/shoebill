package dev.altayeb.shoebill.library

import dev.altayeb.shoebill.library.logic.HostKeyVerdict
import dev.altayeb.shoebill.library.logic.RemoteEntry
import dev.altayeb.shoebill.library.logic.RemoteLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogicTest {
    private val movies = listOf("remote.php", "dav", "files", "me", "Movies")

    private fun propfind(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""

    private fun folder(href: String) = "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
    private fun file(href: String, size: Long) = "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getcontentlength>$size</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>"

    @Test fun safeNames() {
        assertTrue(RemoteLogic.isSafeName("film.mkv"))
        assertTrue(RemoteLogic.isSafeName("الفيلم الأول.mp4"))
        assertTrue(RemoteLogic.isSafeName("..hidden"))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "../x", "x\u0000y", "x\ny", "x".repeat(256))) {
            assertTrue("should refuse [$bad]", !RemoteLogic.isSafeName(bad))
        }
    }

    @Test fun cleanDropsUnsafeAndSortsFoldersFirst() {
        val out = RemoteLogic.clean(listOf(
            RemoteEntry("..", true, 0), RemoteEntry("b.mkv", false, 5), RemoteEntry("A", true, 0),
            RemoteEntry("a.mp4", false, 1), RemoteEntry("x/y", false, 1), RemoteEntry("b.mkv", false, 5),
        ))
        assertEquals(listOf("A", "a.mp4", "b.mkv"), out.map { it.name })
    }

    @Test fun segmentsRefuseTraversal() {
        assertEquals(listOf("a", "b"), RemoteLogic.segments("a//b/"))
        assertNull(RemoteLogic.segments("a/../b"))
        assertEquals("/data/Movies/x.mp4", RemoteLogic.remotePath("/data/", "Movies/x.mp4"))
        assertEquals("/", RemoteLogic.remotePath("", ""))
        assertNull(RemoteLogic.remotePath("", "../etc/passwd"))
        assertNull(RemoteLogic.remotePath("data/..", "x"))
    }

    @Test fun percentDecodeArabicAndEncodedSlash() {
        assertEquals("ال", RemoteLogic.percentDecode("%D8%A7%D9%84"))
        assertEquals("a/b.mkv", RemoteLogic.percentDecode("a%2Fb.mkv"))
        assertEquals("100%x", RemoteLogic.percentDecode("100%x"))
        assertEquals(listOf("dav", "..", "x"), RemoteLogic.hrefSegments("https://h/dav/%2E%2E/x"))
    }

    @Test fun encodeRoundTripsAndKeepsNamesWhole() {
        for (name in listOf("a b#?.mkv", "100%.mkv", "فيلم 1.mp4", "a/b", "ü+&=")) {
            assertEquals(name, RemoteLogic.percentDecode(RemoteLogic.encodeSegment(name)))
        }
        assertEquals("a%20b%23%3F.mkv", RemoteLogic.encodeSegment("a b#?.mkv"))
        assertEquals("-._~AZaz09", RemoteLogic.encodeSegment("-._~AZaz09"))
    }

    @Test fun webDavAddress() {
        assertEquals("https://cloud.example.com/remote.php/dav/files/me", RemoteLogic.webDavBase(" https://cloud.example.com/remote.php/dav/files/me/ "))
        assertEquals("http://192.168.1.5:8080/dav", RemoteLogic.webDavBase("http://192.168.1.5:8080/dav"))
        for (bad in listOf("", "ftp://host/dav", "https:///dav", "https://me:secret@host/dav", "https://host/my folder", "https://هوست/dav", "host/dav")) {
            assertNull("should refuse [$bad]", RemoteLogic.webDavBase(bad))
        }
    }

    @Test fun webDavListingReadsNamesFoldersAndSizes() {
        val xml = propfind(
            folder("/remote.php/dav/files/me/Movies/"),
            folder("/remote.php/dav/files/me/Movies/Sub%20Folder/"),
            file("/remote.php/dav/files/me/Movies/%D8%A7%D9%84%D9%81%D9%8A%D9%84%D9%85.mp4", 1234),
        )
        val out = RemoteLogic.parseWebDavListing(xml, movies)
        assertEquals(listOf(RemoteEntry("Sub Folder", true, 0), RemoteEntry("الفيلم.mp4", false, 1234)), out)
    }

    @Test fun webDavListingDropsTraversalNames() {
        val xml = propfind(
            folder("/remote.php/dav/files/me/Movies/%2E%2E/"),
            folder("/remote.php/dav/files/me/Movies/..%2F..%2Fetc/"),
            file("/remote.php/dav/files/me/Movies/a%2Fb.mkv", 5),
            file("/remote.php/dav/files/me/Movies/ok.mkv", 5),
            file("https://evil.example/remote.php/dav/files/me/Movies/..", 5),
        )
        assertEquals(listOf("ok.mkv"), RemoteLogic.parseWebDavListing(xml, movies).map { it.name })
    }

    @Test fun webDavListingRefusesDoctypeAndEntities() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        assertThrows(IllegalArgumentException::class.java) { RemoteLogic.parseWebDavListing(xxe, movies) }
        val bomb = """<?xml version="1.0"?><!DOCTYPE l [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]><d:multistatus xmlns:d="DAV:">&b;</d:multistatus>"""
        assertThrows(IllegalArgumentException::class.java) { RemoteLogic.parseWebDavListing(bomb, movies) }
        val lowercase = """<!doctype d><d:multistatus xmlns:d="DAV:"/>"""
        assertThrows(IllegalArgumentException::class.java) { RemoteLogic.parseWebDavListing(lowercase, movies) }
    }

    @Test fun webDavListingRefusesBrokenXml() {
        assertThrows(Exception::class.java) { RemoteLogic.parseWebDavListing("<d:multistatus xmlns:d=\"DAV:\"><d:response>", movies) }
        assertThrows(Exception::class.java) { RemoteLogic.parseWebDavListing("not xml at all", movies) }
    }

    @Test fun webDavListingEmptyFolderIsEmpty() {
        assertEquals(emptyList<RemoteEntry>(), RemoteLogic.parseWebDavListing(propfind(folder("/remote.php/dav/files/me/Movies/")), movies))
    }

    @Test fun hostKeyFirstSeenSameAndChanged() {
        assertEquals(HostKeyVerdict.New, RemoteLogic.hostKey(null, "SHA256:aaa"))
        assertEquals(HostKeyVerdict.Same, RemoteLogic.hostKey("SHA256:aaa", "SHA256:aaa"))
        assertEquals(HostKeyVerdict.Changed("SHA256:aaa"), RemoteLogic.hostKey("SHA256:aaa", "SHA256:bbb"))
        assertThrows(IllegalArgumentException::class.java) { RemoteLogic.hostKey(null, " ") }
    }
}
