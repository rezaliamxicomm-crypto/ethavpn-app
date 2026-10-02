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
import com.v2ray.ang.databinding.ActivityHomeBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.EthaSubscription
import com.v2ray.ang.handler.MmkvManager
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
 * The one screen a customer needs: the subscription (from the link, the clipboard or a QR
 * code), one Connect button that picks the best line by itself, and the account card built
 * from the subscription headers. Everything v2rayNG exposes stays reachable under Advanced.
 */
class HomeActivity : HelperBaseActivity() {
    companion object {
        const val EXTRA_LINK = "etha_link"
        const val EXTRA_TEST = "etha_test"          // Settings asked for a "test again"
        private const val CONNECT_GUARD_MS = 25_000L
        private const val TEST_GUARD_MS = 60_000L
        private val PROBE_OK = Regex("\\d+\\s*(ms|мс)")   // the probe's answer with a time (en, fa, ru); any other answer is a failure
    }

    private val binding by lazy { ActivityHomeBinding.inflate(layoutInflater) }
    private val mainViewModel: MainViewModel by viewModels()
    private var sub: SubscriptionCache? = null
    private var connecting = false        // waiting for the core to report started / stopped
    private var refreshingQuietly = false // a background subscription refresh is running
    private var clipboardTried: String? = null   // the link last taken from the clipboard (no second import of the same one)
    private var pendingConnect = false    // waiting for a real-delay batch to pick the line
    private var updateResult: CheckUpdateResult? = null
    private var serverSheet: ServerSheet? = null   // the open server sheet, re-rendered as pings land
    private var pulse: AnimatorSet? = null         // the halo's slow breath while connected
    private var lastProbe: String? = null          // the current server's last probe, for the chip
    private var rateOnProbe = false                // the customer just connected by hand: this probe decides whether it counts for the rating ask

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
        if (it.data?.getBooleanExtra(EthaSettingsActivity.EXTRA_SERVER_CHANGED, false) == true) onServerChoiceChanged()
        if (it.data?.getBooleanExtra(EXTRA_TEST, false) == true) testAgain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = false, title = getString(R.string.app_name))

        binding.btnConnect.setOnClickListener { onConnectClick() }
        binding.btnPaste.setOnClickListener { pasteLink() }
        binding.btnScan.setOnClickListener { scanLink() }
        binding.btnRefresh.setOnClickListener { refreshServers() }
        binding.btnRenew.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_RENEW_URL) }
        binding.btnSupport.setOnClickListener { Utils.openUri(this, AppConfig.ETHA_SUPPORT_URL) }
        binding.btnTest.setOnClickListener { testAgain() }
        binding.panelServer.setOnClickListener { showServerSheet() }
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
            rateOnProbe = running && connecting   // the customer's own Connect, not the state found on opening
            connecting = false
            render()
            if (running) mainViewModel.testCurrentServerRealPing()
        }
        mainViewModel.updateTestResultAction.observe(this) { lastProbe = it; renderChip(); countForRating(it) }
        // every ping lands in the panel and the open sheet as it is measured (the results are cleared when a test starts)
        mainViewModel.updateListAction.observe(this) { renderServerPanel(); serverSheet?.render() }
        mainViewModel.testsFinished.observe(this) {
            AutoSelect.markTested()
            if (pendingConnect) {
                pendingConnect = false
                connectWithBest()
            } else {
                // The line changes only when the customer connects (Auto picks the fastest then) or picks one by
                // hand: a test never moves a live connection (operator's rule, 2026-09-30). Auto and disconnected:
                // the selection moves to the best so the next Connect uses it. Either way the outcome is said —
                // a "Ping all" that changes nothing visible looked broken.
                val s = sub
                if (s != null && !isPinned()) {
                    val best = AutoSelect.pickBest(s.guid)
                    val running = mainViewModel.isRunning.value == true
                    val moved = best != null && best != MmkvManager.getSelectServer()
                    if (moved && !running) MmkvManager.setSelectServer(best!!)
                    val name = best?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.let { ServerPicker.displayName(it) }
                    when {
                        name == null -> toastError(R.string.etha_no_line)
                        moved && running -> toastSuccess(getString(R.string.etha_ping_faster, name))
                        else -> toastSuccess(getString(R.string.etha_ping_best, name))
                    }
                } else if (s != null) {
                    val kept = MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks }?.let { ServerPicker.displayName(it) }
                    if (kept != null) toast(getString(R.string.etha_ping_kept, kept))
                }
                render()
            }
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
        serverSheet?.dismiss()
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
        if (sub != null || connecting || pendingConnect) return true
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

    private fun isPinned() = MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_PINNED, false)

    private fun render() {
        val s = sub
        val servers = s?.let { MmkvManager.decodeServerList(it.guid) } ?: emptyList()
        val empty = s == null || servers.isEmpty()
        binding.cardEmpty.isVisible = empty
        binding.hero.isVisible = !empty
        binding.panelServer.isVisible = !empty
        binding.cardAccount.isVisible = !empty

        val running = mainViewModel.isRunning.value == true
        binding.tvState.text = getString(
            when {
                pendingConnect -> R.string.etha_state_finding
                connecting -> R.string.etha_state_connecting
                running -> R.string.etha_state_connected
                else -> R.string.etha_state_not_connected
            }
        )
        // One of three under the state, in a slot that keeps the hint's two lines of height (so the Connect button
        // never moves): the hint ("Tap to connect" + what Auto does), connected the chip, busy the spinner alone.
        val busy = connecting || pendingConnect
        binding.tvConnectHint.visibility = if (!running && !busy) View.VISIBLE else View.INVISIBLE
        binding.tvConnectHint.text = if (isPinned()) getString(R.string.etha_tap_to_connect)
            else getString(R.string.etha_tap_to_connect) + "\n" + getString(R.string.etha_auto_hint)
        binding.btnConnect.isEnabled = !connecting && !pendingConnect
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.etha_green else R.color.etha_blue)
        )
        binding.halo.setBackgroundResource(if (running) R.drawable.bg_halo_green else R.drawable.bg_halo_blue)
        pulseHalo(running)
        binding.progress.isVisible = busy
        renderChip()
        renderServerPanel()
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

    /** The chip under the state while connected: the server and its last probe. */
    private fun renderChip() {
        val text = chipText()
        binding.tvLine.isVisible = text.isNotEmpty() && !connecting && !pendingConnect
        binding.tvLine.text = text
    }

    /** The server panel: Auto → the line it picked (or the pinned line) and its ping. */
    private fun renderServerPanel() {
        val name = ServerPicker.currentName()
        val ms = ServerPicker.currentDelay()
        binding.tvServerValue.text = when {
            name == null -> getString(R.string.etha_server_auto)
            isPinned() -> name
            else -> getString(R.string.etha_auto_picked, name)
        }
        binding.tvServerMs.text = if (ms > 0) "$ms ms" else ""
        binding.tvServerMs.setTextColor(ServerPicker.dotColor(this, ms))
    }

    private fun showServerSheet() {
        val s = sub ?: return
        serverSheet = ServerPicker.show(
            this, s.guid, isPinned(),
            onPick = { guid ->
                if (guid == null) {
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, false)
                } else {
                    MmkvManager.setSelectServer(guid)
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_PINNED, true)
                }
                onServerChoiceChanged()
            },
            onTest = { testAgain() }
        ).also { sheet -> sheet.setOnDismissListener { if (serverSheet === sheet) serverSheet = null } }
    }

    // ---------------------------------------------------------------- the server choice

    /** A new choice while connected reconnects with it (Auto: the best line after a fresh test). */
    private fun onServerChoiceChanged() {
        render()
        if (mainViewModel.isRunning.value != true) return
        connecting = true
        render()
        CoreServiceManager.stopVService(this)
        lifecycleScope.launch {
            delay(700)
            connecting = false
            if (!isPinned()) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_TEST, 0L)   // Auto: a fresh test before connecting
            onConnectClick()
        }
    }

    private fun testAgain() {
        val s = sub ?: return
        if (MmkvManager.decodeServerList(s.guid).isEmpty()) return
        toast(R.string.etha_state_finding)
        mainViewModel.testAllRealPing()
        render()   // the cleared results show at once; each ping fills in as it lands
        serverSheet?.render()
    }

    /** "CleanIP3 · XHTTP/443 · 412 ms" while connected (the probe's number, else the last test's), else "". */
    private fun chipText(): String {
        if (mainViewModel.isRunning.value != true) return ""
        val name = ServerPicker.currentName() ?: return ""
        val probed = lastProbe?.let { Regex("(\\d+)\\s*ms").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val ms = probed ?: ServerPicker.currentDelay()
        return if (ms > 0) "$name · $ms ms" else name
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
        connecting = true
        val selectedOk = EthaSubscription.selectedIsIn(s.guid)
        when {
            isPinned() && selectedOk -> startVpnFlow()
            selectedOk && AutoSelect.resultsFresh() -> {
                AutoSelect.pickBest(s.guid)?.let { MmkvManager.setSelectServer(it) }
                startVpnFlow()
            }
            else -> {
                // Test every line of the subscription through the core, then connect with the best.
                pendingConnect = true
                mainViewModel.testAllRealPing()
                lifecycleScope.launch {
                    delay(TEST_GUARD_MS)
                    if (pendingConnect) {
                        pendingConnect = false
                        connectWithBest()
                    }
                }
            }
        }
        render()
    }

    private fun connectWithBest() {
        val s = sub ?: run { connecting = false; render(); return }
        val best = AutoSelect.pickBest(s.guid)
        if (best != null) {
            MmkvManager.setSelectServer(best)
        } else {
            // Nothing answered the test: try the line we had, or the first one, rather than give up.
            val fallback = MmkvManager.getSelectServer()?.takeIf { EthaSubscription.selectedIsIn(s.guid) }
                ?: MmkvManager.decodeServerList(s.guid).firstOrNull()
            if (fallback == null) {
                connecting = false
                render()
                toastError(R.string.etha_no_line)
                return
            }
            toast(R.string.etha_no_line)
            MmkvManager.setSelectServer(fallback)
        }
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
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)   // the background refresh, timed from this fetch
                val servers = sub?.let { MmkvManager.decodeServerList(it.guid) }.orEmpty()
                if (servers.isEmpty()) {
                    toastError(R.string.import_subscription_failure)
                } else {
                    toastSuccess(R.string.etha_link_added)
                    if (mainViewModel.isRunning.value != true && !connecting && !pendingConnect) onConnectClick()
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
        if (refreshingQuietly || !EthaSubscription.isStale(s.subscription.lastUpdated)) return
        refreshingQuietly = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AngConfigManager.updateConfigViaSubAll()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Quiet refresh failed", e)
            }
            withContext(Dispatchers.Main) {
                refreshingQuietly = false
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)
            }
        }
    }

    private fun refreshServers() {
        binding.btnRefresh.isEnabled = false
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            withContext(Dispatchers.Main) {
                hideLoading()
                binding.btnRefresh.isEnabled = true
                refreshSubscription()
                render()
                SubscriptionUpdater.sync(forceReschedule = true)   // the background refresh, timed from this fetch
                if (result.successCount > 0) {
                    toastSuccess(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    // ---------------------------------------------------------------- the rating ask

    /**
     * A connection the customer made that works (its probe came back with a time) counts towards the
     * rating ask; RatePrompt says when one is due — rarely, and never after a failure. The stars
     * themselves are on the store's page: "Rate" opens it, "Not now" waits for the next round.
     */
    private fun countForRating(probe: String?) {
        if (!rateOnProbe) return
        rateOnProbe = false
        if (probe == null || !PROBE_OK.containsMatchIn(probe)) return
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
