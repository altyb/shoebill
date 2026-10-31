package dev.altayeb.shoebill.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * HMAC-SHA256 key inside the phone's TEE (not StrongBox: one PIN check is three MACs and StrongBox is ~10x slower).
 * No user auth: the PIN pad's attempt limit guards it; what it adds is that a copy of the stored hashes is useless
 * off this phone, so the PIN needs no slow KDF.
 */
object PinPepper {
    private const val ALIAS = "shoebill_pin_pepper"

    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(key()); doFinal(data) }
}
