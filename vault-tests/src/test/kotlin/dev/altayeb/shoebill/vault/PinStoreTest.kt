package dev.altayeb.shoebill.vault.logic

import dev.altayeb.shoebill.vault.logic.PinStore.Result
import dev.altayeb.shoebill.vault.logic.PinStore.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinStoreTest {
    private class MapStorage : PinStore.Storage {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val storage = MapStorage()
    private var now = 1_000_000L
    private val wrong = "000000"

    private fun store(s: PinStore.Storage = storage) = PinStore(s, { now }, 1000)
    private fun pin(s: String) = s.toCharArray()

    private fun lockOut(s: PinStore): Result.LockedOut {
        repeat(4) { s.check(pin(wrong)) }
        return s.check(pin(wrong)) as Result.LockedOut
    }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test fun freshStoreHasNoPinsThenRealWorks() {
        val s = store()
        Role.values().forEach { assertFalse(s.isSet(it)) }
        s.setPin(Role.Real, pin("123456"))
        assertTrue(s.isSet())
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
    }

    @Test fun pinIsFourToSixteenAsciiDigits() {
        val s = store()
        for (bad in listOf("123", "12345678901234567", "12a456", "", "١٢٣٤٥٦")) {
            assertRejects { s.setPin(Role.Real, pin(bad)) }
        }
        s.setPin(Role.Real, pin("1234"))
        assertEquals(Result.Ok(Role.Real), s.check(pin("1234")))
        s.setPin(Role.Decoy, pin("1234567890123456"))
        assertEquals(Result.Ok(Role.Decoy), s.check(pin("1234567890123456")))
    }

    @Test fun decoyAndWipeNeedRealFirst() {
        val s = store()
        assertRejects { s.setPin(Role.Decoy, pin("222222")) }
        assertRejects { s.setPin(Role.Wipe, pin("333333")) }
        assertFalse(s.isSet(Role.Decoy))
        assertFalse(s.isSet(Role.Wipe))
    }

    @Test fun allPinsMustDiffer() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        assertRejects { s.setPin(Role.Decoy, pin("123456")) }
        s.setPin(Role.Decoy, pin("222222"))
        assertRejects { s.setPin(Role.Wipe, pin("222222")) }
        assertRejects { s.setPin(Role.Wipe, pin("123456")) }
    }

    @Test fun decoyAndWipeReturnTheirRole() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        s.setPin(Role.Decoy, pin("222222"))
        s.setPin(Role.Wipe, pin("333333"))
        assertEquals(Result.Ok(Role.Decoy), s.check(pin("222222")))
        assertEquals(Result.Ok(Role.Wipe), s.check(pin("333333")))
    }

    @Test fun wrongPinsCountDownThenLockOutAndCorrectPinWaits() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        assertEquals(Result.Wrong(4), s.check(pin(wrong)))
        assertEquals(Result.Wrong(3), s.check(pin(wrong)))
        assertEquals(Result.Wrong(2), s.check(pin(wrong)))
        assertEquals(Result.Wrong(1), s.check(pin(wrong)))
        assertEquals(Result.LockedOut(now + 30_000), s.check(pin(wrong)))
        assertEquals(Result.LockedOut(now + 30_000), s.check(pin("123456")))
        now += 30_001
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
    }

    @Test fun lockoutEscalatesAndCapsAtOneHour() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        for (i in 0 until 8) {
            val expected = minOf(30_000L shl i, 3_600_000L)
            val locked = lockOut(s)
            assertEquals(now + expected, locked.untilMs)
            now = locked.untilMs + 1
        }
    }

    @Test fun realSuccessResetsCounterAndEscalation() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        val first = lockOut(s)
        now = first.untilMs + 1
        val second = lockOut(s)
        assertEquals(now + 60_000, second.untilMs)
        now = second.untilMs + 1
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
        assertEquals(now + 30_000, lockOut(s).untilMs)
    }

    @Test fun decoySuccessResetsWrongCount() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        s.setPin(Role.Decoy, pin("222222"))
        repeat(3) { s.check(pin(wrong)) }
        assertEquals(Result.Ok(Role.Decoy), s.check(pin("222222")))
        assertEquals(Result.Wrong(4), s.check(pin(wrong)))
    }

    @Test fun stateSurvivesRestartMidLockout() {
        val s1 = store()
        s1.setPin(Role.Real, pin("123456"))
        s1.setPin(Role.Decoy, pin("222222"))
        repeat(3) { s1.check(pin(wrong)) }

        val s2 = store()
        assertEquals(Result.Wrong(1), s2.check(pin(wrong)))
        val until = now + 30_000
        assertEquals(Result.LockedOut(until), store().check(pin(wrong)))
        assertEquals(Result.LockedOut(until), store().check(pin("123456")))

        now = until + 1
        assertEquals(Result.Ok(Role.Decoy), store().check(pin("222222")))
    }

    @Test fun clearRealRemovesEveryRole() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        s.setPin(Role.Decoy, pin("222222"))
        s.setPin(Role.Wipe, pin("333333"))
        s.clear(Role.Real)
        Role.values().forEach { assertFalse(s.isSet(it)) }
        assertTrue(s.check(pin("123456")) is Result.Wrong)
        assertTrue(s.check(pin("222222")) is Result.Wrong)
        assertTrue(s.check(pin("333333")) is Result.Wrong)
    }

    @Test fun pinDigitsNeverStoredInPlainText() {
        val s = store()
        val pins = listOf("135790", "246802", "864209")
        s.setPin(Role.Real, pin(pins[0]))
        s.setPin(Role.Decoy, pin(pins[1]))
        s.setPin(Role.Wipe, pin(pins[2]))
        assertTrue(storage.map.isNotEmpty())
        for (value in storage.map.values) {
            for (p in pins) assertFalse(value.contains(p))
        }
    }

    @Test fun checkWithNoPinSetStillCounts() {
        val s = store()
        assertEquals(Result.Wrong(4), s.check(pin("123456")))
        repeat(3) { s.check(pin("123456")) }
        assertEquals(Result.LockedOut(now + 30_000), s.check(pin("123456")))
    }

    // ---- chip pepper: fast checks; the secret never leaves the chip, so stolen storage cannot be brute-forced offline

    private val chip = { data: ByteArray -> javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec("chip-secret-A".toByteArray(), "HmacSHA256")); doFinal(data) } }
    private val otherChip = { data: ByteArray -> javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec("chip-secret-B".toByteArray(), "HmacSHA256")); doFinal(data) } }

    @Test fun pepperedStoreSetsAndChecks() {
        val s = PinStore(storage, { now }, 1000, chip)
        s.setPin(Role.Real, pin("123456"))
        s.setPin(Role.Decoy, pin("654321"))
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
        assertEquals(Result.Ok(Role.Decoy), s.check(pin("654321")))
        assertEquals(Result.Wrong(4), s.check(pin(wrong)))
    }

    @Test fun pepperedHashUselessWithoutTheSameChip() {
        PinStore(storage, { now }, 1000, chip).setPin(Role.Real, pin("123456"))
        // same storage, different device secret: the right PIN must not match
        assertEquals(Result.Wrong(4), PinStore(storage, { now }, 1000, otherChip).check(pin("123456")))
        // and without any pepper it must not match either
        assertEquals(Result.Wrong(3), PinStore(storage, { now }, 1000).check(pin("123456")))
    }

    @Test fun oldSlowPinUpgradesToPepperOnSuccess() {
        PinStore(storage, { now }, 1000).setPin(Role.Real, pin("123456"))
        val before = storage.map.filterKeys { it.startsWith("pin.") && !it.startsWith("pin.fail") && !it.startsWith("pin.lock") }
        val s = PinStore(storage, { now }, 1000, chip)
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
        val after = storage.map.filterKeys { it in before.keys }
        assertTrue("stored hash should be rewritten with the chip secret", before != after)
        // after the upgrade the store only verifies with the chip
        assertEquals(Result.Ok(Role.Real), PinStore(storage, { now }, 1000, chip).check(pin("123456")))
        assertEquals(Result.Wrong(4), PinStore(storage, { now }, 1000, otherChip).check(pin("123456")))
    }

    @Test fun pepperedPinsStillNeverInPlainText() {
        PinStore(storage, { now }, 1000, chip).setPin(Role.Real, pin("135790"))
        assertFalse(storage.map.values.any { it.contains("135790") })
    }

    @Test fun rebootRestartsLockoutButNeverLongerThanItsDuration() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        now = 50_000_000L // long uptime when locked
        val locked = lockOut(s)
        assertEquals(now + 30_000, locked.untilMs)
        now = 2_000L // rebooted: monotonic clock starts near zero
        assertEquals(Result.LockedOut(now + 30_000), s.check(pin("123456")))
        now += 30_001
        assertEquals(Result.Ok(Role.Real), s.check(pin("123456")))
    }

    @Test fun forgetDropsOneSlotOnly() {
        val s = store()
        s.setPin(Role.Real, pin("123456"))
        s.setPin(Role.Decoy, pin("222222"))
        s.setPin(Role.Wipe, pin("333333"))
        s.forget(Role.Real)
        assertEquals(Result.Wrong(4), s.check(pin("123456")))
        assertEquals(Result.Ok(Role.Decoy), s.check(pin("222222")))
        assertEquals(Result.Ok(Role.Wipe), s.check(pin("333333")))
    }
}
