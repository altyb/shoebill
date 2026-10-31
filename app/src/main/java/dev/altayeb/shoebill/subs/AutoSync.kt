package dev.altayeb.shoebill.subs

/**
 * Finds how far subtitles are off by lining up "someone is talking" (from the audio) with "a line is on screen".
 * [voice] has one entry per [stepMs], starting at [startMs] of the film. No android imports, see subs-tests.
 */
object AutoSync {
    /** The delay to apply, or null if no offset stands out, so a bad guess never moves good subtitles. */
    fun bestOffsetMs(voice: BooleanArray, startMs: Long, stepMs: Int, cues: List<Cue>, rangeMs: Long = 60_000): Long? {
        if (voice.isEmpty() || cues.isEmpty()) return null
        val range = (rangeMs / stepMs).toInt()
        val mask = BooleanArray(voice.size + 2 * range)
        val base = startMs - range * stepMs // film time of mask[0]
        for (c in cues) {
            val from = ((c.startMs - base) / stepMs).toInt().coerceAtLeast(0)
            val to = ((c.endMs - base) / stepMs).toInt().coerceAtMost(mask.size)
            for (i in from until to) mask[i] = true
        }
        val scores = IntArray(2 * range + 1)
        for (shift in scores.indices) {
            var s = 0
            // shift - range = delay in steps: the mask moved by that much must agree with the voice
            for (i in voice.indices) if (voice[i] == mask[i + 2 * range - shift]) s++
            scores[shift] = s
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        val mean = scores.average()
        val spread = kotlin.math.sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
        if (spread == 0.0 || (scores[best] - mean) / spread < MIN_STANDOUT) return null
        return (best - range) * stepMs.toLong()
    }

    /** True for steps louder than the quiet background, which here stands for speech. */
    fun voiceMask(levels: FloatArray): BooleanArray {
        if (levels.isEmpty()) return BooleanArray(0)
        val sorted = levels.sorted()
        val cut = sorted[sorted.size / 2] * 1.3f
        return BooleanArray(levels.size) { levels[it] > cut && levels[it] > 0f }
    }

    private const val MIN_STANDOUT = 3.5
}
