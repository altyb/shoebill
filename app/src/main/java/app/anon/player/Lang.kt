package app.anon.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import java.util.Locale
import kotlin.system.exitProcess

/**
 * App language: "" follows the phone, otherwise "ar" or "en". Read synchronously in attachBaseContext, so it lives in
 * its own small prefs file. Layout direction follows the locale (Arabic RTL, English LTR).
 */
object Lang {
    private fun prefs(c: Context) = c.getSharedPreferences("lang", Context.MODE_PRIVATE)
    fun get(c: Context): String = prefs(c).getString("tag", "").orEmpty()

    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Saves [tag] and restarts the app: view models hold strings from the application context, so a recreate is not enough. */
    fun set(activity: Activity, tag: String) {
        if (tag == get(activity)) return
        prefs(activity).edit().putString("tag", tag).commit()
        activity.startActivity(
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        exitProcess(0)
    }
}

class ShoebillApp : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))
}
