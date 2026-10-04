package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EchFetchRequest
import com.v2ray.ang.dto.EchFetchResult
import com.v2ray.ang.dto.UrlContentResponse
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import libv2ray.Libv2ray

/**
 * Fetches one of our links, the only way it is ever fetched: with Encrypted Client Hello enforced. The link
 * host's name is never stated in the clear — when ECH is not possible the fetch fails and the next refresh
 * tries again.
 *
 * Two ways out, ECH on both: first on the phone's own network (the app is not inside its own VPN, so this
 * does not depend on the tunnel), the key asked over plain UDP DNS; when that gives nothing, through the
 * running tunnel's local proxy, with the key the app carries (no UDP travels through that proxy).
 *
 * The work is the core's (libv2ray's FetchSubscriptionEch, from echfetch/ in this repo): the key from DNS or
 * the pinned one with the server's retry key; a connection to a Cloudflare address the app already knows,
 * never to the host's A record. This object decides which addresses to offer and reads the answer. The pure
 * functions are covered by JVM unit tests.
 */
object EthaEchFetch {
    private const val TIMEOUT_MS = 20_000L
    private const val TUNNEL_TIMEOUT_MS = 15_000L

    /** Dead addresses cost a dial timeout each before the pinned ones get their turn. */
    const val MAX_STORED_ADDRESSES = 3

    /** The tunnel's local proxy, for the fetch that goes through it. */
    data class TunnelProxy(val address: String, val user: String? = null, val password: String? = null)

    /** The body and headers of `url`, fetched with ECH; null when that did not succeed (the reason is in the log). */
    fun fetch(url: String, subscriptionId: String?, userAgent: String?): UrlContentResponse? {
        val stored = storedAddresses(subscriptionId)
        val direct = call(buildRequest(url, stored, userAgent), "on the phone's network")
        if (answered(direct)) return toResponse(direct)   // the server answered, whatever it said: through the tunnel it says the same
        val proxy = TunnelProxy("127.0.0.1:${SettingsManager.getHttpPort()}", SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
        return toResponse(call(buildRequest(url, stored, userAgent, proxy), "through the tunnel"))
    }

    private fun call(request: EchFetchRequest, way: String): EchFetchResult? {
        val raw = try {
            Libv2ray.fetchSubscriptionEch(JsonUtil.toJson(request))
        } catch (e: UnsatisfiedLinkError) {
            LogUtil.e(AppConfig.TAG, "ECH fetch is missing in libv2ray", e)
            return null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ECH fetch failed", e)
            return null
        }
        val result = JsonUtil.fromJsonSafe(raw, EchFetchResult::class.java)
        if (toResponse(result) == null) {
            LogUtil.w(AppConfig.TAG, "ECH fetch $way gave no subscription: ${describe(result)}")
        } else {
            LogUtil.i(AppConfig.TAG, "Subscription fetched with ECH $way via ${result?.address} (key: ${result?.keySource})")
        }
        return result
    }

    /** Pure: the server itself answered, with ECH — any status. Only a fetch that reached nobody is worth a second way. */
    fun answered(result: EchFetchResult?): Boolean =
        result != null && result.error.isNullOrEmpty() && result.echAccepted && result.status > 0

    /** Pure: the request the core gets — for the phone's own network, or with [proxy] for the way through the tunnel. */
    fun buildRequest(url: String, stored: List<String>, userAgent: String?, proxy: TunnelProxy? = null): EchFetchRequest = EchFetchRequest(
        url = url,
        addresses = candidateAddresses(stored),
        pinnedAddresses = AppConfig.ETHA_ECH_ADDRESSES,
        resolvers = AppConfig.ETHA_ECH_RESOLVERS,
        lookupName = AppConfig.ETHA_ECH_LOOKUP_NAME,
        pinnedKey = AppConfig.ETHA_ECH_PINNED_KEY,
        userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: AppConfig.ETHA_USER_AGENT,
        timeoutMs = if (proxy == null) TIMEOUT_MS else TUNNEL_TIMEOUT_MS,
        proxy = proxy?.address.orEmpty(),
        proxyUser = proxy?.user.orEmpty(),
        proxyPassword = proxy?.password.orEmpty(),
    )

    /** Pure: the stored lines' IPv4 addresses in the order given (the selected line's first), no repeats, at most [MAX_STORED_ADDRESSES]. */
    fun candidateAddresses(stored: List<String>): List<String> =
        stored.map { it.trim() }.filter { isIpv4(it) }.distinct().take(MAX_STORED_ADDRESSES)

    /** Pure: a 2xx answer that came with ECH accepted → its body and headers; anything else → null. */
    fun toResponse(result: EchFetchResult?): UrlContentResponse? {
        if (result == null || !result.error.isNullOrEmpty() || !result.echAccepted) return null
        val body = result.body.orEmpty()
        if (result.status !in 200..299 || body.isEmpty()) return null
        return UrlContentResponse(body, result.headers.orEmpty())
    }

    /** Pure: why a fetch gave nothing, for the log — never a body, never a token. */
    fun describe(result: EchFetchResult?): String = when {
        result == null -> "no result from the core"
        !result.error.isNullOrEmpty() -> result.error.orEmpty()
        !result.echAccepted -> "ECH not accepted"
        result.body.isNullOrEmpty() -> "status ${result.status}, empty body"
        else -> "status ${result.status}"
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    /** The lines of this subscription dial clean Cloudflare addresses: those, the selected line's first. */
    private fun storedAddresses(subscriptionId: String?): List<String> {
        if (subscriptionId.isNullOrBlank()) return emptyList()
        val guids = MmkvManager.decodeServerList(subscriptionId)
        val selected = MmkvManager.getSelectServer()
        val ordered = if (selected != null && guids.remove(selected)) listOf(selected) + guids else guids
        return ordered.mapNotNull { MmkvManager.decodeServerConfig(it)?.server }
    }
}
