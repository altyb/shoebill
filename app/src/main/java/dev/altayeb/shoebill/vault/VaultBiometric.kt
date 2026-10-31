package dev.altayeb.shoebill.vault

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.altayeb.shoebill.R

/** Fingerprint only: the fingerprint button. */
const val AUTH_FINGERPRINT = BiometricManager.Authenticators.BIOMETRIC_STRONG
/** Device PIN/pattern/password only: "Use PIN". API 30+. */
const val AUTH_DEVICE = BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Either factor, used when the key asks for auth mid-operation (fingerprint or the device credential on API 30+). */
fun authAny(): Int = if (Build.VERSION.SDK_INT >= 30) AUTH_FINGERPRINT or AUTH_DEVICE else AUTH_FINGERPRINT

fun showVaultPrompt(activity: FragmentActivity, auth: Int, onOk: () -> Unit, onFail: () -> Unit) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.vault_unlock))
        .setAllowedAuthenticators(auth)
    if (auth and AUTH_DEVICE == 0) info.setNegativeButtonText(activity.getString(R.string.cancel)) // a device-credential prompt brings its own cancel
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
    }).authenticate(info.build())
}

/** The vault can only be guarded if a fingerprint or a screen lock exists (Keystore key needs one). */
fun canGuardVault(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(authAny()) == BiometricManager.BIOMETRIC_SUCCESS
