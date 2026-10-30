package com.v2ray.ang.handler

/**
 * What is known of each line on this network (the same shape as the iOS app's LineHealth): how often it failed in a
 * row, when it may be tried again (the retry doubles from failedRetrySec at each failure up to retryCapSec), its last
 * good time. Keyed by the line's label, which is the same across fetches and across both apps; kept per network across
 * sessions (MMKV, one text per network signature), so an MCI phone that learnt last night that no Cloudflare line
 * passes does not spend its first minute today finding out again, and dead lines fade from the schedule instead of
 * being tried every quarter hour forever. Wall-clock seconds, coarse on purpose.
 */
class LineHealth {
    data class Entry(var fails: Int = 0, var retryAt: Long? = null, var lastMs: Long? = null, var okAt: Long? = null)

    val entries = HashMap<String, Entry>()

    fun ok(id: String, ms: Long, nowSec: Long) {
        val e = entries.getOrPut(id) { Entry() }
        e.fails = 0; e.retryAt = null; e.lastMs = ms; e.okAt = nowSec
    }

    fun failed(id: String, nowSec: Long, policy: AutoPolicy) {
        val e = entries.getOrPut(id) { Entry() }
        e.fails += 1
        val wait = minOf(policy.failedRetrySec.toDouble() * Math.pow(2.0, (e.fails - 1).toDouble()), policy.retryCapSec.toDouble())
        e.retryAt = nowSec + wait.toLong()
    }

    /** May the line be probed now? A line never seen, or whose retry time has come. */
    fun due(id: String, nowSec: Long): Boolean {
        val r = entries[id]?.retryAt ?: return true
        return nowSec >= r
    }

    fun isDead(id: String): Boolean = (entries[id]?.fails ?: 0) > 0
    fun lastMs(id: String): Long? = entries[id]?.lastMs

    /** Every line forgiven: tried again at the next pass (a network change: what failed may pass now). */
    fun forgive() {
        for (e in entries.values) { e.retryAt = null; e.fails = 0 }
    }

    /** The lines in the order the trickle should take them: known good by their last time, then never seen, then the
     *  failed — the subscription's order among equals. */
    fun bestFirst(lines: List<String>): List<String> {
        val indexed = lines.withIndex().toList()
        val good = indexed.filter { !isDead(it.value) && lastMs(it.value) != null }.sortedWith(compareBy({ lastMs(it.value)!! }, { it.index }))
        val unseen = indexed.filter { !isDead(it.value) && lastMs(it.value) == null }
        val dead = indexed.filter { isDead(it.value) }
        return (good + unseen + dead).map { it.value }
    }

    /** Nothing answers: every line is either held as failed here or failed its last probe this session ([result] -1), and the
     *  line in use is among the failed. The moment Searching begins — a line waiting its retry counts as out, so backoff can
     *  never hide a dead network behind "not probed yet". */
    fun allOut(labels: List<String>, current: String, result: (String) -> Long): Boolean {
        if (labels.isEmpty() || result(current) >= 0) return false
        return labels.all { result(it) < 0 || isDead(it) }
    }

    /** The best line known good right now, other than [except]; null when none is known good. */
    fun bestKnown(lines: List<String>, except: String? = null): String? =
        bestFirst(lines).firstOrNull { it != except && !isDead(it) && lastMs(it) != null }

    /** One text for the store: "label|fails|retryAt|lastMs|okAt;…" (a missing number is empty). */
    fun encode(): String = entries.entries.sortedBy { it.key }.joinToString(";") { (k, e) ->
        "$k|${e.fails}|${e.retryAt ?: ""}|${e.lastMs ?: ""}|${e.okAt ?: ""}"
    }

    companion object {
        private val LABEL = Regex("[A-Za-z0-9._-]{1,48}")

        fun decode(text: String?): LineHealth {
            val h = LineHealth()
            if (text.isNullOrBlank()) return h
            for (item in text.split(';')) {
                val f = item.split('|')
                if (f.size != 5 || !LABEL.matches(f[0])) continue
                h.entries[f[0]] = Entry(f[1].toIntOrNull() ?: 0, f[2].toLongOrNull(), f[3].toLongOrNull(), f[4].toLongOrNull())
            }
            return h
        }
    }
}
