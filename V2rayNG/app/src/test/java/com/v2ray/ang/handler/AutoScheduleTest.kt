package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScheduleTest {
    private val p = AutoPolicy()
    private val t0 = 1_800_000_000_000L
    private fun at(s: Long) = t0 + s * 1000L

    @Test
    fun theNextRoundComesOneIntervalAfterTheLastEndedWhateverTheMeasurementTook() {
        val s = AutoSchedule()
        assertNull(s.action(at(1000), inUse = true, searching = false))                 // nothing before the first race ended
        s.roundDone(t0, mapOf("a" to 200L, "b" to 250L, "c" to 300L), p, 0.5, searching = false)   // 180 s
        assertNull(s.action(at(179), inUse = true, searching = false))
        assertEquals(AutoSchedule.Action.ROUND, s.action(at(180), inUse = true, searching = false))
        // the bug of 2.0.0–2.0.2: at that moment every line is measured, not skipped for having been probed "too recently"
        assertEquals(listOf("a", "b", "c"), s.linesForRound(at(180), listOf("a", "b", "c"), searching = false))
        assertNull(s.action(at(180), inUse = false, searching = false))                 // idle: no round
    }

    @Test
    fun aFailedLineWaitsItsRetryAndIsRetriedOncePerWindow() {
        val s = AutoSchedule()
        s.roundDone(t0, mapOf("a" to -1L, "b" to 200L), p, 0.5, searching = false)
        assertEquals(listOf("b"), s.linesForRound(at(180), listOf("a", "b"), searching = false))
        assertEquals(listOf("b"), s.linesForRound(at(899), listOf("a", "b"), searching = false))
        assertEquals(listOf("a", "b"), s.linesForRound(at(900), listOf("a", "b"), searching = false))
        s.roundDone(at(900), mapOf("a" to -1L, "b" to 210L), p, 0.5, searching = false)   // still dead: another 900 s
        assertEquals(listOf("b"), s.linesForRound(at(1080), listOf("a", "b"), searching = false))
        assertEquals(listOf("a", "b"), s.linesForRound(at(1800), listOf("a", "b"), searching = false))
        s.roundDone(at(1800), mapOf("a" to 190L), p, 0.5, searching = false)              // back: free again
        assertEquals(listOf("a", "b"), s.linesForRound(at(1980), listOf("a", "b"), searching = false))
        assertEquals(listOf("a", "b"), s.linesForRound(at(1), listOf("a", "b"), searching = true))   // Searching measures every line
    }

    @Test
    fun theSettlingRoundComesOnceAfterTheConnectRaceAndNotAfterAManualTest() {
        val s = AutoSchedule()
        s.raceDone(t0, mapOf("a" to 393L, "b" to 400L), p, 0.5, searching = false, settle = true)
        assertNull(s.action(at(9), inUse = false, searching = false))
        assertEquals(AutoSchedule.Action.SETTLE, s.action(at(10), inUse = false, searching = false))   // in use or not
        s.settleTaken()
        s.roundDone(at(12), mapOf("a" to 203L, "b" to 390L), p, 0.5, searching = false)
        assertNull(s.action(at(13), inUse = true, searching = false))
        assertEquals(AutoSchedule.Action.ROUND, s.action(at(192), inUse = true, searching = false))    // 180 s after the settle ended
        val m = AutoSchedule()
        m.raceDone(t0, mapOf("a" to 200L), p, 0.5, searching = false, settle = false)
        assertNull(m.action(at(10), inUse = true, searching = false))
        val off = AutoSchedule()
        off.raceDone(t0, mapOf("a" to 200L), AutoPolicy.parse("settle=0"), 0.5, searching = false, settle = true)
        assertNull(off.action(at(10), inUse = true, searching = false))
    }

    @Test
    fun searchingRunsEverySearchSecInUseOrNot() {
        val s = AutoSchedule()
        s.roundDone(t0, mapOf("a" to -1L), p, 0.5, searching = true)
        assertNull(s.action(at(59), inUse = false, searching = true))
        assertEquals(AutoSchedule.Action.ROUND, s.action(at(60), inUse = false, searching = true))
    }

    @Test
    fun theJitterKeepsTheRoundWithinTenPercent() {
        val lo = AutoSchedule(); val hi = AutoSchedule()
        lo.roundDone(t0, emptyMap(), p, 0.0, searching = false)
        hi.roundDone(t0, emptyMap(), p, 1.0, searching = false)
        assertEquals(AutoSchedule.Action.ROUND, lo.action(at(162), inUse = true, searching = false))
        assertNull(lo.action(at(161), inUse = true, searching = false))
        assertEquals(AutoSchedule.Action.ROUND, hi.action(at(198), inUse = true, searching = false))
        assertNull(hi.action(at(197), inUse = true, searching = false))
    }

    @Test
    fun theFallbackLastsOneRoundAndComesBackWhenTheTargetAnswers() {
        assertFalse(AutoSchedule.nextFallback(current = false, fullSet = true, targetAnswered = true, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = false, fullSet = false, targetAnswered = false, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = false, fullSet = true, targetAnswered = false, fallbackAnswered = false))   // a blip: stay
        assertTrue(AutoSchedule.nextFallback(current = false, fullSet = true, targetAnswered = false, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = true, fullSet = true, targetAnswered = true, fallbackAnswered = false))     // the scout answers: back
        assertTrue(AutoSchedule.nextFallback(current = true, fullSet = true, targetAnswered = false, fallbackAnswered = true))
    }

    @Test
    fun aNetworkChangeIsOneRoundFifteenSecondsLaterInUseOrNotAndDebounced() {
        val s = AutoSchedule()
        s.roundDone(t0, mapOf("a" to -1L, "b" to 200L), p, 0.5, searching = false)
        s.networkChanged(at(30))
        assertNull(s.action(at(44), inUse = false, searching = false))
        assertEquals(AutoSchedule.Action.ROUND, s.action(at(45), inUse = false, searching = false))      // idle or not
        assertEquals(listOf("a", "b"), s.linesForRound(at(45), listOf("a", "b"), searching = false))      // failures void
        s.networkChanged(at(50))                                                                          // within a minute: ignored
        assertEquals(at(45), s.nextRoundAt)
        s.roundDone(at(46), mapOf("a" to 210L, "b" to 200L), p, 0.5, searching = false)
        assertFalse(s.forced)
        assertNull(s.action(at(226), inUse = false, searching = false))                                    // back to needing traffic
        s.networkChanged(at(120))                                                                         // a minute later: a new event
        assertEquals(at(135), s.nextRoundAt)
    }

    @Test
    fun trafficReturningAfterIdleBringsARoundAtOnce() {
        val s = AutoSchedule()
        s.roundDone(t0, mapOf("a" to 200L), p, 0.5, searching = false)
        assertNull(s.action(at(500), inUse = false, searching = false))                                    // idle: the 180 s passed, nothing ran
        s.wake(at(500))
        assertEquals(AutoSchedule.Action.ROUND, s.action(at(500), inUse = true, searching = false))
        val fresh = AutoSchedule()
        fresh.wake(t0)                                                                                    // before the first race: nothing to wake
        assertNull(fresh.action(t0, inUse = true, searching = false))
    }
}
