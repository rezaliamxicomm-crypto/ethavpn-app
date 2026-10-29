package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPolicyTest {
    private val p = AutoPolicy()
    private fun c(guid: String, delay: Long, order: Int) = AutoSelect.Candidate(guid, delay, order)

    @Test
    fun theMarginIsAShareOfTheLineInUseWithAFloor() {
        assertTrue(p.shouldSwitch(currentMs = 400, bestMs = 330))      // 60 ms margin at 400: 330 is under 340
        assertFalse(p.shouldSwitch(currentMs = 400, bestMs = 345))
        assertTrue(p.shouldSwitch(currentMs = 100, bestMs = 70))       // the floor, 25 ms, applies under 167 ms
        assertFalse(p.shouldSwitch(currentMs = 100, bestMs = 80))
        assertFalse(p.shouldSwitch(currentMs = 200, bestMs = 200))
    }

    @Test
    fun aFailedLineInUseIsLeftAtOnceAndAnUnmeasuredOneWaits() {
        assertTrue(p.shouldSwitch(currentMs = -1, bestMs = 900))
        assertFalse(p.shouldSwitch(currentMs = 0, bestMs = 50))
        assertFalse(p.shouldSwitch(currentMs = -1, bestMs = -1))
        assertFalse(p.shouldSwitch(currentMs = 300, bestMs = 0))
    }

    @Test
    fun theTargetIsTheBestLineOnlyWhenItEarnsTheMove() {
        val lines = listOf(c("relay", 180, 0), c("clean1", 160, 1), c("clean2", -1, 2), c("ws", 0, 3))
        assertNull(p.target("relay", lines, pinned = false))            // 160 is not 27 (15 % of 180) under 180
        assertEquals("clean1", p.target("relay", listOf(c("relay", 180, 0), c("clean1", 150, 1)), pinned = false))   // 150 is
        assertEquals("clean1", p.target("clean2", lines, pinned = false))
        assertEquals("clean1", p.target(null, lines, pinned = false))
        assertEquals("clean1", p.target("gone", lines, pinned = false))  // a line in use that is not a candidate counts as failed
        assertNull(p.target("ws", lines, pinned = false))                // not measured yet: wait
        assertNull(p.target("clean2", lines, pinned = true))
        assertNull(p.target("relay", listOf(c("relay", -1, 0), c("x", -1, 1)), pinned = false))
        assertNull(p.target("clean1", lines, pinned = false))
    }

    @Test
    fun aStallIsBytesOutAndNoneBackOverTheWindow() {
        val t0 = 1_000_000L
        val s = listOf(Triple(t0, 1000L, 5000L), Triple(t0 + 4000, 4000L, 5000L), Triple(t0 + 8000, 9000L, 5000L))
        assertTrue(p.stalled(s, t0 + 8000))
        assertFalse(p.stalled(s, t0 + 7000))                                             // the window is not full yet
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L), Triple(t0 + 8000, 1500L, 5000L)), t0 + 8000))   // too little went out
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L), Triple(t0 + 8000, 9000L, 5100L)), t0 + 8000))   // something came back
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L)), t0 + 8000))
    }

    @Test
    fun aLineIsDueByItsLastOutcome() {
        val now = 10_000_000L
        assertTrue(p.due(now, now - 181_000, failed = false))
        assertFalse(p.due(now, now - 179_000, failed = false))
        assertFalse(p.due(now, now - 181_000, failed = true))
        assertTrue(p.due(now, now - 901_000, failed = true))
        assertTrue(p.due(now, 0, failed = false))
    }

    @Test
    fun theHeaderReplacesValuesWithinBoundsAndIgnoresTheRest() {
        val q = AutoPolicy.parse("round=120, failed=600;idle=300,margin=10,floor=40,stall=6,timeout=4,conc=16,fresh=900,colour=blue,round=x")
        assertEquals(AutoPolicy(120, 600, 300, 10, 40, 6, 4, 16, 900), q)
        assertEquals(AutoPolicy(), AutoPolicy.parse(null))
        assertEquals(AutoPolicy(), AutoPolicy.parse(" "))
        assertEquals(AutoPolicy(), AutoPolicy.parse("garbage"))
        val clamped = AutoPolicy.parse("round=5,failed=1,idle=1,margin=200,floor=-3,stall=1,timeout=0,conc=0,fresh=-1")
        assertEquals(AutoPolicy(30, 30, 60, 90, 0, 3, 2, 1, 0), clamped)
        assertEquals(30, AutoPolicy.parse("margin=30", base = q).marginPct)
        assertEquals(120, AutoPolicy.parse("margin=30", base = q).roundSec)
    }
}
