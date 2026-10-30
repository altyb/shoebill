package app.anon.player.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ColorSource { Wallpaper, Preset, Custom }
enum class SchemeStyle { TonalSpot, Vibrant, Expressive, Fidelity, Monochrome }
enum class DarkMode { System, Light, Dark }
enum class Contrast { Standard, Medium, High }
enum class Decoder(val hwdec: String) { HW("mediacodec"), HWPlus("mediacodec-copy"), SW("no") }

/** Subtitle look, applied to mpv by player/SubStyle.kt. Colors are ARGB. */
data class SubStyle(
    val scale: Float = 1f,
    val color: Long = 0xFFFFFFFF,
    val outline: Float = 2.5f,
    val outlineColor: Long = 0xFF000000,
    val box: Boolean = false,
    val boxAlpha: Float = 0.6f,
    val pos: Int = 100,
    val bold: Boolean = false,
    val force: Boolean = false,
)

/** Every user setting (rules ق-08, ق-12, ق-18). Shared by player, library, settings and theme. */
data class Settings(
    // appearance
    val colorSource: ColorSource = ColorSource.Wallpaper,
    val seed: Int = 0xFF0B6E69.toInt(),           // preset or custom seed (ARGB)
    val style: SchemeStyle = SchemeStyle.TonalSpot,
    val darkMode: DarkMode = DarkMode.System,
    val amoled: Boolean = false,
    val contrast: Contrast = Contrast.Standard,
    // player
    val decoder: Decoder = Decoder.HWPlus,
    val autoResume: Boolean = true,
    val playNext: Boolean = true,
    val gestures: Boolean = true,
    val backgroundAudio: Boolean = false,
    val subStyle: SubStyle = SubStyle(),
    // privacy
    val keepHistory: Boolean = true,
)

class AppSettings private constructor(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _s

    fun update(f: (Settings) -> Settings) {
        val n = f(_s.value)
        val s = n.subStyle
        p.edit()
            .putString("colorSource", n.colorSource.name).putInt("seed", n.seed).putString("style", n.style.name)
            .putString("darkMode", n.darkMode.name).putBoolean("amoled", n.amoled).putString("contrast", n.contrast.name)
            .putString("decoder", n.decoder.name).putBoolean("autoResume", n.autoResume).putBoolean("playNext", n.playNext)
            .putBoolean("gestures", n.gestures).putBoolean("backgroundAudio", n.backgroundAudio).putBoolean("keepHistory", n.keepHistory)
            .putFloat("sub_scale", s.scale).putLong("sub_color", s.color).putFloat("sub_outline", s.outline)
            .putLong("sub_outline_color", s.outlineColor).putBoolean("sub_box", s.box).putFloat("sub_box_alpha", s.boxAlpha)
            .putInt("sub_pos", s.pos).putBoolean("sub_bold", s.bold).putBoolean("sub_force", s.force)
            .apply()
        _s.value = n
    }

    private fun read() = Settings().let { d ->
        Settings(
            colorSource = enumOr(p.getString("colorSource", null), d.colorSource), seed = p.getInt("seed", d.seed),
            style = enumOr(p.getString("style", null), d.style), darkMode = enumOr(p.getString("darkMode", null), d.darkMode),
            amoled = p.getBoolean("amoled", d.amoled), contrast = enumOr(p.getString("contrast", null), d.contrast),
            decoder = enumOr(p.getString("decoder", null), d.decoder), autoResume = p.getBoolean("autoResume", d.autoResume),
            playNext = p.getBoolean("playNext", d.playNext), gestures = p.getBoolean("gestures", d.gestures),
            backgroundAudio = p.getBoolean("backgroundAudio", d.backgroundAudio), keepHistory = p.getBoolean("keepHistory", d.keepHistory),
            subStyle = readSub(),
        )
    }

    private fun readSub() = SubStyle().let { d ->
        SubStyle(
            scale = p.getFloat("sub_scale", d.scale), color = p.getLong("sub_color", d.color),
            outline = p.getFloat("sub_outline", d.outline), outlineColor = p.getLong("sub_outline_color", d.outlineColor),
            box = p.getBoolean("sub_box", d.box), boxAlpha = p.getFloat("sub_box_alpha", d.boxAlpha),
            pos = p.getInt("sub_pos", d.pos), bold = p.getBoolean("sub_bold", d.bold), force = p.getBoolean("sub_force", d.force),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, d: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: d

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
