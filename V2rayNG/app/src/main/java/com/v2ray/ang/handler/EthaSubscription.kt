package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.net.URI

/**
 * The EthaVPN subscription: what the service's /sub/<token> link looks like, what its response
 * headers say (Subscription-Userinfo, Profile-Title, Profile-Update-Interval, Announce,
 * Support-Url, Profile-Web-Page-Url) and where that lands on the SubscriptionItem.
 *
 * Server semantics, kept exactly: total = 0 means unlimited data, expire = 0 means no expiry;
 * -1 on the item means the header was absent. A malformed header never discards a profile.
 * The pure functions here have no Android dependency and are covered by JVM unit tests.
 */
object EthaSubscription {

    data class UserInfo(val upload: Long, val download: Long, val total: Long, val expire: Long)

    /** `upload=0; download=123; total=0; expire=0` → UserInfo (absent fields -1); nothing usable → null. */
    fun parseUserInfo(header: String?): UserInfo? {
        if (header.isNullOrBlank()) return null
        var up = -1L; var down = -1L; var total = -1L; var expire = -1L; var any = false
        header.split(';', ',').forEach { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) return@forEach
            val v = kv[1].trim().toDoubleOrNull()?.toLong() ?: return@forEach
            when (kv[0].trim().lowercase()) {
                "upload" -> { up = v; any = true }
                "download" -> { down = v; any = true }
                "total" -> { total = v; any = true }
                "expire" -> { expire = v; any = true }
            }
        }
        return if (any) UserInfo(up, down, total, expire) else null
    }

    /** `base64:…` (how the API sends a title or a notice) or plain text; blank or undecodable → null. */
    fun decodeHeaderText(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (!v.startsWith("base64:", ignoreCase = true)) return v
        val decoded = base64Decode(v.substring(7).trim()) ?: return null
        return String(decoded, Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** Profile-Update-Interval is hours; the app schedules minutes, never under its minimum. */
    fun updateIntervalMinutes(header: String?): Long? {
        val hours = header?.trim()?.toLongOrNull() ?: return null
        if (hours <= 0) return null
        return maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, hours * 60)
    }

    /** Days left for the account card: null = unknown, Long.MAX_VALUE = no expiry, else whole days (rounded up, never negative). */
    fun daysLeft(expire: Long, nowSec: Long = System.currentTimeMillis() / 1000): Long? = when {
        expire < 0 -> null
        expire == 0L -> Long.MAX_VALUE
        else -> maxOf(0L, (expire - nowSec + 86399) / 86400)
    }

    /** Whether a subscription fetched at `lastUpdated` (ms; ≤ 0 = never) is due for a quiet refresh. */
    fun isStale(lastUpdated: Long, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = AppConfig.ETHA_SUB_STALE_MS): Boolean =
        lastUpdated <= 0L || nowMs - lastUpdated >= maxAgeMs

    /** An EthaVPN link: https, the link address (ETHA_SUB_HOSTS), exactly /sub/<token> (a fragment is fine, it names the profile). */
    fun isSubLink(text: String?): Boolean = linkToken(text, AppConfig.ETHA_SUB_HOSTS) != null

    /** The account token of one of our links (what stays when an address changes), else null. */
    fun tokenOf(url: String?): String? = linkToken(url, AppConfig.ETHA_SUB_HOSTS)

    /** The token of an https://<one of hosts>/sub/<token> link (a fragment is fine), else null. */
    private fun linkToken(text: String?, hosts: List<String>): String? {
        val uri = try { URI(text?.trim() ?: return null) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (hosts.none { it.equals(host, ignoreCase = true) }) return null
        val path = uri.path ?: return null
        if (!path.startsWith(AppConfig.ETHA_SUB_PATH)) return null
        val token = path.substring(AppConfig.ETHA_SUB_PATH.length)
        return if (token.length >= 8 && token.none { it == '/' }) token else null
    }

    /**
     * A link on an earlier address (ETHA_OLD_SUB_HOSTS) on the only one the app uses:
     * https://<ETHA_SUB_HOST>/sub/<the same token>, the name after '#' kept. Anything else: null.
     * The service answers every token on every address, so it is the same account.
     */
    fun migratedUrl(url: String?): String? {
        val token = linkToken(url, AppConfig.ETHA_OLD_SUB_HOSTS) ?: return null
        val name = url!!.trim().substringAfter('#', "")
        val next = "https://${AppConfig.ETHA_SUB_HOST}${AppConfig.ETHA_SUB_PATH}$token"
        return if (name.isEmpty()) next else "$next#$name"
    }

    /** Whether two links name the same account, whatever address or name either carries. */
    fun sameAccount(a: String?, b: String?): Boolean {
        val ta = tokenOf(migratedUrl(a) ?: a) ?: return false
        return ta == tokenOf(migratedUrl(b) ?: b)
    }

    // Direction and joining marks a right-to-left message leaves around a copied link: not spaces, never part of a link
    private const val INVISIBLE = "​‌‍‎‏‪‫‬‭‮⁦⁧⁨⁩﻿"

    /** The link inside pasted text (a Telegram message adds words, punctuation and invisible marks around it); an earlier address comes back on the current one. */
    fun extractSubLink(text: String?): String? =
        text?.split(Regex("\\s+"))
            ?.map { piece -> piece.filterNot { it in INVISIBLE }.trim().trimEnd('.', ',', ')', ']', '؛', '،') }
            ?.firstNotNullOfOrNull { if (isSubLink(it)) it else migratedUrl(it) }

    /** Copies what the headers say onto the item. Returns true when at least one known header was present. */
    fun applyHeaders(sub: SubscriptionItem, headers: Map<String, String>): Boolean {
        var seen = false
        parseUserInfo(headers["subscription-userinfo"])?.let {
            sub.upload = it.upload; sub.download = it.download; sub.total = it.total; sub.expire = it.expire; seen = true
        }
        if (headers.containsKey("profile-title")) { sub.profileTitle = decodeHeaderText(headers["profile-title"]); seen = true }
        if (headers.containsKey("announce")) { sub.announce = decodeHeaderText(headers["announce"]); seen = true }
        if (headers.containsKey("support-url")) { sub.supportUrl = headers["support-url"]?.trim()?.ifEmpty { null }; seen = true }
        if (headers.containsKey("profile-web-page-url")) { sub.webPageUrl = headers["profile-web-page-url"]?.trim()?.ifEmpty { null }; seen = true }
        updateIntervalMinutes(headers["profile-update-interval"])?.let { minutes ->
            if (sub.updateInterval != minutes) sub.updateInterval = minutes
            sub.autoUpdate = true
            seen = true
        }
        if (seen) sub.infoUpdated = System.currentTimeMillis()
        return seen
    }

    // ---------------------------------------------------------------- storage-backed (Android)

    /** The EthaVPN subscription if there is one, else the first enabled subscription (the app also works as a plain client). */
    fun find(): SubscriptionCache? {
        val subs = MmkvManager.decodeSubscriptions()
        return subs.firstOrNull { isSubLink(it.subscription.url) && it.subscription.enabled }
            ?: subs.firstOrNull { it.subscription.enabled }
    }

    /**
     * Every stored link on an earlier address moves to the current one (migratedUrl), then copies of one
     * account fold into one (mergeDuplicates). Home runs it whenever it looks for the subscription, so a
     * phone moves when it first opens this version (a fetch moves its own link too). True when something changed.
     */
    fun migrateAll(): Boolean {
        var changed = false
        MmkvManager.decodeSubscriptions().forEach { s ->
            migratedUrl(s.subscription.url)?.let { next ->
                s.subscription.url = next
                MmkvManager.encodeSubscription(s.guid, s.subscription)
                changed = true
            }
        }
        return mergeDuplicates() || changed
    }

    /**
     * One subscription per account: a phone that holds the old and the new link of the same account
     * (the new one tapped beside the old before this version) keeps the one with the selected server,
     * else the first; the others go with their servers. Never call it inside a loop over the
     * subscriptions. True when something changed.
     */
    fun mergeDuplicates(): Boolean {
        var changed = false
        MmkvManager.decodeSubscriptions()
            .filter { tokenOf(it.subscription.url) != null }
            .groupBy { tokenOf(it.subscription.url) }
            .values.filter { it.size > 1 }
            .forEach { same ->
                val keep = same.firstOrNull { selectedIsIn(it.guid) } ?: same.first()
                same.filter { it.guid != keep.guid }.forEach {
                    SubscriptionUpdater.cancelOne(subId = it.guid)
                    MmkvManager.removeSubscription(it.guid)
                }
                changed = true
            }
        return changed
    }

    /**
     * "Delete account": every subscription with its servers and test results, the chosen line
     * and the account preferences go; the deleted link is remembered so the clipboard import
     * does not put it straight back (a tap on the link in Telegram, Paste or a QR code still
     * does). Language and expert mode are the phone's, not the account's, and stay.
     */
    fun deleteAll() {
        val subs = MmkvManager.decodeSubscriptions()
        val link = subs.map { it.subscription.url }.firstOrNull { isSubLink(it) } ?: ""
        subs.forEach {
            SubscriptionUpdater.cancelOne(subId = it.guid)
            MmkvManager.removeSubscription(it.guid)
        }
        MmkvManager.removeAllServer()
        MmkvManager.setSelectServer("")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_TEST, 0L)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_DELETED_LINK, link)
    }

    /** Whether the selected server belongs to this subscription. */
    fun selectedIsIn(subId: String): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        return MmkvManager.decodeServerList(subId).contains(guid)
    }

    // ---------------------------------------------------------------- a tiny base64 decoder
    // (java.util.Base64 needs API 26 and android.util.Base64 is not there in JVM tests)
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Decode(input: String): ByteArray? {
        val clean = input.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
