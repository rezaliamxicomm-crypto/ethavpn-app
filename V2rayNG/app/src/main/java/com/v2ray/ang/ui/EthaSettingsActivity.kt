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
import com.v2ray.ang.util.Utils

/**
 * The customer's settings: eight rows. Everything v2rayNG exposes stays in the code but is
 * reachable only through the `Advanced (v2rayNG)` row, which appears after seven taps on the
 * version line in About (expert mode, for the operator and support).
 */
class EthaSettingsActivity : BaseActivity() {
    companion object {
        const val EXTRA_SERVER_CHANGED = "etha_server_changed"
    }

    private val binding by lazy { ActivityEthaSettingsBinding.inflate(layoutInflater) }
    private var serverChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_settings))

        binding.layoutServer.setOnClickListener { pickServer() }
        binding.layoutBypassApps.setOnClickListener {
            // bypass mode: the chosen apps go around the tunnel (banks, government services)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
            startActivity(Intent(this, PerAppProxyActivity::class.java))
        }
        binding.layoutLanguage.setOnClickListener { pickLanguage() }
        binding.layoutUpdate.setOnClickListener { startActivity(Intent(this, CheckUpdateActivity::class.java)) }
        binding.layoutLogs.setOnClickListener { startActivity(Intent(this, LogcatActivity::class.java)) }
        binding.layoutAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.layoutPrivacy.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_PRIVACY_URL) }
        binding.layoutDelete.setOnClickListener { confirmDelete() }
        binding.layoutAdvanced.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        binding.layoutAdvanced.isVisible = MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_EXPERT, false)
        binding.tvServerValue.text = ServerPicker.currentLabel(this, MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_PINNED, false))
        val codes = resources.getStringArray(R.array.language_select_value)
        val names = resources.getStringArray(R.array.language_select)
        val code = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: "auto"
        binding.tvLanguageValue.text = names.getOrNull(codes.indexOf(code).takeIf { it >= 0 } ?: 0)
    }

    private fun pickServer() {
        val sub = EthaSubscription.find() ?: return
        ServerPicker.show(
            this, sub.guid, MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_PINNED, false),
            onPick = { guid ->
                if (guid == null) {
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
                } else {
                    MmkvManager.setSelectServer(guid)
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, true)
                }
                serverChanged = true
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SERVER_CHANGED, true))
                binding.tvServerValue.text = ServerPicker.currentLabel(this, guid != null)
            },
            onTest = {
                // the test itself runs from Home (it owns the view model); go back there
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SERVER_CHANGED, serverChanged).putExtra(HomeActivity.EXTRA_TEST, true))
                finish()
            }
        )
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
