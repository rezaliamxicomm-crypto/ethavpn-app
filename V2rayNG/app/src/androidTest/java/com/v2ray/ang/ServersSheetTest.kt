package com.v2ray.ang

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.HomeActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The Servers sheet seen on a real Android — an emulator in CI (.github/workflows/build.yml, the job `ui-test`; ci/sheet-test.sh).
 *
 * Why this exists: with eleven countries the sheet was taller than most phones' screens and its button was below the edge, so no
 * country could be chosen (3.0.2). 3.0.3 sized the list from Configuration.screenHeightDp — which, for an app that targets Android
 * 15 or later, includes the status and the navigation bar on Android 15+ — and the button was cut off again on those phones. Neither
 * was seen before a release, because nothing but a phone shows a layout. This opens the real sheet of the real Home screen
 * (HomeActivity.openServers, by reflection: no subscription, so nothing reaches the network) with as many countries as it is told,
 * and holds it to what a customer needs: the button whole and above the navigation bar, the title below the status bar, the first
 * row in view, and the last row reachable by scrolling — with every measure written down and two screenshots kept
 * (files/sheet-test in the app's own storage; the CI script pulls them).
 */
@RunWith(AndroidJUnit4::class)
class ServersSheetTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val out: File by lazy { File(inst.targetContext.filesDir, "sheet-test").apply { mkdirs() } }

    private val real = listOf(
        "ca|Canada|CA", "fi|Finland|FI", "fr|France|FR", "de|Germany|DE", "nl|Netherlands|NL", "pl|Poland|PL",
        "se|Sweden|SE", "tr|Turkey|TR", "ae|United Arab Emirates|AE", "gb|United Kingdom|GB", "us|United States|US"
    )

    /** The header the box sends (X-Etha-Exits), for `n` countries: the real eleven first, made-up ones after them. */
    private fun header(n: Int): String {
        val names = when {
            n == 2 -> listOf(real[3], real[10])
            n <= real.size -> real.take(n)
            else -> real + (1..(n - real.size)).map { "x${'a' + (it - 1)}|Country $it|ZZ" }
        }
        return names.mapIndexed { i, s -> "$s|ok|${20 + i}|203.0.113.${20 + i}" }.joinToString(";")
    }

    @Before
    fun quietStart() {
        // nothing of the Home screen's own may open over the sheet or reach the network: the notification permission's dialog
        // (Android 13+), the daily update check
        if (Build.VERSION.SDK_INT >= 33) {
            inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LAST_UPDATE_CHECK, System.currentTimeMillis())
    }

    private class Geo(
        val screenW: Int, val screenH: Int, val density: Float, val cfgH: Int, val barTop: Int, val barBottom: Int, val state: Int,
        val sheet: Rect, val title: Rect, val list: Rect, val rowsH: Int, val rowCount: Int, val rowH: Int,
        val first: Rect, val firstSeen: Rect, val last: Rect, val lastSeen: Rect, val button: Rect, val buttonSeen: Rect
    ) {
        val key: String get() = "$sheet $list $button $buttonSeen $state $barTop $barBottom"
        private fun dp(px: Int) = Math.round(px / density)

        fun lines(where: String): List<String> = listOf(
            "$where:",
            "   sheet ${sheet.toShortString()} (state $state; ${dp(sheet.height())} dp high), title top ${title.top}",
            "   list ${list.toShortString()}: ${list.height()} px (${dp(list.height())} dp) shown of ${rowsH} px (${dp(rowsH)} dp) of rows; $rowCount rows of $rowH px",
            "   first row ${first.toShortString()}, seen ${firstSeen.width()} x ${firstSeen.height()}; last row ${last.toShortString()}, seen ${lastSeen.width()} x ${lastSeen.height()}",
            "   button ${button.toShortString()}: ${button.width()} x ${button.height()} px, seen ${buttonSeen.width()} x ${buttonSeen.height()} px; " +
                "the navigation bar begins at ${screenH - barBottom}"
        )

        /** What a customer cannot do in this state. */
        fun problems(where: String, atEnd: Boolean): List<String> {
            val p = ArrayList<String>()
            if (state != BottomSheetBehavior.STATE_EXPANDED) p.add("$where: the sheet is not fully open (state $state)")
            if (button.height() <= 0 || button.width() <= 0) p.add("$where: the button has no size")
            if (buttonSeen.height() != button.height() || buttonSeen.width() != button.width()) {
                p.add("$where: the button is cut off — ${buttonSeen.height()} of its ${button.height()} px of height can be seen")
            }
            if (button.bottom > screenH - barBottom) p.add("$where: the button reaches ${button.bottom - (screenH - barBottom)} px under the navigation bar")
            if (title.top < barTop) p.add("$where: the title is ${barTop - title.top} px under the status bar")
            if (rowCount > 0 && list.height() < minOf(rowsH, 2 * rowH)) p.add("$where: the list shows ${list.height()} px, less than two rows")
            if (!atEnd && (firstSeen.height() != first.height() || first.height() <= 0)) p.add("$where: the first row is not whole in view")
            if (atEnd && (lastSeen.height() != last.height() || last.height() <= 0)) p.add("$where: the last row cannot be brought whole into view")
            if (atEnd && last.bottom > button.top) p.add("$where: the last row lies under the button")
            return p
        }
    }

    private fun onScreen(v: View): Rect {
        val at = IntArray(2); v.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    /** What of the view no ancestor clips (empty when nothing). */
    private fun seen(v: View): Rect {
        val r = Rect()
        return if (v.isShown && v.getGlobalVisibleRect(r)) r else Rect()
    }

    private fun geometry(a: HomeActivity, d: BottomSheetDialog): Geo {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        a.windowManager.defaultDisplay.getRealMetrics(dm)
        val decor = d.window!!.decorView
        val bars = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val list = d.findViewById<View>(R.id.exit_scroll)!!
        val content = list.parent as ViewGroup            // the sheet's own column: the handle, the title, the list, the button
        val sheet = content.parent as View                // Material's sheet (design_bottom_sheet)
        val title = content.getChildAt(1)
        val rows = d.findViewById<LinearLayout>(R.id.exit_rows)!!
        val button = d.findViewById<View>(R.id.btn_exit_go)!!
        val first = rows.getChildAt(0)
        val last = rows.getChildAt(rows.childCount - 1)
        return Geo(
            dm.widthPixels, dm.heightPixels, dm.density, a.resources.configuration.screenHeightDp, bars?.top ?: 0, bars?.bottom ?: 0,
            d.behavior.state, onScreen(sheet), onScreen(title), onScreen(list), rows.height, rows.childCount, first?.height ?: 0,
            if (first != null) onScreen(first) else Rect(), if (first != null) seen(first) else Rect(),
            if (last != null) onScreen(last) else Rect(), if (last != null) seen(last) else Rect(), onScreen(button), seen(button)
        )
    }

    private fun shot(name: String) {
        try {
            val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
            FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            File(out, "$name.txt").writeText("no screenshot: $e\n")
        }
    }

    private fun sheetOf(a: HomeActivity): BottomSheetDialog? =
        HomeActivity::class.java.getDeclaredField("sheet").apply { isAccessible = true }.get(a) as BottomSheetDialog?

    private fun look(countries: Int) {
        val tag = "sheet-%02d-countries-api%d".format(countries, Build.VERSION.SDK_INT)
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXITS, header(countries))
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ETHA_EXIT_WANT, "")
        val text = ArrayList<String>()
        val problems = ArrayList<String>()
        try {
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                scenario.onActivity { a -> HomeActivity::class.java.getDeclaredMethod("openServers").apply { isAccessible = true }.invoke(a) }
                // shown and laid out the same three looks in a row (the sheet settles, the bars' insets arrive)
                var lastKey = ""
                var same = 0
                val until = SystemClock.uptimeMillis() + 20_000L
                while (SystemClock.uptimeMillis() < until && same < 3) {
                    SystemClock.sleep(400)
                    inst.waitForIdleSync()
                    scenario.onActivity { a ->
                        val d = sheetOf(a)
                        val now = if (d != null && d.isShowing && d.findViewById<View>(R.id.exit_scroll)?.isLaidOut == true) geometry(a, d).key else ""
                        if (now.isNotEmpty() && now == lastKey) same++ else same = 0
                        lastKey = now
                    }
                }
                scenario.onActivity { a ->
                    val d = sheetOf(a)
                    if (d == null || !d.isShowing) {
                        problems.add("the sheet did not open")
                    } else {
                        val g = geometry(a, d)
                        text.add("$countries countries and Auto = ${g.rowCount} rows; Android API ${Build.VERSION.SDK_INT}; screen ${g.screenW} x ${g.screenH} px at density ${g.density} " +
                            "(${Math.round(g.screenW / g.density)} x ${Math.round(g.screenH / g.density)} dp); Configuration.screenHeightDp ${g.cfgH}; " +
                            "status bar ${g.barTop} px, navigation bar ${g.barBottom} px")
                        text.addAll(g.lines("as opened"))
                        problems.addAll(g.problems("as opened", atEnd = false))
                        if (same < 3) problems.add("as opened: the sheet's layout did not settle in 20 s")
                    }
                }
                shot("$tag-1-opened")
                scenario.onActivity { a ->
                    val d = sheetOf(a)
                    val list = d?.findViewById<View>(R.id.exit_scroll)
                    val rows = d?.findViewById<LinearLayout>(R.id.exit_rows)
                    if (list != null && rows != null) list.scrollTo(0, rows.height)      // the end of the list (the scroll view clamps it)
                }
                SystemClock.sleep(600)
                inst.waitForIdleSync()
                scenario.onActivity { a ->
                    val d = sheetOf(a)
                    if (d != null && d.isShowing) {
                        val g = geometry(a, d)
                        text.addAll(g.lines("at the end of the list"))
                        problems.addAll(g.problems("at the end of the list", atEnd = true))
                    }
                }
                shot("$tag-2-end")
            }
        } catch (e: Throwable) {
            problems.add("the test itself failed: $e")
        }
        text.add(if (problems.isEmpty()) "RESULT: a country can be chosen" else "RESULT: FAILED\n   " + problems.joinToString("\n   "))
        File(out, "$tag.txt").writeText(text.joinToString("\n") + "\n")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun twoCountries() = look(2)

    @Test
    fun elevenCountries() = look(11)

    @Test
    fun twentyCountries() = look(20)
}
