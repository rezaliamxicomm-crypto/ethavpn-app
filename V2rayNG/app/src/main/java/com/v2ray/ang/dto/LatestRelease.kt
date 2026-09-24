package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

/** /dl/latest.json on the service's host, written by the release workflow and served by nginx. */
data class LatestRelease(
    @SerializedName("version") val version: String = "",
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("published") val published: String = "",
    @SerializedName("min_supported") val minSupported: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("assets") val assets: List<Asset> = emptyList()
) {
    data class Asset(
        @SerializedName("abi") val abi: String = "",
        @SerializedName("name") val name: String = "",
        @SerializedName("size") val size: Long = 0,
        @SerializedName("sha256") val sha256: String = ""
    )
}
