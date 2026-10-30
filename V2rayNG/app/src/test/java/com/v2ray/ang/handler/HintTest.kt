package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintTest {
    @Test
    fun theHeaderIsParsedAndArrangesTheLines() {
        val h = Hint.parse("v=1;o=REALITY-relay-FRA4-443,REALITY-relay-FRA1-443,WS-www-CleanIP2-443;d=XHTTP-api-CleanIP1-443,XHTTP-api-CleanIP1-ECH3-443;out=0;n=12")!!
        assertEquals(listOf("REALITY-relay-FRA4-443", "REALITY-relay-FRA1-443", "WS-www-CleanIP2-443"), h.order)
        assertEquals(setOf("XHTTP-api-CleanIP1-443", "XHTTP-api-CleanIP1-ECH3-443"), h.dead)
        assertFalse(h.outage); assertEquals(12, h.phones)
        val mine = listOf("REALITY-relay-FRA1-443", "REALITY-relay-FRA4-443", "XHTTP-api-CleanIP1-443", "XHTTP-api-CleanIP1-ECH3-443", "WS-www-CleanIP2-443", "WS-www-CleanIP9-443")
        assertEquals(listOf("REALITY-relay-FRA4-443", "REALITY-relay-FRA1-443", "WS-www-CleanIP2-443", "WS-www-CleanIP9-443", "XHTTP-api-CleanIP1-443", "XHTTP-api-CleanIP1-ECH3-443"), h.arrange(mine))
        assertTrue(Hint.parse("v=1;o=;d=;out=1;n=7")!!.outage)
    }

    @Test
    fun whatIsNotAHint() {
        assertNull(Hint.parse(null)); assertNull(Hint.parse("")); assertNull(Hint.parse("v=2;o=a"))
        assertNull(Hint.parse("v=1;o=;d=;out=0;n=0"))                            // nothing in it
        assertNull(Hint.parse("v=1;o=" + "a,".repeat(3000)))
        assertEquals(listOf("good-1-x-443"), Hint.parse("v=1;o=good-1-x-443,bad label,good-1-x-443,toolong" + "z".repeat(60))!!.order)
    }
}
