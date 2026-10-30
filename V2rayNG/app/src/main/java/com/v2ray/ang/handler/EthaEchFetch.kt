package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EchFetchRequest
import com.v2ray.ang.dto.EchFetchResult
import com.v2ray.ang.dto.UrlContentResponse
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import libv2ray.Libv2ray

/**
 * Fetches one of our links with Encrypted Client Hello enforced: the link host's name is not stated in the
 * clear by anything here.
 *
 * Two ways out, ECH on both: first on the phone's own network (the app is not inside its own VPN, so this
 * does not depend on the tunnel), the key asked over plain UDP DNS; when nobody answers, through the running
 * tunnel's local proxy, with the key the app carries (no UDP travels through that proxy). When neither way
 * got an answer the caller (AngConfigManager.updateConfigViaSub) tries the link hop inside Iran (EthaHopFetch,
 * plain, through the same core call) and then fetches the link once more as before, without ECH — the
 * operator's last resort for a network that blocks ECH itself.
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
    /** The transports whose lines dial a Cloudflare address: only those addresses serve the ECH fetch (a relay would answer with its cover site). */
    val CLOUDFLARE_TRANSPORTS = setOf("xhttp", "splithttp", "ws", "httpupgrade")

    /** The tunnel's local proxy, for the fetch that goes through it. */
    data class TunnelProxy(val address: String, val user: String? = null, val password: String? = null)

    /** What a fetch came to: the subscription, when there is one, whether the server answered at all (any status), and when the box signed the answer (0 unsigned). */
    data class Outcome(val response: UrlContentResponse?, val answered: Boolean, val signedAt: Long = 0L)

    /**
     * `url` fetched with ECH, on the phone's network and then through the tunnel. `response` is the body and headers
     * of a 2xx answer, else null (the reason is in the log); `answered` is false only when nobody answered on either way.
     */
    fun fetch(url: String, subscriptionId: String?, userAgent: String?, headers: Map<String, String> = emptyMap()): Outcome {
        val stored = storedAddresses(subscriptionId)
        val direct = call(buildRequest(url, stored, userAgent, headers = headers), "on the phone's network")
        if (answered(direct)) return Outcome(toResponse(direct), true, direct?.signedAt ?: 0L)   // the server answered, whatever it said: through the tunnel it says the same
        val proxy = TunnelProxy("127.0.0.1:${SettingsManager.getHttpPort()}", SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
        val tunnel = call(buildRequest(url, stored, userAgent, proxy, headers), "through the tunnel")
        return Outcome(toResponse(tunnel), answered(tunnel), tunnel?.signedAt ?: 0L)
    }

    internal fun call(request: EchFetchRequest, way: String): EchFetchResult? {
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
        val got = result != null && result.error.isNullOrEmpty() && result.status in 200..299 && !result.body.isNullOrEmpty()
        if (!got) {
            LogUtil.w(AppConfig.TAG, "fetch $way gave no subscription: ${describe(result)}")
        } else {
            LogUtil.i(AppConfig.TAG, "Subscription fetched ${if (result!!.echAccepted) "with ECH" else "plain"} $way via ${result.address} (key: ${result.keySource}, signed: ${result.signed ?: "-"})")
        }
        return result
    }

    /** Pure: the server itself answered, with ECH — any status — and, for a subscription or an expiry, with the box's signature. */
    fun answered(result: EchFetchResult?): Boolean =
        result != null && result.error.isNullOrEmpty() && result.echAccepted && result.status > 0 && trusted(result)

    /**
     * Pure: the box's own word (2.1). A subscription (200) or an expiry (403) counts only with the box's signature on it
     * (echfetch/signature.go against AppConfig.ETHA_SIGN_KEYS): the hop inside Iran terminates TLS, and without this a
     * seized hop could hand the app other lines, a herding hint or a false expiry. Any other status carries no signature
     * and is taken as it is (nothing of it is acted upon beyond a message). A build pinning no key checks nothing.
     */
    fun trusted(result: EchFetchResult): Boolean {
        if (result.status != 200 && result.status != 403) return true
        if (AppConfig.ETHA_SIGN_KEYS.isEmpty()) return true
        return result.signed == "ok"
    }

    /** Pure: signed longer ago than ETHA_SIGNED_STALE_SEC — taken only when no fresher answer arrives (a hop replaying an old one). */
    fun stale(result: EchFetchResult?, nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        result != null && result.signedAt > 0 && nowSec - result.signedAt > AppConfig.ETHA_SIGNED_STALE_SEC

    /** Pure: the request the core gets — for the phone's own network, or with [proxy] for the way through the tunnel. */
    fun buildRequest(url: String, stored: List<String>, userAgent: String?, proxy: TunnelProxy? = null, headers: Map<String, String> = emptyMap()): EchFetchRequest = EchFetchRequest(
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
        headers = headers,
        signKeys = AppConfig.ETHA_SIGN_KEYS,
    )

    /** Pure: the last resort's request — the link fetched by name without ECH, through the core like every other way (2.1), so
     *  the certificate and the box's signature are checked the same; no address given: the host's name is dialled. */
    fun buildPlainRequest(url: String, userAgent: String?, headers: Map<String, String> = emptyMap()): EchFetchRequest = EchFetchRequest(
        url = url,
        userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: AppConfig.ETHA_USER_AGENT,
        timeoutMs = TIMEOUT_MS,
        headers = headers,
        plain = true,
        signKeys = AppConfig.ETHA_SIGN_KEYS,
    )

    /** Pure: of (transport, address) pairs, the addresses of the lines that dial Cloudflare — never a relay's (since 2026-10-24 the first lines of a subscription). */
    fun addressesOf(lines: List<Pair<String?, String?>>): List<String> =
        lines.filter { it.first?.trim()?.lowercase() in CLOUDFLARE_TRANSPORTS }.mapNotNull { it.second?.trim()?.takeIf { s -> s.isNotEmpty() } }

    /** Pure: the stored lines' IPv4 addresses in the order given (the selected line's first), no repeats, at most [MAX_STORED_ADDRESSES]. */
    fun candidateAddresses(stored: List<String>): List<String> =
        stored.map { it.trim() }.filter { isIpv4(it) }.distinct().take(MAX_STORED_ADDRESSES)

    /** Pure: a 2xx answer that came with ECH accepted → its body and headers; anything else → null. */
    fun toResponse(result: EchFetchResult?): UrlContentResponse? {
        if (result == null || !result.error.isNullOrEmpty() || !result.echAccepted || !trusted(result)) return null
        val body = result.body.orEmpty()
        if (result.status !in 200..299 || body.isEmpty()) return null
        return UrlContentResponse(body, result.headers.orEmpty())
    }

    /** Pure: why a fetch gave nothing, for the log — never a body, never a token. */
    fun describe(result: EchFetchResult?): String = when {
        result == null -> "no result from the core"
        !result.error.isNullOrEmpty() -> result.error.orEmpty()
        !result.echAccepted && result.keySource != "plain" -> "ECH not accepted"
        !trusted(result) -> "status ${result.status}, signature ${result.signed ?: "unchecked"}"
        result.body.isNullOrEmpty() -> "status ${result.status}, empty body"
        else -> "status ${result.status}"
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    /** The Cloudflare lines of this subscription dial clean addresses: those, the selected line's first (the relay lines are left out). */
    fun storedAddresses(subscriptionId: String?): List<String> {
        if (subscriptionId.isNullOrBlank()) return emptyList()
        val guids = MmkvManager.decodeServerList(subscriptionId)
        val selected = MmkvManager.getSelectServer()
        val ordered = if (selected != null && guids.remove(selected)) listOf(selected) + guids else guids
        return addressesOf(ordered.mapNotNull { MmkvManager.decodeServerConfig(it) }.map { it.network to it.server })
    }
}
