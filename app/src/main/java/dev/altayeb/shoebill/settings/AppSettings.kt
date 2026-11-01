package dev.altayeb.shoebill.settings

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
    val colorSource: ColorSource = ColorSource.Preset,
    val seed: Int = 0xFF2F6F8F.toInt(),           // preset or custom seed (ARGB)
    val style: SchemeStyle = SchemeStyle.TonalSpot,
    val darkMode: DarkMode = DarkMode.System,
    val amoled: Boolean = false,
    val contrast: Contrast = Contrast.Standard,
    // player
    val decoder: Decoder = Decoder.HWPlus,
    val deinterlace: Boolean = false, // mpv deinterlace=auto when on; needs a copy decoder (HW+ or SW)
    val autoResume: Boolean = true,
    val playNext: Boolean = true,
    val gestures: Boolean = true,
    val backgroundAudio: Boolean = false,
    val subStyle: SubStyle = SubStyle(),
    // privacy
    val keepHistory: Boolean = true,
    val defaultSpeed: Float = 1f,
    val loopOne: Boolean = false,
    val autoPip: Boolean = true,
    val orientation: Int = 0, // 0 landscape, 1 auto-rotate, 2 follow the video, 3 portrait
    val showRemaining: Boolean = true,
    val rememberBrightness: Boolean = false,
    val brightness: Float = -1f,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSeek: Boolean = true,
    val gestureDoubleTap: Boolean = true,
    val gesturePinch: Boolean = true,
    val holdSpeed: Float = 2f, // 0 is off
    val audioLang: String = "",
    val subLang: String = "",
    val maxVolume: Int = 200,
    val controlsHideSec: Int = 3,
    val doubleTapSec: Int = 10,
    val skipSilence: Boolean = false,
    val rewindBeforePrevious: Boolean = true,
    val pauseOnUnplug: Boolean = true,
    val pauseForOthers: Boolean = true,
    val stopOnClose: Boolean = false,
    val rememberQueue: Boolean = true,
    val minSongSec: Int = 0,
    val hiddenFolders: Set<String> = emptySet(),
    val musicStartTab: Int = 0,
    val coverMorph: Boolean = true,
    val lyricsLarge: Boolean = false,
    val keepScreenOnMusic: Boolean = false,
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
            .putString("decoder", n.decoder.name).putBoolean("deinterlace", n.deinterlace).putBoolean("autoResume", n.autoResume).putBoolean("playNext", n.playNext)
            .putBoolean("gestures", n.gestures).putBoolean("backgroundAudio", n.backgroundAudio).putBoolean("keepHistory", n.keepHistory)
            .putFloat("defaultSpeed", n.defaultSpeed).putBoolean("loopOne", n.loopOne).putBoolean("autoPip", n.autoPip).putInt("orientation", n.orientation)
            .putBoolean("showRemaining", n.showRemaining).putBoolean("rememberBrightness", n.rememberBrightness).putFloat("brightness", n.brightness)
            .putBoolean("gestureBrightness", n.gestureBrightness).putBoolean("gestureVolume", n.gestureVolume).putBoolean("gestureSeek", n.gestureSeek)
            .putBoolean("gestureDoubleTap", n.gestureDoubleTap).putBoolean("gesturePinch", n.gesturePinch).putFloat("holdSpeed", n.holdSpeed)
            .putString("audioLang", n.audioLang).putString("subLang", n.subLang).putInt("maxVolume", n.maxVolume)
            .putInt("controlsHideSec", n.controlsHideSec).putInt("doubleTapSec", n.doubleTapSec).putBoolean("skipSilence", n.skipSilence)
            .putBoolean("rewindBeforePrevious", n.rewindBeforePrevious).putBoolean("pauseOnUnplug", n.pauseOnUnplug).putBoolean("pauseForOthers", n.pauseForOthers)
            .putBoolean("stopOnClose", n.stopOnClose).putBoolean("rememberQueue", n.rememberQueue).putInt("minSongSec", n.minSongSec)
            .putStringSet("hiddenFolders", n.hiddenFolders).putInt("musicStartTab", n.musicStartTab).putBoolean("coverMorph", n.coverMorph)
            .putBoolean("lyricsLarge", n.lyricsLarge).putBoolean("keepScreenOnMusic", n.keepScreenOnMusic)
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
            decoder = enumOr(p.getString("decoder", null), d.decoder), deinterlace = p.getBoolean("deinterlace", d.deinterlace), autoResume = p.getBoolean("autoResume", d.autoResume),
            playNext = p.getBoolean("playNext", d.playNext), gestures = p.getBoolean("gestures", d.gestures),
            backgroundAudio = p.getBoolean("backgroundAudio", d.backgroundAudio), keepHistory = p.getBoolean("keepHistory", d.keepHistory),
            defaultSpeed = p.getFloat("defaultSpeed", d.defaultSpeed), loopOne = p.getBoolean("loopOne", d.loopOne), autoPip = p.getBoolean("autoPip", d.autoPip),
            orientation = p.getInt("orientation", d.orientation), showRemaining = p.getBoolean("showRemaining", d.showRemaining),
            rememberBrightness = p.getBoolean("rememberBrightness", d.rememberBrightness), brightness = p.getFloat("brightness", d.brightness),
            gestureBrightness = p.getBoolean("gestureBrightness", d.gestureBrightness), gestureVolume = p.getBoolean("gestureVolume", d.gestureVolume),
            gestureSeek = p.getBoolean("gestureSeek", d.gestureSeek), gestureDoubleTap = p.getBoolean("gestureDoubleTap", d.gestureDoubleTap),
            gesturePinch = p.getBoolean("gesturePinch", d.gesturePinch), holdSpeed = p.getFloat("holdSpeed", d.holdSpeed),
            audioLang = p.getString("audioLang", d.audioLang).orEmpty(), subLang = p.getString("subLang", d.subLang).orEmpty(), maxVolume = p.getInt("maxVolume", d.maxVolume),
            controlsHideSec = p.getInt("controlsHideSec", d.controlsHideSec), doubleTapSec = p.getInt("doubleTapSec", d.doubleTapSec),
            skipSilence = p.getBoolean("skipSilence", d.skipSilence),
            rewindBeforePrevious = p.getBoolean("rewindBeforePrevious", d.rewindBeforePrevious), pauseOnUnplug = p.getBoolean("pauseOnUnplug", d.pauseOnUnplug),
            pauseForOthers = p.getBoolean("pauseForOthers", d.pauseForOthers), stopOnClose = p.getBoolean("stopOnClose", d.stopOnClose),
            rememberQueue = p.getBoolean("rememberQueue", d.rememberQueue), minSongSec = p.getInt("minSongSec", d.minSongSec),
            hiddenFolders = p.getStringSet("hiddenFolders", d.hiddenFolders)?.toSet() ?: d.hiddenFolders, musicStartTab = p.getInt("musicStartTab", d.musicStartTab),
            coverMorph = p.getBoolean("coverMorph", d.coverMorph), lyricsLarge = p.getBoolean("lyricsLarge", d.lyricsLarge), keepScreenOnMusic = p.getBoolean("keepScreenOnMusic", d.keepScreenOnMusic),
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
