package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.LatestRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a new version comes from. First the service's own `/dl/latest.json` (the host the
 * subscription link lives on, reachable wherever the link is — direct, then through the local
 * proxy when the tunnel is up), which names the APK per ABI with its sha256 so the app can
 * verify what it installs. GitHub's releases API is the fallback only.
 */
object UpdateCheckerManager {

    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        fromLatestJson() ?: fromGitHub(includePreRelease)
    }

    private fun fetch(url: String, timeout: Int = 5000): String? {
        var response = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = timeout))
        if (response.isNullOrEmpty()) {
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = timeout,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword()
                )
            )
        }
        return response
    }

    private fun fromLatestJson(): CheckUpdateResult? {
        val text = fetch(AppConfig.ETHA_LATEST_URL) ?: return null
        val latest = JsonUtil.fromJsonSafe(text, LatestRelease::class.java) ?: return null
        return evaluate(latest, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
    }

    /** Pure: what latest.json means for a running version on a device with these ABIs (null = unusable file). */
    fun evaluate(latest: LatestRelease, currentVersion: String, abis: List<String>): CheckUpdateResult? {
        if (latest.version.isBlank() || latest.assets.isEmpty()) return null
        if (compareVersions(latest.version, currentVersion) <= 0) return CheckUpdateResult(hasUpdate = false)
        val asset = pickAsset(latest.assets, abis) ?: return null
        val mandatory = latest.minSupported?.let { compareVersions(it, currentVersion) > 0 } ?: false
        return CheckUpdateResult(
            hasUpdate = true,
            latestVersion = latest.version,
            releaseNotes = latest.notes.orEmpty(),
            downloadUrl = AppConfig.ETHA_DOWNLOAD_BASE + asset.name,
            sha256 = asset.sha256.lowercase(),
            fileName = asset.name,
            size = asset.size,
            mandatory = mandatory
        )
    }

    /** The first asset matching the device's ABIs in preference order, else the universal one. */
    fun pickAsset(assets: List<LatestRelease.Asset>, abis: List<String>): LatestRelease.Asset? {
        for (abi in abis) {
            assets.firstOrNull { it.abi.equals(abi, ignoreCase = true) }?.let { return it }
        }
        return assets.firstOrNull { it.abi.equals("universal", ignoreCase = true) }
    }

    private fun fromGitHub(includePreRelease: Boolean): CheckUpdateResult {
        val url = if (includePreRelease) AppConfig.APP_API_URL else AppConfig.APP_API_URL.concatUrl("latest")
        val response = fetch(url) ?: throw IllegalStateException("Failed to get response")
        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        } ?: return CheckUpdateResult(hasUpdate = false)

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(AppConfig.TAG, "Found version: $latestVersion (current: ${BuildConfig.VERSION_NAME})")
        return if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val abi = Build.SUPPORTED_ABIS[0]
            val asset = latestRelease.assets.firstOrNull { it.name.contains(abi, true) }
                ?: latestRelease.assets.firstOrNull { it.name.contains("universal", true) }
                ?: throw IllegalStateException("No compatible APK found")
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = asset.browserDownloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    /** Numeric, dot-separated; a missing or non-numeric part counts as 0 ("1.2" == "1.2.0", "v" prefixes are stripped). */
    fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.trim().removePrefix("v").split(".")
        val v2 = version2.trim().removePrefix("v").split(".")
        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val num2 = v2.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }
}
