package app.anon.player.vault

import android.util.Log
import kotlinx.coroutines.withContext
import android.content.ContentResolver
import android.content.ContentUris
import kotlinx.coroutines.runBlocking
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.keystore.UserNotAuthenticatedException
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.anon.player.vault.crypto.BackupFormat
import app.anon.player.vault.logic.PinStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class AutoLock(val seconds: Long, val label: String) {
    Immediately(0, "فوراً"), OneMinute(60, "بعد دقيقة"), FiveMinutes(300, "بعد 5 دقائق"),
}

enum class Phase { Setup, Locked, Unlocked }
enum class MediaFilter(val label: String) { All("الكل"), Video("فيديو"), Photo("صور") }

data class Importing(val done: Int, val total: Int, val current: String)

data class VaultUi(
    val phase: Phase,
    val busy: Boolean = false,
    val needAuth: Boolean = false,           // a Keystore call lapsed mid-operation: UI shows the prompt
    val entries: List<VaultEntry> = emptyList(),
    val thumbs: Map<String, ByteArray> = emptyMap(),
    val filter: MediaFilter = MediaFilter.All,
    val importing: Importing? = null,
    val message: String? = null,
    val deleteRequest: PendingIntent? = null, // system "delete originals?" dialog (API 30+)
    val autoLock: AutoLock = AutoLock.Immediately,
    val keyLevel: KeyLevel? = null,
    val viewing: String? = null,
    val pinRequired: Boolean = false,        // locked and a Real PIN is set: UI shows the PIN pad before the biometric prompt
    val pinError: String? = null,
    val lockedUntil: Long? = null,           // PinStore lockout end, epoch ms
    val pinBusy: Boolean = false,
    val space: VaultSpace = VaultSpace.Real,
    val pinsSet: Set<PinStore.Role> = emptySet(),
    val pinOnly: Boolean = false,            // Real vault opens with its PIN alone (no biometric prompt)
)

/** The user backed out of an auth prompt; the operation is dropped quietly. */
private class VaultAuthCancelled : Exception()

/**
 * Owns the unlocked vault. Keystore calls go through [authed]: if the 30 s window has lapsed, the op suspends,
 * the UI shows the biometric prompt, and the op retries. Nothing outlives [lock]: keys, thumbnails and open playback descriptors are wiped.
 */
class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("vault", android.content.Context.MODE_PRIVATE)
    private val pins = PinStore(object : PinStore.Storage {
        private val sp = app.getSharedPreferences("vault_pin", android.content.Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String?) {
            val e = sp.edit()
            if (value == null) e.remove(key) else e.putString(key, value)
            e.commit() // failed-attempt counters must survive a process kill
        }
    }, android.os.SystemClock::elapsedRealtime, 150_000, pepper = PinPepper::mac)
    private val _ui = MutableStateFlow(
        VaultUi(
            phase = if (gated()) Phase.Locked else Phase.Setup,
            autoLock = AutoLock.entries.firstOrNull { it.name == prefs.getString("autoLock", null) } ?: AutoLock.Immediately,
            pinRequired = gated() && pinGate(),
            pinsSet = pinsSet(),
            pinOnly = pinOnlyReal(),
        ),
    )
    val ui: StateFlow<VaultUi> = _ui.asStateFlow()

    private var repo: VaultRepo? = null
    // open vault file descriptors (one per playback); closing the channel ends the read side, so lock() kills playback
    private val openChannels = mutableListOf<java.nio.channels.SeekableByteChannel>()
    private val fdThread by lazy { android.os.HandlerThread("vault-fd").apply { start() } }
    private var opJob: Job? = null
    private var lockJob: Job? = null
    private var waiter: CompletableDeferred<Boolean>? = null
    private var systemHop = false // our own picker / delete dialog stops the activity: not a reason to lock
    // every repo read/write runs under this, and lock() wipes the keys under it too: a wipe never lands mid-write
    private val opMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var gen = 0 // bumped by lock(): an unlock that started before it must not open the vault after it

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            lockJob?.cancel()
            lockJob = null
        }
        override fun onActivityStopped(activity: Activity) {
            if (activity.isChangingConfigurations || repo == null) return
            // ponytail: while our picker/dialog is up we cannot tell "picking" from "went Home"; lock after 5 min either way
            if (systemHop) { lockJob = viewModelScope.launch { delay(5 * 60_000L); lock() }; return }
            val secs = _ui.value.autoLock.seconds
            if (secs == 0L) lock() else lockJob = viewModelScope.launch { delay(secs * 1000); lock() }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        app.registerActivityLifecycleCallbacks(activityCallbacks)
    }

    override fun onCleared() {
        getApplication<Application>().unregisterActivityLifecycleCallbacks(activityCallbacks)
        lock()
    }

    // ---- setup and unlock

    fun createKey() = launchOp {
        KeystoreMasterKey.create()
        _ui.update { it.copy(phase = Phase.Locked) }
    }

    /** New vault opened by its PIN alone: the Keystore key needs no fingerprint; the PIN pad (slow hash, lockout) guards it. */
    fun createWithPin(pin: CharArray) = launchOp {
        try {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.Default) { pins.setPin(PinStore.Role.Real, pin) }
            KeystoreMasterKey.create(KeystoreMasterKey.pinOnlyAlias(VaultSpace.Real.alias), requireAuth = false)
            KeystoreMasterKey.setPinOnlyFlag(ctx, VaultSpace.Real, true)
            _ui.update { it.copy(phase = Phase.Locked, pinRequired = true, pinsSet = it.pinsSet + PinStore.Role.Real, pinOnly = true) }
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(message = "الرمز ٤ إلى ١٦ رقماً") }
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Called when a biometric/PIN prompt succeeds: resumes a suspended op, or starts an unlock. */
    fun onAuthSucceeded() {
        val w = waiter
        if (w != null) w.complete(true) else unlock()
    }

    fun onAuthFailed() {
        waiter?.complete(false)
    }

    private fun unlock() {
        val g = gen
        launchOp { unlockNow(g) }
    }

    private suspend fun unlockNow(g: Int) {
        val space = _ui.value.space
        val r = try {
            authed { VaultRepo.open(getApplication(), space) }
        } catch (t: Throwable) {
            _ui.update { it.copy(pinBusy = false) }
            throw t
        }
        if (g != gen) { r.wipe(); return } // locked while it was opening
        repo = r
        _ui.update {
            it.copy(
                phase = Phase.Unlocked, entries = r.items, thumbs = r.thumbnails(), pinBusy = false, pinRequired = false,
                keyLevel = runCatching { KeystoreMasterKey.securityLevel(KeystoreMasterKey.activeAlias(getApplication(), space)) }.getOrNull(),
            )
        }
    }

    /** A chip key without vault files is an orphan ("Clear data" keeps Keystore keys): drop it so setup shows. */
    private fun realKeyed(): Boolean {
        val app = getApplication<Application>()
        val alias = KeystoreMasterKey.activeAlias(app, VaultSpace.Real)
        if (!KeystoreMasterKey.exists(alias)) return false
        val dir = java.io.File(app.noBackupFilesDir, VaultSpace.Real.dir)
        // missing or empty only: list() is also null on an I/O error, and then the vault must be kept
        if (!dir.exists() || dir.list()?.isEmpty() == true) {
            KeystoreMasterKey.delete(alias)
            KeystoreMasterKey.delete(KeystoreMasterKey.pinOnlyAlias(VaultSpace.Real.alias))
            return false
        }
        return true
    }
    /** The PIN pad shows while a real vault exists, or a decoy PIN is left after a wipe (so a wipe looks like nothing). */
    private fun gated() = realKeyed() || pins.isSet(PinStore.Role.Decoy)
    private fun pinGate() = pins.isSet(PinStore.Role.Real) || pins.isSet(PinStore.Role.Decoy)
    private fun pinOnlyReal() = KeystoreMasterKey.pinOnly(getApplication(), VaultSpace.Real)

    fun lock() {
        lockJob?.cancel()
        lockJob = null
        opJob?.cancel()
        stopPlayback()
        gen++
        val r = repo
        repo = null
        // a running op keeps its own reference until it finishes; wipe after it, never under it
        if (r != null) kotlin.concurrent.thread(name = "vault-wipe") { kotlinx.coroutines.runBlocking { opMutex.withLock { r.wipe() } } }
        waiter?.complete(false)
        waiter = null
        // A wipe PIN deletes the real key: with no key left the next session starts at Setup, not at a dead unlock.
        val keyed = gated()
        _ui.update {
            VaultUi(
                phase = if (keyed) Phase.Locked else Phase.Setup, autoLock = it.autoLock,
                pinRequired = keyed && pinGate(), pinsSet = it.pinsSet, pinOnly = pinOnlyReal(),
            )
        }
    }

    // ---- PIN gate: Real opens the real vault, Decoy a decoy vault, Wipe deletes the real vault and then shows the decoy

    fun submitPin(pin: CharArray) {
        if (_ui.value.pinBusy) { pin.fill('\u0000'); return }
        _ui.update { it.copy(pinBusy = true, pinError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val r = try { pins.check(pin) } finally { pin.fill('\u0000') }
            when (r) {
                is PinStore.Result.Ok -> {
                    val wipe = r.role == PinStore.Role.Wipe
                    if (wipe) pins.forget(PinStore.Role.Real)
                    val space = if (r.role == PinStore.Role.Real) VaultSpace.Real else VaultSpace.Decoy
                    val app = getApplication<Application>()
                    // The decoy must behave exactly like the real vault: same unlock mode. An empty decoy is re-keyed to match.
                    if (space == VaultSpace.Decoy) {
                        val realMode = KeystoreMasterKey.pinOnly(app, VaultSpace.Real)
                        val decoyDir = java.io.File(app.noBackupFilesDir, VaultSpace.Decoy.dir)
                        if (KeystoreMasterKey.pinOnly(app, VaultSpace.Decoy) != realMode && decoyDir.list().isNullOrEmpty()) {
                            KeystoreMasterKey.delete(VaultSpace.Decoy.alias)
                            KeystoreMasterKey.delete(KeystoreMasterKey.pinOnlyAlias(VaultSpace.Decoy.alias))
                            KeystoreMasterKey.setPinOnlyFlag(app, VaultSpace.Decoy, realMode)
                        }
                    }
                    val pinOnly = KeystoreMasterKey.pinOnly(app, space)
                    val alias = KeystoreMasterKey.activeAlias(app, space)
                    if (space == VaultSpace.Decoy && !KeystoreMasterKey.exists(alias)) KeystoreMasterKey.create(alias, requireAuth = !pinOnly)
                    // PIN-only: the PIN pad keeps its loader until the vault is open (one wait, no second screen);
                    // otherwise it hands over to the fingerprint prompt.
                    _ui.update { it.copy(pinBusy = pinOnly, pinRequired = pinOnly, space = space, pinsSet = pinsSet()) }
                    // Files go after the UI update, so the wipe answers as fast as the decoy does.
                    if (wipe) VaultRepo.wipeAll(app, VaultSpace.Real)
                    if (pinOnly) unlock()
                }
                is PinStore.Result.Wrong ->
                    _ui.update { it.copy(pinBusy = false, pinError = "رمز خاطئ. بقيت ${r.attemptsLeft} محاولات") }
                is PinStore.Result.LockedOut ->
                    _ui.update { it.copy(pinBusy = false, lockedUntil = System.currentTimeMillis() + (r.untilMs - android.os.SystemClock.elapsedRealtime()), pinError = "محاولات كثيرة. انتظر") }
            }
        }
    }

    /** Settings (Real space only). Sets or replaces one role's PIN. */
    fun setPin(role: PinStore.Role, pin: CharArray) {
        _ui.update { it.copy(pinBusy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                pins.setPin(role, pin)
            } catch (e: IllegalArgumentException) {
                _ui.update { it.copy(message = "الرمز ٤ إلى ١٦ رقماً ومختلف عن الرموز الأخرى") }
            } finally {
                pin.fill('\u0000')
                _ui.update { it.copy(pinBusy = false, pinsSet = pinsSet()) }
            }
        }
    }

    fun clearPin(role: PinStore.Role) = launchOp {
        // A PIN-only vault never loses its PIN: back to the biometric key first, then the PIN goes.
        if (role == PinStore.Role.Real && pinOnlyReal() && !switchPinOnly(false)) return@launchOp
        pins.clear(role)
        _ui.update { it.copy(pinsSet = pinsSet()) }
    }

    /** Real space, vault unlocked. Moves the vault key to the PIN-only alias (no auth) or back to the biometric alias. */
    fun setPinOnly(enabled: Boolean) = launchOp {
        if (switchPinOnly(enabled)) {
            _ui.update { it.copy(message = if (enabled) "صار الدخول بالرمز فقط" else "صار الدخول بالرمز والبصمة") }
        }
    }

    /** Returns false (with a message) when the switch did not happen. The old key is deleted only after the rekey committed. */
    private suspend fun switchPinOnly(enabled: Boolean): Boolean {
        val app = getApplication<Application>()
        val r = repo
        if (r == null) { _ui.update { it.copy(message = "افتح الخزنة أولاً") }; return false }
        if (PinStore.Role.Real !in pinsSet()) { _ui.update { it.copy(message = "عيّن رمز الدخول أولاً") }; return false }
        val space = VaultSpace.Real
        val old = KeystoreMasterKey.activeAlias(app, space)
        val target = if (enabled) KeystoreMasterKey.pinOnlyAlias(space.alias) else space.alias
        if (old != target) {
            KeystoreMasterKey.delete(target) // leftover from an earlier failed switch
            KeystoreMasterKey.create(target, requireAuth = !enabled)
            try {
                authed { r.rewrapDataKey(KeystoreMasterKey.load(target)) }
            } catch (t: Throwable) {
                KeystoreMasterKey.delete(target)
                throw t
            }
            KeystoreMasterKey.setPinOnlyFlag(app, space, enabled)
            KeystoreMasterKey.delete(old)
            _ui.update { it.copy(keyLevel = runCatching { KeystoreMasterKey.securityLevel(target) }.getOrNull()) }
        }
        _ui.update { it.copy(pinOnly = enabled) }
        return true
    }

    /** isSet is a plain storage read, no hashing, so it runs on the caller's thread. */
    private fun pinsSet(): Set<PinStore.Role> = PinStore.Role.entries.filter { pins.isSet(it) }.toSet()

    // ---- import (ق-15)

    fun beginSystemHop() { systemHop = true }
    fun endSystemHop() { systemHop = false }

    fun importMedia(uris: List<Uri>) = launchOp {
        val r = repo ?: return@launchOp
        val verified = mutableListOf<Uri>()
        var failed = 0
        try {
            uris.forEachIndexed { i, uri ->
                currentCoroutineContext().ensureActive()
                val name = displayName(uri)
                _ui.update { it.copy(importing = Importing(i, uris.size, name)) }
                try {
                    authed { addOne(r, uri, name) }
                    verified += uri
                } catch (e: VaultAuthCancelled) {
                    throw e
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("vault", "add failed: ${e.javaClass.simpleName}") // type only: messages can hold file names
                    failed++
                }
                _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
            }
        } finally {
            _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
        }
        val app = getApplication<Application>()
        val parts = mutableListOf("أُضيف ${verified.size} ملف إلى الخزنة")
        if (failed > 0) parts += "فشل التحقق من $failed ملف، فبقيت أصولها في المعرض"
        if (verified.isEmpty()) {
            _ui.update { it.copy(message = parts.joinToString(" · ")) }
            return@launchOp
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // the photo picker hands out media/picker/... links; the delete request only takes real MediaStore items
            val mapped = verified.map(::mediaStoreUri)
            // link shapes only (authority + path without the id), never names
            // With "All files access" the vault moves files: originals are deleted here, no dialog. Without it,
            // Android requires its "Allow … to delete?" dialog.
            val leftover = if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                mapped.filter { u -> runCatching { app.contentResolver.delete(u, null, null) > 0 }.getOrDefault(false).not() }
            } else mapped
            val pi = if (leftover.isEmpty()) null else runCatching { MediaStore.createDeleteRequest(app.contentResolver, leftover) }
                .onFailure { Log.w("vault", "createDeleteRequest failed", it) }.getOrNull()
            if (leftover.isNotEmpty() && pi == null) parts += "تعذّر حذف الأصول تلقائياً، احذفها من المعرض"
            _ui.update { it.copy(deleteRequest = pi, message = parts.joinToString(" · ")) }
        } else {
            parts += "أندرويد 10 أو أقدم: الأصول باقية في المعرض، احذفها يدوياً"
            _ui.update { it.copy(message = parts.joinToString(" · ")) }
        }
    }

    private fun mediaStoreUri(u: Uri): Uri {
        if (u.authority != MediaStore.AUTHORITY || "picker" !in u.pathSegments.joinToString("/")) return u
        val id = u.lastPathSegment?.toLongOrNull() ?: return u
        val mime = getApplication<Application>().contentResolver.getType(u).orEmpty()
        val collection = if (mime.startsWith("image/")) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return ContentUris.withAppendedId(collection, id)
    }

    fun onDeleteDialogDone() = _ui.update { it.copy(deleteRequest = null) }

    private fun addOne(r: VaultRepo, uri: Uri, name: String) {
        val cr = getApplication<Application>().contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val video = mime.startsWith("video/")
        // from the source, before anything is encrypted; a format the phone cannot decode just gets the placeholder tile
        val thumb = runCatching { makeThumb(uri) }.getOrNull()
        val duration = if (video) durationOf(uri) else 0L
        r.add(name, mime, duration, thumb, originalFolder(uri)) { cr.openInputStream(uri) ?: throw IOException("no input") }
    }

    /** Original gallery folder (e.g. "DCIM/Camera/"), or null when it cannot be read (no permission, picker, error). */
    private fun originalFolder(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(mediaStoreUri(uri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun makeThumb(uri: Uri): ByteArray? {
        // ponytail: pre-Android 10 gets no thumbnail (placeholder tile); add a decode fallback if that matters.
        if (Build.VERSION.SDK_INT < 29) return null
        val bmp = getApplication<Application>().contentResolver.loadThumbnail(uri, Size(320, 180), null)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun durationOf(uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(getApplication(), uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            mmr.release()
        }
    }

    // The photo picker reports names like "754.mp4" (its own id); the real name lives on the gallery row, so ask that
    // first and fall back to the picker's name, then to the link itself.
    private fun displayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        fun nameOf(u: Uri) = runCatching {
            cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return nameOf(mediaStoreUri(uri)) ?: nameOf(uri) ?: uri.lastPathSegment.orEmpty()
    }

    // ---- item actions

    fun delete(id: String) = launchOp {
        val r = repo ?: return@launchOp
        authed { r.remove(id) }
        _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
    }

    fun exportToGallery(id: String) = returnToGallery(setOf(id))

    /**
     * Moves items back to the gallery: each goes into a new MediaStore row in its original folder (default folder if that
     * is refused), and leaves the vault only after its write succeeds. A failed write deletes its half-written row.
     */
    fun returnToGallery(ids: Set<String>) = launchOp {
        val r = repo ?: return@launchOp
        val list = ids.mapNotNull { r.find(it) }
        if (list.isEmpty()) return@launchOp
        if (Build.VERSION.SDK_INT < 29) {
            _ui.update { it.copy(message = "التصدير إلى المعرض يحتاج أندرويد 10 أو أحدث") }
            return@launchOp
        }
        val cr = getApplication<Application>().contentResolver
        var ok = 0
        var failed = 0
        try {
            list.forEachIndexed { i, e ->
                currentCoroutineContext().ensureActive()
                _ui.update { it.copy(message = "يُرجع ${i + 1} من ${list.size}…") }
                val fallback = if (e.kind == "video") Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val name = r.nameOf(e)
                val original = r.pathOf(e)
                val target = insertPending(cr, e, name, original ?: fallback)
                    ?: original?.takeIf { it != fallback }?.let { insertPending(cr, e, name, fallback) }
                if (target == null) {
                    failed++
                    return@forEachIndexed
                }
                try {
                    // a retry reopens the output in "w" mode, so a re-prompt restarts the write instead of appending
                    authed { cr.openOutputStream(target)?.use { r.exportTo(e, it) } ?: throw IOException("no output") }
                    cr.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    authed { r.remove(e.id) }
                    ok++
                } catch (t: Throwable) {
                    cr.delete(target, null, null)
                    if (t is CancellationException || t is VaultAuthCancelled) throw t
                    failed++
                }
            }
        } finally {
            _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
        }
        _ui.update { it.copy(message = buildString { append("أُرجع $ok ملف إلى المعرض"); if (failed > 0) append(" · فشل $failed") }) }
    }

    private fun insertPending(cr: ContentResolver, e: VaultEntry, name: String, folder: String): Uri? = runCatching {
        cr.insert(
            if (e.kind == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )
    }.getOrNull()

    /** Permanent delete of several vault items. */
    fun deleteMany(ids: Set<String>) = launchOp {
        val r = repo ?: return@launchOp
        var n = 0
        try {
            for (id in ids) if (r.find(id) != null) {
                authed { r.remove(id) }
                n++
            }
        } finally {
            _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
        }
        _ui.update { it.copy(message = "حُذف $n ملف") }
    }

    /** Full plaintext photo in memory for the viewer. Not stored in state. */
    fun decodePhoto(id: String): Bitmap? {
        val r = repo ?: return null
        val e = r.find(id) ?: return null
        val bytes = ByteArrayOutputStream().also { r.exportTo(e, it) }.toByteArray()
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            bytes.fill(0)
        }
    }

    // ---- encrypted backup

    fun exportBackup(uri: Uri, password: CharArray) = backupOp(password) {
        val r = checkNotNull(repo)
        authed { r.warmKeys() }
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { r.exportBackup(it, password) }
            ?: throw IOException("no output")
        "حُفظت النسخة: ${r.items.size} ملف"
    }

    fun importBackup(uri: Uri, password: CharArray) = backupOp(password) {
        val r = checkNotNull(repo)
        try {
            val n = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                r.importBackup(input, password, addOne = { add -> runBlocking { authed(add) } }) { done ->
                    _ui.update { it.copy(message = "استُرجع $done ملف…") }
                }
            } ?: throw IOException("no input")
            "استُرجع $n ملف"
        } finally {
            _ui.update { it.copy(entries = r.items, thumbs = r.thumbnails()) }
        }
    }

    /** Runs a backup op on the IO dispatcher, maps its failures to Arabic messages and always zeroes the password. */
    private fun backupOp(password: CharArray, block: suspend () -> String) = launchOp {
        try {
            val ok = block()
            _ui.update { it.copy(message = ok) }
        } catch (e: BackupFormat.BadPasswordException) {
            _ui.update { it.copy(message = "كلمة المرور غير صحيحة") }
        } catch (e: BackupFormat.CorruptBackupException) {
            _ui.update { it.copy(message = "الملف تالف أو ليس نسخة من الخزنة") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _ui.update { it.copy(message = "تعذّر إكمال العملية") }
        } finally {
            password.fill('\u0000')
        }
    }

    // ---- playback (ق-14)

    /** Hands the player a decrypting proxy file descriptor (VaultFd.kt): seekable, in memory, no sockets. Closed by [stopPlayback]/lock. */
    fun play(id: String, onPlay: (Uri, String) -> Unit) = launchOp {
        val r = repo ?: return@launchOp
        val e = r.find(id) ?: return@launchOp
        // reads happen on the fd thread, which cannot show a prompt: unwrap the key here (re-prompts if needed)
        authed { r.warmKey(e) }
        val ch = r.openChannel(e)
        synchronized(openChannels) { openChannels += ch }
        val sm = getApplication<Application>().getSystemService(android.os.storage.StorageManager::class.java)
        val pfd = sm.openProxyFileDescriptor(
            android.os.ParcelFileDescriptor.MODE_READ_ONLY, VaultFdCallback(ch, e.size), android.os.Handler(fdThread.looper),
        )
        val name = r.nameOf(e)
        // the player takes ownership of the fd (mpv's fdclose://)
        withContext(Dispatchers.Main) { onPlay(Uri.parse("anonfd://${pfd.detachFd()}"), name) }
    }

    /** Called when the player route is gone (VaultScreen re-enters composition). */
    fun stopPlayback() {
        synchronized(openChannels) {
            openChannels.forEach { runCatching { it.close() } }
            openChannels.clear()
        }
    }

    // ---- ui state

    fun nameOf(id: String): String = repo?.let { r -> r.find(id)?.let { r.nameOf(it) } }.orEmpty()
    fun view(id: String?) = _ui.update { it.copy(viewing = id) }
    fun setFilter(f: MediaFilter) = _ui.update { it.copy(filter = f) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun setAutoLock(a: AutoLock) {
        prefs.edit().putString("autoLock", a.name).apply()
        _ui.update { it.copy(autoLock = a) }
    }

    // ---- plumbing

    private fun launchOp(block: suspend () -> Unit) {
        opJob = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                opMutex.withLock { block() }
            } catch (e: VaultAuthCancelled) {
                // user backed out of the prompt
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(message = "تعذّرت العملية: ${e.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(busy = false, importing = null) }
            }
        }
    }

    /** Runs [block]; if Keystore refuses because the 30 s window lapsed, waits for a fresh prompt and retries. */
    private suspend fun <T> authed(block: () -> T): T {
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.none { it is UserNotAuthenticatedException }) throw e
            }
            val w = CompletableDeferred<Boolean>().also { waiter = it }
            _ui.update { it.copy(needAuth = true) }
            val ok = try {
                w.await()
            } finally {
                waiter = null
                _ui.update { it.copy(needAuth = false) }
            }
            if (!ok) throw VaultAuthCancelled()
        }
    }
}
