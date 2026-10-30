package com.v2ray.ang.handler

/**
 * When new connections move to another line — pure, the same in both apps (the iOS app's AutoDecision). Four cases,
 * each with its own rule:
 *
 *   - the line in use failed (its probe, or the stall detector's probe): failover at once to the best line known good,
 *     no dwell (the engine verifies it with one probe before the move);
 *   - the first pass after Connect or a network change: at most one move, for a line clearly better — twice the margin —
 *     than the line in use's fresh number; that is the correction of a pick made on cold numbers;
 *   - steady state, at the end of a pass: a latency-only move needs the best line ahead by the margin at persistPasses
 *     consecutive pass ends, or ahead by twice the margin at one, and never within dwellSec of the last move. A line
 *     20 ms faster this minute and slower the next never moves anyone;
 *   - a manual test (the customer asked): the margin alone decides, no dwell.
 */
class AutoDecision {
    enum class Phase { FIRST, PASS_END, MANUAL, FAILOVER }
    data class Candidate(val id: String, val ms: Long, val order: Int)   // ms: -1 failed, 0 not measured

    var lastSwitchAt: Long? = null            // monotonic ms
    val leading = HashMap<String, Int>()      // candidate → consecutive pass ends ahead by the margin
    var firstSwitchDone = false

    /** The line new connections should take now, or null to stay. [current] is the line in use with its fresh time. */
    fun target(nowMs: Long, phase: Phase, current: Candidate, candidates: List<Candidate>, policy: AutoPolicy): String? {
        val best = best(candidates.filter { it.id != current.id }) ?: return null
        if (current.ms < 0 || phase == Phase.FAILOVER) return commit(best.id, nowMs)
        return when (phase) {
            Phase.MANUAL -> if (policy.shouldSwitch(current.ms, best.ms)) commit(best.id, nowMs) else null
            Phase.FIRST -> {
                if (firstSwitchDone) return null
                if (!policy.shouldSwitch(current.ms, best.ms, factor = 2)) return null
                firstSwitchDone = true
                commit(best.id, nowMs)
            }
            Phase.PASS_END -> {
                val ahead = policy.shouldSwitch(current.ms, best.ms)
                val clearly = policy.shouldSwitch(current.ms, best.ms, factor = 2)
                val streak = if (ahead) (leading[best.id] ?: 0) + 1 else 0
                leading.clear()
                if (ahead) leading[best.id] = streak
                val persistent = clearly || streak >= policy.persistPasses
                if (!persistent) return null
                val last = lastSwitchAt
                if (last != null && nowMs - last < policy.dwellMs) return null   // the counter keeps; the dwell decides later
                commit(best.id, nowMs)
            }
            Phase.FAILOVER -> commit(best.id, nowMs)
        }
    }

    /** A move was made by the engine for its own reasons (a failover it verified): the clocks follow. */
    fun moved(nowMs: Long) { commit("", nowMs) }

    private fun commit(id: String, nowMs: Long): String {
        lastSwitchAt = nowMs
        leading.clear()
        return id
    }

    companion object {
        /** The best measured line: the lowest time, the subscription's order among equals. */
        fun best(candidates: List<Candidate>): Candidate? =
            candidates.filter { it.ms > 0 }.minWithOrNull(compareBy({ it.ms }, { it.order }))
    }
}
