package com.v2ray.ang.handler

/**
 * When the subscription is fetched again while no line answers (3.1.0) — pure, the same in both apps. Until 3.0.4 the
 * engine fetched once on entering Searching and then every searchFetchSec, whether the call had reached the box or not:
 * a phone whose first call was lost waited a minute for its second.
 *
 *   - the first call at once;
 *   - a call the box did not answer is tried again after 5 s, then 10 s, then every 20 s — for the first ten minutes of
 *     the search; after that every searchFetchSec, as before;
 *   - a call the box answered: the next after 20 s, twice — the engine's confirmed call ("every line failed") comes
 *     between them by itself, and the box gives a phone that is still searching twenty seconds after its first call two
 *     more lines — and after that every searchFetchSec;
 *   - after those first ten minutes a call is made only while the phone is in use (traffic within warmSec, or the app on
 *     screen) or once in IDLE_EVERY_MS: a phone with no way out at all, left in a pocket, does not dial every minute for
 *     hours ([allowed]).
 *
 * A search is an episode, and it outlives the engine: the object is held for the whole process (Android) or by the tunnel's
 * provider (iPhone), because a fetch that brings other lines restarts the core and with it the engine. A search that
 * begins within RESUME_MS of the last one's end — the core restarted on the lines the box sent and they do not answer
 * either; a line answered for a moment — is the same search: its calls keep their pace and its one confirmed report stays
 * spent ([began], [ended]). Without that every restart began anew — fetch, restart, search, fetch at once — for as long
 * as the box kept sending other lines (the independent audit of 3.1.0). A call that began in an earlier search says
 * nothing of this one ([done] with its episode). Times are monotonic milliseconds.
 */
class SearchFetch {
    var nextAt = 0L
        private set
    var failures = 0
        private set
    var answers = 0
        private set
    var episode = 0
        private set
    /** this search's "every line failed" has been told to the box: once a search */
    var confirmed = false
        private set
    /** when this search began (a resumed one: when it first began) — the ten-minute rules count from here */
    var beganAt = 0L
        private set
    private var endedAt = -1L          // when the search last ended, or its engine stopped; -1 while one runs (or before the first)

    /** Searching began: the first call is due now — unless this is the last search going on ([RESUME_MS]). */
    fun began(nowMs: Long) {
        val resumed = episode > 0 && endedAt >= 0 && nowMs - endedAt in 0..RESUME_MS
        endedAt = -1L
        if (resumed) return
        episode += 1; beganAt = nowMs; failures = 0; answers = 0; nextAt = nowMs; confirmed = false
    }

    /** The search ended — a line answers again — or the engine that ran it was stopped (the core restarts on other lines). */
    fun ended(nowMs: Long) {
        if (endedAt < 0) endedAt = nowMs
    }

    /** The VPN was switched off (or restarted by anything but the engine's own fetch) while searching: the search is over for
     *  good — the next one is new, and calls at once. */
    fun reset() {
        endedAt = -1L
        nextAt = 0L
    }

    /** A call is being made: nothing else is due until it ends ([done], which takes the episode this returns). */
    fun started(nowMs: Long, policy: AutoPolicy): Int {
        nextAt = nowMs + policy.searchFetchMs
        return episode
    }

    /** A call ended. [answered]: the box gave its own word (its lines, or its refusal) — not: no way reached the box. [of]: the
     *  episode [started] returned; a call of an earlier search changes nothing. */
    fun done(nowMs: Long, answered: Boolean, policy: AutoPolicy, of: Int = episode) {
        if (of != episode) return
        if (answered) {
            failures = 0; answers += 1
            nextAt = nowMs + if (answers <= 2) minOf(REPEAT_MS, policy.searchFetchMs) else policy.searchFetchMs
        } else {
            failures += 1
            val early = nowMs - beganAt <= EARLY_FOR_MS
            nextAt = nowMs + if (!early) policy.searchFetchMs else minOf(policy.searchFetchMs, when (failures) { 1 -> 5_000L; 2 -> 10_000L; else -> 20_000L })
        }
    }

    /** A call was due and none was made (the iPhone's tunnel: the app is on screen and fetches by itself, or the tunnel's memory
     *  is too near its limit): asked again in REPEAT_MS, and neither an answer nor a failure is counted. */
    fun skipped(nowMs: Long, policy: AutoPolicy) {
        nextAt = nowMs + minOf(REPEAT_MS, policy.searchFetchMs)
    }

    /** The engine has told the box that every line failed: not again in this search. */
    fun noteConfirmed() {
        confirmed = true
    }

    fun due(nowMs: Long): Boolean = nowMs >= nextAt

    /** May a call that is due be made now? Freely for the first ten minutes of the search; after that while the phone is in use
     *  ([warm]: traffic within warmSec; [watched]: the app on screen), or when the last call began IDLE_EVERY_MS ago. */
    fun allowed(nowMs: Long, warm: Boolean, watched: Boolean, lastStartMs: Long): Boolean =
        nowMs - beganAt <= EARLY_FOR_MS || warm || watched || nowMs - lastStartMs >= IDLE_EVERY_MS

    companion object {
        /** the box's `widen_after`: a phone still searching this long after its first call is given more lines */
        const val REPEAT_MS = 20_000L
        /** the quick retries of an unanswered call, and the calls of a phone nobody is using, last this long into a search */
        const val EARLY_FOR_MS = 600_000L
        /** …after which a phone nobody is using calls this often */
        const val IDLE_EVERY_MS = 600_000L
        /** a search that begins this soon after the last one's end is the same search */
        const val RESUME_MS = 120_000L
        /** a mark left by a search counts for this long */
        const val MARK_FRESH_MS = 60_000L

        /** What a searching engine leaves in the store (at the search's start, every twenty seconds, and right before its own
         *  restart): when, and the labels it holds. Whichever fetch then brings other lines, the engine that starts on them
         *  knows which are new. */
        fun mark(nowMs: Long, held: Collection<String>): String = "$nowMs|${held.joinToString(",")}"

        /** The labels among [now] that the mark does not name — the lines the box has just given — or none when the mark
         *  is older than MARK_FRESH_MS, from the future, names no line, or is no mark. */
        fun newSince(mark: String, now: List<String>, nowMs: Long): List<String> {
            val i = mark.indexOf('|')
            if (i <= 0) return emptyList()
            val at = mark.substring(0, i).toLongOrNull() ?: return emptyList()
            if (nowMs - at < 0 || nowMs - at > MARK_FRESH_MS) return emptyList()
            val held = mark.substring(i + 1).split(',').filter { it.isNotEmpty() }.toSet()
            if (held.isEmpty()) return emptyList()
            return now.filter { it !in held }
        }
    }
}
