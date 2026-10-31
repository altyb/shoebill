package dev.altayeb.shoebill.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.ui.BrandLoader
import androidx.compose.ui.res.stringResource
import dev.altayeb.shoebill.vault.logic.PinStore
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

/** First run: the rule ق-16 has to be acknowledged before the key exists. */
@Composable
fun SetupGate(canGuard: Boolean, onAccept: () -> Unit, onBack: () -> Unit, onCreateWithPin: (CharArray) -> Unit) {
    var understood by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }
    val mismatchText = stringResource(R.string.vault_pins_mismatch)
    if (pinFlow) {
        PinPad(
            title = if (first == null) stringResource(R.string.vault_choose_pin) else stringResource(R.string.vault_reenter_pin),
            error = mismatch, busy = false, lockedUntil = null,
            onSubmit = { pin ->
                val f = first
                if (f == null) { first = pin; mismatch = null }
                else if (f.contentEquals(pin)) { f.fill('\u0000'); first = null; onCreateWithPin(pin) }
                else { f.fill('\u0000'); pin.fill('\u0000'); first = null; mismatch = mismatchText }
            },
            onBack = { pinFlow = false; first?.fill('\u0000'); first = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.vault_before_create)) },
        text = {
            Column {
                Text(if (canGuard) stringResource(R.string.vault_rule_hint) else stringResource(R.string.vault_need_screen_lock))
                if (canGuard) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text(stringResource(R.string.vault_understood))
                    }
                }
            }
        },
        confirmButton = {
            if (canGuard) Button(onClick = onAccept, enabled = understood) { Text(stringResource(R.string.vault_create)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { pinFlow = true }) { Text(stringResource(R.string.vault_pin_only_short)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
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
            title = { Text(stringResource(R.string.vault_unlock)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
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
            Text(stringResource(R.string.vault_unlock_hint), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (busy) {
                BrandLoader()
            } else {
                FilledTonalButton(onClick = onFingerprint) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vault_fingerprint))
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    TextButton(onClick = onPin) { Text(stringResource(R.string.vault_use_pin)) }
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
            title = if (pinFirst == null) stringResource(R.string.vault_new_pin) else stringResource(R.string.vault_reenter_pin),
            error = if (pinMismatch) stringResource(R.string.vault_pins_mismatch) else null,
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
            title = { Text(stringResource(R.string.vault_settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.vault_access), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (pinOnly) stringResource(R.string.vault_pin) else stringResource(R.string.vault_fingerprint_or_device))
            if (space == VaultSpace.Real) DialCodeRow()
            if (space == VaultSpace.Real) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.vault_pin), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val realSet = PinStore.Role.Real in pinsSet
                PinRoleRow(
                    label = stringResource(R.string.vault_pin_real), subtitle = null, subtitleIsError = false,
                    isSet = realSet, enabled = true,
                    onSet = { pinRole = PinStore.Role.Real }, onRemove = { pendingRemove = PinStore.Role.Real },
                )
                PinRoleRow(
                    label = stringResource(R.string.vault_pin_decoy), subtitle = stringResource(R.string.vault_pin_decoy_hint), subtitleIsError = false,
                    isSet = PinStore.Role.Decoy in pinsSet, enabled = realSet,
                    onSet = { pinRole = PinStore.Role.Decoy }, onRemove = { pendingRemove = PinStore.Role.Decoy },
                )
                PinRoleRow(
                    label = stringResource(R.string.vault_pin_wipe), subtitle = stringResource(R.string.vault_pin_wipe_hint), subtitleIsError = true,
                    isSet = PinStore.Role.Wipe in pinsSet, enabled = realSet,
                    onSet = { pinRole = PinStore.Role.Wipe }, onRemove = { pendingRemove = PinStore.Role.Wipe },
                )
                if (realSet) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.vault_pin_only))
                            Text(
                                stringResource(R.string.vault_pin_only_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = pinOnly, onCheckedChange = onPinOnly, enabled = !busy)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.vault_protection), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.vault_auto_lock), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutoLock.entries.forEach { opt ->
                Row(Modifier.fillMaxWidth().clickable { onAutoLock(opt) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = opt == autoLock, onClick = { onAutoLock(opt) })
                    Text(stringResource(opt.label))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vault_block_screenshots), Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.vault_backup_and_key), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (busy) {
                BrandLoader()
                Text(if (restoring) stringResource(R.string.vault_restoring) else stringResource(R.string.vault_encrypting))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onBeginSystemHop(); exportLauncher.launch("shoebill-vault-${LocalDate.now()}.shoebillvault") }) {
                        Icon(Icons.Rounded.Backup, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_backup))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onBeginSystemHop(); importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.Restore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_restore))
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.vault_master_key, stringResource(keyLevel?.label() ?: R.string.vault_key_unknown)))
        }
    }

    pendingExport?.let { uri ->
        val cr = androidx.compose.ui.platform.LocalContext.current.contentResolver
        // cancelled: the file picker already created an empty file, remove it
        PasswordDialog(stringResource(R.string.vault_encrypted_backup), confirm = true, onDismiss = {
            runCatching { android.provider.DocumentsContract.deleteDocument(cr, uri) }
            pendingExport = null
        }) { pw ->
            restoring = false
            pendingExport = null
            onExport(uri, pw)
        }
    }
    pendingImport?.let { uri ->
        PasswordDialog(stringResource(R.string.vault_restore_backup), confirm = false, onDismiss = { pendingImport = null }) { pw ->
            restoring = true
            pendingImport = null
            onImport(uri, pw)
        }
    }
    pendingRemove?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.vault_remove_pin_title)) },
            text = { Text(stringResource(R.string.vault_remove_pin_body)) },
            confirmButton = {
                TextButton(onClick = { onClearPin(role); pendingRemove = null }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.cancel)) } },
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
            TextButton(onClick = onSet, enabled = enabled) { Text(stringResource(R.string.change)) }
            TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(R.string.remove)) }
        } else {
            TextButton(onClick = onSet, enabled = enabled) { Text(stringResource(R.string.vault_set)) }
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
                    pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.vault_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        again, { again = it }, singleLine = true, label = { Text(stringResource(R.string.confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.vault_password_warning), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(pw.toCharArray()) }, enabled = ok) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun KeyLevel.label(): Int = when (this) {
    KeyLevel.StrongBox -> R.string.vault_key_strongbox
    KeyLevel.Tee -> R.string.vault_key_tee
    KeyLevel.Software -> R.string.vault_key_software
}

/** "Open from the phone app": *#*#code#*#* in the dialer opens the vault (SecretCodeReceiver). */
@Composable
private fun DialCodeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableStateOf(SecretCodeReceiver.dialCode(ctx).orEmpty()) }
    var saved by remember { mutableStateOf(SecretCodeReceiver.dialCode(ctx)) }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.vault_dial_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(8) },
            singleLine = true, label = { Text(stringResource(R.string.vault_dial_hint)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = code.length in 4..8 && code != saved, onClick = { SecretCodeReceiver.setDialCode(ctx, code); saved = code }) { Text(stringResource(R.string.save)) }
        if (saved != null) TextButton(onClick = { SecretCodeReceiver.setDialCode(ctx, null); saved = null; code = "" }) { Text(stringResource(R.string.off)) }
    }
    Text(
        saved?.let { stringResource(R.string.vault_dial_info, it) } ?: stringResource(R.string.vault_dial_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
