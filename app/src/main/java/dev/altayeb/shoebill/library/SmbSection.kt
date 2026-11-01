package dev.altayeb.shoebill.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.library.logic.RemoteEntry
import dev.altayeb.shoebill.library.logic.RemoteKind
import dev.altayeb.shoebill.library.logic.RemoteLogic
import dev.altayeb.shoebill.ui.ConnectedChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "m4v", "3gp", "vob", "ogv")

/** Saved servers (SMB, WebDAV, SFTP, FTP) and a simple folder browser, shown in the Network tab. */
@Composable
fun SmbSection(onPlay: (Uri, String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var servers by remember { mutableStateOf(SmbStore.list(ctx)) }
    var adding by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<SmbServer?>(null) }
    var path by remember { mutableStateOf("") }

    val s = open
    if (s != null) {
        BackHandler { if (path.isEmpty()) open = null else path = path.substringBeforeLast('/', "") }
        SmbFolder(s, path, { path = if (path.isEmpty()) it else "$path/$it" }, onPlay, modifier)
        return
    }
    Column(modifier.fillMaxWidth()) {
        servers.forEach { srv ->
            ListItem(
                headlineContent = { Text(serverTitle(srv)) },
                supportingContent = { Text("${kindName(srv.kind)} · ${srv.user.ifEmpty { stringResource(R.string.smb_guest) }}") },
                leadingContent = { Icon(Icons.Rounded.Storage, null) },
                trailingContent = {
                    IconButton(onClick = { SmbStore.remove(ctx, srv.id); servers = SmbStore.list(ctx) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.smb_remove))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { path = ""; open = srv },
            )
        }
        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.remote_add)) }
    }
    if (adding) AddServerDialog(ctx, onDone = { servers = SmbStore.list(ctx); adding = false }, onCancel = { adding = false })
}

/** What a saved row shows: the address, then the share or root folder if there is one. */
private fun serverTitle(s: SmbServer): String = if (s.share.isEmpty()) s.host else "${s.host}/${s.share}"

@Composable
private fun kindName(kind: String): String = stringResource(
    when (kind) {
        RemoteKind.WEBDAV -> R.string.remote_type_webdav
        RemoteKind.SFTP -> R.string.remote_type_sftp
        RemoteKind.FTP -> R.string.remote_type_ftp
        else -> R.string.remote_type_smb
    },
)

@Composable
private fun SmbFolder(s: SmbServer, path: String, onEnter: (String) -> Unit, onPlay: (Uri, String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    var entries by remember(s, path) { mutableStateOf<List<RemoteEntry>?>(null) }
    var failed by remember(s, path) { mutableStateOf<String?>(null) }
    LaunchedEffect(s, path) {
        withContext(Dispatchers.IO) {
            runCatching { openRemote(ctx, s).use { it.list(path) } }
                .onSuccess { entries = it.filter { e -> e.dir || e.name.substringAfterLast('.').lowercase() in VIDEO } }
                .onFailure { failed = it.message ?: it.javaClass.simpleName }
        }
    }
    Column(modifier.fillMaxSize()) {
        Text("${serverTitle(s)}/$path", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        val f = failed
        val e = entries
        when {
            f != null -> Text(stringResource(R.string.smb_failed, s.host, f), color = MaterialTheme.colorScheme.error)
            e == null -> CircularProgressIndicator()
            e.isEmpty() -> Text(stringResource(R.string.smb_empty))
            else -> LazyColumn {
                items(e, key = { it.name }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        leadingContent = { Icon(if (item.dir) Icons.Rounded.Folder else Icons.Rounded.Movie, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            if (item.dir) onEnter(item.name)
                            else onPlay(SmbProvider.uri(ctx, s.id, if (path.isEmpty()) item.name else "$path/${item.name}"), item.name)
                        },
                    )
                }
            }
        }
    }
}

private fun defaultPort(kind: String): String = when (kind) {
    RemoteKind.SFTP -> "22"
    RemoteKind.FTP -> "21"
    RemoteKind.WEBDAV -> ""
    else -> "445"
}

@Composable
private fun AddServerDialog(ctx: Context, onDone: () -> Unit, onCancel: () -> Unit) {
    var kind by remember { mutableStateOf(RemoteKind.SMB) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(defaultPort(RemoteKind.SMB)) }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    fun choose(k: String) { kind = k; port = defaultPort(k); error = null }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.remote_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectedChoice(
                    options = listOf(
                        RemoteKind.SMB to stringResource(R.string.remote_type_smb),
                        RemoteKind.WEBDAV to stringResource(R.string.remote_type_webdav),
                        RemoteKind.SFTP to stringResource(R.string.remote_type_sftp),
                        RemoteKind.FTP to stringResource(R.string.remote_type_ftp),
                    ),
                    selected = kind,
                    pick = { choose(it) },
                )
                if (kind == RemoteKind.WEBDAV) {
                    OutlinedTextField(host, { host = it.take(512) }, label = { Text(stringResource(R.string.remote_url)) }, singleLine = true)
                } else {
                    OutlinedTextField(host, { host = it.take(253) }, label = { Text(stringResource(R.string.smb_host)) }, singleLine = true)
                    OutlinedTextField(port, { port = it.take(5) }, label = { Text(stringResource(R.string.smb_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(share, { share = it.take(255) },
                        label = { Text(stringResource(if (kind == RemoteKind.SMB) R.string.smb_share else R.string.remote_root)) }, singleLine = true)
                }
                OutlinedTextField(user, { user = it.take(104) },
                    label = { Text(stringResource(if (kind == RemoteKind.SFTP) R.string.remote_user_required else R.string.smb_user)) }, singleLine = true)
                OutlinedTextField(pass, { pass = it.take(256) }, label = { Text(stringResource(R.string.smb_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                when (kind) {
                    RemoteKind.FTP -> Text(stringResource(R.string.remote_ftp_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    RemoteKind.SFTP -> Text(stringResource(R.string.remote_sftp_hint), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = host.trim()
                val p = port.trim().map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("").toIntOrNull()
                val base = if (kind == RemoteKind.WEBDAV) RemoteLogic.webDavBase(h) else null
                error = when {
                    kind == RemoteKind.WEBDAV -> if (base == null) R.string.remote_bad_url else null
                    h.isEmpty() || h.any { it.isWhitespace() || it == '/' || it == '\\' } -> R.string.smb_bad_host
                    p == null || p !in 1..65535 -> R.string.smb_bad_port
                    kind == RemoteKind.SMB && (share.isBlank() || share.any { it == '/' || it == '\\' }) -> R.string.smb_bad_share
                    kind != RemoteKind.SMB && share.isNotBlank() && RemoteLogic.segments(share) == null -> R.string.remote_bad_root
                    kind == RemoteKind.SFTP && user.isBlank() -> R.string.remote_bad_sftp_user
                    else -> null
                }
                if (error == null) {
                    val server = if (kind == RemoteKind.WEBDAV) SmbServer(SmbStore.newId(), base!!, 0, "", user.trim(), kind)
                    else SmbServer(SmbStore.newId(), h, p!!, share.trim(), user.trim(), kind)
                    SmbStore.add(ctx, server, pass)
                    onDone()
                }
            }) { Text(stringResource(R.string.smb_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.close_label)) } },
    )
}
