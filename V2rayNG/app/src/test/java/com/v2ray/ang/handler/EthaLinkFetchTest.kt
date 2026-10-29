package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class EthaLinkFetchTest {
    @Test
    fun theHopWaitsForEchUnlessItWonHereLastTime() {
        assertEquals(EthaLinkFetch.HEAD_START_MS, EthaLinkFetch.headStart(null))
        assertEquals(EthaLinkFetch.HEAD_START_MS, EthaLinkFetch.headStart("ech"))
        assertEquals(EthaLinkFetch.HEAD_START_MS, EthaLinkFetch.headStart("tunnel"))
        assertEquals(0L, EthaLinkFetch.headStart("hop"))
    }

    @Test
    fun theWinningWayIsKeptPerNetwork() {
        assertEquals(AppConfig.PREF_ETHA_FETCH_WAY + "cell:43235", EthaLinkFetch.wayKey("cell:43235"))
        assertEquals(AppConfig.PREF_ETHA_FETCH_WAY + "wifi", EthaLinkFetch.wayKey(" wifi "))
        assertEquals(AppConfig.PREF_ETHA_FETCH_WAY + "any", EthaLinkFetch.wayKey(""))
    }
}
