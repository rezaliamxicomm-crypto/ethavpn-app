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

// MeasureOutbounds measures the outbounds named in tagsCSV ("tag,tag,…") through the running core: a HEAD request to
// url forced down that outbound alone, then a second down the kept-alive connection, the smaller time kept — the warm
// round trip, not the handshakes. At most concurrency at once (0: all), timeoutMs per request (0: 5000). The result
// is "tag=ms;tag=-1;…" in the order given, -1 for an outbound that gave no answer in time; "" when the core is down.
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
	results := make([]int64, len(tags))
	slots := make(chan struct{}, max(concurrency, 1))
	var wg sync.WaitGroup
	for i, tag := range tags {
		wg.Add(1)
		go func(i int, tag string) {
			defer wg.Done()
			slots <- struct{}{}
			defer func() { <-slots }()
			results[i] = measureVia(inst, tag, url, timeout)
		}(i, tag)
	}
	wg.Wait()

	var b strings.Builder
	for i, tag := range tags {
		if i > 0 {
			b.WriteByte(';')
		}
		b.WriteString(tag)
		b.WriteByte('=')
		b.WriteString(strconv.FormatInt(results[i], 10))
	}
	return b.String()
}

// measureVia: one outbound, two HEAD requests down one kept-alive connection, the smaller time; -1 when the first
// got no answer within timeout (no second attempt — a line that does not answer in that window counts as failed).
func measureVia(inst *core.Instance, tag string, url string, timeout time.Duration) int64 {
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
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: timeout}

	first, ok := headOnce(client, url)
	if !ok {
		return -1
	}
	if second, ok := headOnce(client, url); ok && second < first {
		return second
	}
	return first
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
