package com.v2ray.ang.handler

/**
 * The box's advice for this subscription's lines on the phone's network — the header X-Etha-Hint of the fetch (SkyRay
 * 2.1; the API's app/ranking.py), signed with the answer; the same shape as the iOS app's Hint:
 *
 *     v=1;o=<label>,<label>,…;d=<label>,…;out=0|1;n=<phones>
 *
 * [order]: the lines to try first, as the phones of this network found them (tiers of equals spread by the token);
 * [dead]: the lines nobody there reaches; [outage]: every phone of the network is failing on every line — "it is not
 * you"; [phones]: how many reported. Advice, never an order: the phone's own memory of the network wins where it has
 * one, the dead lines are probed last rather than skipped, and every line is verified by the phone's own probes.
 */
data class Hint(val order: List<String> = emptyList(), val dead: Set<String> = emptySet(), val outage: Boolean = false, val phones: Int = 0) {
    /** [labels] (this subscription's, in its order) arranged by the hint: the advised order first, the rest in their own
     *  order, the dead last. A label the hint does not know keeps its place among the rest. */
    fun arrange(labels: List<String>): List<String> {
        val advised = order.filter { it in labels && it !in dead }
        val rest = labels.filter { it !in advised && it !in dead }
        val last = labels.filter { it in dead }
        return advised + rest + last
    }

    companion object {
        const val MAX_LENGTH = 4096
        private val LABEL = Regex("[A-Za-z0-9._-]{1,48}")

        /** The header → the hint; null when it is not one (another version, too long, no fields worth keeping). */
        fun parse(raw: String?): Hint? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.length > MAX_LENGTH) return null
            val fields = HashMap<String, String>()
            for (part in text.split(';')) {
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) continue
                fields[kv[0].trim().lowercase()] = kv[1].trim()
            }
            if (fields["v"] != "1") return null
            val h = Hint(labels(fields["o"]), labels(fields["d"]).toSet(), fields["out"] == "1", fields["n"]?.toIntOrNull() ?: 0)
            return if (h.order.isEmpty() && h.dead.isEmpty() && !h.outage) null else h
        }

        private fun labels(list: String?): List<String> {
            val out = ArrayList<String>()
            for (item in list.orEmpty().split(',')) {
                val l = item.trim()
                if (LABEL.matches(l) && l !in out) out.add(l)
            }
            return out
        }
    }
}
