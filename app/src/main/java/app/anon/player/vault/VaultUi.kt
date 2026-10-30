package app.anon.player.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.anon.player.ui.AnonLoader
import app.anon.player.vault.logic.PinStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val ruleAuthHint = "لا استعادة بلا مفتاح: نسيان الرمز أو مسح الجهاز يُفقد الخزنة نهائياً، والنسخة الاحتياطية لم تُنفَّذ بعد."

/** First run: the rule ق-16 has to be acknowledged before the key exists. */
@Composable
fun SetupGate(canGuard: Boolean, onAccept: () -> Unit, onBack: () -> Unit, onCreateWithPin: (CharArray) -> Unit) {
    var understood by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }
    if (pinFlow) {
        PinPad(
            title = if (first == null) "اختر رمزاً للخزنة" else "أعد إدخال الرمز",
            error = mismatch, busy = false, lockedUntil = null,
            onSubmit = { pin ->
                val f = first
                if (f == null) { first = pin; mismatch = null }
                else if (f.contentEquals(pin)) { f.fill('\u0000'); first = null; onCreateWithPin(pin) }
                else { f.fill('\u0000'); pin.fill('\u0000'); first = null; mismatch = "الرمزان غير متطابقين" }
            },
            onBack = { pinFlow = false; first?.fill('\u0000'); first = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("قبل إنشاء الخزنة") },
        text = {
            Column {
                Text(if (canGuard) ruleAuthHint else "أضف بصمة أو قفل شاشة من إعدادات الجهاز أولاً.")
                if (canGuard) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("فهمت")
                    }
                }
            }
        },
        confirmButton = {
            if (canGuard) Button(onClick = onAccept, enabled = understood) { Text("إنشاء الخزنة") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { pinFlow = true }) { Text("بالرمز فقط") }
                TextButton(onClick = onBack) { Text("رجوع") }
            }
        },
    )
}

/** ش-16: fingerprint, or the device PIN as the fallback. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnlockScreen(
    busy: Boolean,
    message: String?,
    onFingerprint: () -> Unit,
    onPin: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("فتح الخزنة") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(120.dp).clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text("بصمة، أو رمز الجهاز احتياطاً.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (busy) {
                AnonLoader()
            } else {
                FilledTonalButton(onClick = onFingerprint) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("بصمة")
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    TextButton(onClick = onPin) { Text("استخدم الرمز") }
                }
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

/** ش-20: auto-lock, screenshot guard (always on), vault PIN / decoy / wipe, encrypted backup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettings(
    autoLock: AutoLock,
    keyLevel: KeyLevel?,
    busy: Boolean,
    message: String?,
    space: VaultSpace,
    pinsSet: Set<PinStore.Role>,
    pinOnly: Boolean,
    onPinOnly: (Boolean) -> Unit,
    onSetPin: (PinStore.Role, CharArray) -> Unit,
    onClearPin: (PinStore.Role) -> Unit,
    onAutoLock: (AutoLock) -> Unit,
    onExport: (Uri, CharArray) -> Unit,
    onImport: (Uri, CharArray) -> Unit,
    onBack: () -> Unit,
    onBeginSystemHop: () -> Unit = {},
    onEndSystemHop: () -> Unit = {},
) {
    var pendingExport by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { onEndSystemHop(); pendingExport = it }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onEndSystemHop(); pendingImport = it }
    // PIN flow: pick a role, enter twice. The first entry lives only in memory and is zeroed on every exit.
    var pinRole by rememberSaveable { mutableStateOf<PinStore.Role?>(null) }
    var pinFirst by remember { mutableStateOf<CharArray?>(null) }
    var pinMismatch by remember { mutableStateOf(false) }
    var pendingRemove by rememberSaveable { mutableStateOf<PinStore.Role?>(null) }
    DisposableEffect(Unit) { onDispose { pinFirst?.fill('\u0000') } }

    pinRole?.let { role ->
        PinPad(
            title = if (pinFirst == null) "أدخل رمز جديد" else "أعد إدخال الرمز",
            error = if (pinMismatch) "الرمزان غير متطابقين" else null,
            busy = false,
            lockedUntil = null,
            onSubmit = { pin ->
                val first = pinFirst
                if (first == null) {
                    pinFirst = pin
                    pinMismatch = false
                } else {
                    val same = first.contentEquals(pin)
                    first.fill('\u0000')
                    pinFirst = null
                    if (same) {
                        onSetPin(role, pin) // setPin zeroes the copy
                        pinRole = null
                    } else {
                        pin.fill('\u0000')
                        pinMismatch = true
                    }
                }
            },
            onBack = {
                pinFirst?.fill('\u0000')
                pinFirst = null
                pinMismatch = false
                pinRole = null
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("إعدادات الخزنة") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("الدخول", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (pinOnly) "رمز الخزنة" else "البصمة أو رمز الجهاز")
            if (space == VaultSpace.Real) DialCodeRow()
            if (space == VaultSpace.Real) {
                Spacer(Modifier.height(12.dp))
                Text("رمز الخزنة", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val realSet = PinStore.Role.Real in pinsSet
                PinRoleRow(
                    label = "رمز الدخول", subtitle = null, subtitleIsError = false,
                    isSet = realSet, enabled = true,
                    onSet = { pinRole = PinStore.Role.Real }, onRemove = { pendingRemove = PinStore.Role.Real },
                )
                PinRoleRow(
                    label = "رمز التمويه", subtitle = "يفتح خزنة بديلة فارغة يمكنك ملؤها بمحتوى عادي", subtitleIsError = false,
                    isSet = PinStore.Role.Decoy in pinsSet, enabled = realSet,
                    onSet = { pinRole = PinStore.Role.Decoy }, onRemove = { pendingRemove = PinStore.Role.Decoy },
                )
                PinRoleRow(
                    label = "رمز المسح", subtitle = "يمسح الخزنة الحقيقية بصمت ويفتح الخزنة البديلة. لا رجعة فيه", subtitleIsError = true,
                    isSet = PinStore.Role.Wipe in pinsSet, enabled = realSet,
                    onSet = { pinRole = PinStore.Role.Wipe }, onRemove = { pendingRemove = PinStore.Role.Wipe },
                )
                if (realSet) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("الدخول بالرمز فقط")
                            Text(
                                "بدون بصمة. المفتاح يبقى داخل شريحة الهاتف، لكن من يملك صلاحيات root قد يستعمله",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = pinOnly, onCheckedChange = onPinOnly, enabled = !busy)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("الحماية", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("القفل التلقائي عند الخروج من التطبيق", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutoLock.entries.forEach { opt ->
                Row(Modifier.fillMaxWidth().clickable { onAutoLock(opt) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = opt == autoLock, onClick = { onAutoLock(opt) })
                    Text(opt.label)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("منع لقطات الشاشة والتسجيل", Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
            Spacer(Modifier.height(20.dp))
            Text("النسخ والمفتاح", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (busy) {
                AnonLoader()
                Text(if (restoring) "جارٍ الاسترجاع…" else "جارٍ التشفير…")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onBeginSystemHop(); exportLauncher.launch("anon-vault-${LocalDate.now()}.anonvault") }) {
                        Icon(Icons.Rounded.Backup, null)
                        Spacer(Modifier.width(8.dp))
                        Text("نسخ احتياطي")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onBeginSystemHop(); importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.Restore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("استرجاع")
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("المفتاح الرئيسي: ${keyLevel?.label() ?: "غير معروف"}")
        }
    }

    pendingExport?.let { uri ->
        val cr = androidx.compose.ui.platform.LocalContext.current.contentResolver
        // cancelled: the file picker already created an empty file, remove it
        PasswordDialog("نسخ احتياطي مشفّر", confirm = true, onDismiss = {
            runCatching { android.provider.DocumentsContract.deleteDocument(cr, uri) }
            pendingExport = null
        }) { pw ->
            restoring = false
            pendingExport = null
            onExport(uri, pw)
        }
    }
    pendingImport?.let { uri ->
        PasswordDialog("استرجاع النسخة", confirm = false, onDismiss = { pendingImport = null }) { pw ->
            restoring = true
            pendingImport = null
            onImport(uri, pw)
        }
    }
    pendingRemove?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("إزالة الرمز؟") },
            text = { Text("لن يفتح هذا الرمز الخزنة بعد الآن.") },
            confirmButton = {
                TextButton(onClick = { onClearPin(role); pendingRemove = null }) {
                    Text("إزالة", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun PinRoleRow(
    label: String,
    subtitle: String?,
    subtitleIsError: Boolean,
    isSet: Boolean,
    enabled: Boolean,
    onSet: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSet) {
            TextButton(onClick = onSet, enabled = enabled) { Text("تغيير") }
            TextButton(onClick = onRemove, enabled = enabled) { Text("إزالة") }
        } else {
            TextButton(onClick = onSet, enabled = enabled) { Text("تعيين") }
        }
    }
}

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = if (confirm) pw.length >= 8 && pw == again else pw.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("كلمة المرور") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        again, { again = it }, singleLine = true, label = { Text("تأكيد") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("لا يمكن استرجاع النسخة إن نسيت كلمة المرور", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(pw.toCharArray()) }, enabled = ok) { Text("تأكيد") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

fun KeyLevel.label(): String = when (this) {
    KeyLevel.StrongBox -> "StrongBox (عتاد مستقل)"
    KeyLevel.Tee -> "TEE (المعالج الآمن)"
    KeyLevel.Software -> "برمجياً، ليس في العتاد"
}

/** "Open from the phone app": *#*#code#*#* in the dialer opens the vault (SecretCodeReceiver). */
@Composable
private fun DialCodeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableStateOf(SecretCodeReceiver.dialCode(ctx).orEmpty()) }
    var saved by remember { mutableStateOf(SecretCodeReceiver.dialCode(ctx)) }
    Spacer(Modifier.height(12.dp))
    Text("فتح من تطبيق الهاتف", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(8) },
            singleLine = true, label = { Text("رقم من ٤ إلى ٨ خانات") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = code.length in 4..8 && code != saved, onClick = { SecretCodeReceiver.setDialCode(ctx, code); saved = code }) { Text("حفظ") }
        if (saved != null) TextButton(onClick = { SecretCodeReceiver.setDialCode(ctx, null); saved = null; code = "" }) { Text("إيقاف") }
    }
    Text(
        saved?.let { "اطلب *#*#$it#*#* في تطبيق الهاتف لتفتح الخزنة" } ?: "متوقف",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
