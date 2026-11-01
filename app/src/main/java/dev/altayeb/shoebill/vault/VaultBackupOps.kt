package dev.altayeb.shoebill.vault

import android.app.Application
import android.net.Uri
import dev.altayeb.shoebill.R
import dev.altayeb.shoebill.vault.crypto.BackupFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.IOException

// Encrypted backup export/import (the .anonvault file).

internal fun VaultViewModel.exportBackup(uri: Uri, password: CharArray) = backupOp(password) {
    val r = checkNotNull(repo)
    authed { r.warmKeys() }
    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { r.exportBackup(it, password) }
        ?: throw IOException("no output")
    plural(R.plurals.vault_backup_saved, r.items.size)
}

internal fun VaultViewModel.importBackup(uri: Uri, password: CharArray) = backupOp(password) {
    val r = checkNotNull(repo)
    try {
        val n = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            r.importBackup(input, password, addOne = { add -> runBlocking { authed(add) } }) { done ->
                _ui.update { it.copy(message = plural(R.plurals.vault_restore_progress, done)) }
            }
        } ?: throw IOException("no input")
        plural(R.plurals.vault_restored, n)
    } finally {
        _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
    }
}

/** Runs a backup op on the IO dispatcher, maps its failures to localized messages and always zeroes the password. */
private fun VaultViewModel.backupOp(password: CharArray, block: suspend () -> String) = launchOp {
    try {
        val ok = block()
        _ui.update { it.copy(message = ok) }
    } catch (e: BackupFormat.BadPasswordException) {
        _ui.update { it.copy(message = str(R.string.vault_wrong_password)) }
    } catch (e: BackupFormat.CorruptBackupException) {
        _ui.update { it.copy(message = str(R.string.vault_bad_backup)) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        _ui.update { it.copy(message = str(R.string.vault_op_failed)) }
    } finally {
        password.fill('\u0000')
    }
}
