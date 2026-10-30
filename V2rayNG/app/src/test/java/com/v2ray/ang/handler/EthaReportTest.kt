package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EthaReportTest {
    @Test
    fun aLabelIsTheNameWithoutOurPrefix() {
        assertEquals("REALITY-relay-FRA9-443", EthaReport.label("EthaVPN-REALITY-relay-FRA9-443"))
        assertEquals("XHTTP-api-CleanIP1-443", EthaReport.label(" EthaVPN-XHTTP-api-CleanIP1-443 "))
        assertNull(EthaReport.label("Some other provider"))
        assertNull(EthaReport.label("EthaVPN-with spaces-443"))
        assertNull(EthaReport.label(null))
    }

    @Test
    fun theHeaderCarriesEveryMeasuredLineOfOurs() {
        val h = EthaReport.build(1_761_770_000_123L, "manual", "EthaVPN-REALITY-relay-FRA9-443", true, listOf(
            "EthaVPN-REALITY-relay-FRA9-443" to 298L,
            "EthaVPN-XHTTP-api-CleanIP1-443" to -1L,
            "EthaVPN-WS-ECH3-CleanIP2-443" to 0L,          // not measured: left out
            "Other provider line" to 120L,                  // not ours: left out
        ))
        assertEquals("v=1;t=1761770000;trig=manual;use=REALITY-relay-FRA9-443;sw=1;l=REALITY-relay-FRA9-443:298,XHTTP-api-CleanIP1-443:-1", h)
    }

    @Test
    fun noLineOfOursMeasuredMeansNoHeader() {
        assertNull(EthaReport.build(1L, "auto", null, false, listOf("Other" to 5L, "EthaVPN-X-y-z-1" to 0L)))
    }

    @Test
    fun theHeaderStaysSmall() {
        val many = (1..200).map { "EthaVPN-XHTTP-api-CleanIP$it-443" to it.toLong() }
        val h = EthaReport.build(0L, "auto", null, false, many)!!
        assertEquals(EthaReport.MAX_LINES, h.substringAfter("l=").split(',').size)
        assertTrue(h.length < 4096)
    }

    @Test
    fun theFingerprintIgnoresOrderAndIdsButNotTheUserId() {
        fun k(server: String, remarks: String, user: String = "u1") = EthaReport.LineKey(server, "443", remarks, user)
        val a = EthaReport.fingerprint(listOf(k("1.1.1.1", "x"), k("2.2.2.2", "y")))
        val b = EthaReport.fingerprint(listOf(k("2.2.2.2", "y"), k("1.1.1.1", "x")))
        assertEquals(a, b)
        assertTrue(a != EthaReport.fingerprint(listOf(k("1.1.1.1", "x"), k("3.3.3.3", "y"))))
        assertTrue(a != EthaReport.fingerprint(listOf(k("1.1.1.1", "x", "u2"), k("2.2.2.2", "y", "u2"))))   // a rotated user id must reach the running core
        assertEquals(EthaReport.fingerprint(listOf(k("1.1.1.1", "x"))), EthaReport.fingerprint(listOf(k("1.1.1.1", "x"))))   // nothing of the ECH configuration is in the key
    }
}
