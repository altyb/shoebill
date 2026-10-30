package app.anon.player.vault

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.anon.player.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val READ_IMAGES = "android.permission.READ_MEDIA_IMAGES"
private const val READ_VIDEO = "android.permission.READ_MEDIA_VIDEO"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultGrid(
    ui: VaultUi,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onPlay: (String) -> Unit,
    onView: (String?) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onBeginSystemHop: () -> Unit,
    onEndSystemHop: () -> Unit,
    onDeleteDone: () -> Unit,
    onExport: (String) -> Unit, // single item: returns it to the gallery
    onDelete: (String) -> Unit,
    onFilter: (MediaFilter) -> Unit,
    onMessageShown: () -> Unit,
    decodePhoto: (String) -> Bitmap?,
    nameOf: (String) -> String,
    onReturn: (Set<String>) -> Unit,
    onDeleteMany: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        onEndSystemHop()
        if (uris.isNotEmpty()) onImport(uris)
    }
    val openPicker = {
        onBeginSystemHop()
        picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    // Media permissions are asked once, before the first picker; the picker opens whatever the answer is.
    var permAsked by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onEndSystemHop()
        openPicker()
    }
    val addFilesNow = {
        val missing = if (Build.VERSION.SDK_INT >= 33 && !permAsked) {
            listOf(READ_IMAGES, READ_VIDEO).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        } else {
            emptyList()
        }
        permAsked = true
        if (missing.isEmpty()) {
            openPicker()
        } else {
            onBeginSystemHop()
            permLauncher.launch(missing.toTypedArray())
        }
    }
    // Moving, not copying: with "All files access" the originals are deleted without Android's per-batch dialog.
    // Asked once, before the first add; "later" keeps the dialog route.
    var askedAllFiles by rememberSaveable { mutableStateOf(false) }
    var allFilesDialog by remember { mutableStateOf(false) }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onEndSystemHop()
        addFilesNow()
    }
    val needsAllFiles = { app.anon.player.BuildConfig.ALL_FILES && Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager() }
    if (allFilesDialog) {
        AlertDialog(
            onDismissRequest = { allFilesDialog = false },
            title = { Text("نقل بدل النسخ") },
            text = { Text("ليُحذف الأصل من المعرض تلقائياً دون نافذة سماح في كل مرة، فعّل «الوصول إلى كل الملفات» لهذا التطبيق. إن رفضت، تظهر نافذة السماح.") },
            confirmButton = {
                TextButton(onClick = {
                    askedAllFiles = true; allFilesDialog = false; onBeginSystemHop()
                    allFilesLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) { Text("تفعيل") }
            },
            dismissButton = { TextButton(onClick = { askedAllFiles = true; allFilesDialog = false; addFilesNow() }) { Text("لاحقاً") } },
        )
    }
    val addFiles = { if (!askedAllFiles && needsAllFiles()) allFilesDialog = true else addFilesNow() }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        onEndSystemHop()
        onDeleteDone()
    }
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDeleteMany by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting) { selected = emptySet() }

    // System "delete originals?" dialog after a verified import (API 30+).
    LaunchedEffect(ui.deleteRequest) {
        ui.deleteRequest?.let { pi ->
            onBeginSystemHop()
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    val shown = ui.entries.filter {
        when (ui.filter) {
            MediaFilter.All -> true
            MediaFilter.Video -> it.kind == "video"
            MediaFilter.Photo -> it.kind == "photo"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} محدد" else "داخل الخزنة") },
                navigationIcon = {
                    if (selecting) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "إلغاء التحديد") }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text("تحديد الكل") }
                    } else {
                        IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, "قفل الخزنة") }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "إعدادات الخزنة") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!ui.busy && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = addFiles,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("إضافة ملفات") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ui.importing?.let { imp ->
                    val target = if (imp.total == 0) 0f else imp.done.toFloat() / imp.total
                    val smooth by androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(400), label = "import")
                    LinearProgressIndicator(
                        progress = { smooth },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaFilter.entries.forEach { f ->
                        FilterChip(selected = ui.filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
                    }
                }
                if (shown.isEmpty()) {
                    EmptyState(Icons.Filled.Lock, "الخزنة فارغة", Modifier.weight(1f), body = "أضف فيديو ليُشفَّر ويختفي من المعرض")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = if (selecting) 96.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.id }) { e ->
                            Tile(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = androidx.compose.animation.core.tween(350),
                                    placementSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 380f),
                                    fadeOutSpec = androidx.compose.animation.core.tween(200),
                                ),
                                e = e,
                                thumb = ui.thumbs[e.id],
                                selected = e.id in selected,
                                onClick = {
                                    when {
                                        selecting -> selected = if (e.id in selected) selected - e.id else selected + e.id
                                        e.kind == "video" -> onPlay(e.id)
                                        else -> onView(e.id)
                                    }
                                },
                                onLong = { selected = selected + e.id },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val ids = selected
                                selected = emptySet()
                                onReturn(ids)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("إرجاع إلى المعرض")
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteMany = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("حذف")
                        }
                    }
                }
            }

            ui.viewing?.let { id ->
                PhotoViewer(
                    id = id,
                    title = nameOf(id),
                    decode = decodePhoto,
                    onClose = { onView(null) },
                    onExport = {
                        onView(null)
                        onExport(id)
                    },
                    onDelete = { confirmDelete = id },
                )
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف نهائياً؟") },
            text = { Text("يُمحى الملف من الخزنة ولا يمكن استرجاعه.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    onView(null)
                    onDelete(id)
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } },
        )
    }

    if (confirmDeleteMany) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text("حذف $n ملف نهائياً؟") },
            text = { Text("يُمحى الملف من الخزنة ولا يمكن استرجاعه.") },
            confirmButton = {
                Button(onClick = {
                    confirmDeleteMany = false
                    val ids = selected
                    selected = emptySet()
                    onDeleteMany(ids)
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text("إلغاء") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(e: VaultEntry, thumb: ByteArray?, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier) {
    val img = remember(thumb) { thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val scale by animateFloatAsState(if (selected) 0.94f else 1f, label = "tileScale")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                if (e.kind == "video") Icons.Filled.PlayArrow else Icons.Filled.Image, null,
                Modifier.align(Alignment.Center).size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.kind == "video") {
            Text(
                formatDuration(e.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Icon(
                Icons.Filled.Image, null,
                Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp),
                tint = Color.White,
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle, null,
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun PhotoViewer(
    id: String,
    title: String,
    decode: (String) -> Bitmap?,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { decode(id)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bmp?.let {
            Image(
                it, null,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    },
                contentScale = ContentScale.Fit,
            )
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق", tint = Color.White) }
            Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onExport) { Icon(Icons.Filled.SaveAlt, "إرجاع إلى المعرض", tint = Color.White) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "حذف نهائياً", tint = Color.White) }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
