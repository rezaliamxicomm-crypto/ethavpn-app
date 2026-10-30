package com.v2ray.ang.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.text.InputType
import android.text.format.DateUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.EthaAuto
import com.v2ray.ang.databinding.ActivityHomeBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.EthaReport
import com.v2ray.ang.handler.EthaSubscription
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.handler.RatePrompt
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.Updates
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The one screen a customer needs: the subscription (from the link, the clipboard or a QR code), one Connect button
 * that picks the best line by itself, the latency of the line in use, "Test latency" and "Refresh", and the account
 * card built from the subscription headers. No line is named anywhere (2.0.0): the names and every test go to the
 * box instead (EthaReport → the panel). Everything v2rayNG exposes stays reachable under Advanced (expert mode).
 *
 * Three states while the tunnel is up: Connected (the chip shows the line in use's time), Searching for a line (Auto
 * found no line answering — the disc amber, the chip red, the line under the buttons says what to do; Auto keeps
 * measuring and fetching by itself, EthaAuto), and Testing (Test latency is running). The core answers with
 * broadcasts; the service decides every move.
 */
class HomeActivity : HelperBaseActivity() {
    companion object {
        const val EXTRA_LINK = "etha_link"
        private const val CONNECT_GUARD_MS = 25_000L
    }

    private val binding by lazy { ActivityHomeBinding.inflate(layoutInflater) }
    private val mainViewModel: MainViewModel by viewModels()
    private var sub: SubscriptionCache? = null
    private var connecting = false        // waiting for the core to report started / stopped
    private var refreshing = false        // a subscription fetch is running (quiet, or asked for with Refresh)
    private var clipboardTried: String? = null   // the link last taken from the clipboard (no second import of the same one)
    private var testing = false           // Test latency is running: the button is busy, the chip says so
    private var searching = false         // Auto found no line answering (the service's state)
    private var latencyMs = 0L            // the line in use's last time: ms, -1 failed, 0 none yet
    private var latencyAt = 0L
    private var outcome = 0               // the string under the buttons after a test, 0 = none
    private var updateResult: CheckUpdateResult? = null
    private var pulse: AnimatorSet? = null         // the halo's slow breath while connected
    private var rateOnLatency = false              // the customer just connected by hand: the first time that lands decides the rating ask

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startCore()
        } else {
            connecting = false
            render()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartCore()
        }
        SettingsChangeManager.consumeSetupGroupTab()
        refreshSubscription()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = false, title = getString(R.string.app_name))
        // A line pinned by hand in 1.3.x would have no screen to unpin it now: nobody is pinned in 2.0.0.
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)

        binding.btnConnect.setOnClickListener { onConnectClick() }
        binding.btnPaste.setOnClickListener { pasteLink() }
        binding.btnScan.setOnClickListener { scanLink() }
        binding.btnRefresh.setOnClickListener { refreshServers() }
        binding.btnTest.setOnClickListener { testLatency() }
        binding.btnRenew.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_RENEW_URL) }
        binding.btnSupport.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_SUPPORT_URL) }
        // The hero has the height the phone leaves over (activity_home.xml): the disc with its halo grows with it, up
        // to 12 % at 160 dp to spare (168 dp, the design's tall-phone hero) — drawn larger, laid out the same, so
        // nothing else moves; the halo's clear rim is what reaches past its box.
        binding.hero.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val spare = (bottom - top) - binding.hero.paddingTop - binding.hero.paddingBottom - binding.heroBlock.height
            val grow = 1f + 0.12f * (spare / (160f * resources.displayMetrics.density)).coerceIn(0f, 1f)
            binding.ring.scaleX = grow
            binding.ring.scaleY = grow
        }
        binding.tvUpdate.setOnClickListener { Updates.open(this) }

        mainViewModel.isRunning.observe(this) { running ->
            rateOnLatency = running && connecting   // the customer's own Connect, not the state found on opening
            connecting = false
            if (!running) {
                searching = false; testing = false; latencyMs = 0L; outcome = 0
            }
            render()
        }
        mainViewModel.lineLatency.observe(this) { ms ->
            latencyMs = ms
            latencyAt = System.currentTimeMillis()
            renderChip()
            if (rateOnLatency && ms > 0) {
                rateOnLatency = false
                askForRating()
            }
        }
        mainViewModel.autoState.observe(this) { state ->
            searching = state == "searching"
            if (searching) outcome = 0
            render()
        }
        mainViewModel.lineSwitched.observe(this) { render() }
        mainViewModel.roundDone.observe(this) { result ->
            testing = false
            outcome = when (result) {
                "switched" -> R.string.etha_switched_faster
                "kept" -> R.string.etha_on_fastest
                else -> R.string.etha_no_line_answers
            }
            render()
            refreshQuietly()   // the report of that test reaches the box now, not with the next scheduled fetch
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        SubscriptionUpdater.sync()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}

        refreshSubscription()
        render()
        intent?.getStringExtra(EXTRA_LINK)?.let { importLink(it) }
        checkForUpdateDaily()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_LINK)?.let { importLink(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshSubscription()
        render()
        refreshQuietlyIfStale()
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }

    /**
     * No account yet: the landing page copies the customer's link to the clipboard before the
     * download, so the app can add the account by itself — the card underneath only says "tap
     * Paste link" for the phones that hand nothing over. Android gives the clipboard to the app
     * only once its window has focus, hence here, on every focus gain while there is no
     * subscription; a phone that answers the first read with nothing (the focus not yet
     * registered, a vendor's clipboard prompt) gets a second read a moment later. One of our
     * links is imported once, whatever the outcome.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!importFromClipboard()) binding.root.postDelayed({ importFromClipboard() }, 400)
    }

    /** Returns true when the clipboard had text (a link or not) — false means nothing came back. */
    private fun importFromClipboard(): Boolean {
        if (sub != null || connecting) return true
        val text = clipboardText()
        if (text.isBlank()) return false
        val link = EthaSubscription.extractSubLink(text) ?: return true
        // by account, not by text: the deleted link was stored with its "#EthaVPN" name and maybe an old address
        if (link == clipboardTried || EthaSubscription.sameAccount(link, MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_DELETED_LINK))) return true
        clipboardTried = link
        LogUtil.i(AppConfig.TAG, "A link on the clipboard, importing")
        importLink(link)
        return true
    }

    // ---------------------------------------------------------------- state

    private fun refreshSubscription() {
        EthaSubscription.migrateAll()   // links on an earlier address → fra.skyrayconfig.org; one subscription per account
        sub = EthaSubscription.find()
        mainViewModel.subscriptionIdChanged(sub?.guid ?: "")
    }

    private fun render() {
        val s = sub
        val servers = s?.let { MmkvManager.decodeServerList(it.guid) } ?: emptyList()
        val empty = s == null || servers.isEmpty()
        binding.cardEmpty.isVisible = empty
        binding.hero.isVisible = !empty
        binding.cardAccount.isVisible = !empty

        val running = mainViewModel.isRunning.value == true
        binding.tvState.text = getString(
            when {
                connecting -> R.string.etha_state_connecting
                running && searching -> R.string.etha_state_searching
                running && latencyMs <= 0L -> R.string.etha_state_connecting   // up and passing traffic, the first measurement not in yet (the operator's wording, 2026-10-30)
                running -> R.string.etha_state_connected
                else -> R.string.etha_state_not_connected
            }
        )
        // One of three under the state, in a slot that keeps the hint's two lines of height (so the Connect button
        // never moves): the hint ("Tap to connect" + what Auto does), connected the chip, busy the spinner alone.
        val busy = connecting
        binding.tvConnectHint.visibility = if (!running && !busy) View.VISIBLE else View.INVISIBLE
        binding.tvConnectHint.text = getString(R.string.etha_tap_to_connect) + "\n" + getString(R.string.etha_auto_hint)
        binding.btnConnect.isEnabled = !connecting
        val disc = when {
            running && searching -> R.color.etha_amber
            running -> R.color.etha_green
            else -> R.color.etha_blue
        }
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, disc))
        binding.halo.setBackgroundResource(
            when {
                running && searching -> R.drawable.bg_halo_amber
                running -> R.drawable.bg_halo_green
                else -> R.drawable.bg_halo_blue
            }
        )
        pulseHalo(running)
        binding.progress.isVisible = busy
        binding.btnTest.isEnabled = !empty && !testing && !connecting
        binding.btnTest.text = getString(if (testing) R.string.etha_testing else R.string.etha_test_latency)
        binding.btnRefresh.isEnabled = !empty && !refreshing && !connecting
        renderChip()
        renderOutcome()
        if (s != null) renderAccount(s.subscription)
    }

    /** The halo breathes slowly while connected — the one motion on the screen. */
    private fun pulseHalo(on: Boolean) {
        if (on) {
            if (pulse?.isRunning == true) return
            val sx = ObjectAnimator.ofFloat(binding.halo, "scaleX", 1f, 1.12f)
            val sy = ObjectAnimator.ofFloat(binding.halo, "scaleY", 1f, 1.12f)
            val a = ObjectAnimator.ofFloat(binding.halo, "alpha", 0.85f, 1f)
            for (o in listOf(sx, sy, a)) { o.repeatCount = ValueAnimator.INFINITE; o.repeatMode = ValueAnimator.REVERSE }
            pulse = AnimatorSet().apply { playTogether(sx, sy, a); duration = 1300; interpolator = AccelerateDecelerateInterpolator(); start() }
        } else {
            pulse?.cancel(); pulse = null
            binding.halo.scaleX = 1f; binding.halo.scaleY = 1f; binding.halo.alpha = 0.85f
        }
    }

    /** The chip under the state while connected: the line in use's time and its age — never its name. */
    private fun renderChip() {
        val chip = binding.tvLine
        if (mainViewModel.isRunning.value != true || connecting) {
            chip.isVisible = false
            return
        }
        when {
            testing -> styleChip(chip, red = false, text = getString(R.string.etha_testing))
            searching -> styleChip(chip, red = true, text = getString(R.string.etha_no_line_answers) + " · " + ago(latencyAt))
            latencyMs > 0 -> styleChip(chip, red = false, text = "$latencyMs ms · " + ago(latencyAt))
            else -> { chip.isVisible = false; return }
        }
        chip.isVisible = true
    }

    private fun styleChip(chip: TextView, red: Boolean, text: String) {
        chip.text = text
        chip.setBackgroundResource(if (red) R.drawable.bg_chip_red else R.drawable.bg_chip)
        chip.setTextColor(ContextCompat.getColor(this, if (red) R.color.etha_red else R.color.etha_green_light))
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(if (red) R.drawable.bg_dot_red else R.drawable.bg_dot, 0, 0, 0)
    }

    private fun ago(at: Long): String {
        if (at <= 0L || System.currentTimeMillis() - at < 60_000L) return getString(R.string.etha_just_now)
        return DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

    /** Under the buttons: what the last test did, or what to do while no line answers; invisible otherwise (the block keeps its height). */
    private fun renderOutcome() {
        val running = mainViewModel.isRunning.value == true
        val text = when {
            running && searching -> getString(R.string.etha_searching_hint)
            outcome != 0 -> getString(outcome)
            else -> ""
        }
        binding.tvOutcome.text = text
        binding.tvOutcome.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Test latency: connected, the service measures every line through the running core and says what it did
     * (roundDone); disconnected, one throw-away core measures them and the best is the line the next Connect starts
     * on. Either way the result is kept for the box (EthaReport) and a quiet fetch carries it there.
     */
    private fun testLatency() {
        val s = sub ?: return
        val lines = MmkvManager.decodeServerList(s.guid)
        if (lines.isEmpty() || testing) return
        testing = true
        outcome = 0
        render()
        if (mainViewModel.isRunning.value == true) {
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_ETHA_PING_ALL, "")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val results = try {
                EthaAuto.measureOffline(this@HomeActivity, lines)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Test latency failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                testing = false
                if (results == null) {
                    toastError(R.string.toast_failure)
                    render()
                    return@withContext
                }
                for ((guid, ms) in results) MmkvManager.encodeServerTestDelayMillis(guid, ms)
                AutoSelect.markTested()
                val best = AutoSelect.pickBest(s.guid)
                if (best != null) MmkvManager.setSelectServer(best)
                val bestMs = best?.let { results[it] } ?: -1L
                val report = EthaReport.build(
                    System.currentTimeMillis(), "manual", best?.let { MmkvManager.decodeServerConfig(it)?.remarks }, false,
                    lines.map { (MmkvManager.decodeServerConfig(it)?.remarks ?: "") to (results[it] ?: 0L) },
                )
                if (report != null) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_REPORT, report)
                outcome = if (bestMs > 0) R.string.etha_ready else R.string.etha_no_line_answers
                if (bestMs > 0) toastSuccess(getString(R.string.etha_ready_ms, bestMs)) else toastError(R.string.etha_no_line)
                render()
                refreshQuietly()
            }
        }
    }

    private fun renderAccount(item: SubscriptionItem) {
        val days = EthaSubscription.daysLeft(item.expire)
        when {
            days == null -> { binding.tvDays.text = "–"; binding.tvDaysLabel.text = getString(R.string.etha_days_label) }
            days == Long.MAX_VALUE -> { binding.tvDays.text = "∞"; binding.tvDaysLabel.text = getString(R.string.etha_no_expiry) }
            days == 0L -> { binding.tvDays.text = "0"; binding.tvDaysLabel.text = getString(R.string.etha_expired) }
            else -> { binding.tvDays.text = days.toString(); binding.tvDaysLabel.text = getString(R.string.etha_days_label) }
        }
        val used = fmtBytes(maxOf(0L, item.download) + maxOf(0L, item.upload))
        when {
            item.total < 0 -> { binding.tvData.text = "–"; binding.tvDataLabel.text = getString(R.string.etha_data_unknown) }
            item.total == 0L -> { binding.tvData.text = used; binding.tvDataLabel.text = getString(R.string.etha_data_unlimited) }
            else -> { binding.tvData.text = used; binding.tvDataLabel.text = getString(R.string.etha_data_label_of, EthaSubscription.quotaText(item.total)) }
        }
        binding.dataBar.isVisible = item.total > 0
        if (item.total > 0) {
            val usedBytes = maxOf(0L, item.download) + maxOf(0L, item.upload)
            binding.dataBar.setProgressCompat((usedBytes * 100 / item.total).toInt().coerceIn(0, 100), false)
        }
        binding.tvUpdated.text = if (item.lastUpdated > 0) {
            getString(R.string.etha_updated, DateUtils.getRelativeTimeSpanString(item.lastUpdated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        } else getString(R.string.etha_updated_never)
        binding.tvAnnounce.isVisible = !item.announce.isNullOrBlank()
        binding.tvAnnounce.text = item.announce
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", b / 1048576.0)
        else -> String.format(Locale.getDefault(), "%.0f KB", b / 1024.0)
    }

    // ---------------------------------------------------------------- connect

    private fun onConnectClick() {
        val s = sub ?: return
        if (mainViewModel.isRunning.value == true) {
            connecting = true
            render()
            CoreServiceManager.stopVService(this)
            guardConnecting()
            return
        }
        // The line to start on: the best of results younger than ETHA_DELAY_FRESH_MS; else the line used last; else
        // the first. Nothing is waited for: the service measures every line through the running core as soon as the
        // tunnel is up and moves new connections to a better one by itself (EthaAuto).
        val lines = MmkvManager.decodeServerList(s.guid)
        val selected = MmkvManager.getSelectServer()?.takeIf { EthaSubscription.selectedIsIn(s.guid) }
        val start = (if (AutoSelect.resultsFresh()) AutoSelect.pickBest(s.guid) ?: selected else selected) ?: lines.firstOrNull()
        if (start == null) {
            toastError(R.string.etha_no_line)
            return
        }
        MmkvManager.setSelectServer(start)
        connecting = true
        startVpnFlow()
    }

    private fun startVpnFlow() {
        render()
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) startCore() else requestVpnPermission.launch(intent)
        } else {
            startCore()
        }
    }

    private fun startCore() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            connecting = false
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        CoreServiceManager.startVService(this)
        guardConnecting()
    }

    /** The core answers with a broadcast; if none comes, the button must not stay dead. */
    private fun guardConnecting() {
        lifecycleScope.launch {
            delay(CONNECT_GUARD_MS)
            if (connecting) {
                connecting = false
                render()
            }
        }
    }

    private fun restartCore() {
        if (mainViewModel.isRunning.value == true) CoreServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(500)
            startCore()
        }
    }

    // ---------------------------------------------------------------- the link

    private fun pasteLink() {
        val link = EthaSubscription.extractSubLink(clipboardText())
        if (link == null) {
            askForLink()   // nothing to paste: never a dead end
            return
        }
        clipboardTried = link
        importLink(link)
    }

    /** Everything on the clipboard as text: every item, a web address or styled text included (Utils.getClipboard reads the first item's plain text only). */
    private fun clipboardText(): String = try {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        (0 until (clip?.itemCount ?: 0)).joinToString("\n") { clip?.getItemAt(it)?.coerceToText(this)?.toString().orEmpty() }
    } catch (_: Exception) {
        ""
    }

    /**
     * Paste found no link on the clipboard (nothing copied it — an install straight from the store or
     * from the bot's file —, the phone cleared it, or something else was copied since): a box to put the
     * link in, where the keyboard's own paste works, and "Open Telegram", which brings the bot's message
     * with the link. The customer who copies the link there and comes back needs no further tap: the
     * box takes it from the clipboard as soon as it has the screen again.
     */
    private fun askForLink() {
        val input = EditText(this).apply {
            hint = getString(R.string.etha_link_box_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.etha_link_box_title)
            .setMessage(R.string.etha_link_box_text)
            .setView(box)
            .setPositiveButton(R.string.etha_link_box_add, null)   // set below: a text that is not a link keeps the box open
            .setNeutralButton(R.string.etha_link_box_telegram, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun useLink(link: String) {
            dialog.dismiss()
            clipboardTried = link
            importLink(link)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = EthaSubscription.extractSubLink(input.text?.toString())
                if (link == null) input.error = getString(R.string.etha_link_invalid) else useLink(link)
            }
            // the box stays open behind Telegram: back with the link copied, it is taken by itself
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { Utils.openUri(this, AppConfig.ETHA_LINK_URL) }
            dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus && dialog.isShowing) EthaSubscription.extractSubLink(clipboardText())?.let { useLink(it) }
            }
        }
        dialog.show()
    }

    private fun scanLink() {
        launchQRCodeScanner { result ->
            val link = EthaSubscription.extractSubLink(result)
            if (link == null) toastError(R.string.etha_link_invalid) else importLink(link)
        }
    }

    /** Adds the subscription (or refreshes it when it is already there) and connects. */
    private fun importLink(raw: String) {
        val link = EthaSubscription.extractSubLink(raw) ?: raw
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_DELETED_LINK, "")   // asked for by hand: no longer "deleted"
        val named = if (link.contains('#')) link else "$link#${AppConfig.ETHA_SUB_NAME}"
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val (count, countSub) = try {
                AngConfigManager.importBatchConfig(named, "", false)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import the link", e)
                0 to 0
            }
            if (count + countSub == 0) {
                // The same link again (a renewal, a reinstall): refresh instead of failing.
                try { AngConfigManager.updateConfigViaSubAll() } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Failed to refresh", e) }
            }
            withContext(Dispatchers.Main) {
                hideLoading()
                afterFetch()
                val servers = sub?.let { MmkvManager.decodeServerList(it.guid) }.orEmpty()
                if (servers.isEmpty()) {
                    toastError(R.string.import_subscription_failure)
                } else {
                    toastSuccess(R.string.etha_link_added)
                    if (mainViewModel.isRunning.value != true && !connecting) onConnectClick()
                }
            }
        }
    }

    /**
     * The subscription refreshes by itself: a WorkManager job every ETHA_SUB_UPDATE_MINUTES (the
     * API's Profile-Update-Interval, applied on every fetch) and — because Android may hold that
     * job back for hours on a battery-saving phone — a quiet refresh whenever this screen comes
     * up and the last fetch is older than ETHA_SUB_STALE_MS. No spinner, no toast: a failure
     * just leaves the current servers in place.
     */
    private fun refreshQuietlyIfStale() {
        val s = sub ?: return
        if (!EthaSubscription.isStale(s.subscription.lastUpdated)) return
        refreshQuietly()
    }

    private fun refreshQuietly() {
        if (sub == null || refreshing) return
        refreshing = true
        render()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AngConfigManager.updateConfigViaSubAll()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Quiet refresh failed", e)
            }
            withContext(Dispatchers.Main) {
                refreshing = false
                afterFetch()
            }
        }
    }

    /** Refresh: the configs fetched now, with a spinner and a word at the end. */
    private fun refreshServers() {
        if (refreshing) return
        refreshing = true
        render()
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                hideLoading()
                refreshing = false
                afterFetch()
                if (result.successCount > 0) toastSuccess(R.string.etha_refreshed) else toastError(R.string.toast_failure)
            }
        }
    }

    /** After any fetch: the screen, the background schedule, and a running core restarted when the lines changed. */
    private fun afterFetch() {
        refreshSubscription()
        render()
        SubscriptionUpdater.sync(forceReschedule = true)   // the background refresh, timed from this fetch
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_LINES_CHANGED, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINES_CHANGED, false)
            if (mainViewModel.isRunning.value == true) restartCore()   // the core holds the set it started with
        }
    }

    // ---------------------------------------------------------------- the rating ask

    /**
     * A connection the customer made that works (its first measured time landed) counts towards the rating ask;
     * RatePrompt says when one is due — rarely, and never after a failure. The stars themselves are on the store's
     * page: "Rate" opens it, "Not now" waits for the next round.
     */
    private fun askForRating() {
        if (!RatePrompt.connected() || isFinishing || isDestroyed) return
        RatePrompt.asked()
        AlertDialog.Builder(this)
            .setTitle(R.string.etha_rate_app)
            .setMessage(R.string.etha_rate_ask)
            .setPositiveButton(R.string.etha_rate_do) { _, _ ->
                RatePrompt.done()
                Updates.rate(this)
            }
            .setNegativeButton(R.string.etha_rate_later, null)
            .show()
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdateDaily() {
        if (Updates.isPlay()) return          // Google Play keeps the app current
        val last = MmkvManager.decodeSettingsLong(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - last < AppConfig.ETHA_UPDATE_CHECK_MS) return
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate()
                MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, System.currentTimeMillis())
                if (result.hasUpdate) {
                    updateResult = result
                    binding.tvUpdate.isVisible = true
                    binding.tvUpdate.text = if (result.mandatory) {
                        getString(R.string.etha_update_required)
                    } else {
                        getString(R.string.etha_update_available, result.latestVersion)
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Update check skipped: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.etha_settings -> {
            requestActivityLauncher.launch(Intent(this, EthaSettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
