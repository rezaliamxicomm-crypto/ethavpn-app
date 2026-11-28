package com.v2ray.ang

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.HomeActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The Home screen seen on a real Android (3.1.0) — the same emulators as the Servers sheet (ci/sheet-test.sh): the Update
 * button at the start of the toolbar while the store carries a newer version, the app's name there when it does not, the
 * menu button, "Refetch" and "Refetched …", and the account card's two buttons still on the screen. In English and in
 * Farsi, with a screenshot of each for the operator: a layout is seen on a phone, not in a build.
 *
 * The account is made up — three lines on documentation addresses under a link of our own form with a token no account has,
 * fetched "five minutes ago" so that nothing asks the network (Home fetches by itself only what is an hour old or ended) — and
 * is taken away again after each test. The link has our host because Home shows the first subscription that is one of ours
 * before any other (a fresh install holds an empty default group, which a stranger's link would stand behind).
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val out: File by lazy { File(inst.targetContext.filesDir, "sheet-test").apply { mkdirs() } }
    private val subId = "ui-test-account"

    @Before
    fun account() {
        if (Build.VERSION.SDK_INT >= 33) {
            inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        val now = System.currentTimeMillis()
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, now)        // no daily update check
        MmkvManager.encodeSubscription(
            subId,
            SubscriptionItem(
                remarks = "SkyRay", url = "https://${AppConfig.ETHA_SUB_HOSTS.first()}${AppConfig.ETHA_SUB_PATH}ui-test-no-such-account", lastUpdated = now - 5 * 60_000L,
                download = 3_200_000_000L, total = 20_000_000_000L, expire = now / 1000 + 21 * 86_400L, infoUpdated = now
            )
        )
        val lines = listOf("FRA1" to "192.0.2.11", "FRA2" to "198.51.100.12", "ALI1-a" to "203.0.113.13").joinToString("\n") { (label, ip) ->
            "vless://00000000-0000-4000-8000-000000000001@$ip:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=example.com&fp=chrome" +
                "&pbk=${"A".repeat(43)}&sid=ab&type=tcp#EthaVPN-REALITY-relay-$label-443"
        }
        AngConfigManager.importBatchConfig(lines, subId, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXITS, "de|Germany|DE|ok|20|203.0.113.20;us|United States|US|ok|21|203.0.113.21")   // the Server box is there
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT_WANT, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_APP_MIN, "")
    }

    @After
    fun away() {
        MmkvManager.removeSubscription(subId)
        MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, "auto")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_APP_LATEST, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXITS, "")
    }

    private fun onScreen(v: View): Rect {
        val at = IntArray(2); v.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    private fun seen(v: View): Rect {
        val r = Rect()
        return if (v.isShown && v.getGlobalVisibleRect(r)) r else Rect()
    }

    private fun shot(name: String) {
        try {
            val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
            FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            File(out, "$name.txt").writeText("no screenshot: $e\n")
        }
    }

    /** Home in [language], the store's newest version as [latest] says: what is on the screen, measured and photographed. */
    private fun look(name: String, language: String, latest: String, update: Boolean, refetch: String, stamp: String, updateText: String) {
        val tag = "$name-api${Build.VERSION.SDK_INT}"
        MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, language)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_APP_LATEST, latest)
        val text = ArrayList<String>()
        val problems = ArrayList<String>()
        try {
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                SystemClock.sleep(2_000)
                inst.waitForIdleSync()
                scenario.onActivity { a ->
                    val d = a.resources.displayMetrics.density
                    fun dp(px: Int) = Math.round(px / d)
                    val rtl = a.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val toolbar = onScreen(a.findViewById(R.id.toolbar))
                    val pillView = a.findViewById<View>(R.id.update_pill)
                    val menuView = a.findViewById<View>(R.id.btn_menu)
                    val refetchView = a.findViewById<TextView>(R.id.btn_refresh)
                    val stampView = a.findViewById<TextView>(R.id.tv_updated)
                    val bar = a.supportActionBar
                    val titleShown = bar != null && (bar.displayOptions and ActionBar.DISPLAY_SHOW_TITLE) != 0
                    text.add("Home in '$language' (${if (rtl) "right to left" else "left to right"}), the store's newest version: '${latest.ifEmpty { "not said" }}'; Android API ${Build.VERSION.SDK_INT}, density $d")
                    text.add("   toolbar ${toolbar.toShortString()} (${dp(toolbar.height())} dp high); the app's name ${if (titleShown) "shown: '${bar?.title}'" else "not shown"}")
                    if (pillView == null) {
                        problems.add("the Update button is not in the toolbar at all")
                    } else if (update) {
                        val p = onScreen(pillView); val ps = seen(pillView)
                        val lead = if (rtl) toolbar.right - p.right else p.left - toolbar.left
                        val label = pillView.findViewById<TextView>(R.id.update_pill_text)?.text?.toString().orEmpty()
                        text.add("   Update button ${p.toShortString()}: ${dp(p.width())} x ${dp(p.height())} dp, ${dp(lead)} dp from the reading edge, text '$label'")
                        if (!pillView.isShown) problems.add("the Update button is not shown though the store carries a newer version")
                        if (ps.width() != p.width() || ps.height() != p.height()) problems.add("the Update button is cut off: ${ps.width()} x ${ps.height()} of ${p.width()} x ${p.height()} px seen")
                        if (p.top < toolbar.top || p.bottom > toolbar.bottom) problems.add("the Update button reaches outside the toolbar")
                        if (Math.abs(dp(p.height()) - 32) > 1) problems.add("the Update button is ${dp(p.height())} dp high, not 32")
                        if (lead < 0 || dp(lead) > 32) problems.add("the Update button is ${dp(lead)} dp from the reading edge")
                        if (label != updateText) problems.add("the Update button says '$label', not '$updateText'")
                        if (titleShown) problems.add("the app's name is shown beside the Update button")
                        if (menuView != null && Rect.intersects(p, onScreen(menuView))) problems.add("the Update button lies under the menu button")
                    } else {
                        if (pillView.isShown) problems.add("the Update button shows though no newer version is said")
                        if (!titleShown) problems.add("the app's name is not shown though there is no Update button")
                    }
                    if (menuView == null || !menuView.isShown) {
                        problems.add("the menu button is not shown")
                    } else {
                        val m = onScreen(menuView); val ms = seen(menuView)
                        val trail = if (rtl) m.left - toolbar.left else toolbar.right - m.right
                        text.add("   menu button ${m.toShortString()}: ${dp(m.width())} x ${dp(m.height())} dp, ${dp(trail)} dp from the far edge")
                        if (ms.width() != m.width() || ms.height() != m.height()) problems.add("the menu button is cut off")
                        if (dp(m.width()) != 40 || dp(m.height()) != 40) problems.add("the menu button is ${dp(m.width())} x ${dp(m.height())} dp, not 40 x 40")
                        if (m.top < toolbar.top || m.bottom > toolbar.bottom) problems.add("the menu button reaches outside the toolbar")
                    }
                    text.add("   the fetch button says '${refetchView?.text}', the stamp '${stampView?.text}'")
                    if (refetchView?.text?.toString() != refetch) problems.add("the fetch button says '${refetchView?.text}', not '$refetch'")
                    if (stampView?.text?.toString()?.startsWith(stamp) != true) problems.add("the stamp says '${stampView?.text}', not '$stamp …'")
                    for ((what, id) in listOf("Renew" to R.id.btn_renew, "Support" to R.id.btn_support, "Test latency" to R.id.btn_test, "the Server box" to R.id.box_server)) {
                        val v = a.findViewById<View>(id)
                        if (v == null || !v.isShown) problems.add("$what is not shown") else text.add("   $what ${onScreen(v).toShortString()}")
                    }
                }
                shot(tag)
                // the menu button opens Settings, as the icon it replaced did: the tap is taken by the action's own view, which holds the button
                scenario.onActivity { a ->
                    val holder = a.findViewById<View>(R.id.btn_menu)?.parent as? View
                    if (holder == null || !holder.hasOnClickListeners()) problems.add("the menu button takes no tap")
                }
            }
        } catch (e: Throwable) {
            problems.add("the test itself failed: $e")
        }
        text.add(if (problems.isEmpty()) "RESULT: Home is as designed" else "RESULT: FAILED\n   " + problems.joinToString("\n   "))
        File(out, "$tag.txt").writeText(text.joinToString("\n") + "\n")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun englishWithAnUpdate() = look("home-en-update", "en", "android=9.9.9;ios=9.9.9;play=9.9.9", true, "Refetch", "Refetched", "Update the app")

    @Test
    fun englishUpToDate() = look("home-en", "en", "", false, "Refetch", "Refetched", "Update the app")

    @Test
    fun farsiWithAnUpdate() = look("home-fa-update", "fa", "android=9.9.9;ios=9.9.9;play=9.9.9", true, "دریافت دوباره", "آخرین دریافت", "به‌روزرسانی برنامه")
}
