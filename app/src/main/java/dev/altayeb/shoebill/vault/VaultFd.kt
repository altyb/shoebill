package dev.altayeb.shoebill.vault

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * A vault file as a seekable file descriptor (StorageManager.openProxyFileDescriptor): Android calls [onRead] with an
 * offset, we decrypt that range from [channel] in memory. No plaintext on disk, no sockets, no ports.
 */
class VaultFdCallback(private val channel: SeekableByteChannel, private val size: Long) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = size

    override fun onRead(offset: Long, length: Int, data: ByteArray): Int = synchronized(channel) {
        try {
            if (offset >= size) return 0
            channel.position(offset)
            val buf = ByteBuffer.wrap(data, 0, minOf(length.toLong(), size - offset).toInt())
            while (buf.hasRemaining()) if (channel.read(buf) < 0) break
            buf.position()
        } catch (e: IOException) {
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        runCatching { channel.close() }
    }
}
