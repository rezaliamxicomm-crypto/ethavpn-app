package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** A short name for the network the phone is on, to remember which fetch way answered there: "cell:43235", "wifi", "other".
 *  [network]: the network to name — the one under the tunnel while it is up; with the tunnel up the active network is the
 *  tunnel itself and reads "other" (2.1.1). */
object EthaNetwork {
    fun signature(context: Context, network: Network? = null): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network ?: cm.activeNetwork)
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
