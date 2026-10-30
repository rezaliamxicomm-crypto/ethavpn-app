package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class AutoDecisionTest {
    private val p = AutoPolicy()
    private fun c(id: String, ms: Long, order: Int = 0) = AutoDecision.Candidate(id, ms, order)
    private fun s(sec: Int) = sec * 1000L

    @Test
    fun aFailedLineInUseFailsOverToTheBestKnownAtOnce() {
        val d = AutoDecision()
        d.lastSwitchAt = s(95)                                                   // moved five seconds ago: the dwell does not hold a failover
        assertEquals("c", d.target(s(100), AutoDecision.Phase.PASS_END, c("a", -1), listOf(c("a", -1), c("b", 300, 1), c("c", 200, 2)), p))
        assertEquals("b", d.target(s(100), AutoDecision.Phase.FAILOVER, c("a", 200), listOf(c("a", 200), c("b", 300, 1)), p))
        assertNull(d.target(s(100), AutoDecision.Phase.FAILOVER, c("a", -1), listOf(c("a", -1), c("b", -1, 1)), p))   // nothing known good
    }

    @Test
    fun theFirstPassMovesOnceAndOnlyForAClearlyBetterLine() {
        val d = AutoDecision()
        assertNull(d.target(s(1), AutoDecision.Phase.FIRST, c("a", 393), listOf(c("a", 393), c("b", 300, 1)), p))        // 300 is not twice the margin better
        assertEquals("b", d.target(s(2), AutoDecision.Phase.FIRST, c("a", 393), listOf(c("a", 393), c("b", 203, 1)), p))
        assertNull(d.target(s(3), AutoDecision.Phase.FIRST, c("b", 203), listOf(c("b", 203), c("c", 100, 2)), p))         // one move in the first pass
    }

    @Test
    fun steadyStateNeedsPersistenceAndRespectsTheDwell() {
        val d = AutoDecision()
        val lines = listOf(c("a", 200), c("b", 160, 1))                        // b ahead by 20 %: by the margin, not twice
        assertNull(d.target(s(180), AutoDecision.Phase.PASS_END, c("a", 200), lines, p))        // first pass end: noted
        assertEquals(1, d.leading["b"])
        assertEquals("b", d.target(s(360), AutoDecision.Phase.PASS_END, c("a", 200), lines, p))   // second: moves
        assertEquals(s(360), d.lastSwitchAt)
        // a line ahead by twice the margin moves at one pass end, but not within the dwell
        assertNull(d.target(s(400), AutoDecision.Phase.PASS_END, c("b", 160), listOf(c("b", 160), c("c", 90, 2)), p))
        assertEquals("c", d.target(s(450), AutoDecision.Phase.PASS_END, c("b", 160), listOf(c("b", 160), c("c", 90, 2)), p))
        // the lead must be consecutive
        val e = AutoDecision()
        e.target(s(180), AutoDecision.Phase.PASS_END, c("a", 200), listOf(c("a", 200), c("b", 160, 1)), p)
        assertNull(e.target(s(360), AutoDecision.Phase.PASS_END, c("a", 200), listOf(c("a", 200), c("b", 195, 1)), p))    // the lead is gone
        assertEquals(0, e.leading.size)
    }

    @Test
    fun aManualTestMovesByTheMarginAlone() {
        val d = AutoDecision(); d.lastSwitchAt = s(99)
        assertEquals("b", d.target(s(100), AutoDecision.Phase.MANUAL, c("a", 200), listOf(c("a", 200), c("b", 160, 1)), p))
        assertNull(d.target(s(101), AutoDecision.Phase.MANUAL, c("b", 160), listOf(c("b", 160), c("a", 150, 0)), p))      // 10 ms: under the floor
    }

    /** An hour of steady state with every line's time wandering ±10 % around the same value: nobody moves. A line that
     *  then settles 30 % below the line in use for two passes: exactly one move. The line in use dying: one failover. */
    @Test
    fun simulationNoiseMovesNobodyARealLeadMovesOnceADeathMovesAtOnce() {
        val d = AutoDecision()
        val rng = Random(7)
        var current = "r1"; var moves = 0
        val base = linkedMapOf("r1" to 200L, "r2" to 205L, "x1" to 220L, "w1" to 210L)
        val names = base.keys.toList()
        for (pass in 1..20) {                                                   // an hour of passes
            val cands = base.map { (id, ms) -> c(id, ms + rng.nextLong(-(ms / 10), ms / 10 + 1), names.indexOf(id)) }
            d.target(s(pass * 180), AutoDecision.Phase.PASS_END, cands.first { it.id == current }, cands, p)?.let { current = it; moves++ }
        }
        assertEquals("noise of ±10 % must not move anyone", 0, moves)
        for (pass in 21..24) {                                                  // r2 settles clearly ahead
            val cands = listOf(c("r1", 200), c("r2", 140, 1), c("x1", 220, 2), c("w1", 210, 3))
            d.target(s(pass * 180), AutoDecision.Phase.PASS_END, cands.first { it.id == current }, cands, p)?.let { current = it; moves++ }
        }
        assertEquals(1, moves); assertEquals("r2", current)
        val dead = listOf(c("r1", 200), c("r2", -1, 1), c("x1", 220, 2), c("w1", 210, 3))
        assertEquals("r1", d.target(s(25 * 180), AutoDecision.Phase.FAILOVER, c("r2", -1, 1), dead, p))
    }
}
