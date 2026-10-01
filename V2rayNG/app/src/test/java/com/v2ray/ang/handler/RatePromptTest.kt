package com.v2ray.ang.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatePromptTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L   // the first working connection

    @Test
    fun theFirstAskNeedsFiveConnectionsAndThreeDays() {
        assertFalse(RatePrompt.due(RatePrompt.State(connects = 4, firstAt = t0), t0 + 10 * day))
        assertFalse(RatePrompt.due(RatePrompt.State(connects = 5, firstAt = t0), t0 + 3 * day - 1))
        assertTrue(RatePrompt.due(RatePrompt.State(connects = 5, firstAt = t0), t0 + 3 * day))
        assertTrue(RatePrompt.due(RatePrompt.State(connects = 40, firstAt = t0), t0 + 30 * day))
    }

    @Test
    fun aBusyFirstDayDoesNotAsk() {
        assertFalse(RatePrompt.due(RatePrompt.State(connects = 25, firstAt = t0), t0 + day))
    }

    @Test
    fun afterNotNowTheNextAskNeedsTenMoreConnectionsAndTwoWeeks() {
        val asked = RatePrompt.State(connects = 5, firstAt = t0, asks = 1, lastAskAt = t0 + 3 * day, lastAskConnects = 5)
        assertFalse(RatePrompt.due(asked.copy(connects = 14), t0 + 60 * day))
        assertFalse(RatePrompt.due(asked.copy(connects = 15), t0 + 17 * day - 1))
        assertTrue(RatePrompt.due(asked.copy(connects = 15), t0 + 17 * day))
    }

    @Test
    fun threeAsksAtMost() {
        val s = RatePrompt.State(connects = 500, firstAt = t0, asks = 3, lastAskAt = t0 + 40 * day, lastAskConnects = 30)
        assertFalse(RatePrompt.due(s, t0 + 400 * day))
        assertTrue(RatePrompt.due(s.copy(asks = 2), t0 + 400 * day))
    }

    @Test
    fun neverAgainOnceTheCustomerWentToTheStore() {
        assertFalse(RatePrompt.due(RatePrompt.State(connects = 50, firstAt = t0, done = true), t0 + 30 * day))
    }

    @Test
    fun nothingCountedYetOrAClockSetBackJustWaits() {
        assertFalse(RatePrompt.due(RatePrompt.State(), t0))
        assertFalse(RatePrompt.due(RatePrompt.State(connects = 9, firstAt = t0), t0 - 5 * day))
    }
}
