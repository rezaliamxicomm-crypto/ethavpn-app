package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.AutoPolicy
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.ConcurrentHashMap

/**
 * SkyRay's Auto in the service (the same design as the iOS app's AutoEngine): the running core holds every line of the
 * subscription behind one random balancer (CoreConfigManager.getV2rayConfigForLines), this holds the balancer to one
 * line (the core library's SetBalancerTarget) and measures the lines through that same core (MeasureOutbounds: a
 * HEAD request forced down each line, the warm round trip kept). The rules are AutoPolicy's:
 *
 *   - at start every line is measured at once and new connections move to the best when the line in use failed or is
 *     beaten by the margin; a change of line cuts nothing that is open;
 *   - while the tunnel is in use (traffic within idleSec) every line is measured again every roundSec, a failed line
 *     every failedRetrySec; idle, nothing runs;
 *   - a stall — bytes out, none back for stallSec, read from the inbound's counters every TICK_MS — probes the line in
 *     use at once, and a failed probe moves to the best of the rest, measured anew; a pinned line is never moved;
 *   - every result lands in the picker as it is measured (MMKV + MSG_MEASURE_CONFIG_SUCCESS); a move is said with
 *     MSG_ETHA_SWITCHED, the outcome of a "Ping all" with MSG_ETHA_ROUND_DONE.
 */
class EthaAuto(
    private val service: Service,
    private val controller: CoreController,
    private val lines: List<Line>,
    start: String,
) {
    /** One line of the subscription: its profile and its outbound tag in the running core. */
    data class Line(val guid: String, val tag: String, val order: Int)

    private val policy = AutoPolicy.current()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val measuring = Mutex()
    private val byGuid = lines.associateBy { it.guid }
    private val inboundTag = if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()) "tun" else "socks"

    @Volatile var current: String = start
        private set
    private val results = ConcurrentHashMap<String, Long>()     // guid → ms, -1 failed
    private val probedAt = ConcurrentHashMap<String, Long>()
    private val samples = ArrayDeque<Triple<Long, Long, Long>>()  // time, bytes up, bytes down
    @Volatile private var lastTrafficAt = now()
    @Volatile private var lastRoundAt = 0L
    @Volatile private var lastStallProbeAt = 0L

    fun start() {
        LogUtil.i(AppConfig.TAG, "Auto: ${lines.size} lines behind the balancer, starting on ${nameOf(current)}; $policy")
        scope.launch {
            race(reason = "connect", userAsked = false)
            while (isActive) {
                delay(TICK_MS)
                try { tick() } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Auto: tick failed", e) }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    /** The customer asked for every line to be measured now (Ping all, or Auto chosen again after a pick). */
    fun pingAll() {
        scope.launch { race(reason = "asked", userAsked = true) }
    }

    /** Wi-Fi ↔ mobile: what was measured no longer holds; measure again once the network has settled. */
    fun onNetworkChanged() {
        synchronized(samples) { samples.clear() }
        scope.launch {
            delay(NETWORK_SETTLE_MS)
            race(reason = "network", userAsked = false)
        }
    }

    /** The customer picked a line (pinned it): new connections take it now, nothing reconnects. */
    fun switchTo(guid: String, reason: String, notify: Boolean = true): Boolean {
        val line = byGuid[guid] ?: return false
        try {
            controller.setBalancerTarget(AppConfig.TAG_BALANCER, line.tag)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the balancer refused ${nameOf(guid)}", e)
            return false
        }
        current = guid
        MmkvManager.setSelectServer(guid)
        LogUtil.i(AppConfig.TAG, "Auto: new connections take ${nameOf(guid)} ($reason)")
        if (notify) MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_SWITCHED, guid)
        return true
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun tick() {
        val now = now()
        sampleTraffic(now)
        val stalled = synchronized(samples) { policy.stalled(samples.toList(), now) }
        if (stalled && now - lastStallProbeAt >= STALL_PROBE_GAP_MS && !pinned()) {
            lastStallProbeAt = now
            probeCurrent()
            return
        }
        val inUse = now - lastTrafficAt < policy.idleMs
        if (inUse && now - lastRoundAt >= policy.roundMs) round()
    }

    private fun sampleTraffic(now: Long) {
        val parts = try { controller.inboundTraffic(inboundTag).split(',') } catch (_: Exception) { return }
        val up = parts.getOrNull(0)?.toLongOrNull() ?: return
        val down = parts.getOrNull(1)?.toLongOrNull() ?: return
        synchronized(samples) {
            val last = samples.lastOrNull()
            if (last != null && (up != last.second || down != last.third)) lastTrafficAt = now
            samples.addLast(Triple(now, up, down))
            while (samples.size > SAMPLES_KEPT) samples.removeFirst()
        }
    }

    /** The line in use looks dead: one probe of it; failed, the rest measured anew and the best takes over. */
    private suspend fun probeCurrent() {
        val line = byGuid[current] ?: return
        val r = measure(listOf(line), concurrency = 1)
        if ((r[line.guid] ?: -1L) > 0) return
        LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} did not answer after a stall")
        measure(lines.filter { it.guid != current }, concurrency = lines.size)
        lastRoundAt = now()
        decide(reason = "stall", notify = true)
    }

    private suspend fun round() {
        lastRoundAt = now()
        val now = now()
        val due = lines.filter { policy.due(now, probedAt[it.guid] ?: 0L, (results[it.guid] ?: 0L) < 0) }
        if (due.isEmpty()) return
        measure(due, policy.concurrency)
        decide(reason = "round", notify = true)
    }

    private suspend fun race(reason: String, userAsked: Boolean) {
        measure(lines, concurrency = lines.size)
        lastRoundAt = now()
        val moved = decide(reason, notify = !userAsked)
        if (userAsked) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_ROUND_DONE, (if (moved) "switched:" else "kept:") + current)
        }
    }

    /**
     * The lines measured through the running core, at most concurrency at once; every result goes to the picker as it
     * is known. Lines the core did not report (it stopped meanwhile) keep their last result.
     */
    private suspend fun measure(which: List<Line>, concurrency: Int): Map<String, Long> = measuring.withLock {
        if (which.isEmpty()) return emptyMap()
        val raw = try {
            controller.measureOutbounds(which.joinToString(",") { it.tag }, SettingsManager.getDelayTestUrl(),
                policy.probeTimeoutMs, concurrency.toLong())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the measurement failed", e); ""
        }
        val byTag = which.associateBy { it.tag }
        val out = HashMap<String, Long>()
        val t = now()
        for (entry in raw.split(';')) {
            val i = entry.lastIndexOf('=')
            if (i <= 0) continue
            val line = byTag[entry.substring(0, i)] ?: continue
            val ms = entry.substring(i + 1).toLongOrNull() ?: continue
            results[line.guid] = ms
            probedAt[line.guid] = t
            out[line.guid] = ms
            MmkvManager.encodeServerTestDelayMillis(line.guid, ms)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, line.guid)
        }
        // no MSG_MEASURE_CONFIG_FINISH: that is the test service's, and the view model's finish handler may sort or
        // remove servers by their results (v2rayNG's own options) — a round must never do that; the results are fresh
        AutoSelect.markTested()
        LogUtil.i(AppConfig.TAG, "Auto: measured ${out.size} of ${which.size} lines, " +
            out.entries.sortedBy { it.value.takeIf { v -> v > 0 } ?: Long.MAX_VALUE }.take(6).joinToString { "${nameOf(it.key)}=${it.value}" })
        out
    }

    private fun decide(reason: String, notify: Boolean): Boolean {
        val candidates = lines.map { AutoSelect.Candidate(it.guid, results[it.guid] ?: 0L, it.order) }
        val target = policy.target(current, candidates, pinned()) ?: return false
        return switchTo(target, reason, notify)
    }

    private fun pinned() = MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_PINNED, false)
    private fun nameOf(guid: String) = MmkvManager.decodeServerConfig(guid)?.remarks ?: guid
    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TICK_MS = 4_000L
        private const val SAMPLES_KEPT = 16
        private const val STALL_PROBE_GAP_MS = 20_000L
        private const val NETWORK_SETTLE_MS = 15_000L

        /**
         * The lines the selected profile's subscription holds, in its order, with their outbound tags — the members
         * of the balancer — or null when Auto does not apply: no subscription, fewer than two usable lines, or the
         * selected profile not among them (a complex profile, a profile of its own).
         */
        fun linesFor(selectedGuid: String): List<Line>? {
            val subId = MmkvManager.decodeServerConfig(selectedGuid)?.subscriptionId.orEmpty()
            if (subId.isEmpty()) return null
            val lines = usable(MmkvManager.decodeServerList(subId))
            if (lines.size < 2 || lines.none { it.guid == selectedGuid }) return null
            return lines
        }

        /** The usable lines among guids, tagged as getV2rayConfigForLines tags them (the same filter, the same order). */
        fun usable(guids: List<String>): List<Line> {
            val members = guids.mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
                .filter { (_, p) -> !p.configType.isComplexType() && p.server.isNotNullEmpty() }
            return members.mapIndexed { i, (guid, p) -> Line(guid, CoreConfigManager.policyGroupMemberTag(AppConfig.TAG_PROXY, i, p.remarks), i) }
        }

        /**
         * The app's test while the tunnel is down: the lines of one subscription measured through one throw-away core
         * (the core library's MeasureOutboundsIn) instead of one core per line. Null when the guids are not one
         * subscription's usable lines or the core would not build: the caller measures them one by one then.
         */
        fun measureOffline(context: Context, guids: List<String>): Map<String, Long>? {
            val lines = usable(guids)
            if (lines.isEmpty() || lines.size != guids.size) return null
            val subIds = lines.map { MmkvManager.decodeServerConfig(it.guid)?.subscriptionId.orEmpty() }.toSet()
            if (subIds.size != 1 || subIds.first().isEmpty()) return null
            val config = CoreConfigManager.getV2rayConfigForLines(context, lines.map { it.guid })
            if (!config.status) return null
            val policy = AutoPolicy.current()
            val raw = try {
                Libv2ray.measureOutboundsIn(config.content, lines.joinToString(",") { it.tag }, SettingsManager.getDelayTestUrl(),
                    policy.probeTimeoutMs, lines.size.toLong())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Auto: the offline measurement failed", e)
                return null
            }
            val byTag = lines.associateBy { it.tag }
            val out = HashMap<String, Long>()
            for (entry in raw.split(';')) {
                val i = entry.lastIndexOf('=')
                if (i <= 0) continue
                val line = byTag[entry.substring(0, i)] ?: continue
                out[line.guid] = entry.substring(i + 1).toLongOrNull() ?: continue
            }
            return out
        }
    }
}
