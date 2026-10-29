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
    fun theFingerprintIgnoresOrderAndIds() {
        val a = EthaReport.fingerprint(listOf(Triple("1.1.1.1", "443", "x"), Triple("2.2.2.2", "443", "y")))
        val b = EthaReport.fingerprint(listOf(Triple("2.2.2.2", "443", "y"), Triple("1.1.1.1", "443", "x")))
        assertEquals(a, b)
        assertTrue(a != EthaReport.fingerprint(listOf(Triple("1.1.1.1", "443", "x"), Triple("3.3.3.3", "443", "y"))))
    }
}
