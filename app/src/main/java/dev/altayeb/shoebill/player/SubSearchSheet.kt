package dev.altayeb.shoebill.player

import dev.altayeb.shoebill.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.altayeb.shoebill.ui.BrandLoader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import dev.altayeb.shoebill.ui.EmptyState
import dev.altayeb.shoebill.ui.GlassScrim
import dev.altayeb.shoebill.ui.PanelHeader
import dev.altayeb.shoebill.ui.SearchPill
import dev.altayeb.shoebill.ui.glass
import dev.altayeb.shoebill.subs.FailKind
import dev.altayeb.shoebill.subs.ReleaseName
import dev.altayeb.shoebill.subs.SubKeys
import dev.altayeb.shoebill.subs.SubQuery
import dev.altayeb.shoebill.subs.SubResult
import dev.altayeb.shoebill.subs.SubSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val LANGS = listOf("ar" to "عربي", "en" to "English", "fr" to "Français", "es" to "Español", "de" to "Deutsch", "tr" to "Türkçe")

/** ش-21: wide side sheet over the video, which keeps playing. Opened from «بحث أونلاين». */
@Composable
fun SubSearchSheet(
    visible: Boolean,
    player: MpvPlayer,
    videoName: String,
    hash: VideoHash?,
    keys: SubKeys,
    hidden: Set<String>,
    onHide: (String) -> Unit,
    onApplied: (File, String) -> Unit,
    onClose: () -> Unit,
) {
    GlassScrim(visible = visible, onDismiss = onClose)
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).widthIn(max = 470.dp).fillMaxHeight()
                    .glass(RoundedCornerShape(28.dp)),
            ) {
                SearchContent(player, videoName, hash, keys, hidden, onHide, onApplied, onClose)
            }
        }
    }
}

@Composable
private fun SearchContent(
    player: MpvPlayer,
    videoName: String,
    hash: VideoHash?,
    keys: SubKeys,
    hidden: Set<String>,
    onHide: (String) -> Unit,
    onApplied: (File, String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember(videoName) { ReleaseName.parse(videoName) }
    var query by rememberSaveable(videoName) { mutableStateOf(info.title) }
    var langs by remember { mutableStateOf(setOf("ar")) }
    var disabled by remember { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var keysVersion by remember { mutableIntStateOf(0) }
    var showKeys by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SubResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val keyed = remember(keysVersion) { keys.providers().map { it.id } }
    val noResults = stringResource(R.string.no_results)

    LaunchedEffect(reload, hash, keysVersion) {
        val providers = keys.providers(hidden).filter { it.id !in disabled }
        if (providers.isEmpty()) {
            results = emptyList()
            message = context.getString(if (keyed.isEmpty()) R.string.sub_need_key else R.string.sub_no_provider)
            return@LaunchedEffect
        }
        loading = true
        val q = SubQuery(
            title = query.ifBlank { null }, year = info.year, season = info.season, episode = info.episode,
            languages = langs.toList(), fileHash = hash?.hash, fileSize = hash?.size,
        )
        val (found, failures) = SubSearch.searchReport(providers, q)
        failures.forEach { f ->
            when (SubSearch.classify(f.error)) {
                FailKind.Quota -> { onHide(f.providerId); message = context.getString(R.string.sub_fail_quota, SubKeys.LABELS[f.providerId].toString()) }
                FailKind.Network -> { onHide(f.providerId); message = context.getString(R.string.sub_fail_network, SubKeys.LABELS[f.providerId].toString()) }
                FailKind.Auth -> message = context.getString(R.string.sub_fail_auth, SubKeys.LABELS[f.providerId].toString())
                FailKind.Other -> message = context.getString(R.string.sub_fail_other, SubKeys.LABELS[f.providerId].toString())
            }
        }
        results = SubSearch.rank(SubSearch.filterLanguages(found, langs), videoName)
        if (results.isEmpty() && failures.isEmpty()) message = noResults
        loading = false
    }

    fun pick(r: SubResult) {
        busy = r.id
        message = null
        scope.launch {
            try {
                val provider = keys.providers().first { it.id == r.providerId }
                val file = withContext(Dispatchers.IO) {
                    cacheSubtitle(context, "${hash?.hash ?: "nohash"}-${r.providerId}-${r.id}", provider.download(r), r.format)
                }
                player.command("sub-add", file.absolutePath, "select", r.releaseName) // name, not the cache file's hash
                onApplied(file, r.releaseName)
            } catch (e: Exception) {
                message = when (SubSearch.classify(e)) {
                    FailKind.Quota -> context.getString(R.string.sub_dl_quota)
                    FailKind.Network -> context.getString(R.string.sub_dl_network)
                    else -> context.getString(R.string.sub_dl_failed)
                }
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        PanelHeader(stringResource(R.string.sub_search_online), onClose) {
            IconButton(onClick = { showKeys = true }) { Icon(Icons.Rounded.Key, stringResource(R.string.sub_provider_keys)) }
        }
        SearchPill(query, { query = it }, stringResource(R.string.sub_search_hint), Modifier.fillMaxWidth(), onSearch = { reload++ })
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LANGS.forEach { (code, label) ->
                FilterChip(
                    selected = code in langs,
                    onClick = { langs = if (code in langs) langs - code else langs + code },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (keyed.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keyed.forEach { id ->
                    val isHidden = id in hidden
                    FilterChip(
                        selected = id !in disabled && !isHidden,
                        enabled = !isHidden,
                        onClick = { disabled = if (id in disabled) disabled - id else disabled + id },
                        label = { Text(SubKeys.LABELS[id] ?: id) },
                    )
                }
            }
        }
        FilledTonalButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.search)) }
        message?.takeIf { keyed.isNotEmpty() && it != noResults }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
        if (loading) {
            Spacer(Modifier.height(12.dp))
            BrandLoader(Modifier.align(Alignment.CenterHorizontally), 44.dp)
        }
        when {
            keyed.isEmpty() -> EmptyState(
                Icons.Rounded.Key,
                stringResource(R.string.sub_add_key),
                Modifier.weight(1f),
                body = stringResource(R.string.sub_need_key_body),
                action = { FilledTonalButton(onClick = { showKeys = true }) { Text(stringResource(R.string.sub_provider_keys)) } },
            )
            message == noResults -> EmptyState(
                Icons.Rounded.SearchOff,
                noResults,
                Modifier.weight(1f),
                body = stringResource(R.string.sub_try_shorter),
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.providerId + it.id }) { r ->
                    ResultRow(r, busy == r.id, onClick = { if (busy == null) pick(r) }, modifier = Modifier.animateItem())
                }
            }
        }
    }

    if (showKeys) {
        KeysDialog(keys, onDismiss = { showKeys = false }, onSaved = { showKeys = false; keysVersion++ })
    }
}

@Composable
private fun ResultRow(r: SubResult, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // release names are LTR even inside the Arabic UI (ق-04)
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(r.releaseName, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SubKeys.LABELS[r.providerId] ?: r.providerId, style = MaterialTheme.typography.labelMedium)
                    if (r.hashMatch) Text(stringResource(R.string.sub_hash_match), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    r.downloads?.let { Text(pluralStringResource(R.plurals.sub_downloads, it, it.toString()), style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun KeysDialog(keys: SubKeys, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val fields = SubKeys.LABELS.keys.map { id -> id to remember(id) { mutableStateOf(keys.key(id).orEmpty()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sub_provider_keys)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sub_keys_local), style = MaterialTheme.typography.bodyMedium)
                fields.forEach { (id, state) ->
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        singleLine = true,
                        label = { Text(SubKeys.LABELS[id] ?: id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fields.forEach { (id, state) -> keys.save(id, state.value) }
                onSaved()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
