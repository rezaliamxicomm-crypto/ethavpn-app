package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.ui.CheckUpdateActivity
import com.v2ray.ang.util.Utils

/**
 * Where "update" goes: the direct build downloads the signed APK from the service's own /dl/ and
 * installs it (CheckUpdateActivity); the Google Play build never installs anything itself — it
 * opens the listing, and Play keeps the app current (no daily check, no banner).
 */
object Updates {
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"

    fun isPlay(): Boolean = BuildConfig.DISTRIBUTION == "Play"

    fun open(context: Context) {
        if (isPlay()) Utils.openUri(context, PLAY_LISTING)
        else context.startActivity(Intent(context, CheckUpdateActivity::class.java))
    }

    /** "Rate SkyRay": the listing in the Play app itself, where the stars are; no Play app → the listing in a browser. */
    fun rate(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=${BuildConfig.APPLICATION_ID}".toUri()).setPackage("com.android.vending")
            )
        } catch (_: Exception) {
            Utils.openUri(context, PLAY_LISTING)
        }
    }
}
