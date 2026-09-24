package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    // What the last fetch's headers said (EthaSubscription.applyHeaders). -1 = the header was
    // absent; total 0 = unlimited data, expire 0 = no expiry (the server's meaning, kept as is).
    var upload: Long = -1,
    var download: Long = -1,
    var total: Long = -1,
    var expire: Long = -1,
    var profileTitle: String? = null,
    var announce: String? = null,
    var supportUrl: String? = null,
    var webPageUrl: String? = null,
    var infoUpdated: Long = -1,
)

