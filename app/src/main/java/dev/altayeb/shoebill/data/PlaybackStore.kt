package dev.altayeb.shoebill.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** What the app remembers per file (rule ق-12). Keyed by the content uri string. */
data class FileState(
    val key: String,
    val title: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val subtitle: String? = null,      // mpv sid, or an external path
    val subDelayMs: Long = 0,
    val audio: String? = null,         // mpv aid
    val hwdec: String? = null,         // "mediacodec" | "mediacodec-copy" | "no"
    val aspect: String? = null,
    val lastPlayed: Long = 0,
) {
    val unfinished get() = durationMs > 0 && positionMs in 5_000 until durationMs - 30_000
}

/** One shared store. ponytail: SharedPreferences + JSON per file; move to Room if the list grows past a few thousand. */
class PlaybackStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, FileState>> = _all

    fun get(key: String): FileState? = _all.value[key]

    fun save(s: FileState) {
        prefs.edit().putString(s.key, toJson(s).toString()).apply()
        _all.value = _all.value + (s.key to s)
    }

    fun forget(key: String) {
        prefs.edit().remove(key).apply()
        _all.value = _all.value - key
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        _all.value = emptyMap()
    }

    /** «تابع المشاهدة»: unfinished files, most recent first. */
    fun continueWatching(limit: Int = 10) =
        _all.value.values.filter { it.unfinished }.sortedByDescending { it.lastPlayed }.take(limit)

    private fun load(): Map<String, FileState> = prefs.all.mapNotNull { (k, v) ->
        runCatching { fromJson(k, JSONObject(v as String)) }.getOrNull()?.let { k to it }
    }.toMap()

    private fun toJson(s: FileState) = JSONObject().apply {
        put("title", s.title); put("pos", s.positionMs); put("dur", s.durationMs); put("sub", s.subtitle)
        put("subDelay", s.subDelayMs); put("audio", s.audio); put("hwdec", s.hwdec); put("aspect", s.aspect); put("last", s.lastPlayed)
    }

    private fun fromJson(k: String, j: JSONObject) = FileState(
        key = k, title = j.optString("title"), positionMs = j.optLong("pos"), durationMs = j.optLong("dur"),
        subtitle = j.optString("sub").ifEmpty { null }.takeIf { !j.isNull("sub") }, subDelayMs = j.optLong("subDelay"),
        audio = j.optString("audio").takeIf { !j.isNull("audio") && it.isNotEmpty() },
        hwdec = j.optString("hwdec").takeIf { !j.isNull("hwdec") && it.isNotEmpty() },
        aspect = j.optString("aspect").takeIf { !j.isNull("aspect") && it.isNotEmpty() }, lastPlayed = j.optLong("last"),
    )

    companion object {
        @Volatile private var instance: PlaybackStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PlaybackStore(context.applicationContext).also { instance = it }
        }
    }
}
