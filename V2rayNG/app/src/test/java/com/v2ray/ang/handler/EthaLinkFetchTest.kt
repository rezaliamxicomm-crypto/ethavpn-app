package com.v2ray.ang.handler

import com.v2ray.ang.dto.EchFetchResult
import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun theReplayRulesOlderIsNeverTakenStaleIsTheLastResort() {
        val now = 1_800_000_000L
        assertEquals("fresh", EthaLinkFetch.replayVerdict(signedAt = now - 10, held = now - 100, nowSec = now))
        assertEquals("fresh", EthaLinkFetch.replayVerdict(signedAt = now - 10, held = 0, nowSec = now))               // nothing held yet
        assertEquals("fresh", EthaLinkFetch.replayVerdict(signedAt = 0, held = now, nowSec = now))                     // unsigned (a 404): nothing to compare
        assertEquals("older", EthaLinkFetch.replayVerdict(signedAt = now - 200, held = now - 100, nowSec = now))      // before the one held
        assertEquals("stale", EthaLinkFetch.replayVerdict(signedAt = now - 3601, held = 0, nowSec = now))             // an hour old
        assertEquals("fresh", EthaLinkFetch.replayVerdict(signedAt = now - 3600, held = 0, nowSec = now))
        assertEquals("older", EthaLinkFetch.replayVerdict(signedAt = now - 7200, held = now - 100, nowSec = now))     // older beats stale
        assertEquals("refused", EthaLinkFetch.replayVerdict(signedAt = now - 172_801, held = 0, nowSec = now))        // two days: never taken
        assertEquals("stale", EthaLinkFetch.replayVerdict(signedAt = now - 172_800, held = 0, nowSec = now))
        assertEquals(now + 86_400, EthaEchFetch.heldSignedAt(now + 999_999, nowSec = now))                            // a box clock ahead is clamped
        assertEquals(now - 5, EthaEchFetch.heldSignedAt(now - 5, nowSec = now))
        assertTrue(EthaEchFetch.signedWord(EchFetchResult(status = 403, echAccepted = true, signed = "ok")))
        assertFalse(EthaEchFetch.signedWord(EchFetchResult(status = 404, echAccepted = true)))                        // a 404 is no word of the box's: kept aside in the race
        assertFalse(EthaEchFetch.signedWord(EchFetchResult(status = 200, body = "x", echAccepted = true, signed = "bad")))
    }
}
