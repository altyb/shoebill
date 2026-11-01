@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.altayeb.shoebill.settings

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.saveable.rememberSaveable
import dev.altayeb.shoebill.Lang
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.data.PlaybackStore
import android.content.Context
import android.os.Build
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import dev.altayeb.shoebill.ui.theme.BrandPresets
import dev.altayeb.shoebill.ui.theme.Preset
import dev.altayeb.shoebill.ui.theme.isDark
import dev.altayeb.shoebill.ui.theme.seedScheme
import androidx.compose.foundation.selection.selectable
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import dev.altayeb.shoebill.download.DownloadFeature
import dev.altayeb.shoebill.ui.theme.Radius
import kotlinx.coroutines.Dispatchers

// Entry points MainActivity calls (exact signatures).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAppearance: () -> Unit) {
    val context = LocalContext.current
    val app = AppSettings.get(context)
    val s by app.settings.collectAsState()
    var decoderDialog by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<Int?>(null) }
    val backupScope = androidx.compose.runtime.rememberCoroutineScope()
    val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { SettingsBackup.write(context, it) } }.isSuccess
            backupNote = if (ok) R.string.settings_backup_saved else R.string.settings_backup_failed
        }
    }
    val openBackup = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openInputStream(uri)!!.use { SettingsBackup.read(context, it) } }.isSuccess
            backupNote = if (ok) R.string.settings_backup_restored else R.string.settings_backup_bad
        }
    }
    var clearDialog by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    val lang = remember { Lang.get(context) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    var page by rememberSaveable { mutableStateOf(0) } // 0 hub, 1 video, 2 music, 3 downloads, 4 general, 5 about
    BackHandler(page != 0) { page = 0 }
    val titles = listOf(R.string.settings_title, R.string.settings_video, R.string.settings_music, R.string.settings_downloads, R.string.settings_general, R.string.settings_about)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(titles[page])) },
            navigationIcon = { IconButton(onClick = { if (page != 0) page = 0 else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        val motion = MaterialTheme.motionScheme
        AnimatedContent(page, Modifier.padding(pad), transitionSpec = {
            androidx.compose.animation.fadeIn(motion.defaultEffectsSpec()) togetherWith androidx.compose.animation.fadeOut(motion.fastEffectsSpec())
        }, label = "settingsPage") { p ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (p) {
                0 -> {
                    TipCard(R.drawable.art_empty, stringResource(R.string.tip_hub))
                    val cs = MaterialTheme.colorScheme
                    val hasDl = DownloadFeature.ENABLED
                    val hub = groupShapes(if (hasDl) 6 else 5)
                    var n = 0
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        HubRow(Icons.Rounded.PlayCircle, cs.primaryContainer, cs.onPrimaryContainer, stringResource(R.string.settings_video), stringResource(R.string.settings_video_sub), hub[n++]) { page = 1 }
                        HubRow(Icons.Rounded.MusicNote, cs.tertiaryContainer, cs.onTertiaryContainer, stringResource(R.string.settings_music), stringResource(R.string.settings_music_sub), hub[n++]) { page = 2 }
                        if (hasDl) HubRow(Icons.Rounded.Download, cs.secondaryContainer, cs.onSecondaryContainer, stringResource(R.string.settings_downloads), stringResource(R.string.settings_downloads_sub), hub[n++]) { page = 3 }
                        HubRow(Icons.Rounded.Palette, cs.surfaceVariant, cs.onSurfaceVariant, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub_hub), hub[n++]) { onAppearance() }
                        HubRow(Icons.Rounded.Tune, cs.surfaceVariant, cs.onSurfaceVariant, stringResource(R.string.settings_general), stringResource(R.string.settings_general_sub), hub[n++]) { page = 4 }
                        HubRow(Icons.Rounded.Info, cs.surfaceVariant, cs.onSurfaceVariant, stringResource(R.string.settings_about), stringResource(R.string.settings_about_sub), hub[n]) { page = 5 }
                    }
                }
                1 -> {
                    TipCard(R.drawable.art_video, stringResource(R.string.tip_video))
                    GroupTitle(stringResource(R.string.settings_player))
                    val player = groupShapes(4)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingRow(
                            title = stringResource(R.string.settings_decoder), shape = player[0], subtitle = stringResource(s.decoder.description),
                            onClick = { decoderDialog = true }, trailing = { Text(s.decoder.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                        )
                        SwitchRow(stringResource(R.string.settings_auto_resume), player[1], s.autoResume, stringResource(R.string.settings_auto_resume_sub)) { v -> app.update { it.copy(autoResume = v) } }
                        SwitchRow(stringResource(R.string.settings_play_next), player[2], s.playNext, stringResource(R.string.settings_play_next_sub)) { v -> app.update { it.copy(playNext = v) } }
                        SwitchRow(stringResource(R.string.settings_background), player[3], s.backgroundAudio, stringResource(R.string.settings_background_sub)) { v -> app.update { it.copy(backgroundAudio = v) } }
                    }

                    GroupTitle(stringResource(R.string.video_group_playback))
                    val vp = groupShapes(3)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChoiceRow(stringResource(R.string.video_default_speed), vp[0], listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "$it×" }, s.defaultSpeed) { v -> app.update { it.copy(defaultSpeed = v) } }
                        SwitchRow(stringResource(R.string.video_loop), vp[1], s.loopOne, stringResource(R.string.video_loop_sub)) { v -> app.update { it.copy(loopOne = v) } }
                        SwitchRow(stringResource(R.string.video_pip), vp[2], s.autoPip, stringResource(R.string.video_pip_sub)) { v -> app.update { it.copy(autoPip = v) } }
                        // Android has its own per-app switch for this. No permission to ask for, so point at the switch when it is off.
                        val pipAllowed = remember { (context.getSystemService(android.app.AppOpsManager::class.java)).unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED }
                        if (!pipAllowed) SettingRow(stringResource(R.string.video_pip_blocked), groupShapes(1)[0], stringResource(R.string.video_pip_blocked_sub), onClick = {
                            runCatching { context.startActivity(android.content.Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", android.net.Uri.parse("package:" + context.packageName))) }
                        })
                    }
                    GroupTitle(stringResource(R.string.settings_gestures))
                    val ge = groupShapes(if (s.gestures) 8 else 1)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SwitchRow(stringResource(R.string.settings_gestures), ge[0], s.gestures, stringResource(R.string.settings_gestures_sub)) { v -> app.update { it.copy(gestures = v) } }
                        if (s.gestures) {
                            SwitchRow(stringResource(R.string.video_g_brightness), ge[1], s.gestureBrightness) { v -> app.update { it.copy(gestureBrightness = v) } }
                            SwitchRow(stringResource(R.string.video_g_volume), ge[2], s.gestureVolume) { v -> app.update { it.copy(gestureVolume = v) } }
                            SwitchRow(stringResource(R.string.video_g_seek), ge[3], s.gestureSeek) { v -> app.update { it.copy(gestureSeek = v) } }
                            SwitchRow(stringResource(R.string.video_g_pinch), ge[4], s.gesturePinch) { v -> app.update { it.copy(gesturePinch = v) } }
                            SwitchRow(stringResource(R.string.video_g_double), ge[5], s.gestureDoubleTap) { v -> app.update { it.copy(gestureDoubleTap = v) } }
                            ChoiceRow(stringResource(R.string.settings_double_tap), ge[6], listOf(5, 10, 15, 30).map { it to stringResource(R.string.settings_seconds, it) }, s.doubleTapSec) { v -> app.update { it.copy(doubleTapSec = v) } }
                            ChoiceRow(stringResource(R.string.video_hold), ge[7], listOf(0f to stringResource(R.string.off), 1.5f to "1.5×", 2f to "2×", 3f to "3×"), s.holdSpeed) { v -> app.update { it.copy(holdSpeed = v) } }
                        }
                    }
                    GroupTitle(stringResource(R.string.video_group_screen))
                    val vs = groupShapes(5)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChoiceRow(stringResource(R.string.video_orientation), vs[0], listOf(0 to stringResource(R.string.video_o_landscape), 1 to stringResource(R.string.video_o_auto), 2 to stringResource(R.string.video_o_follow), 3 to stringResource(R.string.video_o_portrait)), s.orientation) { v -> app.update { it.copy(orientation = v) } }
                        ChoiceRow(stringResource(R.string.settings_hide_delay), vs[1], listOf(3, 5, 10).map { it to stringResource(R.string.settings_seconds, it) }, s.controlsHideSec) { v -> app.update { it.copy(controlsHideSec = v) } }
                        SwitchRow(stringResource(R.string.video_remaining), vs[2], s.showRemaining, stringResource(R.string.video_remaining_sub)) { v -> app.update { it.copy(showRemaining = v) } }
                        SwitchRow(stringResource(R.string.video_brightness), vs[3], s.rememberBrightness, stringResource(R.string.video_brightness_sub)) { v -> app.update { it.copy(rememberBrightness = v) } }
                        ChoiceRow(stringResource(R.string.player_deinterlace), vs[4], listOf(false to stringResource(R.string.off), true to stringResource(R.string.player_deint_auto)), s.deinterlace) { v -> app.update { it.copy(deinterlace = v) } }
                    }
                    GroupTitle(stringResource(R.string.video_group_language))
                    val la = groupShapes(3)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LangField(stringResource(R.string.video_audio_lang), la[0], s.audioLang) { v -> app.update { it.copy(audioLang = v) } }
                        LangField(stringResource(R.string.video_sub_lang), la[1], s.subLang) { v -> app.update { it.copy(subLang = v) } }
                        ChoiceRow(stringResource(R.string.video_max_volume), la[2], listOf(100, 150, 200).map { it to "$it%" }, s.maxVolume) { v -> app.update { it.copy(maxVolume = v) } }
                    }
                }
                2 -> {
                    TipCard(R.drawable.art_music, stringResource(R.string.tip_music))
                    GroupTitle(stringResource(R.string.music_group_playback))
                    val pb = groupShapes(6)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SwitchRow(stringResource(R.string.settings_skip_silence), pb[0], s.skipSilence, stringResource(R.string.settings_skip_silence_sub)) { v -> app.update { it.copy(skipSilence = v) } }
                        SwitchRow(stringResource(R.string.music_rewind), pb[1], s.rewindBeforePrevious, stringResource(R.string.music_rewind_sub)) { v -> app.update { it.copy(rewindBeforePrevious = v) } }
                        SwitchRow(stringResource(R.string.music_unplug), pb[2], s.pauseOnUnplug, stringResource(R.string.music_unplug_sub)) { v -> app.update { it.copy(pauseOnUnplug = v) } }
                        SwitchRow(stringResource(R.string.music_focus), pb[3], s.pauseForOthers, stringResource(R.string.music_focus_sub)) { v -> app.update { it.copy(pauseForOthers = v) } }
                        SwitchRow(stringResource(R.string.music_stop_close), pb[4], s.stopOnClose, stringResource(R.string.music_stop_close_sub)) { v -> app.update { it.copy(stopOnClose = v) } }
                        SwitchRow(stringResource(R.string.music_remember), pb[5], s.rememberQueue, stringResource(R.string.music_remember_sub)) { v -> app.update { it.copy(rememberQueue = v) } }
                    }
                    GroupTitle(stringResource(R.string.music_group_library))
                    val lib = groupShapes(3)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChoiceRow(stringResource(R.string.music_start_tab), lib[0], listOf(R.string.music_songs, R.string.music_albums, R.string.music_artists, R.string.music_genres, R.string.music_folders, R.string.music_lists).mapIndexed { i, r -> i to stringResource(r) }, s.musicStartTab) { v -> app.update { it.copy(musicStartTab = v) } }
                        ChoiceRow(stringResource(R.string.music_min_length), lib[1], listOf(0, 30, 60, 120).map { it to if (it == 0) stringResource(R.string.off) else stringResource(R.string.settings_seconds, it) }, s.minSongSec) { v -> app.update { it.copy(minSongSec = v) } }
                        val folders by androidx.compose.runtime.produceState(emptyList<String>()) {
                            value = runCatching { dev.altayeb.shoebill.music.querySongs(context, raw = true) }.getOrDefault(emptyList()).map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()
                        }
                        Surface(Modifier.fillMaxWidth(), shape = lib[2], color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.music_folders_shown), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.music_folders_shown_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    folders.forEach { f ->
                                        FilterChip(f !in s.hiddenFolders, { app.update { it.copy(hiddenFolders = if (f in it.hiddenFolders) it.hiddenFolders - f else it.hiddenFolders + f) } }, { Text(f) })
                                    }
                                }
                            }
                        }
                    }
                    GroupTitle(stringResource(R.string.music_group_now))
                    val np = groupShapes(3)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SwitchRow(stringResource(R.string.music_morph), np[0], s.coverMorph, stringResource(R.string.music_morph_sub)) { v -> app.update { it.copy(coverMorph = v) } }
                        SwitchRow(stringResource(R.string.music_lyrics_large), np[1], s.lyricsLarge) { v -> app.update { it.copy(lyricsLarge = v) } }
                        SwitchRow(stringResource(R.string.music_screen_on), np[2], s.keepScreenOnMusic, stringResource(R.string.music_screen_on_sub)) { v -> app.update { it.copy(keepScreenOnMusic = v) } }
                    }
                }
                3 -> {
                    TipCard(R.drawable.art_download, stringResource(R.string.tip_download))
                    DownloadFeature.SettingsSection()
                }
                4 -> {
                    GroupTitle(stringResource(R.string.language))
                    val langNames = mapOf("" to stringResource(R.string.language_system), "ar" to "العربية", "en" to "English")
                    SettingRow(
                        title = stringResource(R.string.language), shape = groupShapes(1)[0], onClick = { langDialog = true },
                        trailing = { Text(langNames[lang].orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                    )

                    GroupTitle(stringResource(R.string.settings_privacy))
                    val privacy = groupShapes(2)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SwitchRow(stringResource(R.string.settings_history), privacy[0], s.keepHistory, stringResource(R.string.settings_history_sub)) { v -> app.update { it.copy(keepHistory = v) } }
                        SettingRow(title = stringResource(R.string.settings_clear_history), shape = privacy[1], subtitle = stringResource(R.string.settings_clear_history_sub), onClick = { clearDialog = true })
                    }

                }
                else -> {
                    GroupTitle(stringResource(R.string.settings_about))
                    val about = groupShapes(6)
                    val open = { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingRow(title = stringResource(R.string.settings_version), shape = about[0], trailing = { Text(version, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                        SettingRow(title = stringResource(R.string.settings_privacy_policy), shape = about[1], subtitle = stringResource(R.string.settings_privacy_policy_sub), onClick = { open("https://github.com/altyb/shoebill/blob/main/PRIVACY.md") })
                        SettingRow(title = stringResource(R.string.settings_source), shape = about[2], subtitle = stringResource(R.string.settings_source_sub), onClick = { open("https://github.com/altyb/shoebill") })
                        SettingRow(title = stringResource(R.string.settings_licenses), shape = about[3], onClick = { licenses = true })
                        SettingRow(title = stringResource(R.string.settings_backup_save), shape = about[4], subtitle = stringResource(R.string.settings_backup_sub), onClick = { saveBackup.launch("shoebill-settings.json") })
                        SettingRow(title = stringResource(R.string.settings_backup_restore), shape = about[5], onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    }
                }
            }
        }
        }
    }

    if (licenses) {
        val text = remember { runCatching { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } + "\n\n" + context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = { Text(text, Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close_label)) } },
        )
    }
    backupNote?.let { note ->
        AlertDialog(
            onDismissRequest = { backupNote = null },
            text = { Text(stringResource(note)) },
            confirmButton = { TextButton(onClick = { backupNote = null }) { Text(stringResource(R.string.close_label)) } },
        )
    }

    if (decoderDialog) {
        AlertDialog(
            onDismissRequest = { decoderDialog = false },
            title = { Text(stringResource(R.string.settings_decoder)) },
            text = {
                Column {
                    Decoder.entries.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = d == s.decoder, role = Role.RadioButton, onClick = {
                                    app.update { it.copy(decoder = d) }
                                    decoderDialog = false
                                })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = d == s.decoder, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(d.label, style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(d.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { decoderDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (langDialog) {
        val activity = context as? Activity ?: (context as? ContextWrapper)?.baseContext as? Activity
        AlertDialog(
            onDismissRequest = { langDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    listOf("" to stringResource(R.string.language_system), "ar" to "العربية", "en" to "English").forEach { (tag, name) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = tag == lang, role = Role.RadioButton, onClick = {
                                    langDialog = false
                                    activity?.let { Lang.set(it, tag) }
                                })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == lang, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_history_q)) },
            text = { Text(stringResource(R.string.settings_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    PlaybackStore.get(context).clearAll()
                    clearDialog = false
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val Decoder.label: String get() = when (this) {
    Decoder.HW -> "HW"
    Decoder.HWPlus -> "HW+"
    Decoder.SW -> "SW"
}

private val Decoder.description: Int get() = when (this) {
    Decoder.HW -> R.string.settings_decoder_hw
    Decoder.HWPlus -> R.string.settings_decoder_hwplus
    Decoder.SW -> R.string.settings_decoder_sw
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = AppSettings.get(context)
    val s by app.settings.collectAsState()
    val dark = s.isDark(isSystemInDarkTheme())
    val hasWallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var hue by remember { mutableFloatStateOf(hueOf(s.seed)) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_appearance)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreviewCard()

            GroupTitle(stringResource(R.string.settings_color_source))
            val fromWallpaper = stringResource(R.string.settings_src_wallpaper)
            val preset = stringResource(R.string.settings_src_preset)
            val custom = stringResource(R.string.settings_src_custom)
            val sources = buildList {
                if (hasWallpaper) add(ColorSource.Wallpaper to fromWallpaper)
                add(ColorSource.Preset to preset)
                add(ColorSource.Custom to custom)
            }
            Choice(sources, s.colorSource) { src -> app.update { it.copy(colorSource = src) } }

            if (s.colorSource == ColorSource.Preset) {
                BrandPresets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { p ->
                            PresetCircle(p, s, dark) { app.update { it.copy(colorSource = ColorSource.Preset, seed = p.seed) } }
                        }
                    }
                }
            }

            if (s.colorSource == ColorSource.Custom) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(s.seed)))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_hue, hue.toInt()), style = MaterialTheme.typography.bodyLarge)
                    }
                    Slider(
                        value = hue, valueRange = 0f..360f,
                        onValueChange = { hue = it },
                        onValueChangeFinished = { app.update { it.copy(colorSource = ColorSource.Custom, seed = hueColor(hue)) } },
                    )
                }
            }

            GroupTitle(stringResource(R.string.settings_style))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SchemeStyle.TonalSpot to stringResource(R.string.settings_style_tonal), SchemeStyle.Vibrant to stringResource(R.string.settings_style_vibrant), SchemeStyle.Expressive to stringResource(R.string.settings_style_expressive),
                    SchemeStyle.Fidelity to stringResource(R.string.settings_style_fidelity), SchemeStyle.Monochrome to stringResource(R.string.settings_style_mono),
                ).forEach { (st, label) ->
                    FilterChip(
                        selected = s.style == st, onClick = { app.update { it.copy(style = st) } }, label = { Text(label) },
                        leadingIcon = if (s.style == st) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null,
                    )
                }
            }

            GroupTitle(stringResource(R.string.settings_mode))
            Choice(
                listOf(DarkMode.System to stringResource(R.string.settings_mode_system), DarkMode.Light to stringResource(R.string.settings_mode_light), DarkMode.Dark to stringResource(R.string.settings_mode_dark)), s.darkMode,
            ) { m -> app.update { it.copy(darkMode = m) } }
            SwitchRow(stringResource(R.string.settings_amoled), groupShapes(1)[0], s.amoled, stringResource(R.string.settings_amoled_sub)) { v -> app.update { it.copy(amoled = v) } }

            GroupTitle(stringResource(R.string.settings_contrast))
            Choice(
                listOf(Contrast.Standard to stringResource(R.string.settings_contrast_standard), Contrast.Medium to stringResource(R.string.settings_contrast_medium), Contrast.High to stringResource(R.string.settings_contrast_high)), s.contrast,
            ) { c -> app.update { it.copy(contrast = c) } }
        }
    }
}

/** Generic single-choice row built on ButtonGroup + ToggleButton. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) =
    dev.altayeb.shoebill.ui.ConnectedChoice(options, selected, pick = onSelect)

/** Preferred track languages, as mpv takes them: ISO codes, most wanted first. */
@Composable
private fun LangField(title: String, shape: androidx.compose.ui.graphics.Shape, value: String, set: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    val ok = text.isBlank() || Regex("[a-zA-Z]{2,3}(,[a-zA-Z]{2,3})*").matches(text.trim())
    Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        androidx.compose.material3.OutlinedTextField(
            text, { text = it.take(40); if (it.isBlank() || Regex("[a-zA-Z]{2,3}(,[a-zA-Z]{2,3})*").matches(it.trim())) set(it.trim().lowercase()) },
            Modifier.fillMaxWidth().padding(16.dp), singleLine = true, label = { Text(title) }, isError = !ok,
            supportingText = { Text(if (ok) stringResource(R.string.video_lang_hint) else title + ": " + stringResource(R.string.video_lang_bad)) },
        )
    }
}

@Composable
private fun PreviewCard() {
    val c = MaterialTheme.colorScheme
    Card(
        shape = Radius.XL,
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp).height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Block(stringResource(R.string.settings_primary), c.primary, c.onPrimary, Modifier.weight(1.4f).fillMaxSize())
            Block(stringResource(R.string.settings_secondary), c.secondaryContainer, c.onSecondaryContainer, Modifier.weight(1f).fillMaxSize())
            Block(stringResource(R.string.settings_tertiary), c.tertiaryContainer, c.onTertiaryContainer, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun Block(label: String, bg: Color, fg: Color, modifier: Modifier) {
    Box(modifier.clip(Radius.L).background(bg), contentAlignment = Alignment.BottomStart) {
        Text(label, color = fg, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(8.dp), autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize))
    }
}

@Composable
private fun PresetCircle(p: Preset, s: Settings, dark: Boolean, onClick: () -> Unit) {
    val scheme = remember(p.seed, dark, s.style, s.contrast) { seedScheme(p.seed, dark, false, s.style, s.contrast) }
    val selected = s.colorSource == ColorSource.Preset && s.seed == p.seed
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(62.dp)) {
                drawArc(scheme.primary, startAngle = 180f, sweepAngle = 180f, useCenter = true)
                drawArc(scheme.secondaryContainer, startAngle = 90f, sweepAngle = 90f, useCenter = true)
                drawArc(scheme.tertiaryContainer, startAngle = 0f, sweepAngle = 90f, useCenter = true)
            }
            if (selected) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(p.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv[0]
}

private fun hueColor(hue: Float): Int = Color.hsv(hue, 0.7f, 0.85f).toArgb()
