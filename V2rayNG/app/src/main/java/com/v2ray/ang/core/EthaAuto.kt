package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AutoPolicy
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.EthaReport
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SkyRay's Auto in the service (the same design as the iOS app's AutoEngine): the running core holds every line of the
 * subscription behind one random balancer (CoreConfigManager.getV2rayConfigForLines), this holds the balancer to one
 * line (the core library's SetBalancerTarget) and measures the lines through that same core (MeasureOutbounds: a
 * HEAD request forced down each line to the box's own probe target, the warm round trip kept). The rules are
 * AutoPolicy's:
 *
 *   - at start every line is measured at once and new connections move to the best when the line in use failed or is
 *     beaten by the margin; a change of line cuts nothing that is open;
 *   - while the tunnel is in use (traffic within idleSec) every line is measured again every roundSec, a failed line
 *     every failedRetrySec; idle, nothing runs;
 *   - a stall — bytes out, none back for stallSec, read from the inbound's counters every TICK_MS — probes the line in
 *     use at once, and a failed probe moves to the best of the rest, measured anew;
 *   - no line answers: the Searching state (MSG_ETHA_STATE) — every line again every searchSec, in use or not, and the
 *     subscription fetched again at once and every searchFetchSec for lines the box may have replaced; a fetch that
 *     brings other lines restarts the core on them (onLinesChanged);
 *   - the line in use's time goes to the UI as it is measured (MSG_ETHA_LATENCY: the chip); a move is said with
 *     MSG_ETHA_SWITCHED, the outcome of a "Test latency" with MSG_ETHA_ROUND_DONE; every round's results are kept as
 *     the report the next fetch carries to the box (EthaReport, the header X-Etha-Lines).
 *
 * Nobody picks a line by hand any more (2.0.0): the customer sees no line name, only the latency.
 */
class EthaAuto(
    private val service: Service,
    private val controller: CoreController,
    private val lines: List<Line>,
    start: String,
    private val onLinesChanged: () -> Unit,
) {
    /** One line of the subscription: its profile and its outbound tag in the running core. */
    data class Line(val guid: String, val tag: String, val order: Int)

    private val policy = AutoPolicy.current()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val measuring = Mutex()
    private val byGuid = lines.associateBy { it.guid }
    private val remarks = lines.associate { it.guid to (MmkvManager.decodeServerConfig(it.guid)?.remarks ?: it.guid) }
    private val inboundTag = if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()) "tun" else "socks"

    @Volatile var current: String = start
        private set
    private val results = ConcurrentHashMap<String, Long>()     // guid → ms, -1 failed
    private val probedAt = ConcurrentHashMap<String, Long>()
    private val samples = ArrayDeque<Triple<Long, Long, Long>>()  // time, bytes up, bytes down
    @Volatile private var lastTrafficAt = now()
    @Volatile private var lastRoundAt = 0L
    @Volatile private var roundIntervalMs = policy.roundMs   // this round's interval: roundSec with the jitter, drawn when a round ends
    @Volatile private var lastStallProbeAt = 0L
    @Volatile private var searching = false
    @Volatile private var lastSearchFetchAt = 0L
    private val fetching = AtomicBoolean(false)
    @Volatile private var probeUrl = AppConfig.ETHA_PROBE_URL
    @Volatile private var fallbackTried = false

    fun start() {
        LogUtil.i(AppConfig.TAG, "Auto: ${lines.size} lines behind the balancer, starting on ${nameOf(current)}; $policy")
        scope.launch {
            race(reason = "connect", userAsked = false)
            scheduleSettle()
            while (isActive) {
                delay(TICK_MS)
                try { tick() } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Auto: tick failed", e) }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    /** The customer pressed Test latency: every line measured now, the decision and the outcome said. */
    fun pingAll() {
        scope.launch { race(reason = "manual", userAsked = true) }
    }

    /** Wi-Fi ↔ mobile: what was measured no longer holds; measure again once the network has settled. */
    fun onNetworkChanged() {
        synchronized(samples) { samples.clear() }
        scope.launch {
            delay(NETWORK_SETTLE_MS)
            race(reason = "network", userAsked = false)
            scheduleSettle()
        }
    }

    /**
     * The first race measures every line at once on a core a second old and reads high: a settling round with the
     * normal concurrency, settleSec later, corrects the pick within seconds instead of at the next round.
     */
    private fun scheduleSettle() {
        if (policy.settleSec <= 0) return
        scope.launch {
            delay(policy.settleSec * 1000L)
            if (searching) return@launch
            LogUtil.i(AppConfig.TAG, "Auto: the settling round")
            measure(lines, policy.concurrency)
            markRound()
            val moved = decide(reason = "settle", notify = true)
            report("settle", moved)
            updateSearching()
        }
    }

    /** A round ended now; the next one comes after roundSec give or take the jitter. */
    private fun markRound() {
        lastRoundAt = now()
        roundIntervalMs = policy.jitteredRoundMs(Math.random())
    }

    /** New connections take the line now; what is open stays where it is. */
    private fun switchTo(guid: String, reason: String, notify: Boolean = true): Boolean {
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
        tellLatency()
        return true
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun tick() {
        val now = now()
        sampleTraffic(now)
        val stalled = synchronized(samples) { policy.stalled(samples.toList(), now) }
        if (stalled && now - lastStallProbeAt >= STALL_PROBE_GAP_MS && !searching) {
            lastStallProbeAt = now
            probeCurrent()
            return
        }
        if (searching) {
            if (now - lastRoundAt >= policy.searchMs) round()
            if (now - lastSearchFetchAt >= policy.searchFetchMs) searchFetch()
            return
        }
        val inUse = now - lastTrafficAt < policy.idleMs
        if (inUse && now - lastRoundAt >= roundIntervalMs) round()
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
        tellLatency()
        if ((r[line.guid] ?: -1L) > 0) return
        LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} did not answer after a stall")
        measure(lines.filter { it.guid != current }, concurrency = lines.size)
        markRound()
        val moved = decide(reason = "stall", notify = true)
        report("stall", moved)
        updateSearching()
    }

    private suspend fun round() {
        val interval = roundIntervalMs
        markRound()
        val now = now()
        val due = if (searching) lines else lines.filter { policy.due(now, probedAt[it.guid] ?: 0L, (results[it.guid] ?: 0L) < 0, interval) }
        if (due.isEmpty()) return
        measure(due, policy.concurrency)
        val moved = decide(reason = "round", notify = true)
        report("auto", moved)
        updateSearching()
    }

    private suspend fun race(reason: String, userAsked: Boolean) {
        measure(lines, concurrency = lines.size)
        markRound()
        val moved = decide(reason, notify = !userAsked)
        report(reason, moved)
        updateSearching()
        if (userAsked) {
            val outcome = when {
                lines.none { (results[it.guid] ?: 0L) > 0 } -> "none"
                moved -> "switched"
                else -> "kept"
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_ROUND_DONE, outcome)
        }
    }

    /**
     * The lines measured through the running core, at most concurrency at once. When a full set gets no answer at all
     * on the probe target, the fallback is tried once (a box that does not have the target yet); lines the core did
     * not report keep their last result.
     */
    private suspend fun measure(which: List<Line>, concurrency: Int): Map<String, Long> = measuring.withLock {
        if (which.isEmpty()) return emptyMap()
        var out = measureOnce(which, concurrency, probeUrl)
        if (which.size == lines.size && out.isNotEmpty() && out.values.all { it < 0 } && !fallbackTried) {
            fallbackTried = true
            val again = measureOnce(which, concurrency, AppConfig.ETHA_PROBE_FALLBACK_URL)
            if (again.values.any { it > 0 }) {
                LogUtil.w(AppConfig.TAG, "Auto: the probe target gave no answer on any line; measuring with the fallback from now on")
                probeUrl = AppConfig.ETHA_PROBE_FALLBACK_URL
                out = again
            }
        }
        val t = now()
        for ((guid, ms) in out) {
            results[guid] = ms
            probedAt[guid] = t
            MmkvManager.encodeServerTestDelayMillis(guid, ms)
        }
        AutoSelect.markTested()
        LogUtil.i(AppConfig.TAG, "Auto: measured ${out.size} of ${which.size} lines, " +
            out.entries.sortedBy { it.value.takeIf { v -> v > 0 } ?: Long.MAX_VALUE }.take(6).joinToString { "${nameOf(it.key)}=${it.value}" })
        if (which.any { it.guid == current }) tellLatency()
        out
    }

    private fun measureOnce(which: List<Line>, concurrency: Int, url: String): Map<String, Long> {
        val raw = try {
            controller.measureOutbounds(which.joinToString(",") { it.tag }, url, policy.probeTimeoutMs, concurrency.toLong())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the measurement failed", e); ""
        }
        val byTag = which.associateBy { it.tag }
        val out = HashMap<String, Long>()
        for (entry in raw.split(';')) {
            val i = entry.lastIndexOf('=')
            if (i <= 0) continue
            val line = byTag[entry.substring(0, i)] ?: continue
            out[line.guid] = entry.substring(i + 1).toLongOrNull() ?: continue
        }
        return out
    }

    private fun decide(reason: String, notify: Boolean): Boolean {
        val candidates = lines.map { AutoSelect.Candidate(it.guid, results[it.guid] ?: 0L, it.order) }
        val target = policy.target(current, candidates, pinned = false) ?: return false
        return switchTo(target, reason, notify)
    }

    /** The line in use's time to the UI: the chip's number. */
    private fun tellLatency() {
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_LATENCY, (results[current] ?: 0L).toString())
    }

    /** What this round measured, kept for the next fetch (the header X-Etha-Lines). */
    private fun report(trigger: String, switched: Boolean) {
        val header = EthaReport.build(now(), trigger, remarks[current], switched, lines.map { (remarks[it.guid] ?: "") to (results[it.guid] ?: 0L) })
        if (header != null) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_REPORT, header)
    }

    /** Searching = every line measured and none answering. Entering it fetches the subscription at once. */
    private fun updateSearching() {
        val dead = lines.all { (results[it.guid] ?: 0L) < 0 }
        if (dead == searching) return
        searching = dead
        LogUtil.i(AppConfig.TAG, if (dead) "Auto: no line answers — searching" else "Auto: a line answers again")
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_STATE, if (dead) "searching" else "ok")
        if (dead) searchFetch()
    }

    /** The subscription fetched again (the link's own route works when the tunnel does not); other lines → the core restarts on them. */
    private fun searchFetch() {
        if (!fetching.compareAndSet(false, true)) return
        lastSearchFetchAt = now()
        scope.launch {
            try {
                AngConfigManager.updateConfigViaSubAll()
                if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_LINES_CHANGED, false)) {
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINES_CHANGED, false)
                    LogUtil.i(AppConfig.TAG, "Auto: the fetch brought other lines; the core restarts on them")
                    onLinesChanged()
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Auto: the fetch while searching failed: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun nameOf(guid: String) = remarks[guid] ?: guid
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
         * (the core library's MeasureOutboundsIn) against the box's probe target, the fallback once when nothing
         * answered. Null when the guids are not one subscription's usable lines or the core would not build.
         */
        fun measureOffline(context: Context, guids: List<String>): Map<String, Long>? {
            val lines = usable(guids)
            if (lines.isEmpty() || lines.size != guids.size) return null
            val subIds = lines.map { MmkvManager.decodeServerConfig(it.guid)?.subscriptionId.orEmpty() }.toSet()
            if (subIds.size != 1 || subIds.first().isEmpty()) return null
            val config = CoreConfigManager.getV2rayConfigForLines(context, lines.map { it.guid })
            if (!config.status) return null
            val policy = AutoPolicy.current()
            fun once(url: String): Map<String, Long>? {
                val raw = try {
                    Libv2ray.measureOutboundsIn(config.content, lines.joinToString(",") { it.tag }, url, policy.probeTimeoutMs, lines.size.toLong())
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
            val first = once(AppConfig.ETHA_PROBE_URL) ?: return null
            if (first.values.any { it > 0 }) return first
            return once(AppConfig.ETHA_PROBE_FALLBACK_URL) ?: first
        }
    }
}
