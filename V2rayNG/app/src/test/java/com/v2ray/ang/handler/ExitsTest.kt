package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitsTest {
    @Test
    fun theHeaderParsesInOrderAndSkipsBadItems() {
        val l = Exits.parse("de|Germany|DE|ok|0|203.0.113.3;us|United States|US|down|90|203.0.113.21;X|bad;tr|Turkey|TR|off|40")
        assertEquals(listOf("de", "us", "tr"), l.map { it.code })
        assertEquals("United States", l[1].name); assertEquals("US", l[1].country); assertEquals(90, l[1].rtt); assertEquals("203.0.113.21", l[1].probe)
        assertEquals(false, l[0].routed); assertEquals(true, l[1].routed); assertEquals(false, l[1].ok); assertEquals("", l[2].probe)
        assertEquals(emptyList<Exits.Exit>(), Exits.parse("")); assertEquals(emptyList<Exits.Exit>(), Exits.parse(null))
    }

    @Test
    fun flags() {
        assertEquals("🇺🇸", Exits.flag("US"))
        assertEquals("🇩🇪", Exits.flag("de"))
        assertEquals("🌐", Exits.flag("")); assertEquals("🌐", Exits.flag("USA"))
    }

    @Test
    fun theLowestVersionOfThisPlatform() {
        assertEquals("3.0.0", Exits.minVersion("android=3.0.0;ios=3.1.0"))
        assertEquals("3.1.0", Exits.minVersion("android=3.0.0;ios=3.1.0", "ios"))
        assertNull(Exits.minVersion("ios=3.1.0")); assertNull(Exits.minVersion("android=3")); assertNull(Exits.minVersion(null))
    }

    @Test
    fun theDeviceLimitsRefusal() {
        assertEquals(2 to 3, Exits.devicesRefusal("{\"detail\":\"devices\",\"limit\":2,\"active\":3}"))
        assertEquals(0 to 0, Exits.devicesRefusal("{\"detail\":\"devices\"}"))
        assertNull(Exits.devicesRefusal("{\"detail\":\"expired\"}")); assertNull(Exits.devicesRefusal(null)); assertNull(Exits.devicesRefusal(""))
    }

    @Test
    fun pairs() {
        assertEquals(mapOf("de" to "203.0.113.3", "us" to "203.0.113.21"), Exits.pairs("de=203.0.113.3, us=203.0.113.21,,bad"))
        assertEquals(emptyMap<String, String>(), Exits.pairs(null))
    }

    @Test
    fun aPendingChoiceIsSettledByTheConfirmationOrByTheFetchThatCarriedIt() {
        assertEquals(true, Exits.settled("auto", "", null)); assertEquals(false, Exits.settled("auto", "us", null))
        assertEquals(true, Exits.settled("us", "us", null)); assertEquals(false, Exits.settled("us", "", null))
        assertEquals(true, Exits.settled("us", "", "us")); assertEquals(false, Exits.settled("us", "", "tr"))
    }

    @Test
    fun theVersionComparison() {
        assertEquals(true, Exits.isBelow("2.1.3", "3.0.0")); assertEquals(false, Exits.isBelow("3.0.0", "3.0.0")); assertEquals(false, Exits.isBelow("3.0.1", "3.0.0"))
        assertEquals(true, Exits.isBelow("3.0", "3.0.1")); assertEquals(true, Exits.isBelow("v2.9.9-beta", "3.0.0"))
    }

    @Test
    fun theParserTakesOddItems() {
        val l = Exits.parse("de|Germany|DE|OK|x|203.0.113.3;us|United States|US|ok|90|203.0.113.21;")
        assertEquals(listOf("de", "us"), l.map { it.code }); assertEquals("ok", l[0].state); assertEquals(0, l[0].rtt); assertEquals(false, l[0].routed); assertEquals(true, l[1].routed)
    }
}
