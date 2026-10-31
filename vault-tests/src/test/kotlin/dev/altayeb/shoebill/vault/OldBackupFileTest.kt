package dev.altayeb.shoebill.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** old-format.anonvault was written by the 0.3.0 code. People's existing backups must keep restoring. */
class OldBackupFileTest {
    private fun old() = javaClass.getResourceAsStream("/old-format.anonvault")!!

    @Test fun oldBackupStillRestores() {
        val got = mutableListOf<Pair<BackupFormat.Item, ByteArray>>()
        BackupFormat.read(old(), "old password".toCharArray()) { item, s -> got += item to s.readBytes() }
        assertEquals(listOf("clip.mp4", "note.jpg"), got.map { it.first.name })
        assertArrayEquals(ByteArray(5000) { (it % 251).toByte() }, got[0].second)
        assertArrayEquals(byteArrayOf(1, 2, 3), got[0].first.thumb)
        assertEquals("hello vault", String(got[1].second))
    }

    @Test fun oldBackupStillRefusesTheWrongPassword() {
        try {
            BackupFormat.read(old(), "wrong".toCharArray()) { _, _ -> }
            fail("opened with the wrong password")
        } catch (_: BackupFormat.BadPasswordException) {}
    }

    @Test fun newBackupsUseTheNewHeader() {
        val out = ByteArrayOutputStream()
        BackupFormat.write(out, "p".toCharArray(), emptyList(), { ByteArrayInputStream(ByteArray(0)) }, 1000)
        assertEquals("SHOEVB1\n", String(out.toByteArray(), 0, 8, Charsets.US_ASCII))
        BackupFormat.read(ByteArrayInputStream(out.toByteArray()), "p".toCharArray()) { _, _ -> }
    }
}
