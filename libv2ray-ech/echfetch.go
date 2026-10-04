package libv2ray

// The SkyRay app's subscription fetch with Encrypted Client Hello (ECH).
//
// The link host is filtered by its name: a connection that states the name in the clear is cut,
// one that hides it with ECH passes. So this fetch never states the name in the clear.
//
//   - The ECH key comes from the host's HTTPS DNS record, asked of public resolvers over plain
//     UDP port 53 (never the phone's own resolver, never DNS over HTTPS), every resolver at once,
//     the first answer that carries a key wins; an answer without the key (an injected one) is
//     skipped and the socket keeps listening for the real one until the time is up.
//   - When no resolver delivers a key, a key the app carries is used; a key the server no longer
//     knows is answered with a retry key, and the fetch goes again with that, so no DNS is needed.
//   - The connection goes to a Cloudflare address the app already knows, in this order: the stored
//     lines' clean addresses, the record's address hints, a pinned list; never the host's A record.
//   - The outer name is the key's public name; the real host travels encrypted. When the server
//     does not accept ECH the request is not sent and the next address is tried: there is no
//     fallback to a plain-text name.
//
// Exposed to the app as FetchSubscriptionEch(requestJSON) -> resultJSON, like FetchTlsCertSha256.

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
	"time"
)

type echFetchRequest struct {
	URL       string   `json:"url"`
	Addresses []string `json:"addresses"`       // Cloudflare addresses to try first (the stored lines' clean ones)
	Pinned    []string `json:"pinnedAddresses"` // tried last, after the record's address hints
	Resolvers []string `json:"resolvers"`       // asked over plain UDP 53 for the HTTPS record; "ip" or "ip:port"
	PinnedKey string   `json:"pinnedKey"`       // base64 ECHConfigList, used when no resolver delivers one
	UserAgent string   `json:"userAgent"`
	TimeoutMs int64    `json:"timeoutMs"`
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
	echDNSTimeout     = 3 * time.Second
	echDialTimeout    = 4 * time.Second
	echDefaultTimeout = 20 * time.Second
	echDNSTypeHTTPS   = 65
)

// echRootCAs lets the tests pin their own certificate authority; nil means the system's.
var echRootCAs *x509.CertPool

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

	key, keySource, hints := echKeyFromDNS(host, req.Resolvers, echDNSTimeout)
	if key == nil && strings.TrimSpace(req.PinnedKey) != "" {
		if k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(req.PinnedKey)); err == nil && len(k) > 0 {
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
		res, err := echFetchOnce(host, port, u.RequestURI(), addr, key, keySource, req.UserAgent, deadline)
		if err == nil {
			return res
		}
		lastErr = err
		// The server did not know our key but handed back the current one: keep it for every
		// remaining attempt, and try this address again with it right away.
		var rej *tls.ECHRejectionError
		if errors.As(err, &rej) && len(rej.RetryConfigList) > 0 {
			key, keySource = rej.RetryConfigList, "retry"
			if res, err = echFetchOnce(host, port, u.RequestURI(), addr, key, keySource, req.UserAgent, deadline); err == nil {
				return res
			}
			lastErr = err
		}
	}
	return echFetchResult{Error: "ech: " + lastErr.Error()}
}

func echFetchOnce(host, port, requestURI, addr string, key []byte, keySource, userAgent string, deadline time.Time) (echFetchResult, error) {
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	d := net.Dialer{Timeout: echDialTimeout}
	raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(addr, port))
	if err != nil {
		return echFetchResult{}, err
	}
	defer raw.Close()
	_ = raw.SetDeadline(deadline)

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
	if err := conn.HandshakeContext(ctx); err != nil {
		return echFetchResult{}, err
	}
	if !conn.ConnectionState().ECHAccepted {
		return echFetchResult{}, errors.New("ech not accepted by " + addr)
	}

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

// ---- the HTTPS record over plain UDP DNS ---------------------------------------------------

type echDNSAnswer struct {
	key   []byte
	hints []string
	src   string
}

// echKeyFromDNS asks every resolver at once for host's HTTPS record and returns the first key
// that arrives, with that answer's IPv4 hints. Nothing usable within timeout: nil.
func echKeyFromDNS(host string, resolvers []string, timeout time.Duration) ([]byte, string, []string) {
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
			if a, ok := echQueryHTTPS(ctx, resolver, host); ok {
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
func echQueryHTTPS(ctx context.Context, resolver, host string) (echDNSAnswer, bool) {
	addr := resolver
	if _, _, err := net.SplitHostPort(resolver); err != nil {
		addr = net.JoinHostPort(resolver, "53")
	}
	var d net.Dialer
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
	if _, err := c.Write(echDNSQuery(id, host)); err != nil {
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
				if len(v) > 4 {
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
