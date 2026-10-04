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
        assertEquals(AppConfig.ETHA_ECH_PINNED_KEY, req.pinnedKey)
        assertEquals(AppConfig.ETHA_USER_AGENT, req.userAgent)             // no user agent given: the app's own
        assertTrue(req.timeoutMs > 0)
        assertEquals("Custom/1.0", EthaEchFetch.buildRequest("https://x/sub/y", emptyList(), " Custom/1.0 ").userAgent)
    }

    @Test
    fun thePinnedKeyIsAnEchConfigList() {
        val key = Base64.getDecoder().decode(AppConfig.ETHA_ECH_PINNED_KEY)
        // ECHConfigList: a two-byte length, then one ECHConfig of version 0xfe0d
        assertEquals(key.size - 2, ((key[0].toInt() and 0xFF) shl 8) or (key[1].toInt() and 0xFF))
        assertEquals(0xFE, key[2].toInt() and 0xFF)
        assertEquals(0x0D, key[3].toInt() and 0xFF)
        assertTrue(AppConfig.ETHA_ECH_RESOLVERS.isNotEmpty())
        assertTrue(AppConfig.ETHA_ECH_ADDRESSES.all { EthaEchFetch.candidateAddresses(listOf(it)) == listOf(it) })
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
}
