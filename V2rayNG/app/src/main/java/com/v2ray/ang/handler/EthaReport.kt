package com.v2ray.ang.handler

/**
 * What Auto measured, for the box: the header X-Etha-Lines on the next subscription fetch (the API stores it as one
 * row of line_reports; the panel shows it — the customer sees no line name). Pure; covered by JVM unit tests.
 *
 *     v=1;t=<epoch s>;trig=<connect|auto|stall|manual|network>;use=<label>;sw=<0|1>;l=<label>:<ms>,<label>:-1,…
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
    fun build(nowMs: Long, trigger: String, inUseRemarks: String?, switched: Boolean, results: List<Pair<String, Long>>): String? {
        val items = results.mapNotNull { (remarks, ms) ->
            val l = label(remarks) ?: return@mapNotNull null
            if (ms == 0L) null else "$l:${if (ms < 0) -1 else ms}"
        }.take(MAX_LINES)
        if (items.isEmpty()) return null
        val use = label(inUseRemarks).orEmpty()
        return "v=1;t=${nowMs / 1000};trig=$trigger;use=$use;sw=${if (switched) 1 else 0};l=${items.joinToString(",")}"
    }

    /** One text for a set of lines (address, port, name): the same set → the same text, whatever the ids. */
    fun fingerprint(lines: List<Triple<String?, String?, String?>>): String =
        lines.map { (server, port, remarks) -> "${server.orEmpty()}:${port.orEmpty()}:${remarks.orEmpty()}" }.sorted().joinToString("|")
}
