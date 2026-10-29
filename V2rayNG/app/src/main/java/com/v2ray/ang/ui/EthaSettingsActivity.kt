package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityEthaSettingsBinding
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.EthaSubscription
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RatePrompt
import com.v2ray.ang.handler.Updates
import com.v2ray.ang.util.Utils

/**
 * The customer's settings: ten rows. Everything v2rayNG exposes stays in the code but is
 * reachable only through the `Advanced (v2rayNG)` row, which appears after seven taps on the
 * version line in About (expert mode, for the operator and support). No line is picked here (2.0.0):
 * the customer sees no line name; the operator picks one in the v2rayNG screens.
 */
class EthaSettingsActivity : BaseActivity() {
    private val binding by lazy { ActivityEthaSettingsBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_settings))

        binding.layoutBypassApps.setOnClickListener {
            // bypass mode: the chosen apps go around the tunnel (banks, government services)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
            startActivity(Intent(this, PerAppProxyActivity::class.java))
        }
        binding.layoutLanguage.setOnClickListener { pickLanguage() }
        binding.layoutUpdate.setOnClickListener { Updates.open(this) }
        binding.layoutLogs.setOnClickListener { startActivity(Intent(this, LogcatActivity::class.java)) }
        binding.layoutShare.setOnClickListener {
            // the system share sheet with the two stores and the bot: a friend picks their platform
            val text = getString(R.string.etha_share_text, AppConfig.ETHA_PLAY_URL, AppConfig.ETHA_APPSTORE_URL, AppConfig.ETHA_SHARE_URL)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, getString(R.string.etha_share_app)))
        }
        binding.layoutRate.setOnClickListener {
            RatePrompt.done()   // the customer went to the store by themselves: the app never asks after this
            Updates.rate(this)
        }
        binding.layoutAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.layoutPrivacy.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_PRIVACY_URL) }
        binding.layoutDelete.setOnClickListener { confirmDelete() }
        binding.layoutAdvanced.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        binding.layoutAdvanced.isVisible = MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_EXPERT, false)
        val codes = resources.getStringArray(R.array.language_select_value)
        val names = resources.getStringArray(R.array.language_select)
        val code = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: "auto"
        binding.tvLanguageValue.text = names.getOrNull(codes.indexOf(code).takeIf { it >= 0 } ?: 0)
    }

    /** "Delete account": everything of the customer's on this phone, after one confirmation. */
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_delete_account)
            .setMessage(R.string.etha_delete_account_confirm)
            .setPositiveButton(R.string.etha_delete_account_do) { _, _ -> deleteAccount() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteAccount() {
        CoreServiceManager.stopVService(this)      // nothing to do when it is not running
        EthaSubscription.deleteAll()
        toastSuccess(R.string.etha_delete_account_done)
        // Home starts over and shows the empty card
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun pickLanguage() {
        val codes = resources.getStringArray(R.array.language_select_value)
        val names = resources.getStringArray(R.array.language_select)
        val current = codes.indexOf(MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: "auto").takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_language)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, codes[which])
                // the locale is applied when an activity is created: start over from Home
                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
