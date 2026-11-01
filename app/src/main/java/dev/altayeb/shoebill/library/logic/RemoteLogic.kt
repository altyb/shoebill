package dev.altayeb.shoebill.library.logic

import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Saved-server kinds. SMB servers were the only kind before; their rows have no kind and read as SMB. */
object RemoteKind {
    const val SMB = "smb"
    const val WEBDAV = "webdav"
    const val SFTP = "sftp"
    const val FTP = "ftp"
}

data class RemoteEntry(val name: String, val dir: Boolean, val size: Long)

sealed interface HostKeyVerdict {
    /** Never seen this server: remember the key and continue. */
    data object New : HostKeyVerdict
    data object Same : HostKeyVerdict
    /** The key differs from the one saved on first connect. Refuse. */
    data class Changed(val saved: String) : HostKeyVerdict
}

/**
 * Everything a remote server can make us do with its names and replies, kept free of Android so the JVM tests
 * cover it. Paths are "/"-separated and relative to the server root; names never contain a slash.
 */
object RemoteLogic {
    private const val MAX_NAME = 255

    /** A name the app will list and play. Rejects "", ".", "..", separators and control characters. */
    fun isSafeName(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_NAME && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it < ' ' || it == '\u007f' }

    /** Drops unsafe names, then sorts folders first, then by name. */
    fun clean(entries: List<RemoteEntry>): List<RemoteEntry> = entries
        .filter { isSafeName(it.name) }
        .distinctBy { it.name }
        .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))

    /** Path to segments. Returns null if any segment is unsafe, so the caller never reaches outside the root. */
    fun segments(path: String): List<String>? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.takeIf { parts.all(::isSafeName) }
    }

    /** Absolute server path for a root folder plus a relative path, or null if either part is unsafe. */
    fun remotePath(root: String, relative: String): String? {
        val r = segments(root) ?: return null
        val p = segments(relative) ?: return null
        return "/" + (r + p).joinToString("/")
    }

    fun percentDecode(s: String): String {
        val src = s.toByteArray(StandardCharsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            val b = src[i]
            if (b == '%'.code.toByte() && i + 2 < src.size && hex(src[i + 1]) >= 0 && hex(src[i + 2]) >= 0) {
                out.write(hex(src[i + 1]) * 16 + hex(src[i + 2]))
                i += 3
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** One path segment, percent-encoded as UTF-8 (RFC 3986 unreserved characters stay as they are). */
    fun encodeSegment(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) sb.append(c.toChar())
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    /** Decoded segments of an href or URL path. Splits before decoding, so "%2F" stays inside one name. */
    fun hrefSegments(href: String): List<String> {
        val path = if (href.contains("://")) href.substringAfter("://").substringAfter('/', "") else href
        return path.split('/').filter { it.isNotEmpty() }.map(::percentDecode)
    }

    /**
     * Base address for WebDAV: http or https, a host, ASCII only, no spaces, and no user name in the URL (the
     * password is stored encrypted, never in a link). Returns the address without a trailing slash, or null.
     */
    fun webDavBase(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.code !in 0x21..0x7e }) return null
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
        return s
    }

    /**
     * Reads a PROPFIND multistatus reply. [requestPath] is the decoded segments of the folder that was asked for,
     * so the folder itself is not listed as its own child. DOCTYPE is refused outright: that is where entity bombs and
     * external entities live, and this app never needs either.
     */
    fun parseWebDavListing(xml: String, requestPath: List<String>): List<RemoteEntry> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw IllegalArgumentException("DTD or entities are not allowed")
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        // Not setting isXIncludeAware: it is off by default, and setting it throws on Android.
        // Android has no XMLConstants.ACCESS_EXTERNAL_*; these are their values, for the desktop parser in tests.
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) {
            // Android's parser may not know a feature; the DOCTYPE check above still holds
            runCatching { factory.setFeature(feature, feature.contains("disallow")) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val out = ArrayList<RemoteEntry>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = text(r, "href") ?: continue
            val segs = hrefSegments(href)
            if (segs.isEmpty() || segs == requestPath) continue
            val dir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = text(r, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            out.add(RemoteEntry(segs.last(), dir, size))
        }
        return clean(out)
    }

    private fun text(parent: Element, local: String): String? {
        val n = parent.getElementsByTagNameNS("DAV:", local)
        return if (n.length == 0) null else n.item(0).textContent
    }

    /**
     * Trust on first use. [stored] is the fingerprint saved on the first connection, or null if there is none. A
     * different key is never accepted silently: the caller must refuse and tell the user.
     */
    fun hostKey(stored: String?, presented: String): HostKeyVerdict {
        require(presented.isNotBlank()) { "host key missing" }
        return when {
            stored == null -> HostKeyVerdict.New
            stored == presented -> HostKeyVerdict.Same
            else -> HostKeyVerdict.Changed(stored)
        }
    }
}
