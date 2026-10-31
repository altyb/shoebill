package dev.altayeb.shoebill.vault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Base64
import kotlin.text.Charsets.UTF_8

/** Key-storage seam. Android impl: Keystore/StrongBox. Tests: in-memory AES-GCM. */
interface MasterKey {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Per-file streaming AEAD (AES-256-GCM-HKDF, 1 MiB segments) with a per-file keyset wrapped by [masterKey].
 * File names use a separate AES-256-GCM keyset, wrapped the same way, persisted via [nameKeyWrapped].
 */
class VaultCrypto(
    private val masterKey: MasterKey,
    nameKeyWrapped: ByteArray? = null,
) {
    /** Wrapped name keyset. Persist it; pass it back to the constructor to keep names decryptable. */
    val nameKeyWrapped: ByteArray = nameKeyWrapped
        ?: masterKey.wrap(serialize(KeysetHandle.generateNew(KeyTemplates.get(NAME_TEMPLATE))))

    private val nameAead: Aead = parse(masterKey.unwrap(this.nameKeyWrapped))
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    fun encrypt(input: InputStream, out: OutputStream, fileId: String): ByteArray {
        val handle = KeysetHandle.generateNew(KeyTemplates.get(FILE_TEMPLATE))
        val wrapped = masterKey.wrap(serialize(handle))
        val streaming = handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        streaming.newEncryptingStream(NonClosingOutputStream(out), fileId.toByteArray(UTF_8)).use { enc ->
            val buf = ByteArray(COPY_BUF)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                enc.write(buf, 0, n)
            }
        }
        return wrapped
    }

    fun openSeekable(ciphertext: SeekableByteChannel, wrappedKey: ByteArray, fileId: String): SeekableByteChannel {
        val streaming = parse(masterKey.unwrap(wrappedKey))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        return PlaintextChannel(streaming.newSeekableDecryptingChannel(ciphertext, fileId.toByteArray(UTF_8)))
    }

    fun encryptName(name: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(nameAead.encrypt(name.toByteArray(UTF_8), NAME_AAD))

    fun decryptName(enc: String): String =
        String(nameAead.decrypt(Base64.getUrlDecoder().decode(enc), NAME_AAD), UTF_8)

    private fun serialize(handle: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())

    private fun parse(bytes: ByteArray): KeysetHandle =
        TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get())

    /**
     * Tink quirks: size() throws until the first non-empty read, and EOF reads return 0 instead of -1.
     * Size is probed lazily (only if asked), so plain seeks never pay for it.
     */
    private class PlaintextChannel(private val tink: SeekableByteChannel) : SeekableByteChannel by tink {
        private var knownSize = -1L

        override fun size(): Long {
            if (knownSize < 0) knownSize = try {
                tink.size()
            } catch (e: IOException) {
                // Only empty plaintext leaves Tink unmatched: a probe read at offset 0 returns 0 (authenticated EOF).
                val at = tink.position().coerceAtLeast(0L) // Tink reports -1 before its first read
                tink.position(0)
                val probe = tink.read(ByteBuffer.allocate(1))
                tink.position(at)
                if (probe > 0) tink.size() else 0L
            }
            return knownSize
        }

        override fun read(dst: ByteBuffer): Int {
            val n = tink.read(dst)
            return if (n == 0 && dst.hasRemaining() && tink.position() >= size()) -1 else n
        }
    }

    /** Tink closes what it wraps; the caller owns [out], so swallow close and only flush. */
    private class NonClosingOutputStream(private val delegate: OutputStream) : FilterOutputStream(delegate) {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun close() = delegate.flush()
    }

    private companion object {
        const val FILE_TEMPLATE = "AES256_GCM_HKDF_1MB"
        const val NAME_TEMPLATE = "AES256_GCM_RAW" // nonce || ciphertext || tag, no Tink prefix
        const val COPY_BUF = 64 * 1024
        val NAME_AAD = "shoebill-vault-name".toByteArray(UTF_8)

        init {
            StreamingAeadConfig.register()
            AeadConfig.register()
        }
    }
}
