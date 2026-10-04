package com.v2ray.ang.dto

/** What the core's FetchSubscriptionEch takes (echfetch/echfetch.go): the link and what to reach it with. The field names are the JSON keys. */
data class EchFetchRequest(
    val url: String = "",
    val addresses: List<String> = emptyList(),        // tried first: the stored lines' clean Cloudflare addresses
    val pinnedAddresses: List<String> = emptyList(),  // tried last
    val resolvers: List<String> = emptyList(),        // asked over plain UDP 53 for the HTTPS record
    val lookupName: String = "",                      // whose HTTPS record carries the key: the ECH public name
    val pinnedKey: String = "",                       // base64 ECHConfigList, offered when no resolver delivers one
    val userAgent: String = "",
    val timeoutMs: Long = 20000L,
    val proxy: String = "",                           // "127.0.0.1:port" of the running tunnel's local proxy: the fetch goes through it
    val proxyUser: String = "",                       // its account, when the customer set one
    val proxyPassword: String = "",
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
