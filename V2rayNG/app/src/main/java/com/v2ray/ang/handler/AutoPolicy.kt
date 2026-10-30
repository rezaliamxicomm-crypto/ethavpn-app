package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * The rules of Auto — the same in both apps (the iOS app's AutoPolicy). The running core holds every line of the
 * subscription behind one balancer; the service measures the lines through that core (core/EthaAuto) as a trickle,
 * never in bursts, and holds the balancer to the best, so a change of line moves new connections only and cuts nothing
 * that is open. The numbers here are the policy's defaults; the service's header X-Etha-Auto ("round=180,failed=900,
 * idle=600,margin=15,floor=25,stall=4,timeout=5,conc=8,fresh=600,search=60,sfetch=300,pace=1,cur=30,jitter=10,dwell=90,
 * persist=2,cap=7200,warm=20,fover=1500") replaces any of them on the next fetch, so they change without a release.
 */
data class AutoPolicy(
    /** every line measured once in this long, one probe at a time (the trickle), while the radio is warm or the app on screen */
    val roundSec: Int = 180,
    /** a line that failed its last probe is tried again after this long, doubling at each failure (LineHealth) */
    val failedRetrySec: Int = 900,
    /** no traffic for this long: idle — traffic returning probes the line in use and resumes the trickle at once */
    val idleSec: Int = 600,
    /** another line must beat the line in use by this share of its time… */
    val marginPct: Int = 15,
    /** …and by at least this many ms, to take over */
    val floorMs: Int = 25,
    /** bytes going out and none coming back for this long: the line in use and the best alternative are probed at once */
    val stallSec: Int = 4,
    /** one probe request's window; a line that does not answer within it counts as failed */
    val probeTimeoutSec: Int = 5,
    /** lines probed at once by a manual test (Test latency measures every line at once) */
    val concurrency: Int = 8,
    /** results younger than this are trusted at Connect */
    val freshSec: Int = 600,
    /** no line answers (the Searching state): every line probed again within this long, in use or not */
    val searchSec: Int = 60,
    /** …and the subscription fetched again this often, for lines the box may have replaced */
    val searchFetchSec: Int = 300,
    /** the first pass after Connect (and after a network change) goes best-known-first at this pace per step, two at a time */
    val firstPaceSec: Int = 1,
    /** the line in use is probed again this often while the app is on screen; otherwise its health is read from the traffic */
    val currentSec: Int = 30,
    /** each step of the trickle is the pace give or take this share, so the probes are not metronomic */
    val jitterPct: Int = 10,
    /** no latency-only move within this long of the last move (a failover ignores it) */
    val dwellSec: Int = 90,
    /** a latency-only move needs the candidate ahead by the margin at this many consecutive pass ends (one when ahead by twice the margin) */
    val persistPasses: Int = 2,
    /** a failed line's retry doubles from failedRetrySec at each failure up to this */
    val retryCapSec: Int = 7200,
    /** a steady step fires only while bytes moved within this long (the radio is up anyway) or the app is on screen */
    val warmSec: Int = 20,
    /** the window of the failover probes (the line in use and the best alternative together) */
    val failoverMs: Int = 1500,
) {
    val roundMs: Long get() = roundSec * 1000L
    val idleMs: Long get() = idleSec * 1000L
    val probeTimeoutMs: Long get() = probeTimeoutSec * 1000L
    val freshMs: Long get() = freshSec * 1000L
    val searchMs: Long get() = searchSec * 1000L
    val searchFetchMs: Long get() = searchFetchSec * 1000L
    val warmMs: Long get() = warmSec * 1000L
    val currentMs: Long get() = currentSec * 1000L
    val dwellMs: Long get() = dwellSec * 1000L

    /**
     * Should new connections move from the line in use (its last time, -1 failed, 0 not measured) to the best line
     * (bestMs > 0)? Yes when the line in use failed, or when the best is better by the margin — at least floorMs and
     * at least marginPct of the line in use, both times [factor] (2 for "clearly better"). A line not measured yet
     * keeps its connections until its own result is in.
     */
    fun shouldSwitch(currentMs: Long, bestMs: Long, factor: Long = 1): Boolean {
        if (bestMs <= 0) return false
        if (currentMs < 0) return true
        if (currentMs == 0L) return false
        val margin = maxOf(floorMs.toLong() * factor, currentMs * marginPct * factor / 100)
        return bestMs < currentMs - margin
    }

    /**
     * The line new connections should take, or null to stay: the lowest measured time (AutoSelect.best: an exact tie
     * goes to the earlier line) when the line in use failed or is beaten by the margin; never while pinned, never
     * without a measured line, and a line in use that is not among the candidates counts as failed. (The service's
     * decisions are AutoDecision's since 2.1; this is the app's test and the offline pick.)
     */
    fun target(current: String?, candidates: List<AutoSelect.Candidate>, pinned: Boolean): String? {
        if (pinned) return null
        val best = AutoSelect.best(candidates) ?: return null
        if (best == current) return null
        if (current == null) return best
        val currentMs = candidates.firstOrNull { it.guid == current }?.delayMs ?: -1L
        val bestMs = candidates.first { it.guid == best }.delayMs
        return if (shouldSwitch(currentMs, bestMs)) best else null
    }

    /**
     * A stall: among the samples (time in ms, bytes up, bytes down; oldest first), between the newest and the newest
     * one at least stallSec older, at least STALL_MIN_UP_BYTES went out and nothing came back.
     */
    fun stalled(samples: List<Triple<Long, Long, Long>>, nowMs: Long): Boolean {
        if (samples.size < 2) return false
        val newest = samples.last()
        val base = samples.lastOrNull { it.first <= nowMs - stallSec * 1000L } ?: return false
        if (base === newest) return false
        return newest.second - base.second >= STALL_MIN_UP_BYTES && newest.third - base.third <= 0
    }

    companion object {
        const val STALL_MIN_UP_BYTES = 2048L
        val DEFAULT = AutoPolicy()

        /**
         * The header's "key=value" list over base: unknown keys ignored, each value clamped to its range, a value that
         * is no number ignored; null or blank leaves base as it is.
         */
        fun parse(header: String?, base: AutoPolicy = DEFAULT): AutoPolicy {
            if (header.isNullOrBlank()) return base
            var p = base
            for (part in header.split(',', ';')) {
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) continue
                val v = kv[1].trim().toIntOrNull() ?: continue
                when (kv[0].trim().lowercase()) {
                    "round" -> p = p.copy(roundSec = v.coerceIn(30, 3600))
                    "failed" -> p = p.copy(failedRetrySec = v.coerceIn(30, 7200))
                    "idle" -> p = p.copy(idleSec = v.coerceIn(60, 7200))
                    "margin" -> p = p.copy(marginPct = v.coerceIn(0, 90))
                    "floor" -> p = p.copy(floorMs = v.coerceIn(0, 2000))
                    "stall" -> p = p.copy(stallSec = v.coerceIn(3, 60))
                    "timeout" -> p = p.copy(probeTimeoutSec = v.coerceIn(2, 30))
                    "conc" -> p = p.copy(concurrency = v.coerceIn(1, 64))
                    "fresh" -> p = p.copy(freshSec = v.coerceIn(0, 86400))
                    "search" -> p = p.copy(searchSec = v.coerceIn(15, 600))
                    "sfetch" -> p = p.copy(searchFetchSec = v.coerceIn(60, 3600))
                    "pace" -> p = p.copy(firstPaceSec = v.coerceIn(1, 30))
                    "cur" -> p = p.copy(currentSec = v.coerceIn(10, 300))
                    "jitter" -> p = p.copy(jitterPct = v.coerceIn(0, 50))
                    "dwell" -> p = p.copy(dwellSec = v.coerceIn(0, 3600))
                    "persist" -> p = p.copy(persistPasses = v.coerceIn(1, 10))
                    "cap" -> p = p.copy(retryCapSec = v.coerceIn(60, 86400))
                    "warm" -> p = p.copy(warmSec = v.coerceIn(5, 600))
                    "fover" -> p = p.copy(failoverMs = v.coerceIn(500, 10000))
                }
            }
            return p
        }

        /** The policy in force on this phone: the service's last header over the defaults. */
        fun current(): AutoPolicy = parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_AUTO_POLICY))
    }
}
