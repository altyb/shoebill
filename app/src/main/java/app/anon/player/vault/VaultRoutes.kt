package app.anon.player.vault

import app.anon.player.ui.SecureWindow
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider

// Entry point MainActivity calls. The vault owns this package; onPlay gets an anonfd:// decrypting descriptor (the player must set FLAG_SECURE).
@Composable
fun VaultScreen(onBack: () -> Unit, onPlay: (Uri, String) -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val vm = ViewModelProvider(activity, ViewModelProvider.AndroidViewModelFactory.getInstance(activity.application))[VaultViewModel::class.java]
    val ui by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Every vault screen is secret: no screenshots or recents thumbnail while it is shown.
    DisposableEffect(Unit) {
        SecureWindow.acquire(activity)
        onDispose { SecureWindow.release(activity) }
    }
    // Playback descriptors live while the vault is unlocked and are closed in lock(); not on re-composition, because
    // route transitions compose this screen while the player is starting.

    // Prompt whenever a Keystore call lapsed mid-operation.
    LaunchedEffect(ui.needAuth) {
        if (ui.needAuth) {
            vm.beginSystemHop()
            showVaultPrompt(activity, authAny(), { vm.endSystemHop(); vm.onAuthSucceeded() }, { vm.endSystemHop(); vm.onAuthFailed() })
        }
    }

    BackHandler {
        when {
            ui.viewing != null -> vm.view(null)
            settingsOpen -> settingsOpen = false
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (ui.phase) {
            Phase.Setup -> SetupGate(canGuard = canGuardVault(activity), onAccept = vm::createKey, onBack = onBack, onCreateWithPin = vm::createWithPin)
            Phase.Locked -> if (ui.pinRequired) {
                PinPad(
                    title = "أدخل رمز الخزنة",
                    error = ui.pinError,
                    busy = ui.pinBusy,
                    lockedUntil = ui.lockedUntil,
                    onSubmit = vm::submitPin,
                    onBack = onBack,
                )
            } else {
                UnlockScreen(
                    busy = ui.busy,
                    message = ui.message,
                    onFingerprint = { vm.beginSystemHop(); showVaultPrompt(activity, AUTH_FINGERPRINT, { vm.endSystemHop(); vm.onAuthSucceeded() }, { vm.endSystemHop(); vm.onAuthFailed() }) },
                    onPin = { vm.beginSystemHop(); showVaultPrompt(activity, AUTH_DEVICE, { vm.endSystemHop(); vm.onAuthSucceeded() }, { vm.endSystemHop(); vm.onAuthFailed() }) },
                    onBack = onBack,
                )
            }
            Phase.Unlocked -> if (settingsOpen) {
                VaultSettings(
                    autoLock = ui.autoLock,
                    keyLevel = ui.keyLevel,
                    busy = ui.busy,
                    message = ui.message,
                    space = ui.space,
                    pinsSet = ui.pinsSet,
                    pinOnly = ui.pinOnly,
                    onPinOnly = vm::setPinOnly,
                    onSetPin = vm::setPin,
                    onClearPin = vm::clearPin,
                    onAutoLock = vm::setAutoLock,
                    onExport = vm::exportBackup,
                    onImport = vm::importBackup,
                    onBack = { settingsOpen = false },
                    onBeginSystemHop = vm::beginSystemHop,
                    onEndSystemHop = vm::endSystemHop,
                )
            } else {
                VaultGrid(
                    ui = ui,
                    onLock = vm::lock,
                    onSettings = { settingsOpen = true },
                    onPlay = { id -> vm.play(id, onPlay) },
                    onView = vm::view,
                    onImport = vm::importMedia,
                    onBeginSystemHop = vm::beginSystemHop,
                    onEndSystemHop = vm::endSystemHop,
                    onDeleteDone = vm::onDeleteDialogDone,
                    onExport = vm::exportToGallery,
                    onDelete = vm::delete,
                    onReturn = vm::returnToGallery,
                    onDeleteMany = vm::deleteMany,
                    onFilter = vm::setFilter,
                    onMessageShown = vm::clearMessage,
                    decodePhoto = vm::decodePhoto,
                    nameOf = vm::nameOf,
                )
            }
        }
    }
}
