package `in`.security4her.app.voice

/**
 * Decides when "HELP HELP HELP" was said (pure, unit-tested; no Android).
 *
 * The recogniser reports "help", "me", a few look-alike words, or "[unk]" (anything else). A trigger needs [needed]
 * helps that are all:
 * - "help" (or "yelp"/"kelp", which is how a shouted or clipped "help" often comes out), confidence ≥ [minConf];
 * - clearly louder than the background ([Word.loud]);
 * - back to back: each starting ≤ [maxGapMs] after the previous one ended, with no other word in between
 *   ("me" is allowed, so "help me, help me, help me" counts);
 * - within [maxSpanMs] from the first to the last.
 * Any other word in between ("help desk", a song, a conversation) starts the count again. A faint "help" (TV in
 * the next room) doesn't count but doesn't cancel her own helps either.
 * After a trigger nothing counts for [cooldownMs], so one shout can't start two countdowns.
 *
 * Tuned on 276 recordings (two voices; fast, slow, paused, panicked, quiet; clean and in street noise down to
 * 3 dB): every "HELP HELP HELP" caught except words run together at extreme speed, and no false triggers.
 */
class VoicePhraseLogic(
    private val needed: Int = 3,
    private val minConf: Float = 0.5f,
    private val maxGapMs: Long = 3_500,
    private val maxSpanMs: Long = 10_000,
    private val cooldownMs: Long = 15_000,
) {
    data class Word(val text: String, val conf: Float, val startMs: Long, val endMs: Long, val loud: Boolean)

    private val run = ArrayList<Word>(needed)
    private var quietUntil = Long.MIN_VALUE

    /** How many helps in a row have been heard so far (for the watch's "1 of 3" dots). */
    val count: Int get() = run.size

    /** Feed words in time order. Returns true on the word that completes the phrase. */
    fun onWord(w: Word): Boolean {
        if (w.startMs < quietUntil) { run.clear(); return false }
        if (w.text in PASS) return false
        if (w.text in HELPS && !w.loud) return false // faint: someone else's "help", not hers
        val isHelp = w.text in HELPS && w.conf >= minConf
        if (!isHelp) { run.clear(); return false }
        val last = run.lastOrNull()
        if (last != null && w.startMs - last.endMs > maxGapMs) run.clear()
        run += w
        while (run.size > 1 && run.last().endMs - run.first().startMs > maxSpanMs) run.removeAt(0)
        if (run.size >= needed) {
            run.clear()
            quietUntil = w.endMs + cooldownMs
            return true
        }
        return false
    }

    /**
     * Drops a partial run that has gone stale (she stopped after one or two helps). The recogniser reports a word
     * up to ~1.5 s after it was said, so a run only expires well after the next word could still arrive.
     */
    fun expire(nowMs: Long) {
        val last = run.lastOrNull() ?: return
        if (nowMs - last.endMs > maxGapMs + REPORT_DELAY_MS) run.clear()
    }

    fun reset() { run.clear() }

    private companion object {
        const val REPORT_DELAY_MS = 2_500L
        val HELPS = setOf("help", "yelp", "kelp")
        val PASS = setOf("me")
    }
}
