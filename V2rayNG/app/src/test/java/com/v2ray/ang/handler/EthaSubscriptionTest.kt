package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EthaSubscriptionTest {

    @Test
    fun userInfoParsesTheApiShape() {
        val info = EthaSubscription.parseUserInfo("upload=0; download=1234567; total=21474836480; expire=1790000000")!!
        assertEquals(0L, info.upload)
        assertEquals(1234567L, info.download)
        assertEquals(21474836480L, info.total)
        assertEquals(1790000000L, info.expire)
    }

    @Test
    fun userInfoKeepsServerSemanticsForZero() {
        val info = EthaSubscription.parseUserInfo("upload=0; download=0; total=0; expire=0")!!
        assertEquals(0L, info.total)          // unlimited
        assertEquals(0L, info.expire)         // never
        assertEquals(Long.MAX_VALUE, EthaSubscription.daysLeft(0L))
    }

    @Test
    fun userInfoToleratesGarbage() {
        assertNull(EthaSubscription.parseUserInfo(null))
        assertNull(EthaSubscription.parseUserInfo(""))
        assertNull(EthaSubscription.parseUserInfo("nonsense"))
        val partial = EthaSubscription.parseUserInfo("download=5; total=abc; expire=")!!
        assertEquals(5L, partial.download)
        assertEquals(-1L, partial.total)
        assertEquals(-1L, partial.expire)
        assertEquals(-1L, partial.upload)
        assertEquals(12L, EthaSubscription.parseUserInfo("download=12.7")!!.download)
    }

    @Test
    fun daysLeftRoundsUpAndNeverGoesNegative() {
        val now = 1_700_000_000L
        assertEquals(1L, EthaSubscription.daysLeft(now + 1, now))
        assertEquals(1L, EthaSubscription.daysLeft(now + 86400, now))
        assertEquals(2L, EthaSubscription.daysLeft(now + 86401, now))
        assertEquals(0L, EthaSubscription.daysLeft(now - 5, now))
        assertNull(EthaSubscription.daysLeft(-1, now))
    }

    @Test
    fun headerTextDecodesBase64OrPassesPlain() {
        assertEquals("EthaVPN", EthaSubscription.decodeHeaderText("base64:RXRoYVZQTg=="))
        assertEquals("سلام", EthaSubscription.decodeHeaderText("base64:2LPZhNin2YU="))
        assertEquals("plain", EthaSubscription.decodeHeaderText("  plain "))
        assertNull(EthaSubscription.decodeHeaderText(""))
        assertNull(EthaSubscription.decodeHeaderText("base64:***"))
        assertNull(EthaSubscription.decodeHeaderText(null))
    }

    @Test
    fun updateIntervalIsHoursToMinutesWithAFloor() {
        assertEquals(720L, EthaSubscription.updateIntervalMinutes("12"))
        assertEquals(60L, EthaSubscription.updateIntervalMinutes("1"))
        assertTrue(EthaSubscription.updateIntervalMinutes("1")!! >= AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES)
        assertNull(EthaSubscription.updateIntervalMinutes("0"))
        assertNull(EthaSubscription.updateIntervalMinutes("x"))
        assertNull(EthaSubscription.updateIntervalMinutes(null))
    }

    @Test
    fun subLinkRecognition() {
        val host = AppConfig.ETHA_SUB_HOST
        assertTrue(EthaSubscription.isSubLink("https://$host/sub/0123456789abcdef"))
        assertTrue(EthaSubscription.isSubLink("https://$host/sub/0123456789abcdef#EthaVPN"))
        assertTrue(EthaSubscription.isSubLink("HTTPS://${host.uppercase()}/sub/0123456789abcdef"))
        assertFalse(EthaSubscription.isSubLink("http://$host/sub/0123456789abcdef"))
        assertFalse(EthaSubscription.isSubLink("https://evil.example/sub/0123456789abcdef"))
        assertFalse(EthaSubscription.isSubLink("https://$host/sub/"))
        assertFalse(EthaSubscription.isSubLink("https://$host/sub/abc"))
        assertFalse(EthaSubscription.isSubLink("https://$host/sub/0123456789abcdef/x"))
        assertFalse(EthaSubscription.isSubLink("https://$host/dl/"))
        assertFalse(EthaSubscription.isSubLink("vless://x"))
        assertFalse(EthaSubscription.isSubLink(null))
        assertFalse(EthaSubscription.isSubLink("not a url at all ://"))
    }

    @Test
    fun extractsTheLinkFromPastedText() {
        val host = AppConfig.ETHA_SUB_HOST
        val link = "https://$host/sub/0123456789abcdef"
        assertEquals(link, EthaSubscription.extractSubLink("🔗 Your link:\n$link\n\nTap it."))
        assertEquals(link, EthaSubscription.extractSubLink("لینک شما: $link، بعد وصل شوید."))
        assertEquals(link, EthaSubscription.extractSubLink(link))
        assertNull(EthaSubscription.extractSubLink("nothing here"))
        assertNull(EthaSubscription.extractSubLink(null))
    }

    @Test
    fun applyHeadersFillsTheItemAndLeavesItAloneOtherwise() {
        val sub = SubscriptionItem(remarks = "EthaVPN", url = "https://x/sub/0123456789abcdef")
        assertFalse(EthaSubscription.applyHeaders(sub, mapOf("content-type" to "text/plain")))
        assertEquals(-1L, sub.total)
        assertEquals(-1L, sub.infoUpdated)
        val headers = mapOf(
            "subscription-userinfo" to "upload=0; download=100; total=0; expire=0",
            "profile-title" to "base64:UmV6YQ==",
            "announce" to "base64:2LPZhNin2YU=",
            "support-url" to "https://t.me/freeandsecurevpn",
            "profile-web-page-url" to "https://x/sub/0123456789abcdef",
            "profile-update-interval" to "12",
        )
        assertTrue(EthaSubscription.applyHeaders(sub, headers))
        assertEquals(100L, sub.download)
        assertEquals(0L, sub.total)
        assertEquals(0L, sub.expire)
        assertEquals("Reza", sub.profileTitle)
        assertEquals("سلام", sub.announce)
        assertEquals("https://t.me/freeandsecurevpn", sub.supportUrl)
        assertEquals("https://x/sub/0123456789abcdef", sub.webPageUrl)
        assertEquals(720L, sub.updateInterval)
        assertTrue(sub.autoUpdate)
        assertTrue(sub.infoUpdated > 0)
        // a later fetch without an announce clears it (the header is present but empty)
        EthaSubscription.applyHeaders(sub, mapOf("announce" to ""))
        assertNull(sub.announce)
        // a broken userinfo keeps the last good numbers
        EthaSubscription.applyHeaders(sub, mapOf("subscription-userinfo" to "garbage"))
        assertEquals(100L, sub.download)
    }

    @Test
    fun base64DecoderHandlesPaddingAndUrlAlphabet() {
        assertEquals("hello", String(EthaSubscription.base64Decode("aGVsbG8=")!!))
        assertEquals("hello", String(EthaSubscription.base64Decode("aGVsbG8")!!))
        assertEquals("hello", String(EthaSubscription.base64Decode("aGVs\nbG8=")!!))
        assertNull(EthaSubscription.base64Decode("!!"))
        assertNull(EthaSubscription.base64Decode(""))
    }
}
