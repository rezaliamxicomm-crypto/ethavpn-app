package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AutoDecision
import com.v2ray.ang.handler.AutoPolicy
import com.v2ray.ang.handler.AutoSchedule
import com.v2ray.ang.handler.AutoSelect
import com.v2ray.ang.handler.EthaReport
import com.v2ray.ang.handler.Hint
import com.v2ray.ang.handler.LineHealth
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
 * SkyRay's Auto in the service — the same design as the iOS app's AutoEngine (2.1). The running core holds every line
 * of the subscription behind one random balancer (CoreConfigManager.getV2rayConfigForLines); this holds the balancer to
 * one line (the core library's SetBalancerTarget) and measures the lines through that same core (MeasureOutbounds: a
 * HEAD request forced down each line to the box's own probe target, the warm round trip kept). The rules are the pure
 * objects': AutoSchedule (when), AutoDecision (whether to move), LineHealth (what is known of each line on this
 * network), AutoPolicy (the numbers), Hint (the box's advice):
 *
 *   - no bursts: after Connect the line in use is probed first (its number on the chip within a second), then the first
 *     pass — every other line, two at a time, a step a second, best-known first (this network's memory, else the box's
 *     hint, else the subscription's order); then the steady trickle, one probe per roundSec/N, give or take the jitter,
 *     only while the radio is warm (bytes moved within warmSec) or the app is on screen (MSG_ETHA_WATCH); the line in
 *     use every currentSec while on screen; a pass ended is a decision and a report;
 *   - moves (AutoDecision): the first pass corrects a cold pick once, for a line clearly better; steady state moves only
 *     for a lead that persists over two pass ends (or is twice the margin), never within the dwell; a manual test by the
 *     margin alone. A change of line moves new connections only and cuts nothing that is open;
 *   - failover, make-before-break: a stall (bytes out, none back for stallSec) or a failed probe of the line in use
 *     probes the line in use and the best-known alternative together within failoverMs; the line in use answering means
 *     nothing happens; it failing and the alternative answering moves at once; the alternative failing too tries the
 *     next best once; then Searching. One quiet fetch follows a failover (at most one in ten minutes);
 *   - a failed line backs off (15 → 30 → 60 → 120 min) and the memory of the network is kept across sessions (MMKV, per
 *     network signature); a network change forgives every line and restarts the first pass in 15 s; idle, nothing runs,
 *     and traffic returning probes the line in use at once;
 *   - Searching (no line answers): the fast trickle over every line, retries ignored, until one answers, and the
 *     subscription fetched again at once and every searchFetchSec; the hint's outage flag changes the text ("outage");
 *   - the box's probe target, the fallback only while the target answers nobody (one probe of the line in use against
 *     the fallback decides, the first probe of every pass is a scout back);
 *   - the line in use's time goes to the UI as it is measured (MSG_ETHA_LATENCY: the chip); a move is said with
 *     MSG_ETHA_SWITCHED, the outcome of a "Test latency" with MSG_ETHA_ROUND_DONE; every pass's results are kept as the
 *     report the next fetch carries to the box (EthaReport, the header X-Etha-Lines) with the best five-second download
 *     rate seen on the line in use during the pass — the passive throughput figure the box's ranking folds in.
 *
 * Nobody picks a line by hand any more (2.0.0): the customer sees no line name, only the latency.
 */
class EthaAuto(
    private val service: Service,
    private val controller: CoreController,
    private val lines: List<Line>,
    start: String,
    network: String,
    private val onLinesChanged: () -> Unit,
) {
    /** One line of the subscription: its profile and its outbound tag in the running core. */
    data class Line(val guid: String, val tag: String, val order: Int)

    private val policy = AutoPolicy.current()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val measuring = Mutex()
    private val byGuid = lines.associateBy { it.guid }
    private val remarks = lines.associate { it.guid to (MmkvManager.decodeServerConfig(it.guid)?.remarks ?: it.guid) }
    private val labels = lines.associate { it.guid to (EthaReport.label(remarks[it.guid]) ?: remarks[it.guid] ?: it.guid) }
    private val byLabel = labels.entries.associate { (guid, label) -> label to guid }
    private val inboundTag = if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()) "tun" else "socks"

    @Volatile var current: String = start
        private set
    private val results = ConcurrentHashMap<String, Long>()     // guid → ms, -1 failed
    private val schedule = AutoSchedule()
    private val decision = AutoDecision()
    @Volatile private var network = network
    private var health = LineHealth.decode(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINE_HEALTH + network))
    private val hint: Hint? = Hint.parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_HINT))
    private val samples = ArrayDeque<Triple<Long, Long, Long>>()  // wall time, bytes up, bytes down (the stall detector)
    private val rateSamples = ArrayDeque<Pair<Long, Long>>()      // monotonic time, bytes down (the rate window)
    @Volatile private var passRateKbps = 0L
    @Volatile private var lastTrafficAt = mono()
    @Volatile private var wasInUse = true
    @Volatile private var lastStallProbeAt = -1_000_000L
    @Volatile private var watchedAt = -1_000_000L
    @Volatile private var searching = false
    @Volatile private var lastSearchFetchAt = 0L
    @Volatile private var lastFailoverFetchAt = -1_000_000L
    @Volatile private var movedInPass = false
    private val fetching = AtomicBoolean(false)

    fun start() {
        val all = lines.map { labels.getValue(it.guid) }
        // the start line: the best this network is known to have, else the hint's first, else what the service chose
        if (health.lastMs(labels.getValue(current)) == null) {
            val known = health.bestKnown(all)
            val advice = hint
            val best = known ?: advice?.arrange(all)?.firstOrNull { it !in advice.dead }
            val guid = best?.let { byLabel[it] }
            if (guid != null && guid != current) switchTo(guid, if (known != null) "known best on $network" else "the box's advice", notify = false)
        }
        schedule.start(mono(), order())
        LogUtil.i(AppConfig.TAG, "Auto: ${lines.size} lines behind the balancer, on ${nameOf(current)}; network $network, ${health.entries.size} lines remembered" +
            (hint?.let { ", the box advises ${it.order.size} (${it.dead.size} dead${if (it.outage) ", outage" else ""})" } ?: "") + "; $policy")
        scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Auto: tick failed", e) }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        scope.cancel()
        saveHealth()
    }

    /** The Home screen is showing the chip: the line in use is kept fresh for it. */
    fun noteWatched() { watchedAt = mono() }

    /** The customer pressed Test latency: every line measured now, the decision by the margin alone, the outcome said. */
    fun pingAll() {
        scope.launch {
            measuring.withLock {
                measureSet(lines, policy.concurrency, policy.probeTimeoutMs)
                val moved = decide(AutoDecision.Phase.MANUAL, "manual", notify = false)
                report("manual", moved)
                updateSearchingLocked()
                if (!searching) schedule.reseed(mono(), order(), policy, Math.random())
                movedInPass = false
                saveHealth()
                val outcome = when {
                    lines.none { (results[it.guid] ?: 0L) > 0 } -> "none"
                    moved -> "switched"
                    else -> "kept"
                }
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_ROUND_DONE, outcome)
            }
        }
    }

    /**
     * Wi-Fi ↔ mobile (or the network back after a gap): what was measured no longer holds. The memory of the new network
     * takes over, every line is forgiven, the first pass restarts in 15 s; a second event within a minute is the same.
     */
    fun onNetworkChanged(signature: String) {
        if (signature != network) {
            saveHealth()
            network = signature
            health = LineHealth.decode(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINE_HEALTH + network))
        }
        health.forgive()
        synchronized(samples) { samples.clear(); rateSamples.clear() }
        decision.firstSwitchDone = false
        val before = schedule.lastNetworkAt
        schedule.networkChanged(mono(), NETWORK_SETTLE_MS)
        if (schedule.lastNetworkAt != before) {
            schedule.order = order()
            LogUtil.i(AppConfig.TAG, "Auto: the network changed ($network); the first pass runs again from ${NETWORK_SETTLE_MS / 1000} s on")
        }
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun tick() {
        val now = mono()
        sampleTraffic(now)
        if (measuring.isLocked) return
        val stalled = synchronized(samples) { policy.stalled(samples.toList(), System.currentTimeMillis()) }
        if (stalled && !searching && now - lastStallProbeAt >= STALL_PROBE_GAP_MS) {
            lastStallProbeAt = now
            LogUtil.w(AppConfig.TAG, "Auto: bytes out and none back for ${policy.stallSec} s on ${nameOf(current)}")
            measuring.withLock { failoverLocked("stall") }
            return
        }
        if (searching && now - lastSearchFetchAt >= policy.searchFetchMs) searchFetch()
        val warm = now - lastTrafficAt < policy.warmMs
        val inUse = now - lastTrafficAt < policy.idleMs
        if (inUse && !wasInUse) { schedule.wake(now); LogUtil.i(AppConfig.TAG, "Auto: traffic is back after the idle gap; measuring now") }
        wasInUse = inUse
        val watched = now - watchedAt < WATCHED_FOR_MS
        val wallSec = System.currentTimeMillis() / 1000
        val action = schedule.action(now, warm, watched, searching, current) { guid -> health.due(labels.getValue(guid), wallSec) }
        when (action) {
            is AutoSchedule.Action.Probe -> measuring.withLock { if (searching) searchStepLocked(action.ids) else probeLinesLocked(action.ids) }
            AutoSchedule.Action.ProbeCurrent -> measuring.withLock { probeCurrentLocked() }
            null -> {
                val n = schedule.nextProbeAt
                if (!searching && n != null && now >= n && (warm || watched || schedule.forced || schedule.phase == AutoSchedule.Phase.FIRST)) {
                    schedule.nothingDue(now, policy, Math.random())   // a step whose time has come with nothing to probe: spent
                }
            }
        }
    }

    /** The counters every tick: traffic (the idle and warm clocks, the stall detector) and the download rate window. */
    private fun sampleTraffic(now: Long) {
        val parts = try { controller.inboundTraffic(inboundTag).split(',') } catch (_: Exception) { return }
        val up = parts.getOrNull(0)?.toLongOrNull() ?: return
        val down = parts.getOrNull(1)?.toLongOrNull() ?: return
        synchronized(samples) {
            val last = samples.lastOrNull()
            if (last != null && (up != last.second || down != last.third)) lastTrafficAt = now
            samples.addLast(Triple(System.currentTimeMillis(), up, down))
            while (samples.size > SAMPLES_KEPT) samples.removeFirst()
            rateSamples.addLast(now to down)
            while (rateSamples.isNotEmpty() && now - rateSamples.first().first > RATE_WINDOW_MS + TICK_MS) rateSamples.removeFirst()
            val base = rateSamples.firstOrNull { now - it.first >= RATE_WINDOW_MS } ?: rateSamples.first()
            if (now - base.first >= RATE_WINDOW_MS - 500) {
                val bytes = down - base.second
                if (bytes >= RATE_MIN_BYTES) {
                    val kbps = bytes * 8 * 1000 / (now - base.first) / 1000
                    if (kbps > passRateKbps) passRateKbps = kbps
                }
            }
        }
    }

    // ---------------------------------------------------------------- the trickle (under the measuring lock)

    /** The lines in the order the trickle takes them: this network's memory first, the box's advice where the memory is empty, else the subscription's. */
    private fun order(): List<String> {
        val all = lines.map { labels.getValue(it.guid) }
        var arranged = health.bestFirst(all)
        if (health.entries.isEmpty()) hint?.let { arranged = it.arrange(all) }
        return arranged.mapNotNull { byLabel[it] }
    }

    /** One step of the trickle (one line; two in the first pass): measured, recorded; the first pass may correct the
     *  start once; a wrapped rotation is a pass — the steady decision and the report. */
    private fun probeLinesLocked(ids: List<String>) {
        val which = ids.mapNotNull { byGuid[it] }
        if (which.isEmpty()) return
        val scout = schedule.fallback && schedule.cursor == 0   // the first probe of a pass on the fallback tries the target
        val url = if (schedule.fallback && !scout) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL
        val out = measureOnce(which, which.size, url, policy.probeTimeoutMs)
        if (scout) {
            val back = out.values.any { it > 0 }
            schedule.fallback = AutoSchedule.nextFallback(current = true, targetAnswered = back, fallbackAnswered = false)
            if (back) LogUtil.i(AppConfig.TAG, "Auto: the probe target answers again; measuring against it")
        }
        record(out, which)
        val first = schedule.phase == AutoSchedule.Phase.FIRST
        val passEnd = schedule.probed(mono(), which.size, policy, Math.random())
        if (first && decide(AutoDecision.Phase.FIRST, "first pass", notify = true)) movedInPass = true
        if (passEnd) {
            if (!first && decide(AutoDecision.Phase.PASS_END, "pass end", notify = true)) movedInPass = true
            report(if (first) "first" else "auto", movedInPass)
            movedInPass = false
            passRateKbps = 0
            saveHealth()
        }
        updateSearchingLocked()
    }

    /** The line in use's own probe (every currentSec while the app is on screen, and when traffic returns): its number to the chip; failed, the failover. */
    private fun probeCurrentLocked() {
        val line = byGuid[current] ?: return
        val out = measureOnce(listOf(line), 1, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.probeTimeoutMs)
        record(out, listOf(line))
        schedule.currentProbed(mono(), policy)
        if ((out[line.guid] ?: -1L) < 0) {
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} did not answer its probe")
            failoverLocked("probe")
        }
    }

    /** Make-before-break: the line in use and the best-known alternative probed together within failoverMs. The line in
     *  use answering: nothing moves. It failing and the alternative answering: new connections move at once. The
     *  alternative failing too: the next best, once more; nothing: Searching decides. */
    private fun failoverLocked(reason: String, attempt: Int = 1) {
        val cur = byGuid[current] ?: return
        val all = lines.map { labels.getValue(it.guid) }
        val mine = labels.getValue(current)
        val advice = hint
        val altLabel = health.bestKnown(all, except = mine)
            ?: advice?.arrange(all)?.firstOrNull { it != mine && it !in advice.dead }
            ?: all.firstOrNull { it != mine }
        val alt = altLabel?.let { byLabel[it] }?.let { byGuid[it] }
        val which = listOfNotNull(cur, alt)
        val out = measureOnce(which, which.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.failoverMs.toLong())
        record(out, which)
        if ((out[cur.guid] ?: -1L) > 0) {
            LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(current)} answers within ${policy.failoverMs} ms ($reason): staying")
            return
        }
        if (alt != null && (out[alt.guid] ?: -1L) > 0) {
            switchTo(alt.guid, "failover after $reason")
            decision.moved(mono())
            report("failover", true)
            saveHealth()
            failoverFetch()
            return
        }
        if (attempt < 2 && alt != null) { failoverLocked(reason, attempt + 1); return }
        updateSearchingLocked()
    }

    /** Searching: a step of the fast trickle (two lines, a second a step, every line in turn). The first to answer takes new connections. */
    private fun searchStepLocked(ids: List<String>) {
        val which = ids.mapNotNull { byGuid[it] }
        if (which.isEmpty()) return
        val out = measureOnce(which, which.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.probeTimeoutMs)
        record(out, which)
        schedule.searchStep(mono(), which.size)
        val found = which.firstOrNull { (out[it.guid] ?: -1L) > 0 }
        if (found != null && found.guid != current) {
            switchTo(found.guid, "a line answers again")
            decision.moved(mono())
        }
        updateSearchingLocked()
    }

    // ---------------------------------------------------------------- measuring

    /** A set of lines at once (a manual test): the box's target; nothing from it on any line and something from the fallback moves the probes to the fallback until the target answers again. */
    private fun measureSet(which: List<Line>, concurrency: Int, timeoutMs: Long): Map<String, Long> {
        if (which.isEmpty()) return emptyMap()
        val out: Map<String, Long>
        if (schedule.fallback) {
            val scout = which.firstOrNull { it.guid == current } ?: which[0]
            val one = measureOnce(listOf(scout), 1, AppConfig.ETHA_PROBE_URL, timeoutMs)
            val back = (one[scout.guid] ?: -1L) > 0
            schedule.fallback = AutoSchedule.nextFallback(current = true, targetAnswered = back, fallbackAnswered = false)
            if (back) LogUtil.i(AppConfig.TAG, "Auto: the probe target answers again; measuring against it")
            out = measureOnce(which, concurrency, if (back) AppConfig.ETHA_PROBE_URL else AppConfig.ETHA_PROBE_FALLBACK_URL, timeoutMs)
        } else {
            var first = measureOnce(which, concurrency, AppConfig.ETHA_PROBE_URL, timeoutMs)
            if (which.size == lines.size && first.isNotEmpty() && first.values.all { it < 0 }) {
                val again = measureOnce(which, concurrency, AppConfig.ETHA_PROBE_FALLBACK_URL, timeoutMs)
                val answered = again.values.any { it > 0 }
                schedule.fallback = AutoSchedule.nextFallback(current = false, targetAnswered = false, fallbackAnswered = answered)
                if (answered) {
                    LogUtil.w(AppConfig.TAG, "Auto: the probe target gave no answer on any line; measuring against the fallback until it answers again")
                    first = again
                }
            }
            out = first
        }
        record(out, which)
        return out
    }

    private fun measureOnce(which: List<Line>, concurrency: Int, url: String, timeoutMs: Long): Map<String, Long> {
        val raw = try {
            controller.measureOutbounds(which.joinToString(",") { it.tag }, url, timeoutMs, concurrency.toLong())
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
        for (line in which) if (line.guid !in out) out[line.guid] = -1L   // a line the core did not report did not answer
        return out
    }

    /** Results into the engine, the memory of the network and the app's store; the line in use's number to the chip. */
    private fun record(out: Map<String, Long>, which: List<Line>) {
        val wallSec = System.currentTimeMillis() / 1000
        for ((guid, ms) in out) {
            results[guid] = ms
            MmkvManager.encodeServerTestDelayMillis(guid, ms)
            if (ms > 0) health.ok(labels.getValue(guid), ms, wallSec) else health.failed(labels.getValue(guid), wallSec, policy)
        }
        AutoSelect.markTested()
        LogUtil.i(AppConfig.TAG, "Auto: measured ${out.size} line(s): " +
            out.entries.sortedBy { it.value.takeIf { v -> v > 0 } ?: Long.MAX_VALUE }.take(6).joinToString { "${nameOf(it.key)}=${it.value}" })
        if (which.any { it.guid == current }) tellLatency()
    }

    // ---------------------------------------------------------------- deciding

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
        passRateKbps = 0
        MmkvManager.setSelectServer(guid)
        LogUtil.i(AppConfig.TAG, "Auto: new connections take ${nameOf(guid)} ($reason)")
        if (notify) MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_SWITCHED, guid)
        tellLatency()
        return true
    }

    private fun decide(phase: AutoDecision.Phase, reason: String, notify: Boolean): Boolean {
        val candidates = lines.map { AutoDecision.Candidate(it.guid, results[it.guid] ?: 0L, it.order) }
        val cur = candidates.firstOrNull { it.id == current } ?: return false
        val target = decision.target(mono(), phase, cur, candidates, policy) ?: return false
        return switchTo(target, reason, notify)
    }

    /** The line in use's time to the UI: the chip's number. */
    private fun tellLatency() {
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_LATENCY, (results[current] ?: 0L).toString())
    }

    /** What was measured, kept for the next fetch (the header X-Etha-Lines), with the pass's best download rate. */
    private fun report(trigger: String, switched: Boolean) {
        val header = EthaReport.build(System.currentTimeMillis(), trigger, remarks[current], switched,
            lines.map { (remarks[it.guid] ?: "") to (results[it.guid] ?: 0L) }, passRateKbps)
        if (header != null) MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_REPORT, header)
    }

    /** Searching = every line measured and none answering. Before entering it the line in use is probed against the
     *  fallback once: an answer there means the target is down, not the lines. Entering fetches the subscription at once. */
    private fun updateSearchingLocked() {
        val dead = lines.isNotEmpty() && lines.all { (results[it.guid] ?: 0L) < 0 }
        if (dead && !searching && !schedule.fallback) {
            val cur = byGuid[current]
            if (cur != null) {
                val out = measureOnce(listOf(cur), 1, AppConfig.ETHA_PROBE_FALLBACK_URL, policy.probeTimeoutMs)
                val ms = out[cur.guid] ?: -1L
                if (ms > 0) {
                    schedule.fallback = true
                    results[cur.guid] = ms
                    health.ok(labels.getValue(cur.guid), ms, System.currentTimeMillis() / 1000)
                    tellLatency()
                    LogUtil.w(AppConfig.TAG, "Auto: the probe target gave no answer on any line but the fallback answers on ${nameOf(current)}: measuring against the fallback until the target answers again")
                    return
                }
            }
        }
        if (dead == searching) return
        searching = dead
        val outage = dead && (hint?.outage ?: false)
        LogUtil.i(AppConfig.TAG, if (dead) "Auto: no line answers — searching${if (outage) " (the box says this network blocks every line right now)" else ""}" else "Auto: a line answers again")
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_STATE, if (!dead) "ok" else if (outage) "outage" else "searching")
        val now = mono()
        if (dead) {
            schedule.searchingBegan(now)
            report("search", false)
            searchFetch()
        } else {
            schedule.reseed(now, order(), policy, Math.random())
            report("search", true)
        }
        saveHealth()
    }

    /** The subscription fetched again (the link's own route works when the tunnel does not); other lines → the core restarts on them. */
    private fun searchFetch() {
        if (!fetching.compareAndSet(false, true)) return
        lastSearchFetchAt = mono()
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

    /** One quiet fetch after a failover, so the box hears of the dead line soon and the lines are fresh — at most one in ten minutes. */
    private fun failoverFetch() {
        val now = mono()
        if (now - lastFailoverFetchAt < FAILOVER_FETCH_GAP_MS) return
        lastFailoverFetchAt = now
        searchFetch()
    }

    private fun saveHealth() {
        try { MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_HEALTH + network, health.encode()) } catch (_: Exception) {}
    }

    private fun nameOf(guid: String) = remarks[guid] ?: guid
    private fun mono() = SystemClock.elapsedRealtime()

    companion object {
        private const val TICK_MS = 1_000L
        private const val SAMPLES_KEPT = 32
        private const val STALL_PROBE_GAP_MS = 10_000L
        private const val NETWORK_SETTLE_MS = 15_000L
        private const val WATCHED_FOR_MS = 15_000L
        private const val RATE_WINDOW_MS = 5_000L
        private const val RATE_MIN_BYTES = 256L * 1024
        private const val FAILOVER_FETCH_GAP_MS = 600_000L

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
