package com.v2ray.ang.handler

/**
 * What Auto measured, for the box: the header X-Etha-Lines on the next subscription fetch (the API stores it as one
 * row of line_reports; the panel shows it — the customer sees no line name). Pure; covered by JVM unit tests.
 *
 *     v=1;t=<epoch s>;trig=<connect|first|auto|failover|manual|network|search>;use=<label>;sw=<0|1>;r=<kbps>;l=<label>:<ms>,<label>:-1,…
 *
 * `r` (2.1): the best five-second download rate seen on the line in use during the pass, in kbps, when any (the passive
 * throughput figure for the box's ranking; a latency probe cannot see a slow line).
 *
 * A label is the line's name without its "EthaVPN-" prefix (REALITY-relay-FRA9-443, XHTTP-api-CleanIP1-443); a line
 * named otherwise is left out. ms -1 = no answer. The header stays under 4 KB (at most 64 lines).
 */
object EthaReport {
    const val PREFIX = "EthaVPN-"
    const val MAX_LINES = 64
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
    fun build(nowMs: Long, trigger: String, inUseRemarks: String?, switched: Boolean, results: List<Pair<String, Long>>, rateKbps: Long = 0L): String? {
        val items = results.mapNotNull { (remarks, ms) ->
            val l = label(remarks) ?: return@mapNotNull null
            if (ms == 0L) null else "$l:${if (ms < 0) -1 else ms}"
        }.take(MAX_LINES)
        if (items.isEmpty()) return null
        val use = label(inUseRemarks).orEmpty()
        val rate = if (rateKbps > 0) ";r=$rateKbps" else ""
        return "v=1;t=${nowMs / 1000};trig=$trigger;use=$use;sw=${if (switched) 1 else 0}$rate;l=${items.joinToString(",")}"
    }

    /** What identifies a line to the running core: its address, port, name and user id — not Cloudflare's ECH
     *  configuration of the hour, which rides in the share link and would make every refresh look like new lines. */
    data class LineKey(val server: String?, val port: String?, val remarks: String?, val userId: String?)

    /** One text for a set of lines: the same set → the same text, whatever the ids; a new user id (the box's
     *  "new user ID") is a change — the running core must take it. */
    fun fingerprint(lines: List<LineKey>): String =
        lines.map { "${it.server.orEmpty()}:${it.port.orEmpty()}:${it.remarks.orEmpty()}:${it.userId.orEmpty()}" }.sorted().joinToString("|")
}
