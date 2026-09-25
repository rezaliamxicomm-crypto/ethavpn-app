package com.v2ray.ang.ui

import com.v2ray.ang.handler.AutoSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPickerTest {
    @Test
    fun autoFirstThenFastestFirstWithThePingLeading() {
        val rows = ServerPicker.rows(
            listOf(AutoSelect.Candidate("a", 120, 0), AutoSelect.Candidate("b", 0, 1), AutoSelect.Candidate("c", -1, 2), AutoSelect.Candidate("d", 80, 3)),
            nameOf = { mapOf("a" to "EthaVPN-XHTTP-fra-CleanIP1-443", "b" to "EthaVPN-WS-fra-CleanIP2-443", "c" to "EthaVPN-XHTTP-fra-Domain-2053", "d" to "Custom line")[it]!! },
            auto = "Auto (fastest)", untested = "not tested", failed = "failed"
        )
        assertEquals(5, rows.size)
        assertNull(rows[0].guid)
        assertEquals("Auto (fastest)", rows[0].text)
        assertEquals(listOf("d", "a", "b", "c"), rows.drop(1).map { it.guid })
        assertEquals("80 ms  ·  Custom line", rows[1].text)
        assertEquals("120 ms  ·  CleanIP1 · XHTTP/443", rows[2].text)
        assertEquals("not tested  ·  CleanIP2 · WS/443", rows[3].text)
        assertEquals("failed  ·  Domain · XHTTP/2053", rows[4].text)
    }

    @Test
    fun displayNameShortensTheServiceNamesOnly() {
        assertEquals("CleanIP3 · XHTTP/8443", ServerPicker.displayName("EthaVPN-XHTTP-fra-CleanIP3-8443"))
        assertEquals("Domain · WS/443", ServerPicker.displayName("EthaVPN-WS-fra-Domain-443"))
        assertEquals("My own server", ServerPicker.displayName("My own server"))
    }
}
