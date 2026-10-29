package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * The rules of Auto — the same in both apps (the iOS app's AutoPolicy). The running core holds every line of the
 * subscription behind one balancer; the service measures the lines through that core (core/EthaAuto) and holds the
 * balancer to the best, so a change of line moves new connections only and cuts nothing that is open. The numbers
 * here are the policy's defaults; the service's header X-Etha-Auto ("round=180,failed=900,idle=600,margin=15,
 * floor=25,stall=8,timeout=5,conc=8,fresh=600") replaces any of them on the next fetch, so they change without a
 * release.
 */
data class AutoPolicy(
    /** every line measured again this often, while the tunnel is in use */
    val roundSec: Int = 180,
    /** a line that failed its last probe is tried again this often instead */
    val failedRetrySec: Int = 900,
    /** no traffic for this long: no rounds until traffic returns */
    val idleSec: Int = 600,
    /** another line must beat the line in use by this share of its time… */
    val marginPct: Int = 15,
    /** …and by at least this many ms, to take over */
    val floorMs: Int = 25,
    /** bytes going out and none coming back for this long: the line in use is probed at once */
    val stallSec: Int = 8,
    /** one probe request's window; a line that does not answer within it counts as failed */
    val probeTimeoutSec: Int = 5,
    /** lines probed at once in a round (the first race and a "Ping all" measure every line at once) */
    val concurrency: Int = 8,
    /** results younger than this are trusted at Connect */
    val freshSec: Int = 600,
) {
    val roundMs: Long get() = roundSec * 1000L
    val idleMs: Long get() = idleSec * 1000L
    val probeTimeoutMs: Long get() = probeTimeoutSec * 1000L
    val freshMs: Long get() = freshSec * 1000L

    /**
     * Should new connections move from the line in use (its last time, -1 failed, 0 not measured) to the best line
     * (bestMs > 0)? Yes when the line in use failed, or when the best is better by the margin — at least floorMs and
     * at least marginPct of the line in use. A line not measured yet keeps its connections until its own result is in.
     */
    fun shouldSwitch(currentMs: Long, bestMs: Long): Boolean {
        if (bestMs <= 0) return false
        if (currentMs < 0) return true
        if (currentMs == 0L) return false
        val margin = maxOf(floorMs.toLong(), currentMs * marginPct / 100)
        return bestMs < currentMs - margin
    }

    /**
     * The line new connections should take, or null to stay: the lowest measured time (AutoSelect.best: an exact tie
     * goes to the earlier line) when the line in use failed or is beaten by the margin; never while pinned, never
     * without a measured line, and a line in use that is not among the candidates counts as failed.
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

    /** Is a line due for a probe in this round? Failed lines wait failedRetrySec, the rest roundSec. */
    fun due(nowMs: Long, probedAtMs: Long, failed: Boolean): Boolean =
        nowMs - probedAtMs >= (if (failed) failedRetrySec else roundSec) * 1000L

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
                }
            }
            return p
        }

        /** The policy in force on this phone: the service's last header over the defaults. */
        fun current(): AutoPolicy = parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_AUTO_POLICY))
    }
}
