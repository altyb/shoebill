package app.anon.player.vault.crypto

import app.anon.player.vault.crypto.BackupFormat.BadPasswordException
import app.anon.player.vault.crypto.BackupFormat.CorruptBackupException
import app.anon.player.vault.crypto.BackupFormat.Item
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

private const val MIB = 1024 * 1024
private const val ITER = 1000
private val PASSWORD = "correct horse".toCharArray()

/**
 * Contract for BackupFormat. Written before the implementation; the implementer must not edit this file.
 * Against the stub every test must fail.
 */
class BackupFormatTest {

    private class Recorded(val item: Item, val content: ByteArray)

    private fun writeBackup(
        items: List<Item>,
        contents: List<ByteArray>,
        password: CharArray = PASSWORD,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupFormat.write(out, password, items, { i -> ByteArrayInputStream(contents[i]) }, ITER)
        return out.toByteArray()
    }

    private fun readAll(backup: ByteArray, password: CharArray = PASSWORD): List<Recorded> {
        val result = mutableListOf<Recorded>()
        BackupFormat.read(ByteArrayInputStream(backup), password) { item, input ->
            result += Recorded(item, input.readBytes())
        }
        return result
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // 1. Round trip: empty, 1 MiB, 70_001 bytes; Arabic and long names; one item with a thumb
    @Test
    fun roundTripPreservesItemsInOrder() {
        val items = listOf(
            Item("فيلم.mkv", "video/x-matroska", 0L, null, 0L),
            Item("a".repeat(200) + ".mp4", "video/mp4", 5_400_000L, Random(11).nextBytes(512), 1L * MIB),
            Item("clip-70k.webm", "video/webm", 12_345L, null, 70_001L),
        )
        val contents = listOf(
            ByteArray(0),
            Random(12).nextBytes(1 * MIB),
            Random(13).nextBytes(70_001),
        )
        val got = readAll(writeBackup(items, contents))

        assertEquals(items.size, got.size)
        for (i in items.indices) {
            assertEquals("name $i", items[i].name, got[i].item.name)
            assertEquals("mime $i", items[i].mime, got[i].item.mime)
            assertEquals("duration $i", items[i].durationMs, got[i].item.durationMs)
            assertEquals("size $i", items[i].size, got[i].item.size)
            if (items[i].thumb == null) {
                assertNull("thumb $i", got[i].item.thumb)
            } else {
                assertArrayEquals("thumb $i", items[i].thumb, got[i].item.thumb)
            }
            assertArrayEquals("content $i", contents[i], got[i].content)
        }
    }

    // 2. Zero items
    @Test
    fun zeroItemsRoundTrip() {
        val backup = writeBackup(emptyList(), emptyList())
        val got = readAll(backup)
        assertEquals(0, got.size)
    }

    // 3. Wrong password: fails before any item is delivered
    @Test
    fun wrongPasswordThrowsBeforeAnyItem() {
        val items = listOf(Item("one.mp4", "video/mp4", 1L, null, 4L))
        val backup = writeBackup(items, listOf(byteArrayOf(1, 2, 3, 4)))
        var calls = 0
        try {
            BackupFormat.read(ByteArrayInputStream(backup), "wrong password".toCharArray()) { _, input ->
                calls++
                input.readBytes()
            }
            fail("wrong password was accepted")
        } catch (e: BadPasswordException) {
            // expected
        }
        assertEquals("onItem called despite wrong password", 0, calls)
    }

    // 4. Flip one byte mid-body
    @Test
    fun flippedMidBodyByteThrowsCorrupt() {
        val items = listOf(Item("mid.mkv", "video/x-matroska", 1L, null, 2L * MIB))
        val backup = writeBackup(items, listOf(Random(21).nextBytes(2 * MIB)))
        val bad = backup.copyOf()
        val pos = bad.size / 2
        bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
        try {
            readAll(bad)
            fail("mid-body tamper was read back without error")
        } catch (e: CorruptBackupException) {
            // expected
        }
    }

    // 5. Flip one byte in the last 32 bytes
    @Test
    fun flippedTailByteThrowsCorrupt() {
        val items = listOf(Item("tail.mp4", "video/mp4", 1L, null, 1000L))
        val backup = writeBackup(items, listOf(Random(22).nextBytes(1000)))
        val bad = backup.copyOf()
        val pos = bad.size - 1 - Random(23).nextInt(32)
        bad[pos] = (bad[pos].toInt() xor 0x80).toByte()
        try {
            readAll(bad)
            fail("tail tamper was read back without error")
        } catch (e: CorruptBackupException) {
            // expected
        }
    }

    // 6. Truncation by 100 bytes
    @Test
    fun truncatedBackupThrowsCorrupt() {
        val items = listOf(Item("cut.mp4", "video/mp4", 1L, null, 5000L))
        val backup = writeBackup(items, listOf(Random(24).nextBytes(5000)))
        val cut = backup.copyOf(backup.size - 100)
        try {
            readAll(cut)
            fail("truncated backup was read back without error")
        } catch (e: CorruptBackupException) {
            // expected
        }
    }

    // 7. Garbage and empty input
    @Test
    fun garbageInputThrowsCorrupt() {
        for (input in listOf(Random(25).nextBytes(4096), ByteArray(0))) {
            try {
                readAll(input)
                fail("non-backup input of size ${input.size} was accepted")
            } catch (e: CorruptBackupException) {
                // expected
            }
        }
    }

    // 8. Random salt/nonces: same input twice differs
    @Test
    fun sameItemsSamePasswordNotIdentical() {
        val items = listOf(Item("same.mp4", "video/mp4", 1L, null, 64L))
        val contents = listOf(Random(26).nextBytes(64))
        val a = writeBackup(items, contents)
        val b = writeBackup(items, contents)
        assertFalse("two backups were byte-identical", a.contentEquals(b))
    }

    // 9. No plaintext name or content marker in the backup bytes
    @Test
    fun plaintextNameAndContentNotVisible() {
        val name = "فيلم.mkv"
        val marker = "MARKER-012345678".toByteArray(Charsets.US_ASCII) // 16 bytes
        assertEquals(16, marker.size)
        val content = marker + Random(27).nextBytes(4096)
        val items = listOf(Item(name, "video/x-matroska", 1L, null, content.size.toLong()))
        val backup = writeBackup(items, listOf(content))

        assertEquals("plaintext name found in backup", -1, indexOf(backup, name.toByteArray(Charsets.UTF_8)))
        assertEquals("content marker found in backup", -1, indexOf(backup, marker))
    }
}
