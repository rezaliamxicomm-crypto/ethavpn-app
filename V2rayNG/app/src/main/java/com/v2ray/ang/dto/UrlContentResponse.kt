package com.v2ray.ang.dto

/** A fetched body with its response headers (names lower-cased; the last value of a repeated header wins). */
data class UrlContentResponse(
    val body: String,
    val headers: Map<String, String> = emptyMap()
)
