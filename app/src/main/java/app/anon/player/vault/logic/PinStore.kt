package app.anon.player.vault.logic

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.MessageDigest

/**
 * Vault PIN store. Each role (Real, Decoy, Wipe) is stored as "salt:hash:iterations"
 * under "pin.<Role>", hashed with PBKDF2-HMAC-SHA256. Every check hashes against all
 * four slots (unset ones use a dummy salt) so timing does not reveal which roles exist.
 * Failed attempts are counted and escalate into lockouts (30s doubling, capped at 1h).
 * [clock] should be monotonic (the app passes elapsedRealtime, so changing the date cannot end a lockout). It restarts
 * at a reboot; a stored lockout that now looks longer than its own duration is cut back to that duration.
 * Never stores or logs PIN digits.
 *
 * With [pepper] (the app passes an HMAC keyed inside the phone's security chip) a slot is stored as "salt:hash:p" and
 * checked with one chip call instead of PBKDF2: fast, and a copy of the storage cannot be brute-forced off the
 * device. Older PBKDF2 slots still verify and are rewritten with the pepper on their next successful check.
 */
class PinStore(
    private val storage: Storage,
    private val clock: () -> Long,
    private val iterations: Int = 310_000,
    private val pepper: ((ByteArray) -> ByteArray)? = null,
) {
    interface Storage {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    enum class Role { Real, Decoy, Wipe }

    sealed class Result {
        data class Ok(val role: Role) : Result()
        data class Wrong(val attemptsLeft: Int) : Result()
        data class LockedOut(val untilMs: Long) : Result()
    }

    private val rng = SecureRandom()
    private val dummySalt = ByteArray(16)

    fun isSet(role: Role = Role.Real): Boolean = storage.get(key(role)) != null

    fun setPin(role: Role, pin: CharArray) {
        require(pin.size in 4..16 && pin.all { it in '0'..'9' }) { "PIN must be 4-16 ASCII digits" }
        if (role != Role.Real) require(isSet(Role.Real)) { "Real PIN must be set first" }
        require(match(pin, skip = role) == null) { "PIN already used by another role" }
        store(role, pin)
    }

    private fun store(role: Role, pin: CharArray) {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val p = pepper
        storage.put(key(role), if (p != null) "${enc(salt)}:${enc(peppered(p, pin, salt))}:p" else "${enc(salt)}:${enc(hash(pin, salt, iterations))}:$iterations")
    }

    /** Removes this one slot only (the wipe PIN drops the real slot but keeps the decoy working). */
    fun forget(role: Role) = storage.put(key(role), null)

    fun clear(role: Role) {
        if (role == Role.Real) Role.values().forEach { storage.put(key(it), null) }
        else storage.put(key(role), null)
    }

    fun check(pin: CharArray): Result {
        val now = clock()
        val until = storage.get("pin.lockUntil")?.toLongOrNull()
        if (until != null && until > now) {
            val dur = storage.get("pin.lockDur")?.toLongOrNull() ?: 30_000L
            if (until - now <= dur) return Result.LockedOut(until)
            storage.put("pin.lockUntil", (now + dur).toString()) // clock restarted (reboot): wait the full duration again
            return Result.LockedOut(now + dur)
        }
        match(pin, skip = null)?.let {
            storage.put("pin.fails", null)
            storage.put("pin.lockouts", null)
            if (pepper != null && storage.get(key(it))?.endsWith(":p") == false) store(it, pin) // upgrade a PBKDF2 slot
            return Result.Ok(it)
        }
        val fails = int("pin.fails") + 1
        if (fails < 5) {
            storage.put("pin.fails", fails.toString())
            return Result.Wrong(5 - fails)
        }
        val lockouts = int("pin.lockouts") + 1
        val duration = minOf(30_000L shl minOf(lockouts - 1, 20), 3_600_000L)
        val lockUntil = now + duration
        storage.put("pin.fails", "0")
        storage.put("pin.lockDur", duration.toString())
        storage.put("pin.lockouts", lockouts.toString())
        storage.put("pin.lockUntil", lockUntil.toString())
        return Result.LockedOut(lockUntil)
    }

    /** Hashes against every role slot (set or dummy, same kind of work) and returns the matching role, if any. */
    private fun match(pin: CharArray, skip: Role?): Role? {
        var found: Role? = null
        val p = pepper
        for (role in Role.values()) {
            val live = if (role == skip) null else storage.get(key(role))
            val parts = live?.split(":")
            val salt = parts?.let { dec(it[0]) } ?: dummySalt
            val h = when {
                parts?.get(2) == "p" -> if (p != null) peppered(p, pin, salt) else null // chip slot, no chip: cannot match
                parts != null -> hash(pin, salt, parts[2].toInt())
                p != null -> peppered(p, pin, salt)
                else -> hash(pin, salt, iterations)
            }
            if (parts != null && h != null && MessageDigest.isEqual(h, dec(parts[1]))) found = role
        }
        return found
    }

    private fun peppered(p: (ByteArray) -> ByteArray, pin: CharArray, salt: ByteArray): ByteArray {
        val digits = ByteArray(pin.size) { pin[it].code.toByte() } // ASCII digits only (setPin enforces)
        val input = salt + digits
        try {
            return p(input)
        } finally {
            digits.fill(0)
            input.fill(0)
        }
    }

    private fun hash(pin: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun int(key: String): Int = storage.get(key)?.toIntOrNull() ?: 0
    private fun key(role: Role) = "pin.${role.name}"
    private fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun dec(s: String): ByteArray = Base64.getDecoder().decode(s)
}
