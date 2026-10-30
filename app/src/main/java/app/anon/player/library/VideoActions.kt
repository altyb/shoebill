package app.anon.player.library

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rename, trash, share. Rename and trash go through the system confirmation (API 30+).
 * Nothing is deleted outright: trash goes to the system bin for 30 days (rule ق-11).
 */
class VideoActions internal constructor(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val confirm: (IntentSender, () -> Unit) -> Unit,
    private val onChanged: () -> Unit,
) {
    fun share(videos: List<Video>) {
        val i = if (videos.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, videos[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(videos.map { it.uri }))
        }
        i.type = "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    }

    fun rename(v: Video, name: String, done: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 30) return
        val sender = MediaStore.createWriteRequest(ctx.contentResolver, listOf(v.uri)).intentSender
        confirm(sender) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) }, null, null)
                }
                done()
                onChanged()
            }
        }
    }

    fun trash(videos: List<Video>, done: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 30) return
        val sender = MediaStore.createTrashRequest(ctx.contentResolver, videos.map { it.uri }, true).intentSender
        confirm(sender) { done(); onChanged() }
    }
}

@Composable
fun rememberVideoActions(onChanged: () -> Unit): VideoActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val done = pending.value
        pending.value = null
        if (r.resultCode == Activity.RESULT_OK) done?.invoke()
    }
    return remember(ctx, scope) {
        VideoActions(
            ctx, scope,
            { sender, done ->
                pending.value = done
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            },
            { changed() },
        )
    }
}
