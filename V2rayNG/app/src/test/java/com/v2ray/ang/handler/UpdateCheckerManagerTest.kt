package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.LatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerManagerTest {
    private val arm = LatestRelease.Asset("arm64-v8a", "EthaVPN_1.1.0_arm64-v8a.apk", 27_000_000, "AB" + "cd".repeat(31))
    private val uni = LatestRelease.Asset("universal", "EthaVPN_1.1.0_universal.apk", 40_000_000, "ef".repeat(32))
    private val latest = LatestRelease(version = "1.1.0", versionCode = 110, minSupported = "1.0.5", notes = "faster", assets = listOf(arm, uni))

    @Test
    fun versionCompare() {
        assertTrue(UpdateCheckerManager.compareVersions("1.1.0", "1.0.9") > 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.0.0", "1.0.0") == 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.2", "1.2.0") == 0)
        assertTrue(UpdateCheckerManager.compareVersions("v2.0.0", "1.9.9") > 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.0.0-beta", "1.0.0") == 0)
        assertTrue(UpdateCheckerManager.compareVersions("garbage", "1.0.0") < 0)
    }

    @Test
    fun abiPreferenceThenUniversal() {
        assertEquals(arm, UpdateCheckerManager.pickAsset(listOf(uni, arm), listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(uni, UpdateCheckerManager.pickAsset(listOf(uni, arm), listOf("x86_64")))
        assertNull(UpdateCheckerManager.pickAsset(listOf(arm), listOf("x86_64")))
    }

    @Test
    fun evaluateBuildsAVerifiableResult() {
        val r = UpdateCheckerManager.evaluate(latest, "1.0.0", listOf("arm64-v8a"))!!
        assertTrue(r.hasUpdate)
        assertEquals("1.1.0", r.latestVersion)
        assertEquals(AppConfig.ETHA_DOWNLOAD_BASE + "EthaVPN_1.1.0_arm64-v8a.apk", r.downloadUrl)
        assertEquals("ab" + "cd".repeat(31), r.sha256)      // lower-cased for the comparison
        assertEquals(27_000_000L, r.size)
        assertTrue(r.mandatory)                              // 1.0.0 < min_supported 1.0.5
        assertEquals("faster", r.releaseNotes)
        val optional = UpdateCheckerManager.evaluate(latest, "1.0.5", listOf("arm64-v8a"))!!
        assertTrue(optional.hasUpdate)
        assertFalse(optional.mandatory)
    }

    @Test
    fun evaluateSaysNoWhenCurrentOrNewer() {
        assertFalse(UpdateCheckerManager.evaluate(latest, "1.1.0", listOf("arm64-v8a"))!!.hasUpdate)
        assertFalse(UpdateCheckerManager.evaluate(latest, "1.2.0", listOf("arm64-v8a"))!!.hasUpdate)
    }

    @Test
    fun evaluateRejectsAnUnusableFile() {
        assertNull(UpdateCheckerManager.evaluate(LatestRelease(), "1.0.0", listOf("arm64-v8a")))
        assertNull(UpdateCheckerManager.evaluate(latest.copy(assets = listOf(arm)), "1.0.0", listOf("x86")))
    }
}
