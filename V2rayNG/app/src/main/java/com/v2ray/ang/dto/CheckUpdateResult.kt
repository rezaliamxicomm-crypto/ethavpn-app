package com.v2ray.ang.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false,
    val sha256: String? = null,        // of the APK at downloadUrl (latest.json); null = unknown (GitHub fallback)
    val fileName: String? = null,
    val size: Long = 0,
    val mandatory: Boolean = false     // the running version is below latest.json's min_supported
)