package com.v2ray.ang.handler

import java.util.concurrent.ConcurrentHashMap

/**
 * Auto's clock, pure so it is tested on its own — the same shape as the iOS app's AutoSchedule. One rule for when the
 * next round runs: an interval after the moment the last measurement ended, whatever it measured and however long it
 * took. Until 2.0.2 the round fired by one clock and each line was then measured only if its own last probe was old
 * enough by a second clock; a round could wake a second too early for every line, measure nothing and move the first
 * clock on, so rounds came every six minutes instead of three.
 *
 *   - nextRoundAt: the next regular round (the Searching state: every searchSec); null until the first race ended.
 *   - settleAt: the one settling round, settleSec after the connect race (it measures every line at once on a core a
 *     second old and reads high).
 *   - retryAt: a failed line is not measured again before its retry time (failedRetrySec after each failed probe).
 *   - fallback: the next measurement goes to the fallback target. Never sticky: in that mode every round first sends
 *     one probe to the box's target and comes back to it the moment it answers.
 *   - a network change is one normal round 15 s later (debounced), not a race of every line at once; traffic
 *     returning after the idle gap brings a round at once.
 */
class AutoSchedule {
    enum class Action { SETTLE, ROUND }

    @Volatile var nextRoundAt: Long? = null
    @Volatile var settleAt: Long? = null
    val retryAt = ConcurrentHashMap<String, Long>()
    @Volatile var fallback = false
    /** the next round runs even with no traffic (a network change, a wake after idle) */
    @Volatile var forced = false
    @Volatile var lastNetworkAt: Long? = null

    /** A measurement ended now with these results (-1 failed): failed lines wait their retry, answered lines are free
     *  again, and the next round comes one interval after this moment. */
    fun roundDone(nowMs: Long, results: Map<String, Long>, policy: AutoPolicy, draw: Double, searching: Boolean) {
        for ((id, ms) in results) if (ms < 0) retryAt[id] = nowMs + policy.failedRetrySec * 1000L else retryAt.remove(id)
        forced = false
        nextRoundAt = nowMs + if (searching) policy.searchSec * 1000L else policy.jitteredRoundMs(draw)
    }

    /** A race ended now (every line at once): as roundDone, plus the settling round settleSec later when asked (after
     *  the connect race; not after a manual test — its numbers are warm). */
    fun raceDone(nowMs: Long, results: Map<String, Long>, policy: AutoPolicy, draw: Double, searching: Boolean, settle: Boolean) {
        roundDone(nowMs, results, policy, draw, searching)
        settleAt = if (settle && policy.settleSec > 0) nowMs + policy.settleSec * 1000L else null
    }

    /** What the tick should do now: the settling round when its time has come, else a round when the interval has
     *  passed and the tunnel is in use (or Searching, or the round is forced), else nothing. */
    fun action(nowMs: Long, inUse: Boolean, searching: Boolean): Action? {
        settleAt?.let { if (nowMs >= it) return Action.SETTLE }
        nextRoundAt?.let { if (nowMs >= it && (inUse || searching || forced)) return Action.ROUND }
        return null
    }

    /** The settling round is taken: it runs once. */
    fun settleTaken() { settleAt = null }

    /** The lines a round measures now: every line but a failed one still waiting its retry; Searching: every line. */
    fun linesForRound(nowMs: Long, lines: List<String>, searching: Boolean): List<String> =
        if (searching) lines else lines.filter { id -> retryAt[id]?.let { nowMs >= it } ?: true }

    /** Wi-Fi ↔ mobile (or the network back after a gap): one normal round settleMs from now, every line, in use or not;
     *  failed lines are free again; a second change within debounceMs is the same change. */
    fun networkChanged(nowMs: Long, settleMs: Long = 15_000L, debounceMs: Long = 60_000L) {
        lastNetworkAt?.let { if (nowMs - it < debounceMs) return }
        lastNetworkAt = nowMs
        retryAt.clear()
        settleAt = null
        forced = true
        nextRoundAt = nowMs + settleMs
    }

    /** Traffic returned after the idle gap: the lines are measured now, not when the old interval runs out. */
    fun wake(nowMs: Long) {
        if (nextRoundAt == null) return
        forced = true
        nextRoundAt = nowMs
    }

    companion object {
        /** The probe target after a measurement: on the target, a full set with nothing from it and something from the
         *  fallback moves the next measurement to the fallback; on the fallback, the one scout probe decides every round. */
        fun nextFallback(current: Boolean, fullSet: Boolean, targetAnswered: Boolean, fallbackAnswered: Boolean): Boolean =
            if (current) !targetAnswered else fullSet && !targetAnswered && fallbackAnswered
    }
}
