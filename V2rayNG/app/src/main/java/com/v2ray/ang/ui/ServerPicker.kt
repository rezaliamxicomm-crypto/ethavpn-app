package com.v2ray.ang.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.MmkvManager

/**
 * The server choice on Home and in Settings: `Auto (fastest)` first, then every line of the
 * subscription with its last real-delay result, and a `Test again` button. Picking a line pins
 * it; picking Auto hands the choice back to [AutoSelect] and the watchdog.
 */
object ServerPicker {

    /** One row of the list; guid null = Auto. */
    data class Row(val guid: String?, val text: String)

    /** Pure: the rows in body order. */
    fun rows(candidates: List<AutoSelect.Candidate>, nameOf: (String) -> String, auto: String, untested: String, failed: String): List<Row> =
        listOf(Row(null, auto)) + candidates.map { c ->
            val ping = when {
                c.delayMs > 0 -> "${c.delayMs} ms"
                c.delayMs < 0 -> failed
                else -> untested
            }
            Row(c.guid, "${nameOf(c.guid)}  ·  $ping")
        }

    /** What the Home button shows for the current choice. */
    fun currentLabel(context: Context, pinned: Boolean): String {
        if (!pinned) return context.getString(R.string.etha_server_auto)
        val guid = MmkvManager.getSelectServer() ?: return context.getString(R.string.etha_server_auto)
        val profile = MmkvManager.decodeServerConfig(guid) ?: return context.getString(R.string.etha_server_auto)
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        return if (delay > 0) "${profile.remarks}  ·  $delay ms" else profile.remarks
    }

    fun show(context: Context, subId: String, pinned: Boolean, onPick: (String?) -> Unit, onTest: () -> Unit): AlertDialog {
        val candidates = AutoSelect.candidates(subId)
        val rows = rows(
            candidates,
            nameOf = { guid -> MmkvManager.decodeServerConfig(guid)?.remarks ?: guid },
            auto = context.getString(R.string.etha_server_auto),
            untested = context.getString(R.string.etha_ping_untested),
            failed = context.getString(R.string.etha_ping_failed)
        )
        val selected = MmkvManager.getSelectServer()
        val checked = if (!pinned) 0 else rows.indexOfFirst { it.guid != null && it.guid == selected }.takeIf { it > 0 } ?: 0
        return AlertDialog.Builder(context)
            .setTitle(R.string.etha_server_pick)
            .setSingleChoiceItems(rows.map { it.text }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onPick(rows[which].guid)
            }
            .setNeutralButton(R.string.etha_test_again) { dialog, _ ->
                dialog.dismiss()
                onTest()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
