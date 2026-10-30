package com.v2ray.ang.handler

/**
 * Auto's clock, pure so it is tested on its own — the same shape as the iOS app's AutoSchedule. Times are monotonic
 * milliseconds (SystemClock.elapsedRealtime in the service): a phone that adjusts its time can neither fire nor delay
 * anything.
 *
 * The lines are measured as a trickle, never in bursts: one probe at a time (two during the first pass), every line
 * once per roundSec while the radio is warm or the app is on screen, the line in use every currentSec while watched.
 * The first pass after Connect and after a network change goes best-known-first at firstPaceSec per step, so the whole
 * picture is in hand within seconds and a pick made on cold numbers is corrected (AutoDecision) without a second burst.
 * A pass ended is the moment of the steady-state decision and of the report to the box. Searching (no line answers) is
 * the fast trickle over every line, retries ignored, until one answers.
 *
 *   - [order], [cursor]: the rotation; a line waiting its retry (LineHealth) is skipped.
 *   - [nextProbeAt], [currentDueAt]: the next step, the line in use's probe (only while watched, or forced).
 *   - [phase]: FIRST (quick, two at a time) or STEADY.
 *   - [fallback]: probes go to the fallback target; never sticky — the first probe of every pass is a scout to the box's
 *     target and the pass comes back the moment it answers.
 */
class AutoSchedule {
    enum class Phase { FIRST, STEADY }
    sealed class Action {
        data class Probe(val ids: List<String>) : Action()
        object ProbeCurrent : Action()
    }

    var order: List<String> = emptyList()
    var cursor = 0
    var phase = Phase.FIRST
    var passes = 0
    var nextProbeAt: Long? = null
    var currentDueAt: Long? = null
    var forced = false
    var lastNetworkAt: Long? = null
    var fallback = false
    /** traffic returned after the idle gap: one probe of the line in use, watched or not, then the trickle under its gate */
    var wakeDue = false
    var searchingSince: Long? = null

    /** Connect: the rotation in the order the health memory suggests, the line in use probed first, then the quick pass. */
    fun start(nowMs: Long, order: List<String>) {
        this.order = order
        cursor = 0; phase = Phase.FIRST; passes = 0; forced = true
        nextProbeAt = nowMs
        currentDueAt = nowMs
    }

    /** A manual test ended now (every line measured, warm): the rotation re-seeded best-first at the steady pace. */
    fun reseed(nowMs: Long, order: List<String>, policy: AutoPolicy, draw: Double) {
        this.order = order
        cursor = 0; phase = Phase.STEADY; forced = false
        nextProbeAt = nowMs + paceMs(policy, draw)
        currentDueAt = nowMs + policy.currentMs
    }

    /**
     * What the tick should do now. [warm]: bytes moved within warmSec (the radio is up anyway: a probe costs no wake-up);
     * [watched]: the app asked for the status in the last seconds (the chip is on screen); [due]: whether a line may be
     * probed now (LineHealth). A steady step fires only warm or watched — otherwise the phone sleeps —; the first pass and
     * a network change are forced; traffic returning after the idle gap gets one probe of the line in use. Searching: every
     * line in turn, two at a time, at its own pace ([searchStep]), freely for SEARCH_GATE_AFTER_MS and then only warm or
     * watched. In the first pass the line in use is left out (it was probed first); in steady state it is in the rotation
     * like any line, once per pass.
     */
    fun action(nowMs: Long, warm: Boolean, watched: Boolean, searching: Boolean, current: String, due: (String) -> Boolean): Action? {
        if (searching) {
            val since = searchingSince
            if (since != null && nowMs - since > SEARCH_GATE_AFTER_MS && !(warm || watched)) return null
            val n = nextProbeAt ?: return null
            if (nowMs < n) return null
            val ids = next(2, null) { true }
            return if (ids.isEmpty()) null else Action.Probe(ids)
        }
        val c = currentDueAt
        if (wakeDue && c != null && nowMs >= c) return Action.ProbeCurrent
        if (!(warm || watched || forced || phase == Phase.FIRST)) return null
        if (c != null && nowMs >= c && (watched || forced)) return Action.ProbeCurrent
        val n = nextProbeAt ?: return null
        if (nowMs < n) return null
        val ids = next(if (phase == Phase.FIRST) 2 else 1, if (phase == Phase.FIRST) current else null, due)
        return if (ids.isEmpty()) null else Action.Probe(ids)
    }

    /** The next lines of the rotation that may be probed: not the line in use, not one waiting its retry; from the cursor to
     *  the end of the rotation (a step never straddles a pass boundary), up to [count]. Empty when nothing before the end may
     *  be probed: the pass ends there ([nothingDue]). */
    fun next(count: Int, current: String?, due: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        var k = cursor
        while (k < order.size && out.size < count) {
            val id = order[k]
            k += 1
            if (id == current || !due(id)) continue
            out.add(id)
        }
        return out
    }

    /** [ids] (what [next] gave) were probed now: the cursor moves past them and past every line skipped on the way; a cursor
     *  at the end of the rotation is a pass. Returns true at a pass end. [active]: how many lines the rotation has to probe
     *  right now (the pace spreads roundSec over them, not over lines waiting their retry). */
    fun probed(nowMs: Long, ids: List<String>, policy: AutoPolicy, draw: Double, active: Int? = null): Boolean {
        val last = ids.lastOrNull()
        val at = if (last == null) -1 else (cursor until order.size).firstOrNull { order[it] == last } ?: -1
        cursor = if (at >= 0) at + 1 else cursor + maxOf(1, ids.size)
        var passEnd = false
        if (cursor >= order.size) {
            cursor = 0; passes += 1; passEnd = true
            if (phase == Phase.FIRST) phase = Phase.STEADY
            forced = false
        }
        nextProbeAt = nowMs + if (phase == Phase.FIRST) policy.firstPaceSec * 1000L else paceMs(policy, draw, active)
        return passEnd
    }

    /** Nothing before the end of the rotation may be probed (the line in use, lines waiting their retry): the pass ends here
     *  without a probe; the next step starts the rotation again after the pace. */
    fun nothingDue(nowMs: Long, policy: AutoPolicy, draw: Double, active: Int? = null) {
        cursor = 0; passes += 1
        if (phase == Phase.FIRST) phase = Phase.STEADY
        forced = false
        nextProbeAt = nowMs + paceMs(policy, draw, active)
    }

    /** The line in use was probed now. */
    fun currentProbed(nowMs: Long, policy: AutoPolicy) {
        currentDueAt = nowMs + policy.currentMs
        wakeDue = false
    }

    /** Searching begins: every line in turn, two at a time, the first step now; ends: [reseed]/[start] as the engine decides. */
    fun searchingBegan(nowMs: Long) {
        cursor = 0; phase = Phase.STEADY
        nextProbeAt = nowMs
        currentDueAt = null
        searchingSince = nowMs
    }

    /** Searching: one step taken; the rotation wraps without end, each line once per searchSec (two lines a step), three
     *  times slower when the box says the network blocks every line ([slow]). */
    fun searchStep(nowMs: Long, count: Int, policy: AutoPolicy, slow: Boolean = false) {
        cursor = (cursor + maxOf(1, count)) % maxOf(1, order.size)
        val steps = maxOf(1, (order.size + 1) / 2)
        nextProbeAt = nowMs + maxOf(1000L, policy.searchMs / steps) * (if (slow) 3 else 1)
    }

    /** Wi-Fi ↔ mobile (or the path back after a gap): the trickle restarts in [settleMs] as a quick first pass, in use or
     *  not; a second event within [debounceMs] is the same event. */
    fun networkChanged(nowMs: Long, settleMs: Long = 15_000L, debounceMs: Long = 60_000L) {
        lastNetworkAt?.let { if (nowMs - it < debounceMs) return }
        lastNetworkAt = nowMs
        cursor = 0; phase = Phase.FIRST; forced = true
        nextProbeAt = nowMs + settleMs
        currentDueAt = nowMs + settleMs
    }

    /** Traffic returned after the idle gap: one probe of the line in use now, watched or not; the trickle itself resumes
     *  under its gate (warm or watched), never a forced pass. */
    fun wake(nowMs: Long) {
        if (nextProbeAt == null) return
        wakeDue = true
        currentDueAt = nowMs
    }

    /** The steady pace: roundSec spread over the lines the rotation has to probe ([active], else every line), give or take
     *  jitterPct ([draw] in 0 ..< 1). */
    fun paceMs(policy: AutoPolicy, draw: Double, active: Int? = null): Long {
        val n = if (active != null && active > 0) active else order.size
        val base = policy.roundMs.toDouble() / maxOf(1, n)
        val share = policy.jitterPct / 100.0 * (2 * draw.coerceIn(0.0, 1.0) - 1)
        return (base * (1 + share)).toLong()
    }

    companion object {
        /** Searching probes freely for this long; after it only while the radio is warm or the app on screen */
        const val SEARCH_GATE_AFTER_MS = 600_000L

        /** The probe target after a measurement: on the target, nothing from it and something from the fallback moves the
         *  next probes to the fallback; on the fallback, the scout probe sent to the target decides. */
        fun nextFallback(current: Boolean, targetAnswered: Boolean, fallbackAnswered: Boolean): Boolean =
            if (current) !targetAnswered else !targetAnswered && fallbackAnswered
    }
}
