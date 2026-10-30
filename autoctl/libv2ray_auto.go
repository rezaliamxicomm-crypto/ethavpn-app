package libv2ray

// SkyRay's Auto, the core side (autoctl/ in the ethavpn-app repository; the build copies this file next to the
// library's own, like echfetch/echfetch.go). The running core holds every line of the subscription behind one
// balancer; the app measures the lines through that same core and holds the balancer to the line it chooses, so a
// change of line moves new connections only and nothing that is open is cut. Nothing here decides anything: the
// policy — when to measure, when to move — lives in the app (core/EthaAuto.kt), and these are its tools:
//
//   - StartLoopOn: StartLoop with the balancer's target set before the core starts, so that not even the first
//     connection takes a random line;
//   - SetBalancerTarget / BalancerTarget: the balancer's override, Xray's own `api bo` without the API inbound;
//   - MeasureOutbounds: the named outbounds measured through the running core (a request forced down one outbound,
//     the forced-outbound hook Xray's observatory uses), MeasureOutboundsIn the same through a throw-away core for
//     the app's test while the tunnel is down — one core for every line instead of one core per line;
//   - InboundTraffic: an inbound's counters as they stand, for the app's stall detector.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	corenet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	corestats "github.com/xtls/xray-core/features/stats"
	coreserial "github.com/xtls/xray-core/infra/conf/serial"
)

// StartLoopOn is StartLoop with the balancer balancerTag held to the outbound target from before the core starts.
// An empty balancerTag or target starts the core as StartLoop does.
func (x *CoreController) StartLoopOn(configContent string, tunFd int32, balancerTag string, target string) error {
	setEnvVariable(tunFdKey, strconv.Itoa(int(tunFd)))

	x.coreMutex.Lock()
	defer x.coreMutex.Unlock()

	if x.IsRunning {
		log.Println("Core is already running")
		return nil
	}

	config, err := coreserial.LoadJSONConfig(strings.NewReader(configContent))
	if err != nil {
		return fmt.Errorf("config error: %w", err)
	}
	inst, err := core.New(config)
	if err != nil {
		return fmt.Errorf("core init failed: %w", err)
	}
	if balancerTag != "" && target != "" {
		if err := overrideBalancer(inst, balancerTag, target); err != nil {
			inst.Close()
			return fmt.Errorf("balancer %s: %w", balancerTag, err)
		}
	}

	x.coreInstance = inst
	if sm, ok := inst.GetFeature(corestats.ManagerType()).(corestats.Manager); ok {
		x.statsManager = sm
	}
	x.IsRunning = true
	if err := inst.Start(); err != nil {
		x.IsRunning = false
		x.statsManager = nil
		x.coreInstance = nil
		inst.Close()
		return fmt.Errorf("startup failed: %w", err)
	}

	x.CallbackHandler.Startup()
	x.CallbackHandler.OnEmitStatus(0, "Started successfully, running")
	log.Printf("Starting core successfully, balancer %q held to %q", balancerTag, target)
	return nil
}

func overrideBalancer(inst *core.Instance, balancerTag string, target string) error {
	r := inst.GetFeature(routing.RouterType())
	if r == nil {
		return errors.New("the core has no router")
	}
	bo, ok := r.(routing.BalancerOverrider)
	if !ok {
		return errors.New("the router cannot override a balancer")
	}
	return bo.SetOverrideTarget(balancerTag, target)
}

// SetBalancerTarget sends every new connection of the balancer over the outbound target; what is open stays where
// it is. An empty target gives the balancer back to its strategy.
func (x *CoreController) SetBalancerTarget(balancerTag string, target string) error {
	inst := x.instance()
	if inst == nil {
		return errors.New("the core is not running")
	}
	return overrideBalancer(inst, balancerTag, target)
}

// BalancerTarget is the outbound the balancer is held to, "" when none or when the core is not running.
func (x *CoreController) BalancerTarget(balancerTag string) string {
	inst := x.instance()
	if inst == nil {
		return ""
	}
	bo, ok := inst.GetFeature(routing.RouterType()).(routing.BalancerOverrider)
	if !ok {
		return ""
	}
	target, err := bo.GetOverrideTarget(balancerTag)
	if err != nil {
		return ""
	}
	return target
}

func (x *CoreController) instance() *core.Instance {
	x.coreMutex.Lock()
	defer x.coreMutex.Unlock()
	if !x.IsRunning {
		return nil
	}
	return x.coreInstance
}

// MeasureOutbounds measures the outbounds named in tagsCSV ("tag,tag,…") through the running core, in two phases, so
// that no line is read while another line's handshakes run beside it (2.1.1). Phase one opens one connection through
// each outbound and sends a first HEAD request to url: the cold time, handshakes and all. Phase two — once every open
// attempt has ended, or phase1Grace after the start, whichever comes first — sends a second HEAD down each kept-alive
// connection, all together: the warm round trip, one small packet each way. A warm read that fails gets one more try on
// a fresh connection, cold and warm again; a line whose first request gets no answer within timeoutMs is failed. At
// most concurrency opens at once (0: all), timeoutMs per request (0: 5000). The result is "tag=warm/cold;…" in the
// order given — warm -1 for a line that failed, cold -1 when even its first request got nothing —, "" when the core
// is down. Until 2.1.0 the warm read followed the cold one at once, beside other lines' handshakes, and a failed warm
// read kept the cold time in its place: a line looked three to five times slower than it was.
func (x *CoreController) MeasureOutbounds(tagsCSV string, url string, timeoutMs int, concurrency int) string {
	inst := x.instance()
	if inst == nil {
		return ""
	}
	return measureOutbounds(inst, splitTags(tagsCSV), url, timeoutMs, concurrency)
}

// MeasureOutboundsIn is MeasureOutbounds through a throw-away core built from configContent — its inbounds dropped,
// its outbounds, dispatcher and log kept, as MeasureOutboundDelay builds one — for the app's test while the tunnel
// is down.
func MeasureOutboundsIn(configContent string, tagsCSV string, url string, timeoutMs int, concurrency int) (string, error) {
	config, err := coreserial.LoadJSONConfig(strings.NewReader(configContent))
	if err != nil {
		return "", fmt.Errorf("config load error: %w", err)
	}
	config.Inbound = nil
	var essential []*serial.TypedMessage
	for _, app := range config.App {
		if app.Type == "xray.app.proxyman.OutboundConfig" ||
			app.Type == "xray.app.dispatcher.Config" ||
			app.Type == "xray.app.log.Config" {
			essential = append(essential, app)
		}
	}
	config.App = essential

	inst, err := core.New(config)
	if err != nil {
		return "", fmt.Errorf("instance creation failed: %w", err)
	}
	if err := inst.Start(); err != nil {
		inst.Close()
		return "", fmt.Errorf("startup failed: %w", err)
	}
	defer inst.Close()
	return measureOutbounds(inst, splitTags(tagsCSV), url, timeoutMs, concurrency), nil
}

func splitTags(csv string) []string {
	var tags []string
	for _, t := range strings.Split(csv, ",") {
		if t = strings.TrimSpace(t); t != "" {
			tags = append(tags, t)
		}
	}
	return tags
}

// phase1Grace: phase two starts this long after the start at the latest, so one line that cannot connect does not hold
// every other line's warm read; the straggler reads alone when (if) it connects, and fails at its own timeout.
const phase1Grace = 2500 * time.Millisecond

type probeResult struct{ warm, cold int64 }

func measureOutbounds(inst *core.Instance, tags []string, url string, timeoutMs int, concurrency int) string {
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}
	if concurrency <= 0 || concurrency > len(tags) {
		concurrency = len(tags)
	}
	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	results := make([]probeResult, len(tags))
	slots := make(chan struct{}, max(concurrency, 1))
	opened := make(chan struct{}, len(tags)) // one token per open attempt ended, whatever it found: never blocks
	warmGo := make(chan struct{})            // closed when phase two may start
	var wg sync.WaitGroup
	for i, tag := range tags {
		wg.Add(1)
		go func(i int, tag string) {
			defer wg.Done()
			results[i] = probeVia(inst, tag, url, timeout, slots, opened, warmGo)
		}(i, tag)
	}
	go func() { // the barrier between the phases
		deadline := time.After(min(phase1Grace, timeout))
		for n := 0; n < len(tags); n++ {
			select {
			case <-opened:
			case <-deadline:
				close(warmGo)
				return
			}
		}
		close(warmGo)
	}()
	wg.Wait()

	var b strings.Builder
	for i, tag := range tags {
		if i > 0 {
			b.WriteByte(';')
		}
		b.WriteString(tag)
		b.WriteByte('=')
		b.WriteString(strconv.FormatInt(results[i].warm, 10))
		b.WriteByte('/')
		b.WriteString(strconv.FormatInt(results[i].cold, 10))
	}
	return b.String()
}

// probeVia: one outbound. Phase one, under the slots: a connection through the outbound and the first HEAD (cold). Then
// it waits for warmGo — passing at once when phase one is already over — and sends the warm HEAD down the same
// connection; when that one fails, once more on a fresh connection, cold and warm again (the first cold time is kept).
// Every open attempt sends one token on opened, whatever it found, so the barrier can count.
func probeVia(inst *core.Instance, tag string, url string, timeout time.Duration, slots chan struct{}, opened chan<- struct{}, warmGo <-chan struct{}) probeResult {
	slots <- struct{}{}
	client, tr := probeClient(inst, tag, timeout)
	cold, ok := headOnce(client, url)
	<-slots
	opened <- struct{}{}
	if !ok {
		tr.CloseIdleConnections()
		return probeResult{warm: -1, cold: -1}
	}
	<-warmGo
	if warm, ok := headOnce(client, url); ok {
		tr.CloseIdleConnections()
		return probeResult{warm: warm, cold: cold}
	}
	tr.CloseIdleConnections()
	client, tr = probeClient(inst, tag, timeout)
	defer tr.CloseIdleConnections()
	if _, ok := headOnce(client, url); !ok {
		return probeResult{warm: -1, cold: cold}
	}
	if warm, ok := headOnce(client, url); ok {
		return probeResult{warm: warm, cold: cold}
	}
	return probeResult{warm: -1, cold: cold}
}

// probeClient: an HTTP client whose every connection is dialled through the one outbound, kept alive between requests.
func probeClient(inst *core.Instance, tag string, timeout time.Duration) (*http.Client, *http.Transport) {
	tr := &http.Transport{
		TLSHandshakeTimeout: timeout,
		DisableKeepAlives:   false,
		MaxIdleConns:        1,
		IdleConnTimeout:     2 * timeout,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := corenet.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
			if err != nil {
				return nil, err
			}
			return core.Dial(session.SetForcedOutboundTagToContext(ctx, tag), inst, dest)
		},
	}
	return &http.Client{Transport: tr, Timeout: timeout}, tr
}

func headOnce(client *http.Client, url string) (int64, bool) {
	req, err := http.NewRequest(http.MethodHead, url, nil)
	if err != nil {
		return -1, false
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1, false
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode >= 400 {
		return -1, false
	}
	return time.Since(start).Milliseconds(), true
}

// InboundTraffic is "uplink,downlink": the inbound's counters as they stand (not reset — QueryStats resets the
// outbounds' for the speed display), for the app's stall detector; "0,0" when there is no such counter. The counters
// exist when the configuration's policy has statsInboundUplink and statsInboundDownlink on.
func (x *CoreController) InboundTraffic(tag string) string {
	x.coreMutex.Lock()
	sm := x.statsManager
	x.coreMutex.Unlock()
	if sm == nil {
		return "0,0"
	}
	return fmt.Sprintf("%d,%d",
		counterValue(sm, "inbound>>>"+tag+">>>traffic>>>uplink"),
		counterValue(sm, "inbound>>>"+tag+">>>traffic>>>downlink"))
}

func counterValue(sm corestats.Manager, name string) int64 {
	c := sm.GetCounter(name)
	if c == nil {
		return 0
	}
	return c.Value()
}
