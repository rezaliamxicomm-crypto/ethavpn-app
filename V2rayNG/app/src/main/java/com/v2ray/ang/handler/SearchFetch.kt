package com.v2ray.ang.handler

/**
 * When the subscription is fetched again while no line answers (3.1.0) — pure, the same in both apps. Until 3.0.4 the
 * engine fetched once on entering Searching and then every searchFetchSec, whether the call had reached the box or not:
 * a phone whose first call was lost waited a minute for its second.
 *
 *   - the first call at once (the engine has just measured every line: the report it carries is whole);
 *   - a call the box did not answer is tried again after 5 s, then 10 s, then every 20 s — for the first ten minutes of
 *     the search; after that every searchFetchSec, as before (a phone with no way out at all must not spend its battery);
 *   - a call the box answered without other lines: once more after 20 s — the box gives a phone that is still searching
 *     two more lines then — and after that every searchFetchSec.
 *
 * Times are monotonic milliseconds.
 */
class SearchFetch {
    var nextAt = 0L
        private set
    var failures = 0
        private set
    var answers = 0
        private set
    private var beganAt = 0L

    /** Searching began: the first call is due now. */
    fun began(nowMs: Long) {
        beganAt = nowMs; failures = 0; answers = 0; nextAt = nowMs
    }

    /** A call is being made: nothing else is due until it ends ([done]). */
    fun started(nowMs: Long, policy: AutoPolicy) {
        nextAt = nowMs + policy.searchFetchMs
    }

    /** A call ended. [answered]: the box answered it (lines, or its own refusal) — not: no way reached the box. */
    fun done(nowMs: Long, answered: Boolean, policy: AutoPolicy) {
        if (answered) {
            failures = 0; answers += 1
            nextAt = nowMs + if (answers == 1) minOf(REPEAT_MS, policy.searchFetchMs) else policy.searchFetchMs
        } else {
            failures += 1
            val early = nowMs - beganAt <= EARLY_FOR_MS
            nextAt = nowMs + if (!early) policy.searchFetchMs else minOf(policy.searchFetchMs, when (failures) { 1 -> 5_000L; 2 -> 10_000L; else -> 20_000L })
        }
    }

    fun due(nowMs: Long): Boolean = nowMs >= nextAt

    companion object {
        /** the box's `widen_after`: a phone still searching this long after its first call is given more lines */
        const val REPEAT_MS = 20_000L
        /** the quick retries of an unanswered call last this long into a search */
        const val EARLY_FOR_MS = 600_000L
        /** a mark left by a search counts for this long */
        const val MARK_FRESH_MS = 60_000L

        /** What a searching engine leaves in the store (at the search's start and at every step): when, and the labels it
         *  holds. Whichever fetch then brings other lines, the engine that starts on them knows which are new. */
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
