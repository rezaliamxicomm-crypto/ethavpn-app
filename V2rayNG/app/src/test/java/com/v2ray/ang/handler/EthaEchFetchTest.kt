package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EchFetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EthaEchFetchTest {

    @Test
    fun candidateAddressesKeepOrderDropRepeatsAndNonIpv4() {
        val stored = listOf("104.21.67.176", " 172.67.179.3 ", "104.21.67.176", "api.xicomm.net", "2606:4700::1", "", "1.2.3", "300.1.1.1")
        assertEquals(listOf("104.21.67.176", "172.67.179.3"), EthaEchFetch.candidateAddresses(stored))
    }

    @Test
    fun candidateAddressesAreCapped() {
        val stored = (1..10).map { "104.21.67.$it" }
        val picked = EthaEchFetch.candidateAddresses(stored)
        assertEquals(EthaEchFetch.MAX_STORED_ADDRESSES, picked.size)
        assertEquals(stored.take(EthaEchFetch.MAX_STORED_ADDRESSES), picked)   // the first ones: the selected line leads
    }

    @Test
    fun requestCarriesTheKeyTheResolversAndTheAddresses() {
        val req = EthaEchFetch.buildRequest("https://fra.skyrayconfig.org/sub/0123456789abcdef", listOf("104.21.67.176"), null)
        assertEquals("https://fra.skyrayconfig.org/sub/0123456789abcdef", req.url)
        assertEquals(listOf("104.21.67.176"), req.addresses)
        assertEquals(AppConfig.ETHA_ECH_ADDRESSES, req.pinnedAddresses)
        assertEquals(AppConfig.ETHA_ECH_RESOLVERS, req.resolvers)
        assertEquals(AppConfig.ETHA_ECH_LOOKUP_NAME, req.lookupName)
        assertEquals(AppConfig.ETHA_ECH_PINNED_KEY, req.pinnedKey)
        assertEquals(AppConfig.ETHA_USER_AGENT, req.userAgent)             // no user agent given: the app's own
        assertTrue(req.timeoutMs > 0)
        assertEquals("", req.proxy)                                        // the phone's own network: no proxy
        assertEquals("Custom/1.0", EthaEchFetch.buildRequest("https://x/sub/y", emptyList(), " Custom/1.0 ").userAgent)
    }

    @Test
    fun theWayThroughTheTunnelNamesItsProxy() {
        val direct = EthaEchFetch.buildRequest("https://fra.skyrayconfig.org/sub/0123456789abcdef", emptyList(), null)
        val tunnel = EthaEchFetch.buildRequest("https://fra.skyrayconfig.org/sub/0123456789abcdef", emptyList(), null, EthaEchFetch.TunnelProxy("127.0.0.1:10808"))
        assertEquals("127.0.0.1:10808", tunnel.proxy)
        assertEquals("", tunnel.proxyUser)                                   // no account set: none sent
        assertEquals("", tunnel.proxyPassword)
        assertTrue(tunnel.timeoutMs in 1 until direct.timeoutMs)             // the second try gets less time than the first
        assertEquals(direct.pinnedKey, tunnel.pinnedKey)                     // the same key and addresses either way
        assertEquals(direct.pinnedAddresses, tunnel.pinnedAddresses)
        val locked = EthaEchFetch.buildRequest("https://x/sub/y", emptyList(), null, EthaEchFetch.TunnelProxy("127.0.0.1:20000", "u", "p"))
        assertEquals("u", locked.proxyUser)
        assertEquals("p", locked.proxyPassword)
    }

    @Test
    fun theRequestIsTheJsonTheCoreReads() {
        // the core reads these keys by name (echfetch.go's json tags): a renamed field would arrive empty
        val json = com.v2ray.ang.util.JsonUtil.toJson(
            EthaEchFetch.buildRequest("https://fra.skyrayconfig.org/sub/0123456789abcdef", listOf("104.21.67.176"), null, EthaEchFetch.TunnelProxy("127.0.0.1:10808", "u", "p"))
        )
        for (key in listOf("url", "addresses", "pinnedAddresses", "resolvers", "lookupName", "pinnedKey", "userAgent", "timeoutMs", "proxy", "proxyUser", "proxyPassword")) {
            assertTrue("missing key $key in $json", json.contains("\"$key\":"))
        }
        val back = com.v2ray.ang.util.JsonUtil.fromJsonSafe(
            "{\"status\":200,\"headers\":{\"profile-update-interval\":\"3\"},\"body\":\"dmxlc3M6Ly8=\",\"echAccepted\":true,\"address\":\"104.21.67.176\",\"keySource\":\"retry\",\"error\":\"\"}",
            EchFetchResult::class.java
        )
        assertEquals(200, back!!.status)
        assertEquals("retry", back.keySource)
        assertEquals("3", back.headers!!["profile-update-interval"])
        assertNotNull(EthaEchFetch.toResponse(back))
    }

    @Test
    fun thePinnedKeyIsAnEchConfigList() {
        val key = Base64.getDecoder().decode(AppConfig.ETHA_ECH_PINNED_KEY)
        // ECHConfigList: a two-byte length, then one ECHConfig of version 0xfe0d
        assertEquals(key.size - 2, ((key[0].toInt() and 0xFF) shl 8) or (key[1].toInt() and 0xFF))
        assertEquals(0xFE, key[2].toInt() and 0xFF)
        assertEquals(0x0D, key[3].toInt() and 0xFF)
        assertTrue(AppConfig.ETHA_ECH_RESOLVERS.isNotEmpty())
        // the pinned list: IPv4 addresses, then one name for the phone's resolver (a network without IPv4 reaches only a name)
        assertTrue(AppConfig.ETHA_ECH_ADDRESSES.dropLast(1).all { EthaEchFetch.candidateAddresses(listOf(it)) == listOf(it) })
        assertEquals(AppConfig.ETHA_ECH_LOOKUP_NAME, AppConfig.ETHA_ECH_ADDRESSES.last())
        assertTrue(AppConfig.ETHA_ECH_LOOKUP_NAME != AppConfig.ETHA_SUB_HOST)   // the link host is never put into a DNS query
    }

    @Test
    fun aGoodAnswerBecomesTheResponse() {
        val result = EchFetchResult(
            status = 200, body = "dmxlc3M6Ly8=", echAccepted = true, address = "104.21.67.176", keySource = "dns:1.1.1.1",
            headers = mapOf("profile-update-interval" to "3", "subscription-userinfo" to "upload=0; download=5; total=0; expire=0"),
        )
        val response = EthaEchFetch.toResponse(result)
        assertNotNull(response)
        assertEquals("dmxlc3M6Ly8=", response!!.body)
        assertEquals("3", response.headers["profile-update-interval"])
        assertEquals("status 200", EthaEchFetch.describe(result))
    }

    @Test
    fun anythingShortOfAnAcceptedTwoHundredIsNothing() {
        assertNull(EthaEchFetch.toResponse(null))
        assertNull(EthaEchFetch.toResponse(EchFetchResult(error = "ech: no key")))
        assertNull(EthaEchFetch.toResponse(EchFetchResult(status = 200, body = "x", echAccepted = false)))
        assertNull(EthaEchFetch.toResponse(EchFetchResult(status = 403, body = "{\"detail\":\"expired\"}", echAccepted = true)))
        assertNull(EthaEchFetch.toResponse(EchFetchResult(status = 200, body = "", echAccepted = true)))
        assertEquals("no result from the core", EthaEchFetch.describe(null))
        assertEquals("ech: no key", EthaEchFetch.describe(EchFetchResult(error = "ech: no key")))
        assertEquals("ECH not accepted", EthaEchFetch.describe(EchFetchResult(status = 200, body = "x")))
        assertEquals("status 403", EthaEchFetch.describe(EchFetchResult(status = 403, body = "x", echAccepted = true)))
        assertEquals("status 200, empty body", EthaEchFetch.describe(EchFetchResult(status = 200, echAccepted = true)))
    }

    @Test
    fun onlyAFetchThatReachedNobodyGoesTheSecondWay() {
        // the server answered — expired, unknown, or the subscription itself: the way through the tunnel would hear the same
        assertTrue(EthaEchFetch.answered(EchFetchResult(status = 200, body = "dmxlc3M6Ly8=", echAccepted = true)))
        assertTrue(EthaEchFetch.answered(EchFetchResult(status = 403, body = "{\"detail\":\"expired\"}", echAccepted = true)))
        assertTrue(EthaEchFetch.answered(EchFetchResult(status = 404, echAccepted = true)))
        // nobody answered: no result, an error, no ECH
        assertTrue(!EthaEchFetch.answered(null))
        assertTrue(!EthaEchFetch.answered(EchFetchResult(error = "ech: dial tcp 104.21.67.176:443: i/o timeout")))
        assertTrue(!EthaEchFetch.answered(EchFetchResult(status = 200, body = "x", echAccepted = false)))
        assertTrue(!EthaEchFetch.answered(EchFetchResult(echAccepted = true)))
    }
}
