package dev.altayeb.shoebill.library.logic

/** A queue saved before the process died, restored against what still exists on the phone. */
object QueueRestore {
    /** Surviving ids in order, and the index that lands on the same song; else the saved index, clamped. */
    fun restore(ids: List<Long>, currentId: Long?, index: Int, exists: (Long) -> Boolean): Pair<List<Long>, Int> {
        val kept = ids.filter(exists)
        if (kept.isEmpty()) return kept to 0
        val i = kept.indexOf(currentId).takeIf { it >= 0 } ?: index.coerceIn(0, kept.lastIndex)
        return kept to i
    }
}

/** Who may connect to the music session: the app itself, the system (trusted), Android Auto. Anyone else is rejected. */
object MusicAccess {
    private val PACKAGES = setOf("com.google.android.projection.gearhead", "com.android.systemui", "android")
    fun allowed(pkg: String, ownPkg: String, trusted: Boolean) = trusted || pkg == ownPkg || pkg in PACKAGES
}

/** After an item fails: move on, unless the queue (or ten items in a row: a revoked permission, a gone SD card) has failed, or repeat-one would replay the same file. */
fun skipAfterError(failStreak: Int, count: Int, hasNext: Boolean, repeatOne: Boolean) = hasNext && !repeatOne && failStreak < minOf(count, 10)

/** Cover request size: pixels rounded up to a 256 step, so a 44 dp and a 52 dp row share one decode and one cache entry. */
fun artBucket(px: Int) = ((px.coerceAtLeast(1) + 255) / 256) * 256
