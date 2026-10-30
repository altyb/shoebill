package app.anon.player.settings

import app.anon.player.data.PlaybackStore
import android.content.Context
import android.os.Build
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import app.anon.player.ui.theme.AnonPresets
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
    var clearDialog by remember { mutableStateOf(false) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الإعدادات") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupTitle("المشغّل")
            val player = groupShapes(4)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingRow(
                    title = "فك الترميز", shape = player[0], subtitle = s.decoder.description,
                    onClick = { decoderDialog = true }, trailing = { Text(s.decoder.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                )
                SwitchRow("استئناف تلقائي", player[1], s.autoResume, "متابعة من آخر موضع توقّف عنده") { v -> app.update { it.copy(autoResume = v) } }
                SwitchRow("التالي في المجلد", player[2], s.playNext, "تشغيل الملف التالي تلقائياً") { v -> app.update { it.copy(playNext = v) } }
                SwitchRow("التشغيل في الخلفية", player[3], s.backgroundAudio, "متابعة الصوت عند إغلاق الشاشة") { v -> app.update { it.copy(backgroundAudio = v) } }
            }

            GroupTitle("الإيماءات")
            SwitchRow("الإيماءات", groupShapes(1)[0], s.gestures, "سحب على الشاشة لتغيير السطوع والصوت والتقديم") { v -> app.update { it.copy(gestures = v) } }

            GroupTitle("المظهر")
            SettingRow(
                title = "المظهر", shape = groupShapes(1)[0], subtitle = "الألوان والنمط والوضع والتباين", onClick = onAppearance,
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )

            GroupTitle("الخصوصية")
            val privacy = groupShapes(2)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchRow("سجل المشاهدة", privacy[0], s.keepHistory, "حفظ مكان التوقّف لكل فيديو") { v -> app.update { it.copy(keepHistory = v) } }
                SettingRow(title = "مسح السجل", shape = privacy[1], subtitle = "حذف كل مواضع التوقّف المحفوظة", onClick = { clearDialog = true })
            }

            GroupTitle("حول")
            SettingRow(title = "الإصدار", shape = groupShapes(1)[0], trailing = { Text(version, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }

    if (decoderDialog) {
        AlertDialog(
            onDismissRequest = { decoderDialog = false },
            title = { Text("فك الترميز") },
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
                                Text(d.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { decoderDialog = false }) { Text("إلغاء") } },
        )
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("مسح سجل المشاهدة؟") },
            text = { Text("ستُحذف كل مواضع التوقّف المحفوظة. لا يمكن التراجع.") },
            confirmButton = {
                TextButton(onClick = {
                    PlaybackStore.get(context).clearAll()
                    clearDialog = false
                }) { Text("مسح") }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("إلغاء") } },
        )
    }
}

private val Decoder.label: String get() = when (this) {
    Decoder.HW -> "HW"
    Decoder.HWPlus -> "HW+"
    Decoder.SW -> "SW"
}

private val Decoder.description: String get() = when (this) {
    Decoder.HW -> "تسريع بالعتاد، الأسرع والأقل استهلاكاً"
    Decoder.HWPlus -> "تسريع بالعتاد مع نسخ الإطارات، أوسع توافقاً"
    Decoder.SW -> "فك ترميز بالمعالج، الأوسع توافقاً"
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
            title = { Text("المظهر") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreviewCard()

            GroupTitle("مصدر الألوان")
            val sources = buildList {
                if (hasWallpaper) add(ColorSource.Wallpaper to "من الخلفية")
                add(ColorSource.Preset to "جاهزة")
                add(ColorSource.Custom to "مخصّص")
            }
            Choice(sources, s.colorSource) { src -> app.update { it.copy(colorSource = src) } }

            if (s.colorSource == ColorSource.Preset) {
                AnonPresets.chunked(4).forEach { row ->
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
                        Text("الدرجة ${hue.toInt()}°", style = MaterialTheme.typography.bodyLarge)
                    }
                    Slider(
                        value = hue, valueRange = 0f..360f,
                        onValueChange = { hue = it },
                        onValueChangeFinished = { app.update { it.copy(colorSource = ColorSource.Custom, seed = hueColor(hue)) } },
                    )
                }
            }

            GroupTitle("النمط")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SchemeStyle.TonalSpot to "هادئ", SchemeStyle.Vibrant to "نابض", SchemeStyle.Expressive to "تعبيري",
                    SchemeStyle.Fidelity to "وفيّ", SchemeStyle.Monochrome to "أحادي",
                ).forEach { (st, label) ->
                    FilterChip(
                        selected = s.style == st, onClick = { app.update { it.copy(style = st) } }, label = { Text(label) },
                        leadingIcon = if (s.style == st) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null,
                    )
                }
            }

            GroupTitle("الوضع")
            Choice(
                listOf(DarkMode.System to "النظام", DarkMode.Light to "فاتح", DarkMode.Dark to "داكن"), s.darkMode,
            ) { m -> app.update { it.copy(darkMode = m) } }
            SwitchRow("أسود حقيقي", groupShapes(1)[0], s.amoled, "خلفية سوداء كاملة لشاشات AMOLED") { v -> app.update { it.copy(amoled = v) } }

            GroupTitle("التباين")
            Choice(
                listOf(Contrast.Standard to "عادي", Contrast.Medium to "متوسط", Contrast.High to "عالٍ"), s.contrast,
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
            Block("أساسي", c.primary, c.onPrimary, Modifier.weight(1.4f).fillMaxSize())
            Block("ثانوي", c.secondaryContainer, c.onSecondaryContainer, Modifier.weight(1f).fillMaxSize())
            Block("ثالث", c.tertiaryContainer, c.onTertiaryContainer, Modifier.weight(1f).fillMaxSize())
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
