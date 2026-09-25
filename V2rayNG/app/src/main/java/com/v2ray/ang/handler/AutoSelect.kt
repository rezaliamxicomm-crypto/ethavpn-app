package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * Which line to connect with. The lowest positive real-delay result wins outright; only an
 * exact tie falls back to the earlier line in the subscription body. Lines with no result or
 * a failed one (delay <= 0) are never chosen.
 */
object AutoSelect {
    const val TIE_MS = 0L

    data class Candidate(val guid: String, val delayMs: Long, val order: Int)

    fun best(candidates: List<Candidate>, exclude: Set<String> = emptySet(), tieMs: Long = TIE_MS): String? {
        val ok = candidates.filter { it.delayMs > 0 && it.guid !in exclude }
        if (ok.isEmpty()) return null
        val min = ok.minOf { it.delayMs }
        return ok.filter { it.delayMs <= min + tieMs }.minByOrNull { it.order }?.guid
    }

    /** Every line of a subscription with its last real-delay result (0 = never tested, -1 = failed). */
    fun candidates(subId: String): List<Candidate> =
        MmkvManager.decodeServerList(subId).mapIndexed { i, guid ->
            Candidate(guid, MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L, i)
        }

    fun pickBest(subId: String, exclude: Set<String> = emptySet()): String? = best(candidates(subId), exclude)

    /** When the last full test of the lines finished (0 = never); the Home screen skips a re-test while it is fresh. */
    fun lastTestAt(): Long = MmkvManager.decodeSettingsLong(AppConfig.PREF_ETHA_LAST_TEST, 0L)
    fun markTested() = MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_TEST, System.currentTimeMillis())
    fun resultsFresh(now: Long = System.currentTimeMillis()): Boolean = now - lastTestAt() < AppConfig.ETHA_DELAY_FRESH_MS
}
