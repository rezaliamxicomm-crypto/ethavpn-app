package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFetchTest {
    private val box = AutoPolicy.parse("search=15,sfetch=60")   // what the box sends

    @Test
    fun theFirstCallIsDueAtOnceAndAnUnansweredOneIsTriedAgainAfter5Then10ThenEvery20Seconds() {
        val f = SearchFetch(); f.began(1_000)
        assertTrue(f.due(1_000))
        f.started(1_000, box); assertFalse(f.due(1_500))                       // nothing else while the call is out
        f.done(3_000, false, box); assertFalse(f.due(7_999)); assertTrue(f.due(8_000))
        f.started(8_000, box); f.done(9_000, false, box); assertFalse(f.due(18_999)); assertTrue(f.due(19_000))
        f.started(19_000, box); f.done(20_000, false, box); assertFalse(f.due(39_999)); assertTrue(f.due(40_000))
        f.started(40_000, box); f.done(41_000, false, box); assertFalse(f.due(60_999)); assertTrue(f.due(61_000))
        assertEquals(4, f.failures)
    }

    @Test
    fun anAnsweredCallThatBroughtNothingIsRepeatedOnceAfter20SecondsThenEverySearchFetchSec() {
        val f = SearchFetch(); f.began(0); f.started(0, box)
        f.done(1_000, true, box); assertFalse(f.due(20_999)); assertTrue(f.due(21_000))     // the box gives a phone still searching more lines then
        f.started(21_000, box); f.done(22_000, true, box); assertFalse(f.due(81_999)); assertTrue(f.due(82_000))
        f.started(82_000, box); f.done(83_000, false, box); assertTrue(f.due(88_000)); assertEquals(1, f.failures)   // an answer had ended the count
        f.started(88_000, box); f.done(89_000, true, box); assertEquals(0, f.failures); assertEquals(3, f.answers)
    }

    @Test
    fun tenMinutesIntoASearchAnUnansweredCallWaitsTheSearchFetchSecAsBefore() {
        val f = SearchFetch(); f.began(0)
        f.started(700_000, box); f.done(701_000, false, box)
        assertFalse(f.due(760_999)); assertTrue(f.due(761_000))
    }

    @Test
    fun aNewSearchBeginsTheCountsAgain() {
        val f = SearchFetch(); f.began(0); f.started(0, box); f.done(1_000, false, box); f.started(6_000, box); f.done(7_000, true, box)
        f.began(500_000)
        assertTrue(f.due(500_000)); assertEquals(0, f.failures); assertEquals(0, f.answers)
    }

    @Test
    fun theMarkNamesTheLinesHeldAndTheNextEngineFindsTheNewOnes() {
        val mark = SearchFetch.mark(1_000_000, listOf("REALITY-relay-FRA7-443", "WS-www-CleanIP1-443"))
        assertEquals("1000000|REALITY-relay-FRA7-443,WS-www-CleanIP1-443", mark)
        val now = listOf("REALITY-relay-FRA7-443", "REALITY-relay-FRA9-443", "WS-www-CleanIP1-443", "REALITY-relay-ALI1-a-443")
        assertEquals(listOf("REALITY-relay-FRA9-443", "REALITY-relay-ALI1-a-443"), SearchFetch.newSince(mark, now, 1_030_000))
        assertEquals(listOf("REALITY-relay-FRA9-443", "REALITY-relay-ALI1-a-443"), SearchFetch.newSince(mark, now, 1_060_000))
        assertEquals(emptyList<String>(), SearchFetch.newSince(mark, now, 1_060_001))      // older than a minute: a tunnel that was switched off
        assertEquals(emptyList<String>(), SearchFetch.newSince(mark, now, 999_999))        // from the future
        assertEquals(emptyList<String>(), SearchFetch.newSince("", now, 1_000_000))
        assertEquals(emptyList<String>(), SearchFetch.newSince("abc|x", now, 1_000_000))
        assertEquals(emptyList<String>(), SearchFetch.newSince("1000000", now, 1_000_000))
        assertEquals(emptyList<String>(), SearchFetch.newSince("1000000|", now, 1_000_000))   // a mark that names no line tells nothing
        assertEquals(emptyList<String>(), SearchFetch.newSince(mark, listOf("REALITY-relay-FRA7-443"), 1_000_000))   // the same lines, or fewer: nothing new
    }
}
