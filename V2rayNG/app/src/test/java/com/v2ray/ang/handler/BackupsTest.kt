package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupsTest {
    private fun r(id: String, ip: String) = Backups.Line(id, ip, "REALITY-relay-$id-443")
    private fun w(id: String, ip: String) = Backups.Line(id, ip, "WS-www-$id-443")
    private val cur = r("FRA7", "203.0.113.9")

    @Test
    fun aRangeIsTheBlockOfAddressesWithItsKind() {
        assertEquals("relay/203.0.113", Backups.range(cur))
        assertEquals("cf/104.16.1", Backups.range(w("CleanIP1", "104.16.1.7")))
        assertEquals("relay/2001:db8:1:2", Backups.range(r("X", "2001:db8:1:2::9")))
        assertEquals("cf/www.example.org", Backups.range(w("C", " WWW.Example.org ")))
        assertEquals("/", Backups.range(Backups.Line("x", null, null)))
        assertEquals("relay", Backups.kind(cur)); assertEquals("cf", Backups.kind(Backups.Line("x", "1.1.1.1", "XHTTP-api-CleanIP1-443"))); assertEquals("", Backups.kind(Backups.Line("x", "1.1.1.1", "other")))
    }

    @Test
    fun threeBackupsComeFromOtherRangesThanTheLineInUseAndThanEachOther() {
        val pref = listOf(r("FRA7-2", "203.0.113.20"), r("FRA9", "198.51.100.5"), r("FRA9-2", "198.51.100.6"), r("FRA12", "192.0.2.4"), r("ALI1", "100.64.1.1"))
        assertEquals(listOf("FRA9", "FRA12", "ALI1"), Backups.pick(cur, pref))   // not the line in use's own block, not a second line of a block already taken
    }

    @Test
    fun shortOfThreeRangesTheRestFillInTheirOrder() {
        val pref = listOf(r("FRA7-2", "203.0.113.20"), r("FRA9", "198.51.100.5"), r("FRA9-2", "198.51.100.6"))
        assertEquals(listOf("FRA9", "FRA7-2", "FRA9-2"), Backups.pick(cur, pref))
        assertEquals(listOf("FRA9"), Backups.pick(cur, pref, 1))
    }

    @Test
    fun oneOfTheThreeIsOfTheOtherKindWhenThePhoneHoldsOne() {
        val pref = listOf(r("FRA9", "198.51.100.5"), r("FRA12", "192.0.2.4"), r("ALI1", "100.64.1.1"), w("CleanIP2", "104.16.2.2"), w("CleanIP4", "104.16.4.4"))
        assertEquals(listOf("FRA9", "FRA12", "CleanIP2"), Backups.pick(cur, pref))   // a network can drop a whole kind: one bet on the other
        val cf = w("CleanIP1", "104.16.1.1")
        assertEquals(listOf("CleanIP2", "FRA9", "FRA12"), Backups.pick(cf, listOf(w("CleanIP2", "104.16.2.2"), r("FRA9", "198.51.100.5"), r("FRA12", "192.0.2.4"), r("ALI1", "100.64.1.1"))))   // the other kind is among them already
    }

    @Test
    fun linesHeldAsFailedOnlyMakeUpTheCount() {
        val live = listOf(r("FRA9", "198.51.100.5"), r("FRA9-2", "198.51.100.6"))
        val dead = listOf(r("FRA12", "192.0.2.4"), w("CleanIP5", "104.16.5.5"))
        assertEquals(listOf("FRA9", "FRA9-2", "FRA12"), Backups.pick(cur, live, 3, dead))   // a live line of a block already taken before a failed line of a new one; a failed line of the other kind takes nobody's place
        assertEquals(listOf("FRA9", "FRA9-2"), Backups.pick(cur, live, 2, dead))
        assertEquals(listOf("FRA12", "CleanIP5"), Backups.pick(cur, emptyList(), 3, dead))
        assertEquals(emptyList<String>(), Backups.pick(cur, emptyList(), 3))
    }

    @Test
    fun theLineInUseIsNeverItsOwnBackupAndNoLineIsTakenTwice() {
        assertEquals(listOf("FRA9"), Backups.pick(cur, listOf(cur, r("FRA9", "198.51.100.5"), r("FRA9", "198.51.100.5")), 3, listOf(r("FRA9", "198.51.100.5"), cur)))
        assertEquals(emptyList<String>(), Backups.pick(cur, listOf(r("FRA9", "198.51.100.5")), 0))
    }
}
