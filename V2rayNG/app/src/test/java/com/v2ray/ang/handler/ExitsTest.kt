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
    fun anotherCountryRestartsTheCoreAndTheSameOneDoesNot() {
        assertEquals(true, Exits.moved("ca", "us")); assertEquals(true, Exits.moved("", "ca")); assertEquals(true, Exits.moved("us", ""))   // a country, another, back to Auto
        assertEquals(false, Exits.moved("us", "us")); assertEquals(false, Exits.moved("", "")); assertEquals(false, Exits.moved(null, ""))   // the same word: nothing to restart for
        assertEquals(false, Exits.moved(" us", "us ")); assertEquals(true, Exits.moved(null, "ca"))
    }

    @Test
    fun theVersionComparison() {
        assertEquals(true, Exits.isBelow("2.1.3", "3.0.0")); assertEquals(false, Exits.isBelow("3.0.0", "3.0.0")); assertEquals(false, Exits.isBelow("3.0.1", "3.0.0"))
        assertEquals(true, Exits.isBelow("3.0", "3.0.1")); assertEquals(true, Exits.isBelow("v2.9.9-beta", "3.0.0"))
    }

    @Test
    fun theUpdateButtonReadsTheStoreThisBuildCameFrom() {
        val h = "android=3.1.0;ios=3.1.1;play=3.0.4"                                  // X-Etha-App-Latest: the direct file, the App Store, Google Play
        assertEquals("3.1.0", Exits.latestFor(h, play = false)); assertEquals("3.0.4", Exits.latestFor(h, play = true))
        assertNull(Exits.latestFor("ios=3.1.0", play = false)); assertNull(Exits.latestFor(null, play = true)); assertNull(Exits.latestFor("play=abc", play = true))
        assertEquals(true, Exits.updateOffered("3.0.4", h, null, play = false))        // the direct file is newer
        assertEquals(false, Exits.updateOffered("3.0.4", h, null, play = true))        // Google Play still carries this one: no button to a store with nothing new
        assertEquals(false, Exits.updateOffered("3.1.0", h, null, play = false)); assertEquals(false, Exits.updateOffered("3.2.0", h, null, play = false))
        assertEquals(false, Exits.updateOffered("3.0.4", null, null, play = true)); assertEquals(false, Exits.updateOffered("3.0.4", "", "", play = false))
        assertEquals(true, Exits.updateOffered("3.0.4", null, "android=3.1.0;ios=3.1.0", play = true))   // the service's lowest version is above this build: the button is there too
        assertEquals(false, Exits.updateOffered("3.1.0", "", "android=3.1.0;ios=3.1.0", play = false))
    }

    @Test
    fun theParserTakesOddItems() {
        val l = Exits.parse("de|Germany|DE|OK|x|203.0.113.3;us|United States|US|ok|90|203.0.113.21;")
        assertEquals(listOf("de", "us"), l.map { it.code }); assertEquals("ok", l[0].state); assertEquals(0, l[0].rtt); assertEquals(false, l[0].routed); assertEquals(true, l[1].routed)
    }
}
