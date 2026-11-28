package com.v2ray.ang.handler

/**
 * The failover's backups (3.1.0) — pure, the same in both apps. When the line in use stalls it is tested together with up
 * to three other lines at once (until 3.0.4: with one, then one more). A filter that takes a block of addresses takes
 * every line in it, so two lines of one block are one bet: the three are taken from ranges the line in use is not in and
 * that differ from each other, as far as the phone holds such lines; and one of them is of the other kind (a Cloudflare
 * line beside relay lines, or the reverse) when the phone holds one, because a network can also drop a whole kind.
 */
object Backups {
    /** One line: its id, its server address as the profile holds it, its label (EthaReport.label). */
    data class Line(val id: String, val server: String?, val label: String?)

    /** The line's range: an IPv4 address's /24, an IPv6 address's /64, a name as it is; prefixed with its kind, so a
     *  Cloudflare address and a relay address are never one range. */
    fun range(line: Line): String {
        val s = line.server?.trim()?.lowercase().orEmpty()
        val v4 = s.split('.')
        val net = when {
            v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } } -> v4.take(3).joinToString(".")
            s.contains(':') -> s.trim('[', ']').split(':').take(4).joinToString(":")
            else -> s
        }
        return kind(line) + "/" + net
    }

    /** relay (Reality through a relay) or cf (a line through Cloudflare); "" when the line is not one of ours. */
    fun kind(line: Line): String {
        val l = line.label.orEmpty()
        return when {
            l.startsWith("REALITY-") -> "relay"
            l.startsWith("WS-") || l.startsWith("XHTTP-") -> "cf"
            else -> ""
        }
    }

    /**
     * Up to [count] ids for [current]. [preferred]: the other lines worth trying, best first; [fill]: the ones held as
     * failed or called dead by the box, taken only when the preferred do not make the count. First the preferred whose
     * range is not taken yet (the line in use's is taken), then the rest of the preferred in their order, then the fill
     * (again a new range first). Then, when every one picked is of the line in use's kind and a preferred line of
     * another kind is at hand, it takes the last place.
     */
    fun pick(current: Line, preferred: List<Line>, count: Int = 3, fill: List<Line> = emptyList()): List<String> {
        if (count <= 0) return emptyList()
        val pref = preferred.filter { it.id != current.id }.distinctBy { it.id }
        val rest = fill.filter { it.id != current.id && pref.none { p -> p.id == it.id } }.distinctBy { it.id }
        val taken = HashSet<String>().apply { add(range(current)) }
        val picked = ArrayList<Line>()
        for (group in listOf(pref, rest)) {
            for (l in group) {
                if (picked.size >= count) break
                if (l !in picked && taken.add(range(l))) picked.add(l)
            }
            for (l in group) {
                if (picked.size >= count) break
                if (l !in picked) picked.add(l)
            }
        }
        val mine = kind(current)
        if (picked.isNotEmpty() && picked.all { kind(it) == mine }) {
            val other = pref.firstOrNull { kind(it) != mine && it !in picked }
            if (other != null) {
                if (picked.size >= count) picked[picked.size - 1] = other else picked.add(other)
            }
        }
        return picked.map { it.id }
    }
}
