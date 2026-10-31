import app.anon.player.player.cast.CastServer
import app.anon.player.player.cast.CastSource
import app.anon.player.player.cast.Dlna
import app.anon.player.player.cast.parseRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class CastLogicTest {
    private val data = ByteArray(1000) { (it % 251).toByte() }
    private val source = object : CastSource {
        override val size = data.size.toLong()
        override fun read(offset: Long, buf: ByteArray, len: Int): Int {
            if (offset >= size) return -1
            val n = minOf(len.toLong(), size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buf, 0, n)
            return n
        }
    }

    @Test fun rangesStayInsideTheFile() {
        assertEquals(0L to 999L, parseRange("bytes=0-", 1000))
        assertEquals(990L to 999L, parseRange("bytes=-10", 1000))
        assertEquals(5L to 9L, parseRange("bytes=5-9", 1000))
        assertEquals(5L to 999L, parseRange("bytes=5-99999", 1000))
        assertNull(parseRange("bytes=1000-", 1000))
        assertNull(parseRange("bytes=9-5", 1000))
        assertNull(parseRange("garbage", 1000))
        assertNull(parseRange(null, 1000))
    }

    @Test fun serverGivesTheFileOnlyAtItsTokenAddress() {
        val server = CastServer(source, "video/mp4", InetAddress.getLoopbackAddress())
        server.start()
        try {
            val ok = URL(server.url).openConnection() as HttpURLConnection
            assertEquals(200, ok.responseCode)
            assertTrue(data.contentEquals(ok.inputStream.readBytes()))

            val part = URL(server.url).openConnection() as HttpURLConnection
            part.setRequestProperty("Range", "bytes=10-19")
            assertEquals(206, part.responseCode)
            assertEquals("bytes 10-19/1000", part.getHeaderField("Content-Range"))
            assertTrue(data.copyOfRange(10, 20).contentEquals(part.inputStream.readBytes()))

            val bad = URL(server.url).openConnection() as HttpURLConnection
            bad.setRequestProperty("Range", "bytes=5000-")
            assertEquals(416, bad.responseCode)

            val root = URL(server.url.substringBeforeLast('/') + "/").openConnection() as HttpURLConnection
            assertEquals(404, root.responseCode)
        } finally { server.stop() }
    }

    @Test fun replyMayOnlyPointAtItsOwnAddress() {
        assertTrue(Dlna.allowed("http://192.168.1.5:80/d.xml", "192.168.1.5"))
        assertFalse(Dlna.allowed("http://10.0.0.1/d.xml", "192.168.1.5"))
        assertFalse(Dlna.allowed("https://192.168.1.5/d.xml", "192.168.1.5"))
        assertFalse(Dlna.allowed("not a url %%", "192.168.1.5"))
    }

    @Test fun readsTvDescription() {
        val xml = """<root><device><friendlyName>Living room</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av/ctl</controlURL></service>
            </serviceList></device></root>"""
        val r = Dlna.parse(xml, "http://192.168.1.5:9000/d.xml")!!
        assertEquals("Living room", r.name)
        assertEquals("http://192.168.1.5:9000/av/ctl", r.control)
        assertNull(Dlna.parse("<root/>", "http://192.168.1.5/d.xml"))
    }

    @Test fun titlesCannotBreakTheXml() {
        val (_, body) = Dlna.setUri("http://a/b?x=1&y=2", "</item><evil>", "video/mp4")
        assertFalse(body.contains("<evil>"))
        assertTrue(body.contains("x=1&amp;y=2"))
    }
}
