package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineHealthTest {
    private val p = AutoPolicy()

    @Test
    fun aFailedLineWaitsLongerEachTimeUpToTheCap() {
        val h = LineHealth()
        assertTrue(h.due("a", 0))                                              // never seen: due
        h.failed("a", 1000, p)
        assertFalse(h.due("a", 1000 + 899)); assertTrue(h.due("a", 1000 + 900))      // 15 min
        h.failed("a", 1900, p)
        assertFalse(h.due("a", 1900 + 1799)); assertTrue(h.due("a", 1900 + 1800))    // 30 min
        h.failed("a", 3700, p); h.failed("a", 7300, p); h.failed("a", 14500, p)
        assertTrue(h.due("a", 14500 + 7200)); assertFalse(h.due("a", 14500 + 7199))  // capped at 2 h
        assertTrue(h.isDead("a"))
        h.ok("a", 180, 30000)
        assertFalse(h.isDead("a")); assertTrue(h.due("a", 30001)); assertEquals(180L, h.lastMs("a"))
        h.failed("a", 40000, p)
        assertTrue(h.due("a", 40000 + 900))                                    // the backoff starts over after a good probe
    }

    @Test
    fun theOrderIsKnownGoodByTimeThenUnseenThenDead() {
        val h = LineHealth()
        h.ok("r2", 190, 1); h.ok("r1", 210, 1); h.failed("x1", 1, p); h.ok("w1", 190, 1)
        assertEquals(listOf("r2", "w1", "r1", "x2", "x1"), h.bestFirst(listOf("r1", "r2", "x1", "x2", "w1")))   // equals keep the subscription's order
        assertEquals("r2", h.bestKnown(listOf("r1", "r2", "x1", "x2", "w1")))
        assertEquals("w1", h.bestKnown(listOf("r1", "r2", "x1", "x2", "w1"), except = "r2"))
        assertNull(LineHealth().bestKnown(listOf("a", "b")))                   // nothing known yet
    }

    @Test
    fun forgivenessAfterANetworkChange() {
        val h = LineHealth()
        h.failed("x1", 1, p); h.failed("x1", 2, p)
        assertFalse(h.due("x1", 100))
        h.forgive()
        assertTrue(h.due("x1", 100)); assertFalse(h.isDead("x1"))
    }

    @Test
    fun nothingAnswersWhenEveryLineFailedOrWaitsItsRetry() {
        val h = LineHealth()
        h.failed("x1", 1, p); h.failed("x2", 1, p)                                          // in backoff: not probed this session
        val results = mapOf("r1" to -1L, "r2" to -1L)                                       // probed and failed
        assertTrue(h.allOut(listOf("r1", "r2", "x1", "x2"), "r1") { results[it] ?: 0L })
        assertFalse(h.allOut(listOf("r1", "r2", "x1", "x2", "w1"), "r1") { results[it] ?: 0L })   // w1 never probed, not held failed: not out
        assertFalse(h.allOut(listOf("r1", "r2", "x1"), "r1") { mapOf("r1" to 200L, "r2" to -1L)[it] ?: 0L })   // the line in use answers
        assertFalse(LineHealth().allOut(emptyList(), "r1") { -1L })
    }

    @Test
    fun itSurvivesTheStore() {
        val h = LineHealth(); h.ok("REALITY-relay-FRA4-443", 100, 5); h.failed("XHTTP-api-CleanIP1-443", 6, p)
        val back = LineHealth.decode(h.encode())
        assertEquals(h.entries, back.entries)
        assertEquals(0, LineHealth.decode(null).entries.size)
        assertEquals(0, LineHealth.decode("garbage|x").entries.size)
    }
}
