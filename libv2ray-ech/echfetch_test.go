package libv2ray

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const (
	testHost       = "sub.example.test"
	testPublicName = "outer.example.test"
	testBody       = "dmxlc3M6Ly9hYmM="
)

// testECHKey makes an ECHConfig (version 0xfe0d, X25519 / HKDF-SHA256 / AES-128-GCM) for the
// server, and the one-entry ECHConfigList a client pins.
func testECHKey(t *testing.T, configID byte) ([]byte, tls.EncryptedClientHelloKey) {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pub := priv.PublicKey().Bytes()
	var c []byte
	c = append(c, configID)
	c = binary.BigEndian.AppendUint16(c, 0x0020) // DHKEM(X25519, HKDF-SHA256)
	c = binary.BigEndian.AppendUint16(c, uint16(len(pub)))
	c = append(c, pub...)
	c = binary.BigEndian.AppendUint16(c, 4)
	c = binary.BigEndian.AppendUint16(c, 0x0001) // HKDF-SHA256
	c = binary.BigEndian.AppendUint16(c, 0x0001) // AES-128-GCM
	c = append(c, 64)                            // maximum_name_length
	c = append(c, byte(len(testPublicName)))
	c = append(c, testPublicName...)
	c = binary.BigEndian.AppendUint16(c, 0) // no extensions
	var cfg []byte
	cfg = binary.BigEndian.AppendUint16(cfg, 0xfe0d)
	cfg = binary.BigEndian.AppendUint16(cfg, uint16(len(c)))
	cfg = append(cfg, c...)
	list := binary.BigEndian.AppendUint16(nil, uint16(len(cfg)))
	list = append(list, cfg...)
	return list, tls.EncryptedClientHelloKey{Config: cfg, PrivateKey: priv.Bytes(), SendAsRetry: true}
}

func testCertificate(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: testHost},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{testHost, testPublicName},
		IsCA:         true, BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(leaf)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}, pool
}

type countingListener struct {
	net.Listener
	accepts atomic.Int64
}

func (l *countingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err == nil {
		l.accepts.Add(1)
	}
	return c, err
}

// testServer serves one subscription over TLS 1.3 with ECH. It counts TCP connections and
// requests that arrived without ECH (there must never be one).
func testServer(t *testing.T, key tls.EncryptedClientHelloKey) (port string, accepts *atomic.Int64, plain *atomic.Int64) {
	t.Helper()
	cert, pool := testCertificate(t)
	prev := echRootCAs
	echRootCAs = pool
	t.Cleanup(func() { echRootCAs = prev })

	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cl := &countingListener{Listener: tcp}
	var plainCount atomic.Int64
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || !r.TLS.ECHAccepted {
			plainCount.Add(1)
		}
		if r.URL.Path != "/sub/abc" || r.Host != testHost {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Profile-Update-Interval", "3")
		w.Header().Set("Subscription-Userinfo", "upload=0; download=5; total=0; expire=0")
		w.Header().Set("Profile-Title", "base64:RXRoYVZQTg==")
		w.Header().Add("Set-Cookie", "a=1")
		w.Header().Add("Set-Cookie", "b=2")
		_, _ = w.Write([]byte(testBody))
	})}
	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"http/1.1"},
	}
	if key.Config != nil {
		tlsCfg.EncryptedClientHelloKeys = []tls.EncryptedClientHelloKey{key}
	}
	go func() { _ = srv.Serve(tls.NewListener(cl, tlsCfg)) }()
	t.Cleanup(func() { _ = srv.Close() })
	_, port, _ = net.SplitHostPort(tcp.Addr().String())
	return port, &cl.accepts, &plainCount
}

func fetchJSON(t *testing.T, req echFetchRequest) echFetchResult {
	t.Helper()
	b, _ := json.Marshal(req)
	var res echFetchResult
	if err := json.Unmarshal([]byte(FetchSubscriptionEch(string(b))), &res); err != nil {
		t.Fatal(err)
	}
	return res
}

func TestEchFreshPinnedKey(t *testing.T) {
	list, key := testECHKey(t, 7)
	port, accepts, plain := testServer(t, key)
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		PinnedKey: base64.StdEncoding.EncodeToString(list),
		UserAgent: "SkyRay/1.3.6 (android)",
		TimeoutMs: 5000,
	})
	if res.Error != "" || res.Status != 200 || !res.EchAccepted {
		t.Fatalf("unexpected result: %+v", res)
	}
	if res.Body != testBody || res.KeySource != "pinned" || res.Address != "127.0.0.1" {
		t.Fatalf("unexpected result: %+v", res)
	}
	if res.Headers["profile-update-interval"] != "3" || res.Headers["profile-title"] != "base64:RXRoYVZQTg==" {
		t.Fatalf("headers not lower-cased or missing: %v", res.Headers)
	}
	if res.Headers["set-cookie"] != "b=2" {
		t.Fatalf("the last value of a repeated header must win: %v", res.Headers)
	}
	if accepts.Load() != 1 || plain.Load() != 0 {
		t.Fatalf("connections=%d plain=%d", accepts.Load(), plain.Load())
	}
}

func TestEchStaleKeyRecoversWithTheRetryKey(t *testing.T) {
	list, key := testECHKey(t, 9)
	port, accepts, plain := testServer(t, key)
	stale := append([]byte(nil), list...)
	stale[6] ^= 0xFF // a config id the server does not know
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		PinnedKey: base64.StdEncoding.EncodeToString(stale),
		TimeoutMs: 5000,
	})
	if res.Error != "" || res.Status != 200 || !res.EchAccepted || res.KeySource != "retry" {
		t.Fatalf("unexpected result: %+v", res)
	}
	if accepts.Load() != 2 || plain.Load() != 0 {
		t.Fatalf("connections=%d plain=%d: one rejected handshake, one accepted, never a plain request", accepts.Load(), plain.Load())
	}
}

func TestEchNoKeyMeansNoConnection(t *testing.T) {
	_, key := testECHKey(t, 3)
	port, accepts, _ := testServer(t, key)
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		TimeoutMs: 3000,
	})
	if res.Error == "" || !strings.Contains(res.Error, "no key") || res.Status != 0 {
		t.Fatalf("unexpected result: %+v", res)
	}
	if accepts.Load() != 0 {
		t.Fatalf("a fetch without a key must not open a connection, got %d", accepts.Load())
	}
}

func TestEchServerWithoutEchIsRefused(t *testing.T) {
	// A plain TLS server with no ECH key at all (what an interceptor or a wrong address looks like):
	// the handshake completes for the outer name only, Go reports the rejection, and no request
	// may be sent.
	list, _ := testECHKey(t, 5)
	port, _, plain := testServer(t, tls.EncryptedClientHelloKey{})
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		PinnedKey: base64.StdEncoding.EncodeToString(list),
		TimeoutMs: 3000,
	})
	if res.Error == "" || res.Status != 0 || res.EchAccepted {
		t.Fatalf("unexpected result: %+v", res)
	}
	if plain.Load() != 0 {
		t.Fatalf("a request went out without ECH: %d", plain.Load())
	}
}

// ---- the resolver side ---------------------------------------------------------------------

// fakeResolver answers HTTPS-record queries over UDP: "good" answers with the key and a hint,
// "tamper" first injects an A-only answer and sends the real one a little later, "silent" never
// answers, "nokey" answers an HTTPS record without the ech parameter.
func fakeResolver(t *testing.T, mode string, key []byte) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			q := append([]byte(nil), buf[:n]...)
			switch mode {
			case "silent":
			case "good":
				_, _ = pc.WriteTo(dnsAnswer(q, key, true, false), from)
			case "nokey":
				_, _ = pc.WriteTo(dnsAnswer(q, nil, false, false), from)
			case "tamper":
				_, _ = pc.WriteTo(dnsAnswer(q, nil, false, true), from)
				time.Sleep(150 * time.Millisecond)
				_, _ = pc.WriteTo(dnsAnswer(q, key, true, false), from)
			}
		}
	}()
	return pc.LocalAddr().String()
}

// dnsAnswer builds a response to query q: an HTTPS record with the ech parameter (withKey) and
// an ipv4hint of 127.0.0.1, or, with injected, a bare A record pointing at a bogus address.
func dnsAnswer(q []byte, key []byte, withKey, injected bool) []byte {
	qend := echSkipName(q, 12) + 4
	resp := append([]byte(nil), q[:qend]...)
	resp[2] = 0x81 // response, recursion desired
	resp[3] = 0x80 // recursion available, no error
	binary.BigEndian.PutUint16(resp[6:], 1)
	resp = append(resp, 0xC0, 0x0C) // the question's name
	if injected {
		resp = binary.BigEndian.AppendUint16(resp, 1) // A
		resp = binary.BigEndian.AppendUint16(resp, 1)
		resp = binary.BigEndian.AppendUint32(resp, 60)
		resp = binary.BigEndian.AppendUint16(resp, 4)
		return append(resp, 10, 10, 34, 34)
	}
	var rd []byte
	rd = binary.BigEndian.AppendUint16(rd, 1) // priority
	rd = append(rd, 0)                        // target: the owner name
	rd = binary.BigEndian.AppendUint16(rd, 4) // ipv4hint
	rd = binary.BigEndian.AppendUint16(rd, 4)
	rd = append(rd, 127, 0, 0, 1)
	if withKey {
		rd = binary.BigEndian.AppendUint16(rd, 5) // ech
		rd = binary.BigEndian.AppendUint16(rd, uint16(len(key)))
		rd = append(rd, key...)
	}
	resp = binary.BigEndian.AppendUint16(resp, echDNSTypeHTTPS)
	resp = binary.BigEndian.AppendUint16(resp, 1)
	resp = binary.BigEndian.AppendUint32(resp, 300)
	resp = binary.BigEndian.AppendUint16(resp, uint16(len(rd)))
	return append(resp, rd...)
}

func TestEchKeyFromDNSSkipsInjectedAnswersAndSilentResolvers(t *testing.T) {
	list, _ := testECHKey(t, 11)
	silent := fakeResolver(t, "silent", nil)
	tamper := fakeResolver(t, "tamper", list)
	nokey := fakeResolver(t, "nokey", nil)
	key, src, hints := echKeyFromDNS(testHost, []string{silent, nokey, tamper}, 2*time.Second)
	if key == nil || string(key) != string(list) {
		t.Fatalf("the real answer behind the injected one was not taken (src=%q)", src)
	}
	if src != "dns:"+tamper || len(hints) != 1 || hints[0] != "127.0.0.1" {
		t.Fatalf("src=%q hints=%v", src, hints)
	}
	if key, _, _ := echKeyFromDNS(testHost, []string{silent, nokey}, 500*time.Millisecond); key != nil {
		t.Fatal("a key appeared out of nothing")
	}
}

func TestEchEndToEndFromDNS(t *testing.T) {
	list, key := testECHKey(t, 13)
	port, _, plain := testServer(t, key)
	good := fakeResolver(t, "good", list)
	// No address given: the record's hint (127.0.0.1) is the one used. The pinned key is stale
	// and must not be needed.
	stale := append([]byte(nil), list...)
	stale[6] ^= 0xFF
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Resolvers: []string{good},
		PinnedKey: base64.StdEncoding.EncodeToString(stale),
		TimeoutMs: 5000,
	})
	if res.Error != "" || res.Status != 200 || res.KeySource != "dns:"+good || res.Address != "127.0.0.1" {
		t.Fatalf("unexpected result: %+v", res)
	}
	if plain.Load() != 0 {
		t.Fatal("a request went out without ECH")
	}
}

func TestEchPinnedAddressesComeAfterTheHints(t *testing.T) {
	list, key := testECHKey(t, 17)
	port, _, plain := testServer(t, key)
	good := fakeResolver(t, "good", list)
	// A dead stored address first (nothing listens on 127.0.0.2), the record's hint (127.0.0.1)
	// second, a pinned one that would also work last: the hint is the one used.
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.2"},
		Pinned:    []string{"127.0.0.1"},
		Resolvers: []string{good},
		TimeoutMs: 4000,
	})
	if res.Error != "" || res.Status != 200 || res.Address != "127.0.0.1" || res.KeySource != "dns:"+good {
		t.Fatalf("unexpected result: %+v", res)
	}
	if plain.Load() != 0 {
		t.Fatal("a request went out without ECH")
	}
}

func TestEchRejectsNonHttps(t *testing.T) {
	res := fetchJSON(t, echFetchRequest{URL: "http://" + testHost + "/sub/abc", Addresses: []string{"127.0.0.1"}, PinnedKey: "AAAA"})
	if !strings.Contains(res.Error, "not an https url") {
		t.Fatalf("unexpected result: %+v", res)
	}
}
