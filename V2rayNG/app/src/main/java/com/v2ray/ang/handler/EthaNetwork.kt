package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** A short name for the network the phone is on, to remember which fetch way answered there: "cell:43235", "wifi", "other". */
object EthaNetwork {
    fun signature(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val op = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperator.orEmpty()
                "cell:" + op.filter { it.isDigit() }.ifEmpty { "unknown" }
            }
            else -> "other"
        }
    } catch (_: Exception) {
        "other"
    }
}
