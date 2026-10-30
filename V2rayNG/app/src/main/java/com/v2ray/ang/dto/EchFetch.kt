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
    val headers: Map<String, String> = emptyMap(),   // extra request headers, every way (X-Etha-Lines)
    val plain: Boolean = false,                       // no ECH: a plain TLS fetch to `addresses`, the url's host as the certificate's name (the link hop inside Iran)
    val signKeys: List<String> = emptyList(),         // the box's public keys (AppConfig.ETHA_SIGN_KEYS): the core checks the answer's signature against them (2.1)
)

/** What it returns: the answer, or why there is none. echAccepted is true on every ECH answer (a request is never sent without it); false on a plain one, whose keySource is "plain". */
data class EchFetchResult(
    val status: Int = 0,
    val headers: Map<String, String>? = null,         // names lower-cased, the last value of a repeated header
    val body: String? = null,
    val echAccepted: Boolean = false,
    val address: String? = null,
    val keySource: String? = null,                    // "dns:<resolver>", "pinned" or "retry"; "plain" when no ECH was asked
    val signed: String? = null,                       // the answer's signature against the pinned keys: ok | none | bad | unknown-key | nokeys (2.1)
    val signedAt: Long = 0L,                          // when the box signed it, unix seconds; 0 unsigned
    val error: String? = null,
)
