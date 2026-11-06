package dev.altayeb.shoebill.music

import android.content.Context
import dev.altayeb.shoebill.library.logic.PlayStat
import dev.altayeb.shoebill.library.logic.PlaylistCodec
import dev.altayeb.shoebill.library.logic.SmartLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Favorites and play counts: one copy per process, shared by the music service (which counts plays) and the screens
 * (which show the lists and the heart). Every change is one atomic update of the flow, then saved; screens only read.
 */
class MusicStats private constructor(c: Context) {
    private val p = c.applicationContext.getSharedPreferences("music_stats", Context.MODE_PRIVATE)
    private val _plays = MutableStateFlow(SmartLists.decode(p.getString("plays", null)))
    private val _favs = MutableStateFlow(PlaylistCodec.decode(p.getString("favs", null)))
    val plays: StateFlow<Map<Long, PlayStat>> = _plays
    /** Oldest favorite first; SmartLists.favorites turns it around. */
    val favorites: StateFlow<List<Long>> = _favs

    fun isFavorite(id: Long) = id in _favs.value
    fun toggleFavorite(id: Long) {
        val n = _favs.updateAndGet { if (id in it) it - id else it + id }
        p.edit().putString("favs", PlaylistCodec.encode(n)).apply()
    }

    // ponytail: stats keep songs deleted from the phone; at ~20 bytes each that is fine until tens of thousands, prune then.
    fun played(id: Long, now: Long = System.currentTimeMillis()) {
        val (w, mo) = periodKeys(now)
        val n = _plays.updateAndGet { m -> m + (id to SmartLists.bump(m[id], now, w, mo)) }
        p.edit().putString("plays", SmartLists.encode(n)).apply()
    }

    fun clearPlays() { _plays.value = emptyMap(); p.edit().remove("plays").apply() }

    private inline fun <T> MutableStateFlow<T>.updateAndGet(f: (T) -> T): T { update(f); return value }

    companion object {
        @Volatile private var one: MusicStats? = null
        fun get(c: Context) = one ?: synchronized(this) { one ?: MusicStats(c).also { one = it } }

        /** This calendar week (from the phone's first day of the week) and month, as numbers that change with them. */
        fun periodKeys(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
            val d = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val first = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
            return d.with(java.time.temporal.TemporalAdjusters.previousOrSame(first)).toEpochDay() to d.year * 12L + d.monthValue
        }
    }
}
