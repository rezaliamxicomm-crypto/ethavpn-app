package com.v2ray.ang.handler

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * This phone as a code for the box (2.1.2): the header X-Etha-Device on every fetch of our link. The first 16 hex digits of
 * SHA-256 over the install id and the package name — the same for this install every time, another in another app, never
 * the id itself and nothing else about the phone. The box counts the phones a link is on, and tells two phones' measurements
 * apart in its ranking.
 */
object EthaDevice {
    @Volatile private var cached: String? = null

    /** Pure: the code for an install id and a package; null when there is no id. */
    fun hashOf(id: String?, pkg: String): String? {
        val v = id?.trim().orEmpty()
        if (v.isEmpty()) return null
        val d = MessageDigest.getInstance("SHA-256").digest("$v:$pkg".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** This install's code, computed once; null when the phone gives no id. */
    fun hash(context: Context): String? = cached ?: try {
        hashOf(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID), context.packageName)
    } catch (_: Exception) {
        null
    }.also { cached = it }
}
