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
import com.v2ray.ang.handler.Backups
import com.v2ray.ang.handler.EthaReport
import com.v2ray.ang.handler.EthaSubscription
import com.v2ray.ang.handler.Hint
import com.v2ray.ang.handler.LineHealth
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SearchFetch
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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
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
 *   - failover (3.1.0): a stall (bytes out, none back for stallSec) or a failed full-window probe of the line in use
 *     probes it together with up to three backups in other ranges (Backups) within failoverMs; the line in use answering
 *     means nothing happens (and the alarm rests a minute: an upload has no downlink); it failing and a backup answering
 *     moves new connections at once — the fastest that answered, pulled after the move, the next one when it carries
 *     nothing; no backup answering: every line at once within SWEEP_TIMEOUT_MS, the lines just tried against the fallback
 *     target beside it (one measurement's time, not two); then Searching. The short windows condemn nobody. A line that
 *     still answers is left only for one that is proved to carry first; a line whose pull failed in this incident is not
 *     taken again in it. One quiet fetch follows a failover (at most one in ten minutes). Until 3.0.4: one alternative,
 *     then one more, then Searching;
 *   - a failed line backs off (15 → 30 → 60 → 120 min) and the memory of the network is kept across sessions (MMKV, per
 *     network signature); a network change loads the new network's memory intact (leaving a dead line for its known-good
 *     one at once) and restarts the first pass in 15 s; the same network coming back forgives every line; idle, nothing
 *     runs; traffic returning probes the line in use once;
 *   - Searching (nothing answers: every line failed or waits its retry, the line in use among the failed): every line in
 *     turn, two at a time, each once per searchSec, three times slower when the box says the network blocks everything,
 *     only warm or on screen after ten minutes; no failure is held against a line meanwhile, and leaving forgives them
 *     all; the subscription is fetched again at once and then as SearchFetch says (an unanswered call after 5, 10, 20 s);
 *     a fetch that brings other lines restarts the core on them (onLinesChanged), and the engine that starts on them puts
 *     new connections on the first new line and measures the new ones first.
 *     "No line answers" is the phone's own network far more often than its lines (a lift, the metro): the search's first
 *     report names the line in use alone, as before 3.1.0. Only when the link has ANSWERED — the phone's own network is
 *     alive at that moment — are all lines measured once more, at once (confirmLocked); if none answers then, the box is
 *     told that every line failed, in a second call at once, and answers that with lines in other ranges;
 *   - the box's probe target, the fallback only while the target answers nobody (one probe of the line in use against the
 *     fallback decides before Searching; every tenth probe while on the fallback is a scout back);
 *   - the line in use's time goes to the UI as it is measured (MSG_ETHA_LATENCY: the chip); a move is said with
 *     MSG_ETHA_SWITCHED, the outcome of a "Test latency" with MSG_ETHA_ROUND_DONE, the state with MSG_ETHA_STATE (also in
 *     answer to every MSG_ETHA_WATCH, so a Home opened mid-outage learns it); every pass's results are kept as the report
 *     the next fetch carries to the box (EthaReport, the header X-Etha-Lines) with the best five-second download rate seen
 *     on the line in use during the pass — the passive throughput figure the box's ranking folds in.
 *
 *   - the pull (2.1.2): a line is judged by bytes where a ping would lie — pullKb KB of the box's blob through the line (the
 *     core library's PullVia), at Connect on the line the first pass picked (c), when the stall watch fired and the line's
 *     HEAD still passed (s: a line the network has soft-killed answers a HEAD and carries nothing — until 2.1.1 that was a
 *     "false alarm" and a minute's rest on the dead line), on a candidate (w: before the switch while the line in use still
 *     answers, right after it when the line in use has failed — 3.1.0), and on the line in use when
 *     a fetch brings the box's word that it is dead for this network (h: the phone's own evidence wins — a line that carries
 *     stays); never in the trickle; at most pullCapPerHour an hour; every pull rides the report header until a fetch has carried
 *     it to the box (p=). A pull whose
 *     bytes stop, or that gets no answer, condemns the line like a failed probe; one that runs out of its window with bytes
 *     still coming is a slow line that carries — never condemned for its speed, but the best alternative is pulled at once
 *     and takes over when it carries every byte within the window (a slow alternative leaves things as they are);
 *   - the box's word that the subscription ended (a signed 403 on a fetch, onExpired): nothing is probed or fetched until
 *     a fetch answers again (Home refreshes; a renewal is the same link), the state says "expired".
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
    private val servers = lines.associate { it.guid to MmkvManager.decodeServerConfig(it.guid)?.server }   // each line's address: the failover's backups come from other ranges (3.1.0)

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
    private val searchFetches = SearchFetch()                    // when the subscription is fetched again while searching (3.1.0); under its own lock
    private var startNew: List<Line> = emptyList()               // the core restarted on lines a search's call brought: they are measured first (3.1.0)
    @Volatile private var lastFailoverFetchAt = -1_000_000L
    @Volatile private var stopped = false
    private var movedInPass = false
    private var movedWhileSearching = false
    private val probedSinceReport = HashSet<String>()
    private val noted = HashSet<String>()                        // lines whose -1 in results is a note for the report, not a failure held against them (3.1.0); under the mutex
    private val pullFailed = HashSet<String>()                   // lines whose pull failed in the failover now running: not taken again in it, their HEAD answers count for nothing; under the mutex
    private var searchConfirmed = false                          // this search's "every line failed" has been told to the box (once a search)
    @Volatile private var confirmDue = false                     // a search's call was answered: the lines are measured once more before the box is told more
    @Volatile private var lastSearchFetchAt = -1_000_000L        // when a search's call last started
    @Volatile private var markedAt = -1_000_000L                 // when the search's mark was last written
    private var probesThisPass = 0
    private var searchSteps = 0
    private val fetching = AtomicBoolean(false)
    private val pullTimes = ArrayDeque<Long>()                   // when the pulls of the last hour were made: the budget (2.1.2)
    private val pulls = ArrayList<EthaReport.Pull>()             // the pulls not yet carried to the box by a fetch
    private var reportedPulls = 0                                // how many of them (the oldest) the stored report header carries
    @Volatile private var lastPull: Triple<String, Long, Boolean>? = null   // the line last pulled, when, and whether it carried
    private var connectPullDue = true                            // the Connect pull: once after Connect, once after a network change
    @Volatile private var expired = false                        // the box said the subscription ended: nothing to look for until a fetch answers again
    private val recentCurrent = ArrayDeque<Pair<Long, Long>>()   // the line in use's last good readings (ms, mono): the chip shows the best within STEADY_WINDOW_MS (2.1.3)
    private var deadPulledFor: Hint? = null                      // the advice the line in use was last judged against (once per advice)
    private val subscriptionId = MmkvManager.decodeServerConfig(start)?.subscriptionId.orEmpty()
    private val ours = EthaSubscription.isSubLink(MmkvManager.decodeSubscription(subscriptionId)?.url)   // our own link: its answers are signed

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
        // 3.1.0: the engine before this one was searching (markSearching left the lines it held) and a fetch has brought others: the
        // old lines have just failed whatever the memory says of them, so new connections take the first new line now — the best
        // of them by the box's advice — and the new lines are measured before anything else (startNewLocked)
        val mark = MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_SEARCH_RESTART).orEmpty()
        if (mark.isNotEmpty()) {
            MmkvManager.encodeSettings(AppConfig.PREF_ETHA_SEARCH_RESTART, "")
            val fresh = SearchFetch.newSince(mark, all, System.currentTimeMillis())
            if (fresh.isNotEmpty()) {
                startNew = fresh.mapNotNull { byLabel[it] }.mapNotNull { byGuid[it] }
                val first = (hint?.arrange(fresh) ?: fresh).firstOrNull()?.let { byLabel[it] }
                if (first != null && first != current) switchTo(first, "a line the box just gave", notify = false)
            }
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
                // every line in one batch, the line in use among them (2.1.3): until 2.1.2 the line in use went first and alone, and a
                // probe alone on an idle link pays the radio's wake-up — it read 300 ms where the lines read together read 160, so
                // every test moved off it and the next test moved back
                measureSet(lines, lines.size, policy.probeTimeoutMs, whole = true)
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
            connectPullDue = true                                 // the first impression on the new network (2.1.2)
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
        synchronized(recentCurrent) { recentCurrent.clear() }      // the chip's recent readings were the old network's (2.1.3)
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
        if (expired) return                                     // nothing to look for until the link answers again (2.1.2)
        if (startNew.isNotEmpty() && now - startedAt >= START_DELAY_MS) { startNewLocked(); return }
        if (confirmDue) { confirmDue = false; if (searching && !searchConfirmed) { confirmLocked(); return } }
        val stalled = synchronized(samples) { policy.stalled(samples.toList(), System.currentTimeMillis()) }
        if (stalled && !searching && now >= stallQuietUntil) {
            stallQuietUntil = now + STALL_PROBE_GAP_MS
            LogUtil.w(AppConfig.TAG, "Auto: bytes out and none back for ${policy.stallSec} s on ${nameOf(current)}")
            failoverLocked("stall")
            return
        }
        val warm = now - lastTrafficAt < policy.warmMs
        // a search's calls: paced by their outcome; after the first ten minutes only while the phone is in use (traffic, or the app on
        // screen) or once in SearchFetch.IDLE_EVERY_MS — a phone with no way out at all in a pocket does not dial every minute for hours
        if (searching && synchronized(searchFetches) { searchFetches.due(now) && searchFetches.allowed(now, warm, now - watchedAt < WATCHED_FOR_MS, lastSearchFetchAt) }) searchFetch()
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
        if (first) connectPullLocked()
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

    /** The failover (3.1.0): the line in use and up to three backups in other ranges probed together within failoverMs. The
     *  line in use answering: nothing moves, the alarm rests a minute. It failing and a backup answering: new connections move
     *  at once to the fastest that answered, and only then is the old line held as failed; the line taken is pulled, and left
     *  for the next that answered when it carries nothing. No backup answering: every line at once (sweepLocked). The short
     *  window itself condemns nobody — answers are kept, failures not. [currentFailed]: the line in use is already known to
     *  have failed (a probe, or a pull): not probed again here. [pullFailedToo]: it was a pull that failed — the line answers
     *  and carries nothing, so an answer of it later in this incident counts for nothing. */
    private fun failoverLocked(reason: String, currentFailed: Boolean = false, pullFailedToo: Boolean = false) {
        val cur = byGuid[current] ?: return
        pullFailed.clear()
        try {
            if (pullFailedToo) pullFailed.add(cur.guid)
            val backups = backupsFor(cur)
            val which = if (currentFailed) backups else listOf(cur) + backups
            if (which.isEmpty()) { condemn(cur.guid); updateSearchingLocked(force = true); return }
            val out = measureOnce(which, which.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.failoverMs.toLong())
            if (out.isEmpty()) return
            record(out, which, condemn = false)                 // a 1.5-s window: answers count, failures do not
            if (!currentFailed && (out[cur.guid] ?: -1L) > 0) {
                // the line in use answers its HEAD; after a stall that is not the whole story — a line the network has soft-killed answers
                // a HEAD and carries nothing (2.1.2): the pull is the second opinion, and only a pull that fails lets the failover go on
                val p = if (reason == "stall") pullLocked(cur, "s") else null
                if (reason != "stall" || p == null || p.carries) {
                    stallQuietUntil = mono() + STALL_FALSE_ALARM_REST_MS
                    LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(current)} answers within ${policy.failoverMs} ms ($reason): staying")
                    if (p?.endLetter == "w") slowEscapeLocked("slow under a stall")   // it carries, slowly: a faster line may take over
                    return
                }
                pullFailed.add(cur.guid)
                LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} answers but carries nothing: the stall is real")
            }
            val answered = backups.filter { (out[it.guid] ?: -1L) > 0 }.sortedBy { out[it.guid] ?: Long.MAX_VALUE }
            if (moveToFirstThatCarries(cur, answered, "failover after $reason", curAlive = false)) return
            // neither the line in use nor a backup: every line at once, and whether it is the probe target that is down
            sweepLocked(cur, backups, reason)
        } finally {
            pullFailed.clear()
        }
    }

    /** Up to BACKUPS other lines for the failover: the ones this network's memory knows good (by time), then the ones it has
     *  not seen (in the box's advised order) — each in another range than the line in use and than each other where there are
     *  such (Backups) —, and only to make the count the ones held as failed or called dead by the box. */
    private fun backupsFor(cur: Line): List<Line> {
        val advice = hint
        val others = order().filter { it != cur.guid }
        val out = { g: String -> health.isDead(labels.getValue(g)) || (advice != null && labels.getValue(g) in advice.dead) }
        val asLine = { g: String -> Backups.Line(g, servers[g], labels[g]) }
        return Backups.pick(asLine(cur.guid), others.filter { !out(it) }.map(asLine), BACKUPS, others.filter(out).map(asLine)).mapNotNull { byGuid[it] }
    }

    /** New connections move to the first of [answered] (lines that answered a moment ago, best first) that carries; a line whose
     *  pull failed in this incident is passed over. True when a line was taken: the box is told (the report, one quiet fetch).
     *
     *  [curAlive] false — the line in use has just failed: the move comes first (new connections are better off on a line that
     *  answers than on one that does not, for the second or three the pull takes), the old line is held as failed at the first
     *  move, and the line taken is then pulled (2.1.2: a line that answers a HEAD may carry nothing); one that carries nothing
     *  is held as failed and the next takes over. When none carries, new connections go back to the line they came from, so
     *  that `current` is the line the caller knows.
     *  [curAlive] true — the line in use still answers: the proof first, then the move (as before 3.1.0); nothing is held
     *  against the line in use, and nothing moves unless a line is proved to carry. */
    private fun moveToFirstThatCarries(cur: Line, answered: List<Line>, why: String, curAlive: Boolean): Boolean {
        var moved = false
        for (alt in answered) {
            if (alt.guid == cur.guid || alt.guid in pullFailed) continue
            if (curAlive) {
                if (pullLocked(alt, "w")?.carries == false) { condemn(alt.guid); pullFailed.add(alt.guid); continue }
                if (!switchTo(alt.guid, why)) continue
            } else {
                if (!switchTo(alt.guid, why)) continue
                if (!moved) { condemn(cur.guid); moved = true }
                if (pullLocked(alt, "w")?.carries == false) {       // it answers but carries nothing: not it, the next one
                    LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(alt.guid)} answers but carries nothing: the next line that answered")
                    condemn(alt.guid); pullFailed.add(alt.guid)
                    continue
                }
            }
            decision.moved(mono())
            report("failover", true)
            saveHealth()
            failoverFetch()
            return true
        }
        if (moved) switchTo(cur.guid, "nothing that answered carries: back on the line it came from")
        return false
    }

    /** The lines of one measurement of "every line": the line in use and the ones just tried first, MAX_AT_ONCE at most (a
     *  subscription holds eight to twelve; nothing here is to open an unbounded number of connections at once). */
    private fun everyLine(first: List<Line>): List<Line> = (first + lines).distinctBy { it.guid }.take(MAX_AT_ONCE)

    /** Every line at once (3.1.0) within SWEEP_TIMEOUT_MS — the failover's last step before Searching, where until 3.0.4 one
     *  more alternative was tried. Beside it, on another thread, the lines the failover has just tried ([cur] and [tried])
     *  against the fallback target: an answer there with none from the box's target means the target is down, not the lines
     *  (the core library's measurement keeps nothing between calls: two may run at once).
     *    - the target is down and the line in use answers the fallback: staying (nothing was wrong with the line);
     *    - the line in use answers in this longer window: after a stall it is pulled like in the failover — it stays when it
     *      carries; otherwise it is left only for a FASTER line that is proved to carry first;
     *    - it does not answer: the fastest line that does takes new connections, pulled after the move;
     *    - nothing answers on either target: the line in use is held as failed and Searching begins. No failure of this
     *      measurement is held against a line or reported: nothing here says whether the lines or the phone's own network
     *      failed (confirmLocked finds that out). */
    private fun sweepLocked(cur: Line, tried: List<Line>, reason: String) {
        val timeout = minOf(policy.probeTimeoutMs, SWEEP_TIMEOUT_MS)
        val onFallback = schedule.fallback
        val control = (listOf(cur) + tried).distinctBy { it.guid }
        val all = everyLine(control)
        val beside: FutureTask<Map<String, Long>>? = if (onFallback) null
            else FutureTask(Callable { measureOnce(control, control.size, AppConfig.ETHA_PROBE_FALLBACK_URL, timeout, recordCold = false) })
        if (beside != null) Thread(beside, "etha-sweep-fallback").start()
        val out = measureOnce(all, all.size, if (onFallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, timeout)
        val fb: Map<String, Long> = try { beside?.get() ?: emptyMap() } catch (_: Exception) { emptyMap() }
        if (out.isEmpty()) return                                // no measurement at all (the core restarting)
        val why = "failover after $reason"
        if (out.values.none { it > 0 } && fb.values.any { it > 0 }) {
            schedule.fallback = true
            LogUtil.w(AppConfig.TAG, "Auto: the probe target gave no answer on any line but the fallback answers: measuring against the fallback until the target answers again")
            record(fb, control, condemn = false)
            if ((fb[cur.guid] ?: -1L) > 0 && cur.guid !in pullFailed) {   // the line in use is alive: the target was the trouble
                stallQuietUntil = mono() + STALL_FALSE_ALARM_REST_MS
                return
            }
            val alive = control.filter { (fb[it.guid] ?: -1L) > 0 }.sortedBy { fb[it.guid] ?: Long.MAX_VALUE }
            if (moveToFirstThatCarries(cur, alive, why, curAlive = false)) return
            condemn(cur.guid)
            updateSearchingLocked(force = true, swept = true)
            return
        }
        record(out, all, condemn = false)                        // answers kept; a failure here is neither held against a line nor reported
        var curAlive = cur.guid !in pullFailed && (out[cur.guid] ?: -1L) > 0
        if (curAlive && reason == "stall") {
            val p = pullLocked(cur, "s")                         // as in the failover: a line that answers after a stall is believed only when it carries
            if (p == null || p.carries) {
                stallQuietUntil = mono() + STALL_FALSE_ALARM_REST_MS
                LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(cur.guid)} answers within $timeout ms and carries (stall): staying")
                if (p?.endLetter == "w") slowEscapeLocked("slow under a stall")
                return
            }
            pullFailed.add(cur.guid); curAlive = false
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(cur.guid)} answers but carries nothing: the stall is real")
        }
        val alive = all.filter { it.guid != cur.guid && (out[it.guid] ?: -1L) > 0 }.sortedBy { out[it.guid] ?: Long.MAX_VALUE }
        if (curAlive) {
            val mine = out[cur.guid] ?: Long.MAX_VALUE
            if (!moveToFirstThatCarries(cur, alive.filter { (out[it.guid] ?: Long.MAX_VALUE) < mine }, why, curAlive = true)) {
                stallQuietUntil = mono() + STALL_FALSE_ALARM_REST_MS
                LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(cur.guid)} answers within $timeout ms and nothing faster is proved to carry ($reason): staying")
            }
            return
        }
        if (moveToFirstThatCarries(cur, alive, why, curAlive = false)) return
        condemn(cur.guid)
        updateSearchingLocked(force = true, swept = true)
    }

    /** A search's call has just been ANSWERED by the box — so the phone's own network is alive at this moment — and the
     *  answer brought no other lines: every line once more, at once, within failoverMs. One that answers ends the search (most
     *  searches are the phone's own network coming back). None: now it is the lines, not the network — every line goes into
     *  the report as failed, made this second, and the box is called again at once; it answers such a report with lines in
     *  other ranges. Once a search. (The first form of 3.1.0 made that report at the search's start, from one measurement
     *  taken while nothing said whether the phone was online: a lift would have read as "every line failed".) */
    private fun confirmLocked() {
        val cur = byGuid[current] ?: return
        val all = everyLine(listOf(cur))
        val out = measureOnce(all, all.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, policy.failoverMs.toLong())
        if (out.isEmpty()) return                                // no measurement at all: the next answered call asks again
        searchConfirmed = true
        if (out.values.any { it > 0 }) {
            record(out, all, condemn = false)
            val best = all.filter { (out[it.guid] ?: -1L) > 0 }.minByOrNull { out[it.guid] ?: Long.MAX_VALUE }
            if (best != null && best.guid != current && switchTo(best.guid, "a line answers again")) {
                decision.moved(mono())
                movedWhileSearching = true
            }
            updateSearchingLocked()                              // the search is over: forgiven, reported
            return
        }
        record(out, all, condemn = false, note = true)           // nothing held against a line; every one of them goes into this report
        report("search", false)
        LogUtil.w(AppConfig.TAG, "Auto: the link answers and none of ${all.size} lines does: the box is told that every line failed")
        searchFetch()
    }

    /** The core restarted on lines a search's fetch brought (3.1.0): they are measured before anything else, at once, and the
     *  fastest takes new connections (start() has already put them on the first). When none answers nothing more is done
     *  here: the first pass and the failover follow in their own time. */
    private fun startNewLocked() {
        val which = startNew
        startNew = emptyList()
        if (which.isEmpty()) return
        val out = measureOnce(which, which.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, minOf(policy.probeTimeoutMs, SWEEP_TIMEOUT_MS))
        if (out.isEmpty()) return
        record(out, which, condemn = false)
        val best = which.filter { (out[it.guid] ?: -1L) > 0 }.minByOrNull { out[it.guid] ?: Long.MAX_VALUE }
        if (best == null) {
            LogUtil.w(AppConfig.TAG, "Auto: none of the ${which.size} line(s) the box just gave answers yet")
            return
        }
        LogUtil.i(AppConfig.TAG, "Auto: the box's new lines answer; the fastest is ${nameOf(best.guid)}")
        if (best.guid != current && switchTo(best.guid, "the fastest of the lines the box just gave")) decision.moved(mono())
    }

    /** The line failed for good within this session: its result and its memory say so (its retry backs off). */
    private fun condemn(guid: String) {
        val again = results[guid] == -1L && guid !in noted       // already held as failed this episode: one failure on the memory, not two (2.1.2) — a note is not that
        noted.remove(guid)
        results[guid] = -1L
        if (!again) health.failed(labels.getValue(guid), System.currentTimeMillis() / 1000, policy)
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
        markSearching(true)
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
    private fun measureOnce(which: List<Line>, concurrency: Int, url: String, timeoutMs: Long, recordCold: Boolean = true): Map<String, Long> {
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
            if (recordCold && pair.second > 0) colds[line.guid] = pair.second   // a country's probe (recordCold false) includes a card's hop: not the line's cold time
        }
        if (raw.isNotEmpty()) for (line in which) if (line.guid !in out) out[line.guid] = -1L   // a line the core did not report did not answer
        return out
    }

    /** Results into the engine, the memory of the network and the app's store; the line in use's number to the chip.
     *  [condemn]: whether a failure is held against the line (not from a short failover window, not while searching).
     *  [note] (3.1.0): a failure not held against the line still goes into the engine's results and the next report — a
     *  measurement of every line that found nothing is exactly what the box needs to hear. */
    private fun record(out: Map<String, Long>, which: List<Line>, condemn: Boolean = true, note: Boolean = false) {
        if (stopped) return
        val wallSec = System.currentTimeMillis() / 1000
        for ((guid, ms) in out) {
            if (ms > 0 && guid in pullFailed) continue           // it answers and carries nothing (its pull failed a moment ago): not an answer
            if (ms > 0) {
                results[guid] = ms
                noted.remove(guid)
                health.ok(labels.getValue(guid), ms, wallSec)
                if (guid == current) {
                    firstReading = true
                    synchronized(recentCurrent) { recentCurrent.addLast(ms to mono()); while (recentCurrent.size > STEADY_KEPT) recentCurrent.removeFirst() }
                }
            } else if (condemn) {
                results[guid] = ms
                noted.remove(guid)
                health.failed(labels.getValue(guid), wallSec, policy)
            } else if (note) {
                if (results[guid] != -1L) noted.add(guid)        // a line already held as failed stays held
                results[guid] = ms
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
        synchronized(recentCurrent) { recentCurrent.clear() }
        if (MmkvManager.decodeServerConfig(guid) != null) MmkvManager.setSelectServer(guid)
        LogUtil.i(AppConfig.TAG, "Auto: new connections take ${nameOf(guid)} ($reason)")
        if (notify) MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_SWITCHED, guid)
        tellLatency()
        return true
    }

    private fun decide(phase: AutoDecision.Phase, reason: String, notify: Boolean): Boolean {
        var candidates = lines.map { AutoDecision.Candidate(it.guid, results[it.guid] ?: 0L, it.order) }
        var cur = candidates.firstOrNull { it.id == current } ?: return false
        // The second look (2.1.3): numbers taken apart are not comparable — a probe alone on an idle link pays the radio's wake-up —
        // so before any move by latency the line in use and the candidate are measured together, and the decision sees those numbers.
        val stale = AutoDecision.best(candidates.filter { it.id != current })
        if (stale != null && AutoDecision.worthASecondLook(phase, cur.ms, stale.ms, policy) && !(phase == AutoDecision.Phase.FIRST && decision.firstSwitchDone)) {
            val apart = cur.ms to stale.ms
            val pair = listOfNotNull(byGuid[current], byGuid[stale.id])
            val timeout = if (phase == AutoDecision.Phase.FIRST) minOf(policy.probeTimeoutMs, FIRST_PASS_TIMEOUT_MS) else policy.probeTimeoutMs
            val out = measureOnce(pair, pair.size, if (schedule.fallback) AppConfig.ETHA_PROBE_FALLBACK_URL else AppConfig.ETHA_PROBE_URL, timeout)
            if (out.isEmpty()) return false                      // no measurement at all (the core restarting): no move on the numbers taken apart
            record(out, pair)
            candidates = lines.map { AutoDecision.Candidate(it.guid, results[it.guid] ?: 0L, it.order) }
            cur = candidates.first { it.id == current }
            LogUtil.i(AppConfig.TAG, "Auto: a second look, together: ${nameOf(current)}=${results[current]} ${nameOf(stale.id)}=${results[stale.id]} (apart they read ${apart.first} and ${apart.second})")
            if (cur.ms < 0) {                                     // the line in use failed in the look: the failover's own path (its fetch, its report)
                failoverLocked("probe", currentFailed = true)
                return false
            }
            val lead = AutoDecision.best(candidates.filter { it.id != current })
            if (lead != null && lead.id != stale.id) {            // another line leads by a number not taken together: not this pass — before the decision's clocks move
                LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(lead.id)} leads by a reading taken apart: no move this pass")
                return false
            }
        }
        val target = decision.target(mono(), phase, cur, candidates, policy) ?: return false
        val p = byGuid[target]?.let { pullLocked(it, "w") }
        if (p != null && !p.carries) {                            // the candidate answers but carries nothing (2.1.2): no move, the candidate held as failed
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(target)} answers but carries nothing: no move")
            condemn(target)
            if (phase == AutoDecision.Phase.FIRST) decision.firstSwitchDone = false   // the first pass's one correction is not spent on it
            return false
        }
        if (p != null && p.endLetter == "w") {                   // a slow line wins no move by its ping (it may be the one a slow escape just left)
            LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(target)} answers faster but carries slowly: no move")
            return false
        }
        return switchTo(target, reason, notify)
    }

    // ---------------------------------------------------------------- the pull (2.1.2)

    /** The budget: at most pullCapPerHour pulls in any hour; none while the policy says 0 or the probes run against the fallback
     *  (the blob is the box's). */
    private fun pullAllowed(now: Long): Boolean {
        if (policy.pullKb <= 0 || policy.pullCapPerHour <= 0 || schedule.fallback) return false
        while (pullTimes.isNotEmpty() && now - pullTimes.first() > PULL_HOUR_MS) pullTimes.removeFirst()
        return pullTimes.size < policy.pullCapPerHour
    }

    /** pullKb KB of the box's blob through the line (the core library's PullVia, a Range request): true when the line carries — every
     *  byte came, or the window (pullWinSec) ran out with bytes still coming: slow, never condemned for its speed —, false when it answers
     *  but carries nothing — the bytes stopped for stallSec, or nothing came at all —, null when no pull was made (the budget spent, the
     *  pull off, the core down). Every pull goes into the next report. Under the mutex: the window is bounded, the tick waits. */
    private fun pullLocked(line: Line, why: String): EthaReport.Pull? {
        val now = mono()
        if (stopped || !pullAllowed(now)) return null
        pullTimes.addLast(now)
        val raw = try {
            controller.pullVia(line.tag, AppConfig.ETHA_PULL_URL, policy.pullKb.toLong(), policy.pullWinMs, maxOf(policy.stallMs, PULL_IDLE_MIN_MS))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Auto: the pull failed", e)
            return null
        }
        val (got, ms, end) = parsePull(raw) ?: return null
        val pull = EthaReport.Pull(labels.getValue(line.guid), got.toInt(), policy.pullKb, ms, why, end)
        synchronized(pulls) {
            pulls.add(pull)
            while (pulls.size > EthaReport.MAX_PULLS) pulls.removeAt(0)
        }
        lastPull = Triple(line.guid, now, pull.carries)
        LogUtil.i(AppConfig.TAG, "Auto: pulled $got of ${policy.pullKb} KB through ${nameOf(line.guid)} in $ms ms ($why): " + when (pull.endLetter) {
            "f" -> "it carries"
            "w" -> if (got > 0) "slow, but it carries" else "nothing came in the window"
            "i" -> "the bytes stopped: it answers but carries nothing"
            else -> "no answer"
        })
        return pull
    }

    /** The line in use carries, but slowly — its pull ran out of its window with bytes still coming: the best alternative is pulled at
     *  once, and new connections move only to a line that proves faster, every byte within the window. A slow or dead alternative leaves
     *  things as they are: a slow network is nobody's fault, and churning through the lines would only hurt (2.1.2). */
    private fun slowEscapeLocked(reason: String) {
        val mine = labels.getValue(current)
        val all = lines.map { labels.getValue(it.guid) }
        val advice = hint
        val altLabel = health.bestKnown(all, except = mine)
            ?: (advice?.arrange(all) ?: all).firstOrNull { it != mine && !health.isDead(it) && (advice == null || it !in advice.dead) }
            ?: return
        val alt = byLabel[altLabel]?.let { byGuid[it] } ?: return
        val p = pullLocked(alt, "w") ?: return
        when {
            p.endLetter == "f" -> {
                LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(alt.guid)} carries every byte within the window where ${nameOf(current)} was slow ($reason): moving")
                switchTo(alt.guid, "faster than ${nameOf(current)}")
                decision.moved(mono())
                report("auto", true)
                saveHealth()
            }
            !p.carries -> condemn(alt.guid)
            else -> LogUtil.i(AppConfig.TAG, "Auto: ${nameOf(alt.guid)} is slow too ($reason): staying on ${nameOf(current)} — a slow network")
        }
    }

    /** Connect: the line the first pass picked is pulled once — a line pulled on its way in (the first pass's correction) is not pulled
     *  again; one that carries nothing is left for the best alternative, which is pulled before the move. */
    private fun connectPullLocked() {
        if (!connectPullDue) return                               // a wake runs the first pass again; the pull is once per Connect and per network change
        connectPullDue = false
        val cur = byGuid[current] ?: return
        val lp = lastPull
        if (lp != null && lp.first == current && mono() - lp.second < PULL_FRESH_MS) return
        val p = pullLocked(cur, "c") ?: return
        if (!p.carries) {
            LogUtil.w(AppConfig.TAG, "Auto: ${nameOf(current)} answers but carries nothing right after Connect: leaving it")
            condemn(current)
            failoverLocked("pull", currentFailed = true, pullFailedToo = true)
        } else if (p.endLetter == "w") {
            slowEscapeLocked("slow right after Connect")
        }
    }

    /** Our link answered (the app's fetch, from the Home screen, the hourly job or the engine's own): the box's advice is fresh. The
     *  line in use the box now calls dead for this network is pulled — the phone's own evidence wins: a line that carries stays — and
     *  left for a live one when it carries nothing; a subscription the box had called ended is back. [subId]: whose fetch. */
    fun onFetched(subId: String) {
        if (subId.isNotEmpty() && subId != subscriptionId) return
        scope.launch { measuring.withLock { if (!stopped) fetchedLocked() } }
    }

    private fun fetchedLocked() {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_ETHA_LINE_REPORT).isNullOrEmpty()) {   // the fetch spent the header: the pulls it carried are delivered
            synchronized(pulls) { pulls.subList(0, minOf(reportedPulls, pulls.size)).clear() }
            reportedPulls = 0
        }
        if (expired) {
            expired = false
            LogUtil.i(AppConfig.TAG, "Auto: the link answers again: the subscription is back")
            schedule.freshen(mono(), order())
            probesThisPass = 0
            announceState()
        }
        val before = hint
        refreshHint()
        val advice = hint ?: return
        if (searching || schedule.fallback) return
        val mine = labels.getValue(current)
        if (mine !in advice.dead || (advice == before && deadPulledFor == advice)) return
        deadPulledFor = advice
        val lp = lastPull
        if (lp != null && lp.first == current && mono() - lp.second < PULL_FRESH_MS) return   // judged seconds ago (a failover's fetch follows its pull)
        val cur = byGuid[current] ?: return
        val all = lines.map { labels.getValue(it.guid) }
        if (all.none { it != mine && !health.isDead(it) && it !in advice.dead }) return   // nowhere better to go
        val p = pullLocked(cur, "h") ?: return
        if (!p.carries) {
            LogUtil.w(AppConfig.TAG, "Auto: the box calls ${nameOf(current)} dead on this network and it carries nothing: leaving it")
            condemn(current)
            failoverLocked("hint", currentFailed = true, pullFailedToo = true)
        } else {
            LogUtil.i(AppConfig.TAG, "Auto: the box calls ${nameOf(current)} dead on this network but it carries: staying")
        }
    }

    /** The box's word that the subscription ended (a signed 403 on a fetch of our link): nothing is probed or fetched until a fetch
     *  answers again (the Home screen refreshes on its own; a renewal is the same link); the state says so. [subId]: whose. */
    fun onExpired(subId: String) {
        if (subId.isNotEmpty() && subId != subscriptionId) return
        scope.launch {
            measuring.withLock {
                if (stopped || expired) return@withLock
                expired = true
                LogUtil.w(AppConfig.TAG, "Auto: the box says the subscription ended; nothing to look for until the link answers again")
                announceState()
            }
        }
    }

    /** The Servers sheet (3.0.0): each country's probe measured through the line in use — a HEAD to http://<probe>/exit, no fallback (a dead
     *  card fails), one country at a time under the measuring mutex like every measurement; the answer to the UI as "code=ms,…" (-1 failed). */
    fun measureExits(spec: String) {
        if (stopped) return
        val wanted = spec.split(",").mapNotNull { it.trim().split("=", limit = 2).takeIf { p -> p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty() } }
        if (wanted.isEmpty()) return
        scope.launch {
            measuring.withLock {
                if (stopped) return@withLock
                val line = byGuid[current] ?: return@withLock
                val out = wanted.joinToString(",") { (code, ip) ->
                    val ms = try { measureOnce(listOf(line), 1, "http://$ip${AppConfig.ETHA_EXIT_PROBE_PATH}", 4_000L, recordCold = false)[line.guid] ?: -1L } catch (_: Exception) { -1L }
                    "$code=$ms"
                }
                LogUtil.i(AppConfig.TAG, "Auto: the countries measured through ${nameOf(current)}: $out")
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_EXITS_MEASURED, out)
            }
        }
    }

    /** The line in use's time to the UI: the chip's number. */
    private fun tellLatency() {
        if (stopped) return
        val latest = results[current] ?: 0L
        if (latest <= 0 && !firstReading) return   // before the first answer after Connect the chip stays "Connecting…", whatever a probe did (2.1.1)
        val ms = AutoDecision.steady(synchronized(recentCurrent) { recentCurrent.toList() }, latest, mono(), STEADY_WINDOW_MS)   // the best recent reading (2.1.3)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_LATENCY, ms.toString())
    }

    /** The state and the latency said again (a Home opened or recreated mid-session learns them). */
    private fun announceState() {
        if (stopped) return
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_ETHA_STATE, if (expired) "expired" else if (!searching) "ok" else if (outage) "outage" else "searching")
        if (results.containsKey(current)) tellLatency()
    }

    /** What was measured since the last report — the lines probed, not the ones waiting their retry with an old result —
     *  kept for the next fetch (the header X-Etha-Lines), with the pass's best download rate. */
    private fun report(trigger: String, switched: Boolean) {
        if (stopped) return
        val probed = synchronized(probedSinceReport) { probedSinceReport.toSet() }
        val pulled = synchronized(pulls) { pulls.toList() }
        val header = EthaReport.build(System.currentTimeMillis(), trigger, remarks[current], switched,
            lines.map { (remarks[it.guid] ?: "") to (if (it.guid in probed) (results[it.guid] ?: 0L) else 0L) }, passRateKbps,
            colds = lines.mapNotNull { l -> colds[l.guid]?.takeIf { l.guid in probed && it > 0 }?.let { (remarks[l.guid] ?: "") to it } }.toMap(),
            pulls = pulled)
        if (header != null) {
            MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINE_REPORT, header)
            synchronized(probedSinceReport) { probedSinceReport.clear() }
            reportedPulls = pulled.size                           // the header is replaced at every pass end: the pulls stay until a fetch has carried one (fetchedLocked)
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
    private fun updateSearchingLocked(force: Boolean = false, swept: Boolean = false) {
        val all = lines.map { labels.getValue(it.guid) }
        val dead = force || health.allOut(all, labels.getValue(current)) { resultOf(it) }
        if (dead && !searching && !schedule.fallback && !swept) {   // [swept]: the sweep has just asked the fallback, beside its own measurement
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
            schedule.searchingBegan(now, if (swept) now + SEARCH_FIRST_STEP_MS else now)   // every line has just been measured: the first step a few seconds on
            searchConfirmed = false; confirmDue = false
            synchronized(searchFetches) { searchFetches.began(now) }
            markedAt = -1_000_000L
            markSearching(true)
            report("search", false)                              // names the line in use, as before 3.1.0: whether it is the lines or the phone's network is not known yet (confirmLocked)
            searchFetch()
        } else {
            markSearching(false)
            confirmDue = false
            health.forgive()                                      // what failed during the outage may well pass now
            schedule.reseed(now, order(), policy, Math.random())
            probesThisPass = 0
            report("search", movedWhileSearching)
            movedWhileSearching = false
        }
        saveHealth()
    }

    /** The subscription fetched again (the link's own route works when the tunnel does not); other lines → the core restarts on
     *  them. [search]: a search's own call — SearchFetch decides the next by whether the box answered, and an answer that
     *  brought no other lines has every line measured once more (confirmLocked, at the next tick). */
    private fun searchFetch(search: Boolean = true) {
        if (stopped || !fetching.compareAndSet(false, true)) return
        val started = mono()
        val episode = if (search) synchronized(searchFetches) { searchFetches.started(started, policy) } else 0
        if (search) lastSearchFetchAt = started
        scope.launch {
            var answered = false
            var confirm = false
            try {
                val before = AngConfigManager.oursAnsweredAt
                val result = AngConfigManager.updateConfigViaSubAll()
                // the box's own word (a signed answer of our link) — or, for a subscription that is not ours, an update that worked
                answered = AngConfigManager.oursAnsweredAt != before || (!ours && result.successCount > 0)
                if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ETHA_LINES_CHANGED, false)) {
                    MmkvManager.encodeSettings(AppConfig.PREF_ETHA_LINES_CHANGED, false)
                    if (searching) { markedAt = -1_000_000L; markSearching(true) }   // fresh at the moment it matters: the engine that starts next reads it
                    LogUtil.i(AppConfig.TAG, "Auto: the fetch brought other lines; the core restarts on them")
                    onLinesChanged()
                } else {
                    confirm = search && answered && ours
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Auto: the fetch while searching failed: ${e.message}")
            } finally {
                if (search) synchronized(searchFetches) { searchFetches.done(mono(), answered, policy, episode) }
                fetching.set(false)
                if (confirm) confirmDue = true                    // after the fetch has let go: the confirmation's own call must find it free
            }
        }
    }

    /** While searching, the lines held are left in the store with the time (3.1.0), written again every MARK_EVERY_MS and right
     *  before the engine's own restart: whichever fetch brings other lines — the engine's own, the Home screen's, the hourly
     *  job's — the engine that starts on them knows which are new (start: new connections on the first of them; startNewLocked:
     *  they are measured first). Leaving the search takes the mark away; one left by a tunnel that was switched off is too old
     *  to count a little later (SearchFetch.MARK_FRESH_MS). Only our own lines' labels go into it. */
    private fun markSearching(on: Boolean) {
        if (stopped) return
        val now = mono()
        if (on && now - markedAt < MARK_EVERY_MS) return
        markedAt = now
        val held = lines.mapNotNull { EthaReport.label(remarks[it.guid]) }
        try { MmkvManager.encodeSettings(AppConfig.PREF_ETHA_SEARCH_RESTART, if (on && held.isNotEmpty()) SearchFetch.mark(System.currentTimeMillis(), held) else "") } catch (_: Exception) {}
    }

    /** One quiet fetch after a failover, so the box hears of the dead line soon and the lines are fresh — at most one in ten minutes. */
    private fun failoverFetch() {
        val now = mono()
        if (now - lastFailoverFetchAt < FAILOVER_FETCH_GAP_MS) return
        lastFailoverFetchAt = now
        searchFetch(search = false)
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
        private const val PULL_HOUR_MS = 3_600_000L            // the pull budget's window (2.1.2)
        private const val PULL_FRESH_MS = 60_000L              // a line pulled within this is not pulled again at Connect
        private const val STEADY_WINDOW_MS = 90_000L           // the chip shows the best reading of the line in use within this (2.1.3)
        private const val STEADY_KEPT = 5
        private const val BACKUPS = 3                          // the failover probes the line in use with this many other lines (3.1.0)
        private const val SWEEP_TIMEOUT_MS = 3_000L            // every line at once: a line that says nothing in this long is no way out now
        private const val MAX_AT_ONCE = 16                     // …and never more lines than this in one measurement
        private const val SEARCH_FIRST_STEP_MS = 3_000L        // a search that began with every line measured takes its first step this much later
        private const val MARK_EVERY_MS = 20_000L              // the search's mark is written again this often
        private const val PULL_IDLE_MIN_MS = 4_000L            // a pull's bytes may pause this long whatever stallSec says (the box may send 3: the alarm, not the proof)

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

        /** One result of the core library's PullVia: "got/ms/end" → (KB that came, ms, how it ended: f | i | w | e, "" from a library
         *  without it); null when it is not one (the core down). */
        fun parsePull(v: String): Triple<Long, Long, String>? {
            val parts = v.split('/')
            if (parts.size < 2) return null
            val got = parts[0].toLongOrNull() ?: return null
            val ms = parts[1].toLongOrNull() ?: return null
            val end = parts.getOrNull(2)?.trim()?.take(1).orEmpty()
            return if (got < 0 || ms < 0 || (end.isNotEmpty() && end !in "fiwe")) null else Triple(got, ms, end)
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
