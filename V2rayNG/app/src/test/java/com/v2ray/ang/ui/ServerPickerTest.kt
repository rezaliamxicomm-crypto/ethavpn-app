package com.v2ray.ang.ui

import com.v2ray.ang.handler.AutoSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPickerTest {
    @Test
    fun autoFirstThenBodyOrderWithPing() {
        val rows = ServerPicker.rows(
            listOf(AutoSelect.Candidate("a", 120, 0), AutoSelect.Candidate("b", 0, 1), AutoSelect.Candidate("c", -1, 2)),
            nameOf = { mapOf("a" to "CleanIP1", "b" to "CleanIP2", "c" to "Domain")[it]!! },
            auto = "Auto (fastest)", untested = "not tested", failed = "failed"
        )
        assertEquals(4, rows.size)
        assertNull(rows[0].guid)
        assertEquals("Auto (fastest)", rows[0].text)
        assertEquals("a", rows[1].guid)
        assertEquals("CleanIP1  ·  120 ms", rows[1].text)
        assertEquals("CleanIP2  ·  not tested", rows[2].text)
        assertEquals("Domain  ·  failed", rows[3].text)
    }
}
