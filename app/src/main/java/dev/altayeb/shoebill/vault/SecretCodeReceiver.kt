package dev.altayeb.shoebill.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.altayeb.shoebill.MainActivity

/**
 * Dialing *#*#<code>#*#* in the phone app opens the vault (Android's secret-code broadcast). The code is the user's
 * own, set in vault settings; any other code is ignored. Off until a code is set.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dialed = intent.data?.host ?: return
        val code = dialCode(context) ?: return
        if (dialed != code) return
        // an in-process flag, not an intent extra: other apps can start MainActivity with any extra they like
        vaultAsked = true
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        @Volatile var vaultAsked = false
        private fun prefs(c: Context) = c.getSharedPreferences("vault_pin", Context.MODE_PRIVATE)
        fun dialCode(c: Context): String? = prefs(c).getString("dialCode", null)
        fun setDialCode(c: Context, code: String?) {
            prefs(c).edit().apply { if (code == null) remove("dialCode") else putString("dialCode", code) }.apply()
        }
    }
}
