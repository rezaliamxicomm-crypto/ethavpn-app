package com.v2ray.ang.dto

/** What the core's FetchSubscriptionEch takes (libv2ray-ech/echfetch.go): the link and what to reach it with. */
data class EchFetchRequest(
    val url: String = "",
    val addresses: List<String> = emptyList(),        // tried first: the stored lines' clean Cloudflare addresses
    val pinnedAddresses: List<String> = emptyList(),  // tried last, after the HTTPS record's address hints
    val resolvers: List<String> = emptyList(),        // asked over plain UDP 53 for the HTTPS record
    val pinnedKey: String = "",                       // base64 ECHConfigList, offered when no resolver delivers one
    val userAgent: String = "",
    val timeoutMs: Long = 20000L,
)

/** What it returns: the answer, or why there is none. echAccepted is true on every answer; a request is never sent without it. */
data class EchFetchResult(
    val status: Int = 0,
    val headers: Map<String, String>? = null,         // names lower-cased, the last value of a repeated header
    val body: String? = null,
    val echAccepted: Boolean = false,
    val address: String? = null,
    val keySource: String? = null,                    // "dns:<resolver>", "pinned" or "retry"
    val error: String? = null,
)
