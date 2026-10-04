package libv2ray

// The SkyRay apps' subscription fetch with Encrypted Client Hello (ECH): one file, the same in the
// Android and the iOS repository (there it is compiled into libXray's C bridge as package main).
//
// Every fetch of the app's own link goes through here, and ECH is enforced: once a key is set Go's
// TLS never falls back to a server name in the clear, and the request is written only after the
// server has accepted ECH. A link that cannot be fetched with ECH is not fetched.
//
//   - The key (Cloudflare's ECH configuration) is asked over plain UDP DNS, port 53, of public
//     resolvers, all at once: the HTTPS record of lookupName, the first answer that carries a key
//     wins; an answer without one (an injected one) is skipped and the socket keeps listening
//     until the time is up. Never the phone's own resolver, never DNS over HTTPS.
//   - When no resolver delivers a key, the key the app carries is offered. A key the server no
//     longer knows is answered with a retry key, and the fetch goes again with that one — so the
//     carried key never has to be fresh.
//   - The connection goes to a Cloudflare address the app already knows, in this order: the stored
//     lines' clean addresses, the record's address hints (only when the record is the host's own),
//     a pinned list; never the host's A record.
//   - Past the tunnel or through it: with "interface" every socket is bound to that interface
//     (iOS, to leave beside a running tunnel); with "proxy" the connection is opened through that
//     local HTTP proxy (Android, the running tunnel's own) — no UDP travels there, so no resolver
//     is asked and the key is the carried one.
//
// Exposed to the apps as FetchSubscriptionEch(requestJSON) -> resultJSON.

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"
)

type echFetchRequest struct {
	URL           string   `json:"url"`
	Addresses     []string `json:"addresses"`       // Cloudflare addresses to try first (the stored lines' clean ones)
	Pinned        []string `json:"pinnedAddresses"` // tried last
	Resolvers     []string `json:"resolvers"`       // asked over plain UDP 53 for the HTTPS record; "ip" or "ip:port"
	LookupName    string   `json:"lookupName"`      // whose HTTPS record carries the key; empty: the url's host
	PinnedKey     string   `json:"pinnedKey"`       // base64 ECHConfigList, offered when no resolver delivers one
	UserAgent     string   `json:"userAgent"`
	TimeoutMs     int64    `json:"timeoutMs"`
	Interface     string   `json:"interface"`     // bind every socket to this interface (past a running tunnel)
	Proxy         string   `json:"proxy"`         // "host:port" of a local HTTP proxy to connect through (the running tunnel's)
	ProxyUser     string   `json:"proxyUser"`     // its account, when it asks for one
	ProxyPassword string   `json:"proxyPassword"` //
}

type echFetchResult struct {
	Status      int               `json:"status"`
	Headers     map[string]string `json:"headers"` // names lower-cased, the last value of a repeated header
	Body        string            `json:"body"`
	EchAccepted bool              `json:"echAccepted"`
	Address     string            `json:"address"`
	KeySource   string            `json:"keySource"` // "dns:<resolver>", "pinned" or "retry"
	Error       string            `json:"error"`
}

const (
	echMaxBody        = 4 << 20
	echDefaultTimeout = 20 * time.Second
	echDNSTypeHTTPS   = 65
)

// Variables, so the tests can shorten them.
var (
	echDNSTimeout       = 2 * time.Second // how long the resolvers get before the carried key is offered
	echDialTimeout      = 4 * time.Second // per address
	echHandshakeTimeout = 6 * time.Second // per address: one that stalls must not use up the others' time
)

// echRootCAs lets the tests pin their own certificate authority; nil means the system's.
var echRootCAs *x509.CertPool

// echSocketControl is what net.Dialer.Control takes.
type echSocketControl = func(network, address string, c syscall.RawConn) error

// echBindToInterface is set by the platform file that knows how to bind a socket to an interface
// (echfetch_darwin.go). Where there is none, a request that names an interface fails rather than
// leave by another way than the caller asked for.
var echBindToInterface func(name string) (echSocketControl, error)

// FetchSubscriptionEch fetches requestJSON's url over TLS with ECH enforced and returns the
// result as JSON (see echFetchRequest / echFetchResult).
func FetchSubscriptionEch(requestJSON string) string {
	var req echFetchRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return marshalEchResult(echFetchResult{Error: "request: " + err.Error()})
	}
	return marshalEchResult(fetchSubscriptionEch(req))
}

func marshalEchResult(r echFetchResult) string {
	if r.Headers == nil {
		r.Headers = map[string]string{}
	}
	b, err := json.Marshal(r)
	if err != nil {
		return `{"error":"result: ` + strings.ReplaceAll(err.Error(), `"`, `'`) + `"}`
	}
	return string(b)
}

// echRoute is how a fetch leaves the phone: bound to an interface, through a proxy, or neither.
type echRoute struct {
	control       echSocketControl
	proxy         string
	proxyUser     string
	proxyPassword string
}

func fetchSubscriptionEch(req echFetchRequest) echFetchResult {
	u, err := url.Parse(strings.TrimSpace(req.URL))
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Hostname() == "" {
		return echFetchResult{Error: "ech: not an https url"}
	}
	host := u.Hostname()
	port := u.Port()
	if port == "" {
		port = "443"
	}
	timeout := time.Duration(req.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = echDefaultTimeout
	}
	deadline := time.Now().Add(timeout)

	route := echRoute{proxy: strings.TrimSpace(req.Proxy), proxyUser: req.ProxyUser, proxyPassword: req.ProxyPassword}
	if name := strings.TrimSpace(req.Interface); name != "" {
		if echBindToInterface == nil {
			return echFetchResult{Error: "ech: binding to an interface is not supported here"}
		}
		control, err := echBindToInterface(name)
		if err != nil {
			return echFetchResult{Error: "ech: interface " + name + ": " + err.Error()}
		}
		route.control = control
	}

	var key []byte
	var keySource string
	var hints []string
	if route.proxy == "" { // an HTTP proxy carries no UDP
		lookup := strings.TrimSuffix(strings.TrimSpace(req.LookupName), ".")
		if lookup == "" {
			lookup = host
		}
		key, keySource, hints = echKeyFromDNS(lookup, req.Resolvers, echDNSTimeout, route.control)
		if !strings.EqualFold(lookup, host) {
			hints = nil // another name's addresses
		}
	}
	if key == nil && strings.TrimSpace(req.PinnedKey) != "" {
		if k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(req.PinnedKey)); err == nil && echValidConfigList(k) {
			key, keySource = k, "pinned"
		}
	}
	if key == nil {
		return echFetchResult{Error: "ech: no key: no resolver answered and no pinned key"}
	}

	addrs := echDedupe(append(append(append([]string{}, req.Addresses...), hints...), req.Pinned...))
	if len(addrs) == 0 {
		return echFetchResult{Error: "ech: no address to connect to"}
	}

	var lastErr error = errors.New("no attempt made")
	for _, addr := range addrs {
		if !time.Now().Before(deadline) {
			break
		}
		res, err := echFetchOnce(host, port, u.RequestURI(), addr, key, keySource, req.UserAgent, route, deadline)
		if err == nil {
			return res
		}
		lastErr = err
		// The server did not know our key but handed back the current one: keep it for every
		// remaining attempt, and try this address again with it right away.
		var rej *tls.ECHRejectionError
		if errors.As(err, &rej) && echValidConfigList(rej.RetryConfigList) {
			key, keySource = rej.RetryConfigList, "retry"
			if res, err = echFetchOnce(host, port, u.RequestURI(), addr, key, keySource, req.UserAgent, route, deadline); err == nil {
				return res
			}
			lastErr = err
		}
	}
	return echFetchResult{Error: "ech: " + lastErr.Error()}
}

func echFetchOnce(host, port, requestURI, addr string, key []byte, keySource, userAgent string, route echRoute, deadline time.Time) (echFetchResult, error) {
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	raw, err := echDial(ctx, route, net.JoinHostPort(addr, port))
	if err != nil {
		return echFetchResult{}, err
	}
	defer raw.Close()

	cfg := &tls.Config{
		ServerName: host,
		MinVersion: tls.VersionTLS13,
		NextProtos: []string{"http/1.1"},
		// With a config list set, Go never falls back to a plain-text name: a server that does
		// not accept ECH ends the handshake with an error instead.
		EncryptedClientHelloConfigList: key,
		RootCAs:                        echRootCAs,
	}
	conn := tls.Client(raw, cfg)
	hctx, hcancel := context.WithTimeout(ctx, echHandshakeTimeout)
	err = conn.HandshakeContext(hctx)
	hcancel()
	if err != nil {
		return echFetchResult{}, err
	}
	if !conn.ConnectionState().ECHAccepted {
		return echFetchResult{}, errors.New("ech not accepted by " + addr)
	}
	_ = raw.SetDeadline(deadline) // the exchange itself may take what is left

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+host+requestURI, nil)
	if err != nil {
		return echFetchResult{}, err
	}
	httpReq.Host = host
	if strings.TrimSpace(userAgent) != "" {
		httpReq.Header.Set("User-Agent", userAgent)
	}
	httpReq.Header.Set("Accept", "*/*")
	httpReq.Header.Set("Connection", "close")
	if err := httpReq.Write(conn); err != nil {
		return echFetchResult{}, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), httpReq)
	if err != nil {
		return echFetchResult{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, echMaxBody+1))
	if err != nil {
		return echFetchResult{}, err
	}
	if len(body) > echMaxBody {
		return echFetchResult{}, errors.New("response larger than the limit")
	}
	headers := make(map[string]string, len(resp.Header))
	for name, values := range resp.Header {
		if len(values) > 0 {
			headers[strings.ToLower(name)] = values[len(values)-1]
		}
	}
	return echFetchResult{
		Status:      resp.StatusCode,
		Headers:     headers,
		Body:        string(body),
		EchAccepted: true,
		Address:     addr,
		KeySource:   keySource,
	}, nil
}

// echDial opens the TCP connection to target ("ip:port"): straight, bound to the route's
// interface, or through the route's HTTP proxy with CONNECT.
func echDial(ctx context.Context, route echRoute, target string) (net.Conn, error) {
	d := net.Dialer{Timeout: echDialTimeout, Control: route.control}
	if route.proxy == "" {
		return d.DialContext(ctx, "tcp", target)
	}
	c, err := d.DialContext(ctx, "tcp", route.proxy)
	if err != nil {
		return nil, err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	connect := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
	if route.proxyUser != "" || route.proxyPassword != "" {
		connect += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(route.proxyUser+":"+route.proxyPassword)) + "\r\n"
	}
	if _, err := c.Write([]byte(connect + "\r\n")); err != nil {
		c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		c.Close()
		return nil, errors.New("proxy: " + err.Error())
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		c.Close()
		return nil, errors.New("proxy answered " + resp.Status)
	}
	if br.Buffered() > 0 { // nothing should follow the answer before we speak; keep it if something did
		return &echBufferedConn{Conn: c, r: br}, nil
	}
	return c, nil
}

type echBufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *echBufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

func echDedupe(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// echValidConfigList says whether b has the shape of an ECHConfigList with at least one config
// of the version this client speaks (0xfe0d). A list without that shape would end every
// handshake before it began.
func echValidConfigList(b []byte) bool {
	if len(b) < 6 || int(binary.BigEndian.Uint16(b))+2 != len(b) {
		return false
	}
	known := false
	for p := 2; p < len(b); {
		if p+4 > len(b) {
			return false
		}
		version := binary.BigEndian.Uint16(b[p:])
		size := int(binary.BigEndian.Uint16(b[p+2:]))
		p += 4
		if p+size > len(b) {
			return false
		}
		p += size
		if version == 0xfe0d && size > 0 {
			known = true
		}
	}
	return known
}

// ---- the HTTPS record over plain UDP DNS ---------------------------------------------------

type echDNSAnswer struct {
	key   []byte
	hints []string
	src   string
}

// echKeyFromDNS asks every resolver at once for name's HTTPS record and returns the first key
// that arrives, with that answer's IPv4 hints. Nothing usable within timeout: nil.
func echKeyFromDNS(name string, resolvers []string, timeout time.Duration, control echSocketControl) ([]byte, string, []string) {
	resolvers = echDedupe(resolvers)
	if len(resolvers) == 0 {
		return nil, "", nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	results := make(chan echDNSAnswer, len(resolvers))
	var wg sync.WaitGroup
	for _, r := range resolvers {
		wg.Add(1)
		go func(resolver string) {
			defer wg.Done()
			if a, ok := echQueryHTTPS(ctx, resolver, name, control); ok {
				results <- a
			}
		}(r)
	}
	go func() { wg.Wait(); close(results) }()
	for a := range results {
		return a.key, "dns:" + a.src, a.hints
	}
	return nil, "", nil
}

// echQueryHTTPS sends one HTTPS-record query and reads datagrams until one carries an ECH key
// or the context ends. An answer without the key (an injected one) is skipped, not trusted.
func echQueryHTTPS(ctx context.Context, resolver, name string, control echSocketControl) (echDNSAnswer, bool) {
	addr := resolver
	if _, _, err := net.SplitHostPort(resolver); err != nil {
		addr = net.JoinHostPort(resolver, "53")
	}
	d := net.Dialer{Control: control}
	c, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return echDNSAnswer{}, false
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	var idb [2]byte
	if _, err := rand.Read(idb[:]); err != nil {
		return echDNSAnswer{}, false
	}
	id := binary.BigEndian.Uint16(idb[:])
	if _, err := c.Write(echDNSQuery(id, name)); err != nil {
		return echDNSAnswer{}, false
	}
	buf := make([]byte, 4096)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return echDNSAnswer{}, false
		}
		if key, hints, ok := echParseHTTPS(buf[:n], id); ok {
			return echDNSAnswer{key: key, hints: hints, src: resolver}, true
		}
	}
}

func echDNSQuery(id uint16, name string) []byte {
	pkt := make([]byte, 12)
	binary.BigEndian.PutUint16(pkt[0:], id)
	binary.BigEndian.PutUint16(pkt[2:], 0x0100) // recursion desired
	binary.BigEndian.PutUint16(pkt[4:], 1)
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		if label == "" || len(label) > 63 {
			continue
		}
		pkt = append(pkt, byte(len(label)))
		pkt = append(pkt, label...)
	}
	pkt = append(pkt, 0)
	pkt = binary.BigEndian.AppendUint16(pkt, echDNSTypeHTTPS)
	return binary.BigEndian.AppendUint16(pkt, 1)
}

// echSkipName returns the offset after a (possibly compressed) name, or -1 when malformed.
func echSkipName(d []byte, off int) int {
	for hops := 0; hops < 128; hops++ {
		if off >= len(d) {
			return -1
		}
		l := int(d[off])
		switch {
		case l&0xC0 == 0xC0:
			if off+2 > len(d) {
				return -1
			}
			return off + 2
		case l == 0:
			return off + 1
		default:
			off += 1 + l
		}
	}
	return -1
}

// echParseHTTPS reads a DNS answer to our query (id must match) and returns the ECH config list
// and the IPv4 hints of its HTTPS record; ok is false when there is no such record.
func echParseHTTPS(d []byte, id uint16) (key []byte, hints []string, ok bool) {
	if len(d) < 12 || binary.BigEndian.Uint16(d[0:]) != id || d[2]&0x80 == 0 || d[3]&0x0F != 0 {
		return nil, nil, false
	}
	qd := int(binary.BigEndian.Uint16(d[4:]))
	an := int(binary.BigEndian.Uint16(d[6:]))
	off := 12
	for i := 0; i < qd; i++ {
		if off = echSkipName(d, off); off < 0 || off+4 > len(d) {
			return nil, nil, false
		}
		off += 4
	}
	for i := 0; i < an; i++ {
		if off = echSkipName(d, off); off < 0 || off+10 > len(d) {
			return nil, nil, false
		}
		typ := binary.BigEndian.Uint16(d[off:])
		rl := int(binary.BigEndian.Uint16(d[off+8:]))
		off += 10
		if off+rl > len(d) {
			return nil, nil, false
		}
		rd := d[off : off+rl]
		off += rl
		if typ != echDNSTypeHTTPS || len(rd) < 3 {
			continue
		}
		p := echSkipName(rd, 2) // priority, then the target name
		if p < 0 {
			continue
		}
		var k []byte
		var h []string
		for p+4 <= len(rd) {
			pk := binary.BigEndian.Uint16(rd[p:])
			pl := int(binary.BigEndian.Uint16(rd[p+2:]))
			p += 4
			if p+pl > len(rd) {
				break
			}
			v := rd[p : p+pl]
			p += pl
			switch pk {
			case 4: // ipv4hint
				for j := 0; j+4 <= len(v); j += 4 {
					h = append(h, net.IP(v[j:j+4]).String())
				}
			case 5: // ech
				if echValidConfigList(v) {
					k = append([]byte(nil), v...)
				}
			}
		}
		if k != nil {
			return k, h, true
		}
	}
	return nil, nil, false
}
