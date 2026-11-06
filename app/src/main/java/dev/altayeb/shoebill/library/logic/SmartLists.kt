package dev.altayeb.shoebill.library.logic

/**
 * How often and when a song was last played: [last] in epoch ms, 0 if never. [week] and [month] count the plays in the
 * calendar week [weekKey] and month [monthKey] only; a play in a newer week or month starts that counter again.
 */
data class PlayStat(val count: Int = 0, val last: Long = 0, val weekKey: Long = 0, val week: Int = 0, val monthKey: Long = 0, val month: Int = 0)

/** Which plays Most played ranks by. */
enum class PlayPeriod { Week, Month, All }

/**
 * The ready-made music lists (Favorites, Recently added, Recently played, Most played, Not played) and the rule for
 * what counts as a play. Pure, so the lists can be tested without a phone; the app feeds it songs and saved stats.
 */
object SmartLists {
    const val LIMIT = 100

    /** A play is 30 s of listening, or half of a song shorter than a minute. Skipping through never counts. */
    fun counts(listenedMs: Long, durationMs: Long): Boolean =
        listenedMs >= 30_000 || (durationMs in 1..59_999 && listenedMs * 2 >= durationMs)

    /** One more play at [now], in week [weekKey] and month [monthKey] (any numbers that change when the week or month does). */
    fun bump(old: PlayStat?, now: Long, weekKey: Long, monthKey: Long): PlayStat {
        val o = old ?: PlayStat()
        return PlayStat(o.count + 1, now, weekKey, if (o.weekKey == weekKey) o.week + 1 else 1, monthKey, if (o.monthKey == monthKey) o.month + 1 else 1)
    }

    /** Plays in [period] as of week [weekKey] / month [monthKey]: a counter from an older week or month is 0. */
    fun plays(st: PlayStat?, period: PlayPeriod, weekKey: Long, monthKey: Long): Int = when {
        st == null -> 0
        period == PlayPeriod.All -> st.count
        period == PlayPeriod.Week -> if (st.weekKey == weekKey) st.week else 0
        else -> if (st.monthKey == monthKey) st.month else 0
    }

    /** Newest first, added at or after [since] (same unit as [added]; 0 = all of them). */
    fun <T> recentlyAdded(all: List<T>, added: (T) -> Long, since: Long = 0): List<T> = all.filter { added(it) >= since }.sortedByDescending(added)
    fun <T> recentlyPlayed(all: List<T>, id: (T) -> Long, stats: Map<Long, PlayStat>): List<T> =
        all.filter { (stats[id(it)]?.last ?: 0) > 0 }.sortedByDescending { stats.getValue(id(it)).last }.take(LIMIT)
    fun <T> mostPlayed(all: List<T>, id: (T) -> Long, stats: Map<Long, PlayStat>, period: PlayPeriod = PlayPeriod.All, weekKey: Long = 0, monthKey: Long = 0): List<T> {
        fun n(t: T) = plays(stats[id(t)], period, weekKey, monthKey)
        return all.filter { n(it) > 0 }.sortedWith(compareByDescending<T> { n(it) }.thenByDescending { stats.getValue(id(it)).last }).take(LIMIT)
    }

    /** Time spent listening: each song's plays in [period] times its length. */
    fun <T> listened(songs: List<T>, id: (T) -> Long, length: (T) -> Long, stats: Map<Long, PlayStat>, period: PlayPeriod, weekKey: Long, monthKey: Long): Long =
        songs.sumOf { plays(stats[id(it)], period, weekKey, monthKey) * length(it) }
    /** In library order (newest first), all of them: this is the list to find forgotten songs in. */
    fun <T> notPlayed(all: List<T>, id: (T) -> Long, stats: Map<Long, PlayStat>): List<T> = all.filter { (stats[id(it)]?.count ?: 0) == 0 }
    /** Last favorited first; songs no longer on the phone simply drop out. */
    fun <T> favorites(all: List<T>, id: (T) -> Long, favs: List<Long>): List<T> {
        val byId = all.associateBy(id)
        return favs.asReversed().mapNotNull(byId::get)
    }

    /** "id:count:last:weekKey:week:monthKey:month;…" (older saves end after last). Bad parts are skipped, so one broken entry never loses the rest. */
    fun encode(stats: Map<Long, PlayStat>): String = stats.entries.joinToString(";") { (k, v) -> "$k:${v.count}:${v.last}:${v.weekKey}:${v.week}:${v.monthKey}:${v.month}" }
    fun decode(s: String?): Map<Long, PlayStat> = s.orEmpty().split(';').mapNotNull { part ->
        val f = part.split(':')
        val id = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val c = f.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        fun l(i: Int) = f.getOrNull(i)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        fun n(i: Int) = f.getOrNull(i)?.toIntOrNull()?.coerceIn(0, c) ?: 0
        id to PlayStat(c, l(2), l(3), n(4), l(5), n(6))
    }.toMap()
}

/**
 * Listening time of the song now playing, from play/pause/track-change events with a clock. [finish] returns the song
 * id once its listening reached a play (and only once per play), so a pause, a seek or a kill mid-song never double-counts.
 */
class PlayTracker {
    private var id: Long? = null
    private var duration = 0L
    private var listened = 0L
    private var since = -1L // clock at the last start of playing, -1 while paused
    private var counted = false

    /** A new song (or the same one again on repeat). Returns the previous one if that was a play. */
    fun start(newId: Long?, durationMs: Long, playing: Boolean, now: Long): Long? {
        val done = check(now)
        id = newId; duration = durationMs; listened = 0; counted = false; since = if (playing) now else -1
        return done
    }
    fun duration(ms: Long) { if (ms > 0) duration = ms }
    fun playing(on: Boolean, now: Long): Long? {
        if (on) { if (since < 0) since = now; return null }
        return check(now).also { since = -1 }
    }
    /** Called now and then while playing, so a song listened to and then killed still counts. */
    fun check(now: Long): Long? {
        if (since >= 0) { listened += now - since; since = now }
        val i = id ?: return null
        if (counted || !SmartLists.counts(listened, duration)) return null
        counted = true
        return i
    }
}
