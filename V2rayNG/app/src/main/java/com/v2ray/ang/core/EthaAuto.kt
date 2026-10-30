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
 *     hint, else the subscription's order); then the steady trickle, one probe per roundSec/N over the lines that can be
 *     probed (the line in use among them), give or take the jitter, only while the radio is warm (bytes moved within
 *     warmSec) or the Home screen shows the chip (MSG_ETHA_WATCH every 10 s); the line in use also every currentSec on
 *     screen; a pass ended — by its last probe or by a tail of lines waiting their retry — is a decision and a report;
 *   - moves (AutoDecision): the first pass corrects a cold pick once, for a line clearly better; steady state moves only
 *     for a lead that persists over two pass ends (or is twice the margin), never within the dwell; a manual test by the
 *     margin alone. A change of line moves new connections only and cuts nothing that is open;
 *   - failover, make-before-break: a stall (bytes out, none back for stallSec) or a failed full-window probe of the line
 *     in use probes it and the best alternative not yet tried together within failoverMs; the line in use answering means
 *     nothing happens (and the alarm rests a minute: an upload has no downlink); it failing and the alternative answering
 *     moves at once — only then is the old line held as failed; the alternative failing too tries the next, once; then
 *     Searching. The short window itself condemns nobody. One quiet fetch follows a failover (at most one in ten minutes);
 *   - a failed line backs off (15 → 30 → 60 → 120 min) and the memory of the network is kept across sessions (MMKV, per
 *     network signature); a network change loads the new network's memory intact (leaving a dead line for its known-good
 *     one at once) and restarts the first pass in 15 s; the same network coming back forgives every line; idle, nothing
 *     runs; traffic returning probes the line in use once;
 *   - Searching (nothing answers: every line failed or waits its retry, the line in use among the failed): every line in
 *     turn, two at a time, each once per searchSec, three times slower when the box says the network blocks everything,
 *     only warm or on screen after ten minutes; no failure is held against a line meanwhile, and leaving forgives them
 *     all; the subscription is fetched again at once and every searchFetchSec; a fetch that brings other lines restarts
 *     the core on them (onLinesChanged);
 *   - the box's probe target, the fallback only while the target answers nobody (one probe of the line in use against the
 *     fallback decides before Searching; every tenth probe while on the fallback is a scout back);
 *   - the line in use's time goes to the UI as it is measured (MSG_ETHA_LATENCY: the chip); a move is said with
 *     MSG_ETHA_SWITCHED, the outcome of a "Test latency" with MSG_ETHA_ROUND_DONE, the state with MSG_ETHA_STATE (also in
 *     answer to every MSG_ETHA_WATCH, so a Home opened mid-outage learns it); every pass's results are kept as the report
 *     the next fetch carries to the box (EthaReport, the header X-Etha-Lines) with the best five-second download rate seen
 *     on the line in use during the pass — the passive throughput figure the box's ranking folds in.
 *
 * Nobody picks a line by hand any more (2.0.0): the customer sees no line name, only the latency. Every decision runs
 * under one mutex (the tick, a manual test, a network change); the connectivity thread only posts into the engine's scope.
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
    private val byLabel = HashMap<String, String>().also { m -> for (l in lines) m.putIfAbsent(labels.getValue(l.guid), l.guid) }
    private val inboundTag = if (SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()) "tun" else "socks"

    @Volatile var current: String = start
        private set
    private val results = ConcurrentHashMap<String, Long>()     // guid → ms, -1 failed
    private val colds = ConcurrentHashMap<String, Long>()       // guid → the connect time of the last probe: its first request, handshakes and all (2.1.1)
    @Volatile private var firstReading = false                  // the line in use has answered once since Connect; before that the chip stays "Connecting…"
    @Volatile private var earlyRetried = false                  // the first probe after Connect failed and was given a second try
    @Volatile private var startedAt = 0L
    @Volatile private var lastPassAt = 0L                       // when the whole picture was last measured (a pass end)
    private val schedule = AutoSchedule()
    private val decision = AutoDecision()
    @Volatile private var network = network
    private var health = LineHealth.decode(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINE_HEALTH + network))
    @Volatile private var hint: Hint? = Hint.parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_HINT))
    private val samples = ArrayDeque<Triple<Long, Long, Long>>()  // wall time, bytes up, bytes down (the stall detector)
    private val rateSamples = ArrayDeque<Pair<Long, Long>>()      // monotonic time, bytes down (the rate window)
    @Volatile private var passRateKbps = 0L
    @Volatile private var lastTrafficAt = mono()
    @Volatile private var wasInUse = true
    @Volatile private var stallQuietUntil = 0L
    @Volatile private var watchedAt = -1_000_000L
    @Volatile private var searching = false
    @Volatile private var outage = false
    @Volatile private var lastSearchFetchAt = 0L
    @Volatile private var lastFailoverFetchAt = -1_000_000L
    @Volatile private var stopped = false
    private var movedInPass = false
    private var movedWhileSearching = false
    private val probedSinceReport = HashSet<String>()
    private var probesThisPass = 0
    private var searchSteps = 0
    private val fetching = AtomicBoolean(false)

    fun start() {
        val all = lines.map { labels.getValue(it.guid) }
        // the start line: the best this network is known to have when the service's choice is unknown here or held as
        // failed, else the hint's first, else the service's choice
        val mine = labels.getValue(current)
        if (health.lastMs(mine) == null || health.isDead(mine)) {
            val known = health.bestKnown(all)
            val advice = hint
            val best = known ?: advice?.arrange(all)?.firstOrNull { !health.isDead(it) && it !in advice.dead }
            val guid = best?.let { byLabel[it] }
            if (guid != null && guid != current) switchTo(guid, if (known != null) "known best on $network" else "the box's advice", notify = false)
        }
        startedAt = mono(); lastPassAt = startedAt
        schedule.start(startedAt, order(), START_DELAY_MS)   // the first probe two seconds on, when the tunnel has settled (2.1.1)
        LogUtil.i(AppConfig.TAG, "Auto: ${lines.size} lines behind the balancer, on ${nameOf(current)}; network $network, ${health.entries.size} lines remembered" +
            (hint?.let { ", the box advises ${it.order.size} (${it.dead.size} dead${if (it.outage) ", outage" else ""})" } ?: "") + "; $policy")
        scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Auto: tick failed", e) }
                delay(TICK_MS)
            }
        }
    }

    /** The engine is done: nothing it still has in flight touches the core or the store after this. */
    fun stop() {
        stopped = true
        scope.cancel()
        saveHealth(force = true)
    }

    /** The screen came on, or the phone woke: the line in use is probed at once, and when the whole picture is older than
     *  STALE_PASS_MS the quick first pass runs again. Nothing runs while the phone sleeps (2.1.1). */
    fun onScreenOn() {
        scope.launch { measuring.withLock { if (!stopped) wakeLocked() } }
    }

    private fun wakeLocked() {
        val now = mono()
        if (schedule.nextProbeAt == null) return
        if (!searching && now - lastPassAt > STALE_PASS_MS) {
            schedule.freshen(now, order())
            probesThisPass = 0
            LogUtil.i(AppConfig.TAG, "Auto: the phone woke with a picture ${(now - lastPassAt) / 60_000} min old; the quick pass runs again")
        } else {
            schedule.wake(now)
        }
    }

    /** The Home screen is showing the chip: the line in use is kept fresh for it, and the state is said again (a Home
     *  opened mid-outage learns it). */
    fun noteWatched() {
        watchedAt = mono()
        announceState()
    }

    /** The customer pressed Test latency: every line measured now — the line in use first, so the chip has its number
     *  within a second, then every other line at once (the core library connects them all, then reads them all: no line
     *  is read beside another's handshakes) —, the decision by the margin alone, the outcome said. */
    fun pingAll() {
        scope.launch {
            measuring.withLock {
                if (stopped) return@withLock
                byGuid[current]?.let { measureSet(listOf(it), 1, policy.probeTimeoutMs, whole = false) }
                val rest = lines.filter { it.guid != current }
                if (rest.isNotEmpty()) measureSet(rest, rest.size, policy.probeTimeoutMs, whole = true)
                val moved = decide(AutoDecision.Phase.MANUAL, "manual", notify = false)
                report("manual", moved)
                updateSearchingLocked()
                if (!searching) schedule.reseed(mono(), order(), policy, Math.random())
                movedInPass = false
                probesThisPass = 0
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
     * Wi-Fi ↔ mobile (or the network back after a gap), from the connectivity thread: posted into the engine's scope and
     * done under the mutex. Another network's memory takes over intact — a line it holds as failed is left for its
     * known-good one at once —; the same network coming back forgives every line. The first pass restarts in 15 s; a
     * second event within a minute is the same.
     */
    fun onNetworkChanged(signature: String) {
        scope.launch { measuring.withLock { if (!stopped) networkChangedLocked(signature) } }
    }

    private fun networkChangedLocked(signature: String) {
        if (signature != network) {
            saveHealth()
            network = signature
            health = LineHealth.decode(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINE_HEALTH + network))
            val all = lines.map { labels.getValue(it.guid) }
            if (health.isDead(labels.getValue(current))) {
                val best = health.bestKnown(all)?.let { byLabel[it] }
                if (best != null && best != current) switchTo(best, "known good on $network, ${nameOf(current)} is not")
            }
        } else {
            health.forgive()
        }
        synchronized(samples) { samples.clear(); rateSamples.clear() }
        decision.firstSwitchDone = false
        val before = schedule.lastNetworkAt
        schedule.networkChanged(mono(), NETWORK_SETTLE_MS)
        if (schedule.lastNetworkAt != before) {
            schedule.order = order()
            probesThisPass = 0
            LogUtil.i(AppConfig.TAG, "Auto: the network changed ($network); the first pass runs again from ${NETWORK_SETTLE_MS / 1000} s on")
        }
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun tick() {
        val now = mono()
        sampleTraffic(now)
        if (stopped) return
        measuring.withLock { if (!stopped) tickLocked(now) }
    }

    private fun tickLocked(now: Long) {
        val stalled = synchronized(samples) { policy.stalled(samples.toList(), System.currentTimeMillis()) }
        if (stalled && !searching && now >= stallQuietUntil) {
            stallQuietUntil = now + STALL_PROBE_GAP_MS
            LogUtil.w(AppConfig.TAG, "Auto: bytes out and none back for ${policy.stallSec} s on ${nameOf(current)}")
            failoverLocked("stall")
            return
        }
        if (searching && now - lastSearchFetchAt >= policy.searchFetchMs) searchFetch()
        val warm = now - lastTrafficAt < policy.warmMs
        val inUse = now - lastTrafficAt < policy.idleMs
        if (inUse && !wasInUse) { schedule.wake(now); LogUtil.i(AppConfig.TAG, "Auto: traffic is back after the idle gap; the line in use is probed") }
        wasInUse = inUse
        val watched = now - watchedAt < WATCHED_FOR_MS
        val wallSec = System.currentTimeMillis() / 1000
        val due: (String) -> Boolean = { guid -> health.due(labels.getValue(guid), wallSec) }
        when (val action = schedule.action(now, warm, watched, searching, current, due)) {
            is AutoSchedule.Action.Probe -> if (searching) searchStepLocked(action.ids) else probeLinesLocked(action.ids, due)
            AutoSchedule.Action.ProbeCurrent -> probeCurrentLocked()
            null -> {
                // a step whose time has come with nothing left to probe before the rotation's end: the pass ends here
                val n = schedule.nextProbeAt
                if (!searching && n != null && now >= n && (warm || watched || schedule.forced || schedule.phase == AutoSchedule.Phase.FIRST)) {
                    val first = schedule.phase == AutoSchedule.Phase.FIRST
                    if (probesThisPass > 0) passEnded(first)
                    schedule.nothingDue(now, policy, Math.random(), activeCount(due))
                }
            }
        }
    }

    /** How many lines the rotation can probe right now (the pace spreads the round over them). */
    private fun activeCount(due: (String) -> Boolean): Int =
        lines.count { due(it.guid) && (it.guid != current || schedule.phase != AutoSchedule.Phase.FIRST) }

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

    // ---------------------------------------------------------------- the trickle (under the mutex)

    /** The lines in the order the trickle takes them: what this network's memory knows good, by time; then the lines it has
     *  not seen, in the box's advised order where there is one (its dead ones last among them), else the subscription's;
     *  then the lines the memory holds as failed. By guid. */
    private fun order(): List<String> {
        val all = lines.map { labels.getValue(it.guid) }
        val good = health.bestFirst(all).filter { health.lastMs(it) != null && !health.isDead(it) }
        val unseen = all.filter { health.lastMs(it) == null && !health.isDead(it) }
        val dead = all.filter { health.isDead(it) }
        return (good + (hint?.arrange(unseen) ?: unseen) + dead).mapNotNull { byLabel[it] }
    }

    private fun resultOf(label: String): Long = byLabel[label]?.let { results[it] } ?: 0L

    /** One step of the trickle (one line; two in the first pass): measured, recorded; the first pass may correct the start
     *  once; a wrapped rotation is a pass — the steady decision and the report. The line in use failing its full-window
     *  probe here is a failover. */
    private fun probeLinesLocked(ids: List<String>, due: (String) -> Boolean) {
        val which = ids.mapNotNull { byGuid[it] }
        if (which.isEmpty()) return
        val first = schedule.phase == AutoSchedule.Phase.FIRST
        val scout = schedule.fallback && schedule.cursor == 0   // the first probe of a pass on the fallback tries the target
        val url = if (schedule.fallback && !scout) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL
        val timeout = if (first) minOf(policy.probeTimeoutMs, FIRST_PASS_TIMEOUT_MS) else policy.probeTimeoutMs
        val out = measureOnce(which, which.size, url, timeout)
        if (scout) {
            val back = out.values.any { it > 0 }
            schedule.fallback = AutoSchedule.nextFallback(current = true, targetAnswered = back, fallbackAnswered = false)
            if (back) LogUtil.i(AppConfig.TAG, "Auto: the probe target answers again; measuring against it")
        }
        if (out.isEmpty()) { schedule.probed(mono(), which.map { it.guid }, policy, Math.random(), activeCount(due)); return }   // no measurement at all (the core restarting)
        record(out, which)
        probesThisPass += which.size
        val passEnd = schedule.probed(mono(), which.map { it.guid }, policy, Math.random(), activeCount(due))
        if (first && decide(AutoDecision.Phase.FIRST, "first pass", notify = true)) movedInPass = true
        if (passEnd) passEnded(first)
        if (which.any { it.guid == current } && (out[current] ?: -1L) < 0) {
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} did not answer its probe")
            failoverLocked("probe")
            return
        }
        updateSearchingLocked()
    }

    /** A pass is over, by its last probe or by a tail of lines waiting their retry: the steady decision, the report, the
     *  rate window, the memory saved. */
    private fun passEnded(first: Boolean) {
        if (!first && decide(AutoDecision.Phase.PASS_END, "pass end", notify = true)) movedInPass = true
        report(if (first) "first" else "auto", movedInPass)
        movedInPass = false
        passRateKbps = 0
        probesThisPass = 0
        lastPassAt = mono()
        saveHealth()
    }

    /** The line in use's own probe (every currentSec while the app is on screen, and once when traffic returns): its number
     *  to the chip; a full-window failure, the failover. */
    private fun probeCurrentLocked() {
        val line = byGuid[current] ?: return
        val out = measureOnce(listOf(line), 1, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.probeTimeoutMs)
        schedule.currentProbed(mono(), policy)
        if (out.isEmpty()) return
        if ((out[line.guid] ?: -1L) < 0 && !firstReading && !earlyRetried && mono() - startedAt < EARLY_RETRY_WINDOW_MS) {
            // the first probe after Connect ran on a tunnel still settling: once more in a second, nothing held against the line (2.1.1)
            earlyRetried = true
            schedule.currentDueAt = mono() + EARLY_RETRY_MS
            LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(current)} gave no answer right after Connect; once more in a second")
            return
        }
        record(out, listOf(line))
        if ((out[line.guid] ?: -1L) < 0) {
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} did not answer its probe")
            failoverLocked("probe")
        }
    }

    /** Make-before-break: the line in use and the best alternative not yet tried probed together within failoverMs. The
     *  line in use answering: nothing moves, the alarm rests a minute. It failing and the alternative answering: new
     *  connections move at once, and only then is the old line held as failed. The alternative failing too: the next, once
     *  more; nothing answering: Searching. The short window itself condemns nobody — answers are kept, failures not. */
    private fun failoverLocked(reason: String, tried: Set<String> = emptySet(), attempt: Int = 1) {
        val cur = byGuid[current] ?: return
        val all = lines.map { labels.getValue(it.guid) }
        val mine = labels.getValue(current)
        val excluded = tried + mine
        val advice = hint
        val arranged = advice?.arrange(all) ?: all
        val altLabel = health.bestKnown(all, except = mine)?.takeIf { it !in excluded }
            ?: arranged.firstOrNull { it !in excluded && !health.isDead(it) && (advice == null || it !in advice.dead) }
            ?: arranged.firstOrNull { it !in excluded }
        val alt = altLabel?.let { byLabel[it] }?.let { byGuid[it] }
        val which = listOfNotNull(cur, alt)
        val out = measureOnce(which, which.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.failoverMs.toLong())
        if (out.isEmpty()) return
        record(out, which, condemn = false)                     // a 1.5-s window: answers count, failures do not
        if ((out[cur.guid] ?: -1L) > 0) {
            stallQuietUntil = mono() + STALL_FALSE_ALARM_REST_MS
            LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(current)} answers within ${policy.failoverMs} ms ($reason): staying")
            return
        }
        if (alt != null && (out[alt.guid] ?: -1L) > 0) {
            condemn(cur.guid)
            switchTo(alt.guid, "failover after $reason")
            decision.moved(mono())
            report("failover", true)
            saveHealth()
            failoverFetch()
            return
        }
        val next = if (alt != null) tried + labels.getValue(alt.guid) else tried
        if (attempt < 2 && alt != null) { failoverLocked(reason, next, attempt + 1); return }
        // nothing answered the line in use nor two alternatives: the line in use is held as failed and Searching decides
        condemn(cur.guid)
        updateSearchingLocked(force = true)
    }

    /** The line failed for good within this session: its result and its memory say so (its retry backs off). */
    private fun condemn(guid: String) {
        results[guid] = -1L
        health.failed(labels.getValue(guid), System.currentTimeMillis() / 1000, policy)
        synchronized(probedSinceReport) { probedSinceReport.add(guid) }
        if (guid == current) tellLatency()
    }

    /** Searching: a step over every line in turn (two lines). The first to answer takes new connections. Every tenth step on
     *  the fallback scouts the box's target. */
    private fun searchStepLocked(ids: List<String>) {
        val which = ids.mapNotNull { byGuid[it] }
        if (which.isEmpty()) return
        searchSteps += 1
        val scout = schedule.fallback && searchSteps % 10 == 0
        val url = if (schedule.fallback && !scout) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL
        val out = measureOnce(which, which.size, url, policy.probeTimeoutMs)
        if (scout) {
            val back = out.values.any { it > 0 }
            schedule.fallback = AutoSchedule.nextFallback(current = true, targetAnswered = back, fallbackAnswered = false)
            if (back) LogUtil.i(AppConfig.TAG, "Auto: the probe target answers again; measuring against it")
        }
        if (out.isNotEmpty()) record(out, which, condemn = false)   // while searching no failure is held against a line
        schedule.searchStep(mono(), which.size, policy, slow = hint?.outage ?: false)
        if (searchSteps % 30 == 0) refreshHint()
        val found = which.firstOrNull { (out[it.guid] ?: -1L) > 0 }
        if (found != null && found.guid != current) {
            switchTo(found.guid, "a line answers again")
            decision.moved(mono())
            movedWhileSearching = true
        }
        updateSearchingLocked()
    }

    // ---------------------------------------------------------------- measuring

    /** A set of lines at once (a manual test): the box's target; nothing from it on any line and something from the fallback moves the probes to the fallback until the target answers again.
     *  [whole]: the set stands for every line (the fallback question is asked only then). */
    private fun measureSet(which: List<Line>, concurrency: Int, timeoutMs: Long, whole: Boolean = which.size == lines.size): Map<String, Long> {
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
            if (whole && first.isNotEmpty() && first.values.all { it < 0 }) {
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
        if (out.isNotEmpty()) record(out, which)
        return out
    }

    /** The lines measured through the running core; a line the core reports nothing for did not answer. A call that fails
     *  outright (the core restarting) is no measurement: an empty map, nothing recorded, nothing judged. */
    private fun measureOnce(which: List<Line>, concurrency: Int, url: String, timeoutMs: Long): Map<String, Long> {
        if (stopped) return emptyMap()
        val raw = try {
            controller.measureOutbounds(which.joinToString(",") { it.tag }, url, timeoutMs, concurrency.toLong())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the measurement failed", e)
            return emptyMap()
        }
        val byTag = which.associateBy { it.tag }
        val out = HashMap<String, Long>()
        for (entry in raw.split(';')) {
            val i = entry.lastIndexOf('=')
            if (i <= 0) continue
            val line = byTag[entry.substring(0, i)] ?: continue
            val pair = parsePair(entry.substring(i + 1)) ?: continue
            out[line.guid] = pair.first
            if (pair.second > 0) colds[line.guid] = pair.second
        }
        if (raw.isNotEmpty()) for (line in which) if (line.guid !in out) out[line.guid] = -1L   // a line the core did not report did not answer
        return out
    }

    /** Results into the engine, the memory of the network and the app's store; the line in use's number to the chip.
     *  [condemn]: whether a failure is held against the line (not from a short failover window, not while searching). */
    private fun record(out: Map<String, Long>, which: List<Line>, condemn: Boolean = true) {
        if (stopped) return
        val wallSec = System.currentTimeMillis() / 1000
        for ((guid, ms) in out) {
            if (ms > 0) {
                results[guid] = ms
                health.ok(labels.getValue(guid), ms, wallSec)
                if (guid == current) firstReading = true
            } else if (condemn) {
                results[guid] = ms
                health.failed(labels.getValue(guid), wallSec, policy)
            } else continue
            synchronized(probedSinceReport) { probedSinceReport.add(guid) }
            MmkvManager.encodeServerTestDelayMillis(guid, ms)
        }
        AutoSelect.markTested()
        LogUtil.i(AppConfig.TAG, "Auto: measured ${out.size} line(s): " +
            out.entries.sortedBy { it.value.takeIf { v -> v > 0 } ?: Long.MAX_VALUE }.take(6).joinToString { "${nameOf(it.key)}=${it.value}" })
        if (which.any { it.guid == current }) tellLatency()
    }

    // ---------------------------------------------------------------- deciding

    /** New connections take the line now; what is open stays where it is. The selection follows only while the profile
     *  still exists (a refetch gives the lines new ids; the running engine keeps the old ones). */
    private fun switchTo(guid: String, reason: String, notify: Boolean = true): Boolean {
        if (stopped) return false
        val line = byGuid[guid] ?: return false
        try {
            controller.setBalancerTarget(AppConfig.TAG_BALANCER, line.tag)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the balancer refused ${nameOf(guid)}", e)
            return false
        }
        current = guid
        passRateKbps = 0
        if (MmkvManager.decodeServerConfig(guid) != null) MmkvManager.setSelectServer(guid)
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
        if (stopped) return
        val ms = results[current] ?: 0L
        if (ms <= 0 && !firstReading) return   // before the first answer after Connect the chip stays "Connecting…", whatever a probe did (2.1.1)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_LATENCY, ms.toString())
    }

    /** The state and the latency said again (a Home opened or recreated mid-session learns them). */
    private fun announceState() {
        if (stopped) return
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_STATE, if (!searching) "ok" else if (outage) "outage" else "searching")
        if (results.containsKey(current)) tellLatency()
    }

    /** What was measured since the last report — the lines probed, not the ones waiting their retry with an old result —
     *  kept for the next fetch (the header X-Etha-Lines), with the pass's best download rate. */
    private fun report(trigger: String, switched: Boolean) {
        if (stopped) return
        val probed = synchronized(probedSinceReport) { probedSinceReport.toSet() }
        val header = EthaReport.build(System.currentTimeMillis(), trigger, remarks[current], switched,
            lines.map { (remarks[it.guid] ?: "") to (if (it.guid in probed) (results[it.guid] ?: 0L) else 0L) }, passRateKbps,
            colds = lines.mapNotNull { l -> colds[l.guid]?.takeIf { l.guid in probed && it > 0 }?.let { (remarks[l.guid] ?: "") to it } }.toMap())
        if (header != null) {
            MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_REPORT, header)
            synchronized(probedSinceReport) { probedSinceReport.clear() }
        }
    }

    /** The box's latest advice (the fetch while searching brings a fresh hint): the outage flag follows it. */
    private fun refreshHint() {
        val fresh = Hint.parse(MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_HINT)) ?: return
        if (fresh == hint) return
        hint = fresh
        if (searching && outage != fresh.outage) {
            outage = fresh.outage
            announceState()
            LogUtil.i(AppConfig.TAG, "Auto: the box's advice changed while searching (outage: $outage)")
        }
    }

    /** Searching = nothing answers: every line failed this session or waits its retry, the line in use among the failed
     *  (LineHealth.allOut) — or the failover exhausted its alternatives ([force]). Before entering it the line in use is
     *  probed against the fallback once: an answer there means the target is down, not the lines. Entering turns the clock
     *  to the search pace and fetches the subscription; leaving re-seeds the clock, forgives every line and reports. */
    private fun updateSearchingLocked(force: Boolean = false) {
        val all = lines.map { labels.getValue(it.guid) }
        val dead = force || health.allOut(all, labels.getValue(current)) { resultOf(it) }
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
        outage = dead && (hint?.outage ?: false)
        LogUtil.i(AppConfig.TAG, if (dead) "Auto: no line answers — searching${if (outage) " (the box says this network blocks every line right now)" else ""}" else "Auto: a line answers again")
        announceState()
        val now = mono()
        if (dead) {
            searchSteps = 0
            movedWhileSearching = false
            schedule.searchingBegan(now)
            report("search", false)
            searchFetch()
        } else {
            health.forgive()                                      // what failed during the outage may well pass now
            schedule.reseed(now, order(), policy, Math.random())
            probesThisPass = 0
            report("search", movedWhileSearching)
            movedWhileSearching = false
        }
        saveHealth()
    }

    /** The subscription fetched again (the link's own route works when the tunnel does not); other lines → the core restarts on them. */
    private fun searchFetch() {
        if (stopped || !fetching.compareAndSet(false, true)) return
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

    private fun saveHealth(force: Boolean = false) {
        if (stopped && !force) return
        try { MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_HEALTH + network, health.encode()) } catch (_: Exception) {}
    }

    private fun nameOf(guid: String) = remarks[guid] ?: guid
    private fun mono() = SystemClock.elapsedRealtime()

    companion object {
        private const val TICK_MS = 1_000L
        private const val SAMPLES_KEPT = 32
        private const val STALL_PROBE_GAP_MS = 10_000L
        private const val STALL_FALSE_ALARM_REST_MS = 60_000L
        private const val NETWORK_SETTLE_MS = 5_000L           // 15 s until 2.1.0; Connect itself no longer counts as a change
        private const val START_DELAY_MS = 2_000L              // the first probe after Connect waits for the tunnel to settle
        private const val EARLY_RETRY_WINDOW_MS = 10_000L      // a first probe that fails within this gets one more try…
        private const val EARLY_RETRY_MS = 1_000L              // …a second later, nothing held against the line
        private const val STALE_PASS_MS = 600_000L             // the phone woke with a picture older than this: the quick pass again
        private const val WATCHED_FOR_MS = 15_000L
        private const val RATE_WINDOW_MS = 5_000L
        private const val RATE_MIN_BYTES = 256L * 1024
        private const val FAILOVER_FETCH_GAP_MS = 600_000L
        private const val FIRST_PASS_TIMEOUT_MS = 3_000L

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

        /** One result of the core library's MeasureOutbounds: "warm/cold" (2.1.1: the warm round trip and the connect time) or a
         *  bare "ms" from an older library → (warm, cold), cold -1 when there is none; null when it is neither. */
        fun parsePair(v: String): Pair<Long, Long>? {
            val slash = v.indexOf('/')
            if (slash < 0) return v.toLongOrNull()?.let { it to -1L }
            val warm = v.substring(0, slash).toLongOrNull() ?: return null
            return warm to (v.substring(slash + 1).toLongOrNull() ?: -1L)
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
                    out[line.guid] = parsePair(entry.substring(i + 1))?.first ?: continue
                }
                return out
            }
            val first = once(AppConfig.ETHA_PROBE_URL) ?: return null
            if (first.values.any { it > 0 }) return first
            return once(AppConfig.ETHA_PROBE_FALLBACK_URL) ?: first
        }
    }
}
