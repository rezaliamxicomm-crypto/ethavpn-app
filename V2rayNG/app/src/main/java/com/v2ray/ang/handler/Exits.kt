package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig

/**
 * The Servers box (3.0.0): where this phone's traffic leaves the network — a country the box offers (the header X-Etha-Exits:
 * `code|name|country|state|rtt|probe;…`), chosen on the phone and sent to the box on the next fetch (X-Etha-Exit-Set), the
 * box's word of the choice coming back as X-Etha-Exit. Germany is the box itself; every other country is a second hop the
 * box routes this phone's own user through, so a choice needs no new lines and no reconnect — new connections take it.
 * The latencies are measured, never computed: a HEAD to each country's probe address through the line in use
 * (EthaAuto.measureExits), kept here for the sheet before the next measurement. The pure parts are covered by JVM tests.
 */
object Exits {
    data class Exit(val code: String, val name: String, val country: String, val state: String, val rtt: Int, val probe: String) {
        /** A second hop (a card), not the box itself. */
        val routed: Boolean get() = probe.isNotEmpty() && probe != AppConfig.ETHA_PROBE_BOX_IP
        val ok: Boolean get() = state == "ok"
    }

    private val CODE = Regex("^[a-z]{2}[a-z0-9]{0,6}$")

    /** Pure: the header's value → the countries, in the box's order; a bad item is skipped. */
    fun parse(raw: String?): List<Exit> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Exit>()
        for (item in raw.split(";")) {
            val f = item.trim().split("|")
            if (f.size < 5 || !CODE.matches(f[0])) continue
            out.add(Exit(f[0], f[1].trim().ifEmpty { f[0] }, f[2].trim().uppercase().take(2), f[3].trim().lowercase(), f[4].trim().toIntOrNull() ?: 0, f.getOrNull(5)?.trim().orEmpty()))
        }
        return out
    }

    /** Pure: a two-letter country as its flag (regional indicators: "US" → 🇺🇸); the globe for anything else. */
    fun flag(country: String): String {
        val c = country.trim().uppercase()
        if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "🌐"
        return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Pure: `android=3.0.0;ios=3.0.0` → this platform's lowest version, null when none. */
    fun minVersion(raw: String?, platform: String = "android"): String? =
        raw?.split(";")?.map { it.trim().split("=", limit = 2) }?.firstOrNull { it.size == 2 && it[0] == platform && Regex("^\\d+\\.\\d+\\.\\d+$").matches(it[1]) }?.get(1)

    /** Pure: the device limit's signed 403 body (`{"detail":"devices","limit":2,"active":3}`) → (limit, active); null for any other body. */
    fun devicesRefusal(body: String?): Pair<Int, Int>? {
        if (body == null || !Regex("\"detail\"\\s*:\\s*\"devices\"").containsMatchIn(body)) return null
        val limit = Regex("\"limit\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val active = Regex("\"active\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return limit to active
    }

    /** Pure: "code=ms,…" → the map; "code=ip,…" the same shape (the measurement's request). */
    fun pairs(raw: String?): Map<String, String> =
        raw.orEmpty().split(",").mapNotNull { it.trim().split("=", limit = 2).takeIf { p -> p.size == 2 && p[0].isNotEmpty() } }.associate { it[0] to it[1] }

    // ---- the store
    fun stored(): List<Exit> = parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_EXITS))
    fun offered(): List<Exit> = stored().filter { it.state != "off" }
    fun byCode(code: String): Exit? = stored().firstOrNull { it.code == code }
    /** The box itself among the countries (its probe address): the control beside a card's probe — a dead line fails both, a dead card only its own. */
    fun controlCode(): String? = stored().firstOrNull { it.probe == AppConfig.ETHA_PROBE_BOX_IP }?.code
    /** The box's word of this phone's choice ("" = Auto). */
    fun current(): String = MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_EXIT).orEmpty()
    /** A choice the box has not confirmed yet ("auto" for none), else null. */
    fun want(): String? = MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_EXIT_WANT)?.takeIf { it.isNotEmpty() }
    /** What the box shows: the pending choice, else the box's word. */
    fun shown(): String = want()?.let { if (it == "auto") "" else it } ?: current()
    fun choose(code: String) = MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT_WANT, code.ifEmpty { "auto" })

    /** The fetch's answer: the list, the box's word of the choice, the lowest app version (absent = none). A pending choice ends with the
     *  answer to the fetch that carried it (`carried`): confirmed or not, the box's word stands — a choice the box could not apply (a country
     *  turned off meanwhile, a phone on the shared id) is not shown as if it had been. */
    fun remember(headers: Map<String, String>, carried: String? = null) {
        val list = headers[AppConfig.ETHA_EXITS_HEADER]?.trim().orEmpty()
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXITS, if (parse(list).isNotEmpty()) list else "")
        val mine = headers[AppConfig.ETHA_EXIT_HEADER]?.trim().orEmpty()
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT, mine)
        val w = want()
        if (w != null && settled(w, mine, carried)) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT_WANT, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_APP_MIN, headers[AppConfig.ETHA_APP_MIN_HEADER]?.trim().orEmpty())
    }

    /** Pure: a pending choice is settled by the box's confirmation (`auto` by an absent word), or by the answer to the fetch that carried it. */
    fun settled(want: String, mine: String, carried: String?): Boolean = (if (want == "auto") mine.isEmpty() else mine == want) || carried == want

    /** Pure: `version` below `min` — numeric, dot-separated, a missing part 0, a suffix ignored. */
    fun isBelow(version: String, min: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(version); val b = parts(min)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrNull(i) ?: 0; val y = b.getOrNull(i) ?: 0; if (x != y) return x < y }
        return false
    }

    /** The last measured latencies, code → ms (-1 failed), for the sheet before the next measurement. */
    fun measured(): Map<String, Long> = pairs(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_EXIT_MS)).mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap()
    fun rememberMeasured(fresh: Map<String, Long>) {
        val all = measured().toMutableMap(); all.putAll(fresh)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT_MS, all.entries.joinToString(",") { "${it.key}=${it.value}" })
    }

    /** The update screen is due: the box's lowest Android version is above this build (X-Etha-App-Min). */
    fun updateDue(): Boolean = minVersion(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_APP_MIN))?.let { isBelow(BuildConfig.VERSION_NAME, it) } ?: false
}
