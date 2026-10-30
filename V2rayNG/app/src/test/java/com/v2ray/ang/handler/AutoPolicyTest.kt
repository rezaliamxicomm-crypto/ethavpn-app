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
        assertFalse(p.stalled(s, t0 + 3000))                                             // the window is not full yet
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L), Triple(t0 + 8000, 1500L, 5000L)), t0 + 8000))   // too little went out
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L), Triple(t0 + 8000, 9000L, 5100L)), t0 + 8000))   // something came back
        assertFalse(p.stalled(listOf(Triple(t0, 1000L, 5000L)), t0 + 8000))
    }

    @Test
    fun theHeaderReplacesValuesWithinBoundsAndIgnoresTheRest() {
        val q = AutoPolicy.parse("round=120, failed=600;idle=300,margin=10,floor=40,stall=6,timeout=4,conc=16,fresh=900,colour=blue,round=x")
        assertEquals(AutoPolicy(roundSec = 120, failedRetrySec = 600, idleSec = 300, marginPct = 10, floorMs = 40, stallSec = 6, probeTimeoutSec = 4, concurrency = 16, freshSec = 900), q)
        assertEquals(AutoPolicy(), AutoPolicy.parse(null))
        assertEquals(AutoPolicy(), AutoPolicy.parse(" "))
        assertEquals(AutoPolicy(), AutoPolicy.parse("garbage"))
        val clamped = AutoPolicy.parse("round=5,failed=1,idle=1,margin=200,floor=-3,stall=1,timeout=0,conc=0,fresh=-1")
        assertEquals(AutoPolicy(roundSec = 30, failedRetrySec = 30, idleSec = 60, marginPct = 90, floorMs = 0, stallSec = 3, probeTimeoutSec = 2, concurrency = 1, freshSec = 0), clamped)
        assertEquals(30, AutoPolicy.parse("margin=30", base = q).marginPct)
        assertEquals(120, AutoPolicy.parse("margin=30", base = q).roundSec)
    }

    @Test
    fun theSearchingCadenceComesFromTheHeaderToo() {
        val d = AutoPolicy()
        assertEquals(60, d.searchSec)
        assertEquals(300, d.searchFetchSec)
        val q = AutoPolicy.parse("search=30,sfetch=120")
        assertEquals(30, q.searchSec)
        assertEquals(120, q.searchFetchSec)
        assertEquals(30_000L, q.searchMs)
        val clamped = AutoPolicy.parse("search=1,sfetch=99999")
        assertEquals(15, clamped.searchSec)
        assertEquals(3600, clamped.searchFetchSec)
    }

    @Test
    fun theTrickleKnobsComeFromTheHeaderToo() {
        val d = AutoPolicy()
        assertEquals(4, d.stallSec); assertEquals(1, d.firstPaceSec); assertEquals(30, d.currentSec); assertEquals(10, d.jitterPct)
        assertEquals(90, d.dwellSec); assertEquals(2, d.persistPasses); assertEquals(7200, d.retryCapSec); assertEquals(20, d.warmSec); assertEquals(1500, d.failoverMs)
        val q = AutoPolicy.parse("pace=5,cur=60,jitter=50,dwell=300,persist=3,cap=600,warm=60,fover=3000")
        assertEquals(5, q.firstPaceSec); assertEquals(60, q.currentSec); assertEquals(50, q.jitterPct)
        assertEquals(300, q.dwellSec); assertEquals(3, q.persistPasses); assertEquals(600, q.retryCapSec); assertEquals(60, q.warmSec); assertEquals(3000, q.failoverMs)
        val clamped = AutoPolicy.parse("pace=0,cur=9999,jitter=99,dwell=-5,persist=0,cap=1,warm=1,fover=1")
        assertEquals(1, clamped.firstPaceSec); assertEquals(300, clamped.currentSec); assertEquals(50, clamped.jitterPct)
        assertEquals(0, clamped.dwellSec); assertEquals(1, clamped.persistPasses); assertEquals(60, clamped.retryCapSec); assertEquals(5, clamped.warmSec); assertEquals(500, clamped.failoverMs)
        // twice the margin: 15 % and 25 ms become 30 % and 50 ms
        assertTrue(d.shouldSwitch(currentMs = 400, bestMs = 270, factor = 2))    // 400 − 120 = 280: 270 is clearly better
        assertFalse(d.shouldSwitch(currentMs = 400, bestMs = 290, factor = 2))
        assertTrue(d.shouldSwitch(currentMs = 400, bestMs = 290))                // by the plain margin it is
    }
}
