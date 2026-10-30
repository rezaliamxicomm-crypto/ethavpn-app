package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EchFetchRequest
import com.v2ray.ang.dto.EchFetchResult
import com.v2ray.ang.dto.UrlContentResponse

/**
 * The link fetched through the hop inside Iran: the service's second address for the link, for networks that
 * filter the link host's name. The way after the two ECH ways (EthaEchFetch) gave nothing, before the plain last
 * resort. A plain TLS fetch by the core (echfetch.go, "plain": true) — the hop's name is not listed and its
 * certificate is public — dialled at the address the service last named for it, so no DNS is asked and none can be
 * poisoned; the name serves the certificate check alone. On the phone's own network (the app is not inside its VPN).
 *
 * The service names the current hops in the response header X-Etha-Link-Hops ("host=ip,host=ip"); the app keeps
 * the last value it saw and falls back to AppConfig.ETHA_LINK_HOPS — so the spare name can take over without a
 * release. The pure functions are covered by JVM unit tests.
 */
object EthaHopFetch {
    const val TIMEOUT_MS = 12_000L

    data class Hop(val host: String, val ip: String)

    private val HOST = Regex("^(?=.{1,253}$)(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$")

    /** Pure: "host=ip, host=ip" → the hops in that order, no repeats; an entry that is not a name and an IPv4 address is skipped. */
    fun parseHops(header: String?): List<Hop> =
        header.orEmpty().split(',').mapNotNull { entry ->
            val kv = entry.trim().split('=', limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val host = kv[0].trim().lowercase()
            val ip = kv[1].trim()
            if (HOST.matches(host) && isIpv4(ip)) Hop(host, ip) else null
        }.distinct()

    /** Pure: the hops back into the header's form, for keeping. */
    fun format(hops: List<Hop>): String = hops.joinToString(",") { "${it.host}=${it.ip}" }

    /** Pure: the hop's address of a token. */
    fun url(token: String, hop: Hop): String = "https://${hop.host}${AppConfig.ETHA_SUB_PATH}$token"

    /** Pure: the request the core gets — the hop's url, its address, no ECH, no proxy (the phone's own network). */
    fun buildRequest(token: String, hop: Hop, userAgent: String?, headers: Map<String, String> = emptyMap()): EchFetchRequest = EchFetchRequest(
        url = url(token, hop),
        addresses = listOf(hop.ip),
        userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: AppConfig.ETHA_USER_AGENT,
        timeoutMs = TIMEOUT_MS,
        headers = headers,
        plain = true,
        signKeys = AppConfig.ETHA_SIGN_KEYS,
    )

    /** Pure: the hop itself answered — any status — and, for a subscription or an expiry, with the box's signature (EthaEchFetch.trusted). */
    fun answered(result: EchFetchResult?): Boolean = result != null && result.error.isNullOrEmpty() && result.status > 0 && EthaEchFetch.trusted(result)

    /** Pure: a 2xx answer → its body and headers; anything else → null. */
    fun toResponse(result: EchFetchResult?): UrlContentResponse? {
        if (result == null || !result.error.isNullOrEmpty() || !EthaEchFetch.trusted(result)) return null
        val body = result.body.orEmpty()
        if (result.status !in 200..299 || body.isEmpty()) return null
        return UrlContentResponse(body, result.headers.orEmpty())
    }

    /** The hops to try, in order: what the service last said, else the built-in list. */
    fun hops(): List<Hop> =
        parseHops(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINK_HOPS)).ifEmpty { AppConfig.ETHA_LINK_HOPS.map { Hop(it.first, it.second) } }

    /** Keeps what the service said about the hops; a header with no valid entry changes nothing. */
    fun remember(headers: Map<String, String>) {
        val hops = parseHops(headers[AppConfig.ETHA_LINK_HOPS_HEADER] ?: return)
        if (hops.isNotEmpty()) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINK_HOPS, format(hops))
    }

    /** `token`'s link fetched through the hops, one after the other, on the phone's network. */
    fun fetch(token: String, userAgent: String?, headers: Map<String, String> = emptyMap()): EthaEchFetch.Outcome {
        if (token.isEmpty()) return EthaEchFetch.Outcome(null, false)
        for (hop in hops()) {
            val result = EthaEchFetch.call(buildRequest(token, hop, userAgent, headers), "through the hop ${hop.host}")
            if (answered(result)) return EthaEchFetch.Outcome(toResponse(result), true, result?.signedAt ?: 0L)
        }
        return EthaEchFetch.Outcome(null, false)
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }
}
