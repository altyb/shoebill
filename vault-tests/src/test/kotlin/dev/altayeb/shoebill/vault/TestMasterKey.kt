package dev.altayeb.shoebill.vault.crypto

import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** In-memory AES-256-GCM master key for JVM tests. Fresh random key per instance. */
class TestMasterKey : MasterKey {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val rnd = SecureRandom()

    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_LEN) { "wrapped key too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOf(IV_LEN)))
        return c.doFinal(wrapped.copyOfRange(IV_LEN, wrapped.size))
    }

    private companion object {
        const val IV_LEN = 12
    }
}

/** Counts bytes returned by read() so a test can assert how much ciphertext was touched. */
class CountingChannel(private val delegate: SeekableByteChannel) : SeekableByteChannel by delegate {
    var bytesRead: Long = 0
        private set

    override fun read(dst: java.nio.ByteBuffer): Int {
        val n = delegate.read(dst)
        if (n > 0) bytesRead += n
        return n
    }
}
