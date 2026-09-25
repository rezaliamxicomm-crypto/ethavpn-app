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

    private val ETHA_NAME = Regex("^EthaVPN-([A-Za-z0-9]+)-[^-]+-(.+?)-(\\d+)$")

    /**
     * The server names the subscription carries are `EthaVPN-XHTTP-fra-CleanIP1-443`; a customer
     * needs `CleanIP1 · XHTTP/443`. Anything else is shown as it is.
     */
    fun displayName(remarks: String): String {
        val m = ETHA_NAME.find(remarks.trim()) ?: return remarks
        val (proto, kind, port) = m.destructured
        return "$kind · $proto/$port"
    }

    /** Pure: Auto first, then the lines fastest first (untested, then failed, at the end); the ping leads each row. */
    fun rows(candidates: List<AutoSelect.Candidate>, nameOf: (String) -> String, auto: String, untested: String, failed: String): List<Row> {
        val sorted = candidates.sortedWith(compareBy({ if (it.delayMs > 0) 0 else if (it.delayMs == 0L) 1 else 2 }, { it.delayMs }, { it.order }))
        return listOf(Row(null, auto)) + sorted.map { c ->
            val ping = when {
                c.delayMs > 0 -> "${c.delayMs} ms"
                c.delayMs < 0 -> failed
                else -> untested
            }
            Row(c.guid, "$ping  ·  ${displayName(nameOf(c.guid))}")
        }
    }

    /** What the server field shows: Auto with the line it picked (once one is selected), or the pinned line. */
    fun currentLabel(context: Context, pinned: Boolean): String {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val delay = guid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis } ?: 0L
        val line = profile?.let { if (delay > 0) "${displayName(it.remarks)} (${delay} ms)" else displayName(it.remarks) }
        return when {
            !pinned && line == null -> context.getString(R.string.etha_server_auto)
            !pinned -> context.getString(R.string.etha_auto_picked, line)
            line == null -> context.getString(R.string.etha_server_auto)
            else -> line
        }
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
