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
        assertFalse(sch.probed(s(10.0), listOf("r2", "x1"), p, 0.5))
        assertNull(sch.action(s(10.5), warm = false, watched = false, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("x2", "w1")), sch.action(s(11.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // a second a step
        assertFalse(sch.probed(s(11.0), listOf("x2", "w1"), p, 0.5))
        assertEquals(AutoSchedule.Action.Probe(listOf("w2")), sch.action(s(12.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // the pass ends with w2
        assertTrue(sch.probed(s(12.0), listOf("w2"), p, 0.5))                                                                        // a pass: the first pass is over
        assertEquals(AutoSchedule.Phase.STEADY, sch.phase); assertEquals(1, sch.passes); assertFalse(sch.forced)
        assertNull(sch.action(s(13.0), warm = false, watched = false, searching = false, current = "r1", due = all))        // cold and unwatched: nothing
        assertEquals(30_000L, sch.paceMs(p, 0.5))                                                                           // 180 s over 6 lines
        assertNull(sch.action(s(41.0), warm = true, watched = false, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("r1")), sch.action(s(42.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // one at a time from here, the line in use among them
        sch.probed(s(42.0), listOf("r1"), p, 0.5)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2")), sch.action(s(72.0), warm = true, watched = false, searching = false, current = "r1", due = all))
        // the pace spreads the round over the lines that can be probed, not over lines waiting their retry
        sch.probed(s(72.0), listOf("r2"), p, 0.5, active = 2)
        assertEquals(s(72.0) + 90_000L, sch.nextProbeAt)                                                                  // 180 s over 2 live lines
    }

    @Test
    fun aSteadyStepNeedsAWarmRadioOrTheScreenAndTheFirstPassNeedsNeither() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(60.0), warm = false, watched = false, searching = false, current = "r1", due = all))          // cold and unwatched: the phone sleeps
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(60.0), warm = false, watched = true, searching = false, current = "r1", due = all))   // on screen: the line in use first
        sch.currentProbed(s(60.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r1")), sch.action(s(60.0), warm = false, watched = true, searching = false, current = "r1", due = all))   // …then the step (steady: the line in use is in the rotation)
        assertEquals(AutoSchedule.Action.Probe(listOf("r1")), sch.action(s(60.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // traffic flowing: the step, no extra current probe
        val f = AutoSchedule(); f.start(0, lines)
        assertEquals(AutoSchedule.Action.ProbeCurrent, f.action(0, warm = false, watched = false, searching = false, current = "r1", due = all))   // the first pass asks nobody
    }

    @Test
    fun theLineInUseIsProbedEveryThirtySecondsOnlyWhileWatched() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(29.0), warm = true, watched = true, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(30.0), warm = true, watched = true, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("r1")), sch.action(s(30.0), warm = true, watched = false, searching = false, current = "r1", due = all))   // not watched: the trickle only
        sch.currentProbed(s(30.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r1")), sch.action(s(30.0), warm = true, watched = true, searching = false, current = "r1", due = all))    // both due: the current came first, the step follows
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
        assertEquals(0, sch.cursor); assertEquals(1, sch.passes)                                                      // the rest held nothing: the pass ended
        // a skipped line on the way counts as consumed: the step after [r2, x1] (r1 skipped) is [x2, w1], never x1 again
        val r = AutoSchedule(); r.start(0, listOf("r1", "r2", "x1", "x2", "w1", "w2"))
        assertEquals(listOf("r2", "x1"), r.next(2, "r1", all)); r.probed(0, listOf("r2", "x1"), p, 0.5)
        assertEquals(3, r.cursor); assertEquals(listOf("x2", "w1"), r.next(2, "r1", all))
        r.probed(s(1.0), listOf("x2", "w1"), p, 0.5); assertEquals(listOf("w2"), r.next(2, "r1", all))                // a step never straddles a pass
    }

    @Test
    fun idleStopsTheTrickleAndTrafficReturningResumesItWithTheLineInUse() {
        val sch = AutoSchedule(); sch.reseed(0, lines, p, 0.5)
        assertNull(sch.action(s(600.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        sch.wake(s(600.0))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(600.0), warm = false, watched = false, searching = false, current = "r1", due = all))   // one probe of the line in use, watched or not
        sch.currentProbed(s(600.0), p)
        assertNull(sch.action(s(601.0), warm = false, watched = false, searching = false, current = "r1", due = all))                 // then the trickle waits for the radio or the screen: no forced pass
        assertFalse(sch.forced)
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
        sch.searchStep(s(50.0), 2, p)
        assertNull(sch.action(s(79.0), warm = false, watched = false, searching = true, current = "a", due = all))                   // paced: 3 lines, two a step → a step every 30 s, each line once a minute
        assertEquals(AutoSchedule.Action.Probe(listOf("c")), sch.action(s(80.0), warm = false, watched = false, searching = true, current = "a", due = all))   // to the end of the rotation…
        sch.searchStep(s(80.0), 1, p, slow = true)                                                                                  // the box says the network blocks every line: three times slower
        assertNull(sch.action(s(169.0), warm = false, watched = false, searching = true, current = "a", due = all))
        assertEquals(AutoSchedule.Action.Probe(listOf("a", "b")), sch.action(s(170.0), warm = false, watched = false, searching = true, current = "a", due = all))   // …then round again
        sch.searchStep(s(170.0), 2, p)
        assertNull(sch.action(s(700.0), warm = false, watched = false, searching = true, current = "a", due = all))                  // ten minutes in: only warm or watched
        assertEquals(AutoSchedule.Action.Probe(listOf("c")), sch.action(s(700.0), warm = true, watched = false, searching = true, current = "a", due = all))
    }

    @Test
    fun theFallbackLastsOnlyWhileTheTargetAnswersNobody() {
        assertFalse(AutoSchedule.nextFallback(current = false, targetAnswered = true, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = false, targetAnswered = false, fallbackAnswered = false))   // a blip: nobody answers → stay
        assertTrue(AutoSchedule.nextFallback(current = false, targetAnswered = false, fallbackAnswered = true))
        assertFalse(AutoSchedule.nextFallback(current = true, targetAnswered = true, fallbackAnswered = false))     // the scout answers: back
        assertTrue(AutoSchedule.nextFallback(current = true, targetAnswered = false, fallbackAnswered = true))
    }

    @Test
    fun connectCanHoldTheFirstProbeAndWakingWithAnOldPictureFreshensIt() {
        val sch = AutoSchedule()
        sch.start(s(10.0), lines, delayMs = 2_000L)                                                                       // 2.1.1: two seconds for the tunnel to settle
        assertNull(sch.action(s(11.9), warm = true, watched = true, searching = false, current = "r1", due = all))
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(12.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        sch.currentProbed(s(12.0), p)
        sch.reseed(s(60.0), lines, p, 0.5)
        sch.freshen(s(700.0), lines)                                                                                        // the phone woke with an old picture
        assertEquals(AutoSchedule.Phase.FIRST, sch.phase); assertTrue(sch.forced)
        assertEquals(AutoSchedule.Action.ProbeCurrent, sch.action(s(700.0), warm = false, watched = false, searching = false, current = "r1", due = all))
        sch.currentProbed(s(700.0), p)
        assertEquals(AutoSchedule.Action.Probe(listOf("r2", "x1")), sch.action(s(700.0), warm = false, watched = false, searching = false, current = "r1", due = all))
    }
}
