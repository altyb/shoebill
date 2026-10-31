package dev.altayeb.shoebill.player

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * Equalizer on mpv's audio session. This FFmpeg build has its filters compiled out, so mpv's own "af" cannot do it.
 * Band levels live in prefs. Phones without the effect get null from [open] and the panel hides the sliders.
 */
class AudioFx private constructor(private val eq: Equalizer, private val prefs: android.content.SharedPreferences) {
    val bands: Int get() = eq.numberOfBands.toInt()
    val minLevel: Int get() = eq.bandLevelRange[0].toInt()
    val maxLevel: Int get() = eq.bandLevelRange[1].toInt()
    val enabled: Boolean get() = eq.enabled

    fun hz(band: Int): Int = eq.getCenterFreq(band.toShort()) / 1000
    fun level(band: Int): Int = eq.getBandLevel(band.toShort()).toInt()

    fun setEnabled(on: Boolean) {
        eq.enabled = on
        prefs.edit().putBoolean("on", on).apply()
    }

    fun setLevel(band: Int, millibels: Int) {
        eq.setBandLevel(band.toShort(), millibels.toShort())
        prefs.edit().putInt("b$band", millibels).apply()
    }

    fun reset() { for (b in 0 until bands) setLevel(b, 0) }

    fun release() = eq.release()

    companion object {
        fun open(context: Context, session: Int): AudioFx? = runCatching {
            val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
            val eq = Equalizer(0, session)
            for (b in 0 until eq.numberOfBands) {
                val saved = prefs.getInt("b$b", 0)
                if (saved != 0) eq.setBandLevel(b.toShort(), saved.toShort())
            }
            eq.enabled = prefs.getBoolean("on", false)
            AudioFx(eq, prefs)
        }.getOrNull()
    }
}
