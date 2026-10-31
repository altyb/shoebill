package dev.altayeb.shoebill.player.cast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer

/** One cast at a time, for the whole app: it outlives the dialog and stops from the same dialog. */
object Casting {
    @Volatile var active: Pair<Renderer, CastServer?>? = null

    fun stop() {
        val a = active ?: return
        active = null
        runCatching { Dlna.send(a.first.control, Dlna.STOP) }
        a.second?.stop()
    }

    fun discover(context: Context): List<Renderer> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("cast").apply { acquire() }
        val found = LinkedHashMap<String, Renderer>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                val bytes = Dlna.SEARCH.toByteArray()
                s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val until = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { break }
                    val loc = Dlna.location(String(p.data, 0, p.length)) ?: continue
                    if (loc in found || !Dlna.allowed(loc, p.address.hostAddress.orEmpty())) continue
                    runCatching {
                        val c = URL(loc).openConnection().apply { connectTimeout = 2000; readTimeout = 3000 }
                        Dlna.parse(c.getInputStream().bufferedReader().use { it.readText().take(256_000) }, loc)
                    }.getOrNull()?.let { found[loc] = it }
                }
            }
        } finally { lock.release() }
        return found.values.toList()
    }

    /** Local files go through [CastServer]; network links go straight to the TV. */
    fun start(context: Context, uri: Uri, title: String, to: Renderer) {
        stop()
        var server: CastServer? = null
        val url: String
        val mime: String
        if (uri.scheme == "content") {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("cannot open")
            val ch = FileInputStream(pfd.fileDescriptor).channel
            mime = context.contentResolver.getType(uri) ?: "video/mp4"
            val source = object : CastSource {
                override val size = pfd.statSize
                override fun read(offset: Long, buf: ByteArray, len: Int) =
                    synchronized(ch) { ch.read(ByteBuffer.wrap(buf, 0, len), offset) }
            }
            val ip = lanAddress() ?: throw java.io.IOException("no Wi-Fi address")
            server = CastServer(source, mime, ip).also { it.start() }
            url = server.url
        } else {
            url = uri.toString()
            mime = "video/mp4"
        }
        try {
            Dlna.send(to.control, Dlna.setUri(url, title, mime))
            Dlna.send(to.control, Dlna.PLAY)
        } catch (e: Exception) {
            server?.stop()
            throw e
        }
        active = to to server
    }

    private fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
}

@Composable
fun CastDialog(uri: Uri, title: String, onPauseLocal: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Renderer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = Casting.active
    LaunchedEffect(Unit) {
        if (active == null) devices = withContext(Dispatchers.IO) { runCatching { Casting.discover(context) }.getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cast_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val d = devices
                when {
                    active != null -> Text(stringResource(R.string.cast_playing_on, active.first.name))
                    d == null -> CircularProgressIndicator()
                    d.isEmpty() -> Text(stringResource(R.string.cast_none))
                    else -> d.forEach { r ->
                        Text(r.name, Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val failed = withContext(Dispatchers.IO) {
                                    runCatching { Casting.start(context, uri, title, r) }.exceptionOrNull()
                                }
                                if (failed == null) { onPauseLocal(); onDismiss() }
                                else error = context.getString(R.string.cast_failed, r.name)
                            }
                        }.padding(vertical = 12.dp))
                    }
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (active != null) TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { Casting.stop() }; onDismiss() }
            }) { Text(stringResource(R.string.cast_stop)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_label)) }
        },
    )
}
