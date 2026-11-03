package com.v2ray.ang.handler

/**
 * What Auto measured, for the box: the header X-Etha-Lines on the next subscription fetch (the API stores it as one
 * row of line_reports; the panel shows it — the customer sees no line name). Pure; covered by JVM unit tests.
 *
 *     v=1;t=<epoch s>;trig=<connect|first|auto|failover|manual|network|search>;use=<label>;sw=<0|1>;r=<kbps>;l=<label>:<ms>,<label>:-1,…;c=<label>:<ms>,…;p=<label>:<got>/<want>/<ms>/<why>,…
 *
 * `r` (2.1): the best five-second download rate seen on the line in use during the pass, in kbps, when any (the passive
 * throughput figure for the box's ranking; a latency probe cannot see a slow line). `c` (2.1.1): the connect time of each
 * line that was measured — its first request, handshakes and all — beside `l`'s warm round trip; only lines with one. `p` (2.1.2):
 * the pulls since the last report — `want` KB of the box's blob asked through the line, `got` the KB that came, `ms` how long it took
 * or ran, `why` c (at Connect) | s (a stall whose HEAD passed) | w (before a switch) | h (the box called the line dead); at most
 * MAX_PULLS, the newest kept.
 *
 * A label is the line's name without its "EthaVPN-" prefix (REALITY-relay-FRA9-443, XHTTP-api-CleanIP1-443); a line
 * named otherwise is left out. ms -1 = no answer. The header stays under 4 KB (at most 64 lines).
 */
object EthaReport {
    const val PREFIX = "EthaVPN-"
    const val MAX_LINES = 64
    const val MAX_PULLS = 16

    /** One pull (2.1.2): the line's label, the KB that came, the KB asked, the time, why. */
    data class Pull(val label: String, val got: Int, val want: Int, val ms: Long, val why: String)
    private val LABEL = Regex("[A-Za-z0-9._-]{1,48}")

    /** The line's label for the box, or null when the line is not one of ours. */
    fun label(remarks: String?): String? {
        val r = remarks?.trim() ?: return null
        if (!r.startsWith(PREFIX)) return null
        val l = r.removePrefix(PREFIX)
        return if (LABEL.matches(l)) l else null
    }

    /**
     * The header. `results`: (remarks, ms) in the subscription's order, -1 failed, 0 not measured (left out);
     * null when nothing measured was one of our lines.
     */
    fun build(nowMs: Long, trigger: String, inUseRemarks: String?, switched: Boolean, results: List<Pair<String, Long>>, rateKbps: Long = 0L,
              colds: Map<String, Long> = emptyMap(), pulls: List<Pull> = emptyList()): String? {
        val items = results.mapNotNull { (remarks, ms) ->
            val l = label(remarks) ?: return@mapNotNull null
            if (ms == 0L) null else "$l:${if (ms < 0) -1 else ms}"
        }.take(MAX_LINES)
        if (items.isEmpty()) return null
        val named = items.map { it.substringBeforeLast(':') }.toSet()
        val cold = results.mapNotNull { (remarks, _) ->
            val l = label(remarks) ?: return@mapNotNull null
            val c = colds[remarks] ?: return@mapNotNull null
            if (l in named && c > 0) "$l:$c" else null
        }
        val use = label(inUseRemarks).orEmpty()
        val rate = if (rateKbps > 0) ";r=$rateKbps" else ""
        val c = if (cold.isEmpty()) "" else ";c=${cold.joinToString(",")}"
        val p = pulls.filter { LABEL.matches(it.label) && it.want > 0 }.takeLast(MAX_PULLS)
            .joinToString(",") { "${it.label}:${maxOf(0, it.got)}/${it.want}/${maxOf(0L, it.ms)}/${it.why.take(1).ifEmpty { "o" }}" }
            .let { if (it.isEmpty()) "" else ";p=$it" }
        return "v=1;t=${nowMs / 1000};trig=$trigger;use=$use;sw=${if (switched) 1 else 0}$rate;l=${items.joinToString(",")}$c$p"
    }

    /** What identifies a line to the running core: its address, port, name and user id — not Cloudflare's ECH
     *  configuration of the hour, which rides in the share link and would make every refresh look like new lines. */
    data class LineKey(val server: String?, val port: String?, val remarks: String?, val userId: String?)

    /** One text for a set of lines: the same set → the same text, whatever the ids; a new user id (the box's
     *  "new user ID") is a change — the running core must take it. */
    fun fingerprint(lines: List<LineKey>): String =
        lines.map { "${it.server.orEmpty()}:${it.port.orEmpty()}:${it.remarks.orEmpty()}:${it.userId.orEmpty()}" }.sorted().joinToString("|")
}
