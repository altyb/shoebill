package app.anon.player.settings

import app.anon.player.Lang
import app.anon.player.R
import app.anon.player.data.PlaybackStore
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilterChip
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
import app.anon.player.ui.theme.BrandPresets
import app.anon.player.ui.theme.Preset
import app.anon.player.ui.theme.isDark
import app.anon.player.ui.theme.seedScheme
import androidx.compose.foundation.selection.selectable

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
    val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { SettingsBackup.write(context, it) } }.isSuccess
            backupNote = if (ok) R.string.settings_backup_saved else R.string.settings_backup_failed
        }
    }
    val openBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openInputStream(uri)!!.use { SettingsBackup.read(context, it) } }.isSuccess
            backupNote = if (ok) R.string.settings_backup_restored else R.string.settings_backup_bad
        }
    }
    var clearDialog by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }
    val lang = remember { Lang.get(context) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

            GroupTitle(stringResource(R.string.settings_gestures))
            SwitchRow(stringResource(R.string.settings_gestures), groupShapes(1)[0], s.gestures, stringResource(R.string.settings_gestures_sub)) { v -> app.update { it.copy(gestures = v) } }

            GroupTitle(stringResource(R.string.language))
            val langNames = mapOf("" to stringResource(R.string.language_system), "ar" to "العربية", "en" to "English")
            SettingRow(
                title = stringResource(R.string.language), shape = groupShapes(1)[0], onClick = { langDialog = true },
                trailing = { Text(langNames[lang].orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )

            GroupTitle(stringResource(R.string.settings_appearance))
            SettingRow(
                title = stringResource(R.string.settings_appearance), shape = groupShapes(1)[0], subtitle = stringResource(R.string.settings_appearance_sub), onClick = onAppearance,
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )

            GroupTitle(stringResource(R.string.settings_privacy))
            val privacy = groupShapes(2)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow(stringResource(R.string.settings_history), privacy[0], s.keepHistory, stringResource(R.string.settings_history_sub)) { v -> app.update { it.copy(keepHistory = v) } }
                SettingRow(title = stringResource(R.string.settings_clear_history), shape = privacy[1], subtitle = stringResource(R.string.settings_clear_history_sub), onClick = { clearDialog = true })
            }

            GroupTitle(stringResource(R.string.settings_about))
            val about = groupShapes(6)
            val open = { url: String -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
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
        val activity = context as? android.app.Activity ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
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
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    ButtonGroup(
        overflowIndicator = { },
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            toggleableItem(
                checked = value == selected,
                label = label,
                onCheckedChange = { if (it) onSelect(value) },
            )
        }
    }
}

@Composable
private fun PreviewCard() {
    val c = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
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
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(bg), contentAlignment = Alignment.BottomStart) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(10.dp))
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
