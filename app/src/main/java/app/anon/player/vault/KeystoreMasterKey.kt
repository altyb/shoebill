package app.anon.player.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.anon.player.vault.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class KeyLevel { StrongBox, Tee, Software }

/** Two independent vaults on disk, each with its own Keystore key. Decoy is opened by the decoy PIN. */
enum class VaultSpace(val dir: String, val alias: String) {
    Real("vault", KeystoreMasterKey.ALIAS), Decoy("vault_decoy", "anon_vault_decoy"),
}

/**
 * AES-256-GCM key that never leaves the Keystore (StrongBox, else TEE). Timed auth: usable for 30 s after a strong
 * biometric or device-credential unlock. Once that window lapses, Cipher.init throws UserNotAuthenticatedException.
 */
class KeystoreMasterKey private constructor(private val key: SecretKey) : MasterKey {

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key) // Keystore draws a random 12-byte IV
        val iv = c.iv
        return iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_LEN) { "wrapped key too short" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOf(IV_LEN)))
        return c.doFinal(wrapped.copyOfRange(IV_LEN, wrapped.size))
    }

    companion object {
        const val ALIAS = "anon_vault_master"
        /** PIN-only key of a space = its auth alias + this suffix. The auth alias keeps its name, so old vaults still open. */
        private const val PIN_ONLY_SUFFIX = "_pin"
        private const val PREFS = "vault_pin"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val AUTH_SECONDS = 30

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun pinOnlyAlias(alias: String): String = alias + PIN_ONLY_SUFFIX

        /** Which Keystore alias holds the space's key right now: the PIN-only one when the space's flag is set. */
        fun activeAlias(context: Context, space: VaultSpace): String =
            if (pinOnly(context, space)) pinOnlyAlias(space.alias) else space.alias

        fun pinOnly(context: Context, space: VaultSpace): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pinOnly.${space.name}", null) == "1"

        fun setPinOnlyFlag(context: Context, space: VaultSpace, on: Boolean) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (on) e.putString("pinOnly.${space.name}", "1") else e.remove("pinOnly.${space.name}")
            e.commit() // the flag picks the alias to load: it must be on disk before the old key goes
        }

        fun exists(alias: String = ALIAS): Boolean = keyStore().containsAlias(alias)

        fun load(alias: String = ALIAS): KeystoreMasterKey = KeystoreMasterKey(keyStore().getKey(alias, null) as SecretKey)

        fun delete(alias: String) = keyStore().deleteEntry(alias)

        /** StrongBox first; if the device has none (or it is busy) fall back to the default TEE-backed Keystore.
         *  [requireAuth] false = PIN-only key: usable without a biometric or device-credential unlock. */
        fun create(alias: String = ALIAS, requireAuth: Boolean = true) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    gen.init(spec(alias, strongBox = true, requireAuth))
                    gen.generateKey()
                    return
                } catch (e: StrongBoxUnavailableException) {
                    // no StrongBox here: TEE below
                }
            }
            gen.init(spec(alias, strongBox = false, requireAuth))
            gen.generateKey()
        }

        private fun spec(alias: String, strongBox: Boolean, requireAuth: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            if (requireAuth) {
                b.setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(false)
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setUserAuthenticationParameters(
                        AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                }
            }
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }

        /** Where the key really lives, for the banner. Reads key metadata only; needs no authentication. */
        fun securityLevel(alias: String = ALIAS): KeyLevel {
            val key = keyStore().getKey(alias, null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            return if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLevel.StrongBox
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLevel.Tee
                    else -> KeyLevel.Software
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyLevel.Tee else KeyLevel.Software
            }
        }
    }
}
