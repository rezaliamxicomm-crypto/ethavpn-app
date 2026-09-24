package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSelectTest {
    private fun c(guid: String, delay: Long, order: Int) = AutoSelect.Candidate(guid, delay, order)

    @Test
    fun lowestPositiveDelayWins() {
        val best = AutoSelect.best(listOf(c("a", 400, 0), c("b", 120, 1), c("c", 90, 2)))
        assertEquals("c", best)
    }

    @Test
    fun withinTheTieTheEarlierLineWins() {
        // clean IPs come first in the body: 95 ms on line 0 beats 80 ms on line 3 (within 30 ms)
        val best = AutoSelect.best(listOf(c("clean1", 95, 0), c("clean2", 300, 1), c("domain", 80, 3)))
        assertEquals("clean1", best)
        // but a clearly faster later line wins
        assertEquals("domain", AutoSelect.best(listOf(c("clean1", 200, 0), c("domain", 80, 3))))
    }

    @Test
    fun untestedAndFailedLinesAreNeverChosen() {
        assertNull(AutoSelect.best(listOf(c("a", 0, 0), c("b", -1, 1))))
        assertEquals("b", AutoSelect.best(listOf(c("a", 0, 0), c("b", 500, 1), c("c", -1, 2))))
        assertNull(AutoSelect.best(emptyList()))
    }

    @Test
    fun excludedLinesAreSkipped() {
        val list = listOf(c("a", 50, 0), c("b", 60, 1), c("c", 70, 2))
        assertEquals("b", AutoSelect.best(list, exclude = setOf("a")))
        assertEquals("c", AutoSelect.best(list, exclude = setOf("a", "b")))
        assertNull(AutoSelect.best(list, exclude = setOf("a", "b", "c")))
    }
}
