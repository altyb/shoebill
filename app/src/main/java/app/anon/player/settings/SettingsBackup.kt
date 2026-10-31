package app.anon.player.settings

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Settings, playlists and watch history as one JSON file the user keeps. Subtitle API keys, network share passwords
 * and everything about the vault are never part of it.
 */
object SettingsBackup {
    private val FILES = listOf("settings", "playlists", "playback", "equalizer", "lang")
    private const val MAX_CHARS = 16 * 1024 * 1024

    fun write(context: Context, out: OutputStream) {
        val root = JSONObject().put("shoebill", 1)
        for (name in FILES) {
            val o = JSONObject()
            for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                when (v) {
                    is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
                }
            }
            root.put(name, o)
        }
        out.writer().use { it.write(root.toString()) }
    }

    /** Throws IOException unless this is a Shoebill file; nothing is changed in that case. */
    fun read(context: Context, input: InputStream) {
        val text = input.reader().use { r ->
            val buf = CharArray(MAX_CHARS + 1)
            var n = 0
            while (n <= MAX_CHARS) { val c = r.read(buf, n, buf.size - n); if (c < 0) break; n += c }
            if (n > MAX_CHARS) throw IOException("too large")
            String(buf, 0, n)
        }
        val root = try { JSONObject(text) } catch (e: org.json.JSONException) { throw IOException(e) }
        if (root.optInt("shoebill") != 1) throw IOException("not a Shoebill backup")
        for (name in FILES) {
            val o = root.optJSONObject(name) ?: continue
            val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val item = o.optJSONObject(k) ?: continue
                when (item.optString("t")) {
                    "b" -> e.putBoolean(k, item.optBoolean("v"))
                    "i" -> e.putInt(k, item.optInt("v"))
                    "l" -> e.putLong(k, item.optLong("v"))
                    "f" -> e.putFloat(k, item.optDouble("v").toFloat())
                    "s" -> e.putString(k, item.optString("v"))
                }
            }
            e.commit()
        }
    }
}
