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

    data class Outcome(val response: UrlContentResponse?, val answered: Boolean, val way: String)

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
            results.put(Outcome(EthaEchFetch.toResponse(r), EthaEchFetch.answered(r), "ech"))
        }
        thread(isDaemon = true, name = "fetch-tunnel") {
            val proxy = EthaEchFetch.TunnelProxy("127.0.0.1:${SettingsManager.getHttpPort()}", SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
            val r = EthaEchFetch.call(EthaEchFetch.buildRequest(url, stored, userAgent, proxy, headers), "through the tunnel")
            results.put(Outcome(EthaEchFetch.toResponse(r), EthaEchFetch.answered(r), "tunnel"))
        }
        thread(isDaemon = true, name = "fetch-hop") {
            if (head > 0) Thread.sleep(head)
            val o = EthaHopFetch.fetch(token, userAgent, headers)
            results.put(Outcome(o.response, o.answered, "hop"))
        }
        val deadline = System.currentTimeMillis() + WAIT_MS
        var seen = 0
        while (seen < 3) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val o = results.poll(left, TimeUnit.MILLISECONDS) ?: break
            seen++
            if (o.answered) {
                MmkvManager.encodeSettings(key, o.way)
                LogUtil.i(AppConfig.TAG, "Subscription answered ${o.way} first (network $networkSignature)")
                return o
            }
        }
        return Outcome(null, false, "")
    }
}
