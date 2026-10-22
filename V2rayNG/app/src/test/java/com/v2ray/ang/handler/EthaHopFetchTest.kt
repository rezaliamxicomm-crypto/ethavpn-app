package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EchFetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EthaHopFetchTest {

    @Test
    fun parsesTheHeaderInOrderAndSkipsGarbage() {
        val hops = EthaHopFetch.parseHops(" www.harlow-systems.org=185.83.210.148 , WWW.Spare.Example=10.0.0.1,bad,=1.2.3.4,host=300.1.1.1,-x.y=1.2.3.4, www.harlow-systems.org=185.83.210.148")
        assertEquals(listOf(EthaHopFetch.Hop("www.harlow-systems.org", "185.83.210.148"), EthaHopFetch.Hop("www.spare.example", "10.0.0.1")), hops)
        assertEquals("www.harlow-systems.org=185.83.210.148,www.spare.example=10.0.0.1", EthaHopFetch.format(hops))
        assertEquals(emptyList<EthaHopFetch.Hop>(), EthaHopFetch.parseHops(null))
        assertEquals(emptyList<EthaHopFetch.Hop>(), EthaHopFetch.parseHops(" "))
        assertEquals(emptyList<EthaHopFetch.Hop>(), EthaHopFetch.parseHops("garbage"))
    }

    @Test
    fun theBuiltInHopsAreValidAndRoundTrip() {
        val built = AppConfig.ETHA_LINK_HOPS.map { EthaHopFetch.Hop(it.first, it.second) }
        assertTrue(built.isNotEmpty())
        assertEquals(built, EthaHopFetch.parseHops(EthaHopFetch.format(built)))
        assertTrue(built.all { it.host in AppConfig.ETHA_HOP_SUB_HOSTS })   // a link on the hop's name is ours
    }

    @Test
    fun requestIsPlainAtTheHopsAddressWithNoProxy() {
        val hop = EthaHopFetch.Hop("www.harlow-systems.org", "185.83.210.148")
        val req = EthaHopFetch.buildRequest("0123456789abcdef", hop, null)
        assertEquals("https://www.harlow-systems.org/sub/0123456789abcdef", req.url)
        assertEquals(listOf("185.83.210.148"), req.addresses)
        assertTrue(req.plain)
        assertEquals("", req.proxy)                                        // the phone's own network
        assertTrue(req.pinnedAddresses.isEmpty()); assertTrue(req.resolvers.isEmpty()); assertEquals("", req.pinnedKey)
        assertEquals(AppConfig.ETHA_USER_AGENT, req.userAgent)
        assertEquals(EthaHopFetch.TIMEOUT_MS, req.timeoutMs)
        assertEquals("Custom/1.0", EthaHopFetch.buildRequest("t", hop, " Custom/1.0 ").userAgent)
        assertFalse(EthaEchFetch.buildRequest("https://x/sub/y", emptyList(), null).plain)   // the ECH ways stay ECH
    }

    @Test
    fun answeredIsAnyStatusAndAResponseIsA2xxBody() {
        assertTrue(EthaHopFetch.answered(EchFetchResult(status = 403, keySource = "plain")))
        assertTrue(EthaHopFetch.answered(EchFetchResult(status = 200, body = "x", keySource = "plain")))
        assertFalse(EthaHopFetch.answered(EchFetchResult(status = 0)))
        assertFalse(EthaHopFetch.answered(EchFetchResult(status = 200, body = "x", error = "plain: dial")))
        assertFalse(EthaHopFetch.answered(null))
        assertNotNull(EthaHopFetch.toResponse(EchFetchResult(status = 200, body = "x", headers = mapOf("a" to "b"))))
        assertEquals(mapOf("a" to "b"), EthaHopFetch.toResponse(EchFetchResult(status = 200, body = "x", headers = mapOf("a" to "b")))?.headers)
        assertNull(EthaHopFetch.toResponse(EchFetchResult(status = 403, body = "x")))
        assertNull(EthaHopFetch.toResponse(EchFetchResult(status = 200, body = "")))
        assertEquals("status 403", EthaEchFetch.describe(EchFetchResult(status = 403, keySource = "plain")))   // not "ECH not accepted"
    }
}
