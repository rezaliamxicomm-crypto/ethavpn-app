package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.util.LogUtil
import java.net.URLDecoder

/**
 * Entry point for a link: `ethavpn://install-sub?url=…&name=…` (the landing page),
 * `ethavpn://install-config?url=…`, a verified App Link `https://<host>/sub/<token>` (tapped
 * in Telegram), or text shared to the app. It only works out what the link is and hands it
 * to [HomeActivity], which imports it with a progress bar and connects — an import started
 * here would die with this activity.
 */
class UrlSchemeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var link: String? = null
        try {
            when (intent.action) {
                Intent.ACTION_SEND -> if ("text/plain" == intent.type) {
                    link = intent.getStringExtra(Intent.EXTRA_TEXT)
                }

                Intent.ACTION_VIEW -> {
                    val uri = intent.data
                    link = when {
                        uri == null -> null
                        "https".equals(uri.scheme, ignoreCase = true) -> uri.toString()
                        uri.host == "install-sub" || uri.host == "install-config" -> {
                            val raw = uri.getQueryParameter("url").orEmpty()
                            val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
                            val name = uri.fragment ?: uri.getQueryParameter("name")
                            if (decoded.isNotEmpty() && !decoded.contains('#') && !name.isNullOrEmpty()) "$decoded#$name" else decoded
                        }
                        else -> null
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
        }
        if (link.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
        } else {
            LogUtil.i(AppConfig.TAG, "Link received")
        }
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!link.isNullOrEmpty()) putExtra(HomeActivity.EXTRA_LINK, link)
        })
        finish()
    }
}
