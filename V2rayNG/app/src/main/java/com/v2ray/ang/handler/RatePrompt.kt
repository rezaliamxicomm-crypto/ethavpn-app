package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * When the app asks for a store rating by itself (Settings always has "Rate SkyRay"). Counted in
 * connections that worked — the customer tapped Connect and the probe came back with a time —
 * because a rating is asked for when the service has just done its job, and in Iran the store
 * opens only through the VPN.
 *
 * The first ask comes with the 5th such connection, three days or more after the first one;
 * after "Not now" the next comes ten connections and two weeks later; three asks at most, and
 * none once the customer went to the store (no store tells an app whether a rating was left).
 * Only the Google Play build asks: an install from the direct APK has no Play record to rate.
 */
object RatePrompt {
    const val FIRST_CONNECTS = 5
    const val FIRST_DAYS = 3
    const val AGAIN_CONNECTS = 10
    const val AGAIN_DAYS = 14
    const val MAX_ASKS = 3
    private const val DAY_MS = 86_400_000L

    data class State(
        val connects: Int = 0, val firstAt: Long = 0L, val asks: Int = 0,
        val lastAskAt: Long = 0L, val lastAskConnects: Int = 0, val done: Boolean = false
    )

    /** Whether to ask now (pure; a clock set back just waits). */
    fun due(s: State, now: Long): Boolean = when {
        s.done || s.asks >= MAX_ASKS || s.firstAt <= 0L -> false
        s.asks == 0 -> s.connects >= FIRST_CONNECTS && now - s.firstAt >= FIRST_DAYS * DAY_MS
        else -> s.connects - s.lastAskConnects >= AGAIN_CONNECTS && now - s.lastAskAt >= AGAIN_DAYS * DAY_MS
    }

    // ---------------------------------------------------------------- storage-backed (Android)

    fun state() = State(
        MmkvManager.decodeSettingsInt(AppConfig.PREF_ETHA_RATE_CONNECTS, 0),
        MmkvManager.decodeSettingsLong(AppConfig.PREF_ETHA_RATE_FIRST_AT, 0L),
        MmkvManager.decodeSettingsInt(AppConfig.PREF_ETHA_RATE_ASKS, 0),
        MmkvManager.decodeSettingsLong(AppConfig.PREF_ETHA_RATE_LAST_ASK_AT, 0L),
        MmkvManager.decodeSettingsInt(AppConfig.PREF_ETHA_RATE_LAST_ASK_CONNECTS, 0),
        MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_RATE_DONE, false)
    )

    /** One more connection that worked. True when this is the moment to ask (the Play build only). */
    fun connected(now: Long = System.currentTimeMillis()): Boolean {
        val s = state()
        if (s.done || s.asks >= MAX_ASKS) return false   // nothing left to count for
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_CONNECTS, s.connects + 1)
        if (s.firstAt <= 0L) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_FIRST_AT, now)
        return Updates.isPlay() && due(state(), now)
    }

    /** The app asked: the next ask is counted from here. */
    fun asked(now: Long = System.currentTimeMillis()) {
        val s = state()
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_ASKS, s.asks + 1)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_LAST_ASK_AT, now)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_LAST_ASK_CONNECTS, s.connects)
    }

    /** The customer went to the store (from the ask or from Settings): never asked again. */
    fun done() {
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_RATE_DONE, true)
    }
}
