package dev.altayeb.shoebill.player.cast

import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.io.IOException

/** Plain DLNA/UPnP, no Google library, so the app stays free software. No android imports: see library-tests. */
data class Renderer(val name: String, val control: String)

object Dlna {
    const val SEARCH = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    fun location(ssdpReply: String): String? =
        ssdpReply.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /** [fromHost] = who answered: a reply may only point at its own address, not make us fetch elsewhere. */
    fun allowed(location: String, fromHost: String): Boolean = runCatching {
        val u = URI(location)
        u.scheme == "http" && u.host == fromHost
    }.getOrDefault(false)

    /** Renderer from its description XML, or null if it has no AVTransport. */
    fun parse(xml: String, location: String): Renderer? {
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }.firstOrNull { "AVTransport" in tag(it, "serviceType").orEmpty() } ?: return null
        val control = tag(service, "controlURL") ?: return null
        return Renderer(tag(xml, "friendlyName") ?: URI(location).host, URI(location).resolve(control).toString())
    }

    private fun tag(xml: String, name: String) =
        Regex("<$name>\\s*(.*?)\\s*</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun setUri(url: String, title: String, mime: String): Pair<String, String> {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${escape(mime)}:*\">${escape(url)}</res></item></DIDL-Lite>"
        return "SetAVTransportURI" to "<InstanceID>0</InstanceID><CurrentURI>${escape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>"
    }

    val PLAY = "Play" to "<InstanceID>0</InstanceID><Speed>1</Speed>"
    val STOP = "Stop" to "<InstanceID>0</InstanceID>"

    /** Sends one AVTransport action; throws IOException with the device's status when it refuses. */
    fun send(control: String, action: Pair<String, String>) {
        val c = URL(control).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#${action.first}\"")
            c.outputStream.use {
                it.write(("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                    "<u:${action.first} xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">${action.second}" +
                    "</u:${action.first}></s:Body></s:Envelope>").toByteArray())
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("${action.first}: HTTP $code")
        } finally { c.disconnect() }
    }
}

/** What the media server reads from. [read] returns the bytes read at [offset], or -1 at the end. */
interface CastSource {
    val size: Long
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/** "bytes=a-b" as an inclusive pair inside [size], or null if there is none or it does not fit. */
fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
    val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header?.trim().orEmpty()) ?: return null
    val (a, b) = m.destructured
    val from: Long
    val to: Long
    if (a.isEmpty()) {
        val tail = b.toLongOrNull() ?: return null
        from = (size - tail).coerceAtLeast(0)
        to = size - 1
    } else {
        from = a.toLongOrNull() ?: return null
        to = (b.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
    }
    return if (from > to || from >= size) null else from to to
}

/**
 * Serves one file to the TV for as long as casting lasts. The address holds a random token and nothing else is
 * served, so another device on the network cannot browse the phone; it does see the file if it learns the address.
 */
class CastServer(private val source: CastSource, private val mime: String, bind: InetAddress) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 8, bind)
    val url = "http://${bind.hostAddress}:${server.localPort}/$token"

    fun start() {
        Thread({
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ runCatching { s.use(::serve) } }, "cast-conn").apply { isDaemon = true }.start()
            }
        }, "cast-server").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { server.close() } }

    private fun serve(s: Socket) {
        s.soTimeout = 30_000
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':')
        }
        val parts = request.split(' ')
        val out = BufferedOutputStream(s.getOutputStream())
        fun head(status: String, vararg lines: String) =
            out.write(("HTTP/1.1 $status\r\nConnection: close\r\nAccept-Ranges: bytes\r\n" +
                lines.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
        if (parts.size < 2 || parts[1] != "/$token" || parts[0] !in setOf("GET", "HEAD")) {
            head("404 Not Found", "Content-Length: 0")
            out.flush()
            return
        }
        val size = source.size
        val r = parseRange(range, size)
        if (range != null && r == null) {
            head("416 Range Not Satisfiable", "Content-Range: bytes */$size", "Content-Length: 0")
            out.flush()
            return
        }
        val from = r?.first ?: 0L
        val to = r?.second ?: (size - 1)
        val ct = "Content-Type: $mime"
        val len = "Content-Length: ${to - from + 1}"
        if (r != null) head("206 Partial Content", ct, len, "Content-Range: bytes $from-$to/$size")
        else head("200 OK", ct, len, "transferMode.dlna.org: Streaming")
        if (parts[0] == "GET") {
            val buf = ByteArray(64 * 1024)
            var pos = from
            while (pos <= to) {
                val n = source.read(pos, buf, minOf((to - pos + 1), buf.size.toLong()).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
        }
        out.flush()
    }
}
