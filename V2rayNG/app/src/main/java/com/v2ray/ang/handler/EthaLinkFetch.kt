package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentResponse
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Our own link, fetched three ways at once (the operator's order of 2026-10-29, after the review): Encrypted Client
 * Hello on the phone's own network and through the running tunnel start together; the link hop inside Iran starts
 * after a head start for ECH — at once on a network where the hop answered last time. The first server answer wins,
 * whatever it said (the others run out by themselves, their answers dropped). ECH is still always tried, and still
 * first; the plain fetch by name stays the caller's last resort, after all of this, never alongside it.
 *
 * `headers` travel on every way (X-Etha-Lines, what Auto measured). The pure functions are covered by JVM unit tests.
 */
object EthaLinkFetch {
    const val HEAD_START_MS = 3_000L
    const val WAIT_MS = 25_000L              // longer than any way's own clock

    data class Outcome(val response: UrlContentResponse?, val answered: Boolean, val way: String, val signedAt: Long = 0L)

    /** Pure: the hop's head start on a network — none where it won last time. */
    fun headStart(rememberedWay: String?): Long = if (rememberedWay == "hop") 0L else HEAD_START_MS

    /** Pure: the preference the winning way is kept under, per network signature. */
    fun wayKey(networkSignature: String): String = AppConfig.PREF_ETHA_FETCH_WAY + networkSignature.trim().ifEmpty { "any" }

    fun fetch(url: String, subscriptionId: String?, userAgent: String?, headers: Map<String, String>, networkSignature: String): Outcome {
        val key = wayKey(networkSignature)
        val head = headStart(MmkvManager.decodeSettingsString(key))
        val token = EthaSubscription.tokenOf(url).orEmpty()
        val results = LinkedBlockingQueue<Outcome>()
        val stored = EthaEchFetch.storedAddresses(subscriptionId)
        thread(isDaemon = true, name = "fetch-direct") {
            val r = EthaEchFetch.call(EthaEchFetch.buildRequest(url, stored, userAgent, headers = headers), "on the phone's network")
            results.put(Outcome(EthaEchFetch.toResponse(r), EthaEchFetch.answered(r), "ech", r?.signedAt ?: 0L))
        }
        thread(isDaemon = true, name = "fetch-tunnel") {
            val proxy = EthaEchFetch.TunnelProxy("127.0.0.1:${SettingsManager.getHttpPort()}", SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
            val r = EthaEchFetch.call(EthaEchFetch.buildRequest(url, stored, userAgent, proxy, headers), "through the tunnel")
            results.put(Outcome(EthaEchFetch.toResponse(r), EthaEchFetch.answered(r), "tunnel", r?.signedAt ?: 0L))
        }
        thread(isDaemon = true, name = "fetch-hop") {
            if (head > 0) Thread.sleep(head)
            val o = EthaHopFetch.fetch(token, userAgent, headers)
            results.put(Outcome(o.response, o.answered, "hop", o.signedAt))
        }
        // The replay rules (2.1): an answer signed before the one this phone already holds is never taken (a hop serving
        // last week's answer); one signed longer ago than ETHA_SIGNED_STALE_SEC is kept aside and taken only when no
        // fresher answer comes from any way.
        val held = MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_SIGNED_AT)?.toLongOrNull() ?: 0L
        val deadline = System.currentTimeMillis() + WAIT_MS
        var seen = 0
        var stale: Outcome? = null
        while (seen < 3) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val o = results.poll(left, TimeUnit.MILLISECONDS) ?: break
            seen++
            if (!o.answered) continue
            val verdict = replayVerdict(o.signedAt, held, System.currentTimeMillis() / 1000)
            if (verdict == "older") { LogUtil.w(AppConfig.TAG, "Subscription ${o.way}: signed before the one held (${o.signedAt} < $held), not taken"); continue }
            if (verdict == "stale") { if (stale == null) stale = o; LogUtil.w(AppConfig.TAG, "Subscription ${o.way}: signed long ago (${o.signedAt}), kept aside"); continue }
            return accept(o, key, held, networkSignature)
        }
        stale?.let { return accept(it, key, held, networkSignature, aside = true) }
        return Outcome(null, false, "")
    }

    /** Pure: "older" (signed before what is held: never taken), "stale" (signed long ago: last resort), else "fresh". */
    fun replayVerdict(signedAt: Long, held: Long, nowSec: Long): String = when {
        signedAt <= 0 -> "fresh"                                   // unsigned (a status with no signature): nothing to compare
        held > 0 && signedAt < held -> "older"
        nowSec - signedAt > AppConfig.ETHA_SIGNED_STALE_SEC -> "stale"
        else -> "fresh"
    }

    private fun accept(o: Outcome, key: String, held: Long, networkSignature: String, aside: Boolean = false): Outcome {
        MmkvManager.encodeSettings(key, o.way)
        if (o.signedAt > 0) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_SIGNED_AT, maxOf(held, o.signedAt).toString())
        LogUtil.i(AppConfig.TAG, "Subscription answered ${o.way} first (network $networkSignature${if (aside) ", a stale answer, nothing fresher came" else ""})")
        return o
    }
}
