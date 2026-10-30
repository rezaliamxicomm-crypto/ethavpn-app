package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScheduleTest {
    private val p = AutoPolicy()
    private val lines = listOf("r1", "r2", "x1", "x2", "w1", "w2")
    private val all: (String) -> Boolean = { true }
    private fun s(sec: Double) = (sec * 1000).toLong()

    @Test
    fun connectProbesTheLineInUseFirstThenTheQuickPassTwoAtATime() {
        val sch = AutoSchedule()
        assertNull(sch.action(0, warm = true, watched = true, searching = false, current = "r1", due = all))   // not started
        sch.start(s(10.0), lines)
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(10.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // first pass: warm or not, watched or not
        sch.currentProbed(s(10.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2", "x1")), sch.action(s(10.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // two at a time, the line in use skipped
        assertFalse(sch.probed(s(10.0), 2, p, 0.5))
        assertNull(sch.action(s(10.5), warm = false, watched = false, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("x2", "w1")), sch.action(s(11.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // a second a step
        assertFalse(sch.probed(s(11.0), 2, p, 0.5))
        assertEquals(AutoSchedule.Action.Probe(listOf("w2")), sch.action(s(12.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // the pass ends with w2
        assertTrue(sch.probed(s(12.0), 1, p, 0.5))                                                                        // a pass: the first pass is over
        assertEquals(AutoSchedule.Phase.STEADY, sch.phase); assertEquals(1, sch.passes); assertFalse(sch.forced)
        assertNull(sch.action(s(13.0), warm = false, watched = false, searching = false, current = "r1", due = all))        // cold and unwatched: nothing
        assertEquals(30_000L, sch.paceMs(p, 0.5))                                                                           // 180 s over 6 lines
        assertNull(sch.action(s(41.0), warm = true, watched = false, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(42.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // one at a time from here
    }

    @Test
    fun aSteadyStepNeedsAWarmRadioOrTheScreenAndTheFirstPassNeedsNeither() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(60.0), warm = false, watched = false, searching = false, current = "r1", due = all))          // cold and unwatched: the phone sleeps
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(60.0), warm = false, watched = true, searching = false, current = "r1", due = all))   // on screen: the line in use first
        sch.currentProbed(s(60.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(60.0), warm = false, watched = true, searching = false, current = "r1", due = all))   // …then the step
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(60.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // traffic flowing: the step, no current
        val f = AutoSchedule(); f.start(0, lines)
        assertEquals(AutoSchedule.Action.ProbeCurrent, f.action(0, warm = false, watched = false, searching = false, current = "r1", due = all))   // the first pass asks nobody
    }

    @Test
    fun theLineInUseIsProbedEveryThirtySecondsOnlyWhileWatched() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(29.0), warm = true, watched = true, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(30.0), warm = true, watched = true, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(30.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // not watched: the trickle only
        sch.currentProbed(s(30.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(30.0), warm = true, watched = true, searching = false, current = "r1", due = all))    // both due: the current came first, the step follows
    }

    @Test
    fun theJitterKeepsEachStepWithinTenPercentOfThePace() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertEquals(27_000L, sch.paceMs(p, 0.0))
        assertEquals(33_000L, sch.paceMs(p, 1.0))
        assertEquals(180_000L, AutoSchedule().paceMs(p, 0.5))                                                              // no lines yet: the whole interval
    }

    @Test
    fun linesWaitingTheirRetryAreSkippedAndASpentStepMovesOn() {
        val sch = AutoSchedule(); sch.reseed(0, listOf("a", "b", "c"), p, 0.5)
        val due: (String) -> Boolean = { it != "b" }
        assertEquals(listOf("c"), sch.next(1, "a", due))
        assertEquals(listOf("a", "c"), sch.next(2, null, due))
        assertEquals(emptyList<String>(), sch.next(1, "a") { it == "a" })
        sch.nothingDue(s(60.0), p, 0.5)
        assertEquals(120_000L, sch.nextProbeAt)
    }

    @Test
    fun idleStopsTheTrickleAndTrafficReturningResumesItWithTheLineInUse() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(600.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        sch.wake(s(600.0))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(600.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // forced: even unwatched
        val fresh = AutoSchedule(); fresh.wake(s(5.0))
        assertNull(fresh.action(s(5.0), warm = true, watched = true, searching = false, current = "r1", due = all))        // before Connect: nothing to wake
    }

    @Test
    fun aNetworkChangeRestartsAQuickPassInFifteenSecondsInUseOrNotDebounced() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        sch.networkChanged(s(100.0))
        assertNull(sch.action(s(114.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(115.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        sch.currentProbed(s(115.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2", "x1")), sch.action(s(115.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // the quick pass, two at a time
        sch.networkChanged(s(130.0))                                                                                        // within a minute: the same event
        assertEquals(s(100.0), sch.lastNetworkAt)
        assertEquals(AutoSchedule.Phase.FIRST, sch.phase)
    }

    @Test
    fun searchingIsTheFastTrickleOverEveryLineRetriesIgnored() {
        val sch = AutoSchedule(); sch.reseed(0, listOf("a", "b", "c"), p, 0.5)
        sch.searchingBegan(s(50.0))
        assertEquals(AutoSchedule.Action.Probe(listOf("a", "b")), sch.action(s(50.0), warm = false, watched = false, searching = true, current = "a") { false })   // every line, the line in use too, two at a time
        sch.searchStep(s(50.0), 2)
        assertNull(sch.action(s(50.5), warm = false, watched = false, searching = true, current = "a", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("c", "a")), sch.action(s(51.0), warm = false, watched = false, searching = true, current = "a", due = all))
    }

    @Test
    fun theFallbackLastsOnlyWhileTheTargetAnswersNobody() {
        assertFalse(AutoSchedule.nextFallback(current = false, targetAnswered = true, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = false, targetAnswered = false, fallbackAnswered = false))   // a blip: nobody answers → stay
        assertTrue(AutoSchedule.nextFallback(current = false, targetAnswered = false, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = true, targetAnswered = true, fallbackAnswered = false))     // the scout answers: back
        assertTrue(AutoSchedule.nextFallback(current = true, targetAnswered = false, fallbackAnswered = true))
    }
}
