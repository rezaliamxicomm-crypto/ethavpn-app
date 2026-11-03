package libv2ray

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// A throw-away core with two live outbounds and one dead: the forced-outbound measurement tells them apart, and the
// result keeps the order asked for.
func TestMeasureOutboundsInTellsALiveLineFromADeadOne(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer srv.Close()
	config := `{
	  "log": {"loglevel": "warning"},
	  "inbounds": [{"tag": "socks", "protocol": "socks", "listen": "127.0.0.1", "port": 0}],
	  "outbounds": [
	    {"tag": "line-a", "protocol": "freedom"},
	    {"tag": "line-b", "protocol": "blackhole"},
	    {"tag": "line-c", "protocol": "freedom"}
	  ],
	  "routing": {"balancers": [{"tag": "auto", "selector": ["line-"], "strategy": {"type": "random"}}],
	              "rules": [{"type": "field", "network": "tcp,udp", "balancerTag": "auto"}]}
	}`
	out, err := MeasureOutboundsIn(config, "line-a, line-b,line-c,", srv.URL+"/generate_204", 3000, 2)
	if err != nil {
		t.Fatal(err)
	}
	parts := strings.Split(out, ";")
	if len(parts) != 3 || !strings.HasPrefix(parts[0], "line-a=") || !strings.HasPrefix(parts[1], "line-b=") || !strings.HasPrefix(parts[2], "line-c=") {
		t.Fatalf("order or shape: %q", out)
	}
	if parts[1] != "line-b=-1/-1" {
		t.Fatalf("the blackhole should have failed cold and warm: %q", out)
	}
	for _, p := range []string{parts[0], parts[2]} {
		pair := strings.SplitN(p[len("line-x="):], "/", 2)
		if len(pair) != 2 {
			t.Fatalf("a result is warm/cold: %q", out)
		}
		for _, v := range pair {
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil || n < 0 {
				t.Fatalf("a freedom outbound should have answered cold and warm: %q", out)
			}
		}
	}
}

// Phase two does not wait for a line that cannot connect: with one line that hangs (every connection sent to a listener
// that never answers) beside a live one and a 3-second window, the live line's warm read is in by the 2.5-second grace,
// and the whole call ends with the hanging line's timeout, not before and not long after.
func TestMeasureOutboundsDoesNotHoldTheLiveLinesForADeadOne(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer srv.Close()
	tarpit, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tarpit.Close()
	go func() { // accepts and holds every connection, answering nothing
		for {
			c, err := tarpit.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) { defer c.Close(); _, _ = io.Copy(io.Discard, c) }(c)
		}
	}()
	config := `{
	  "log": {"loglevel": "warning"},
	  "inbounds": [{"tag": "socks", "protocol": "socks", "listen": "127.0.0.1", "port": 0}],
	  "outbounds": [
	    {"tag": "line-a", "protocol": "freedom"},
	    {"tag": "line-b", "protocol": "freedom", "settings": {"redirect": "` + tarpit.Addr().String() + `"}}
	  ]
	}`
	start := time.Now()
	out, err := MeasureOutboundsIn(config, "line-a,line-b", srv.URL+"/generate_204", 3000, 0)
	if err != nil {
		t.Fatal(err)
	}
	took := time.Since(start)
	if took < 2500*time.Millisecond || took > 4500*time.Millisecond {
		t.Fatalf("the call should end with the dead line's window, not before or long after: %v (%q)", took, out)
	}
	parts := strings.Split(out, ";")
	if len(parts) != 2 || parts[1] != "line-b=-1/-1" || strings.HasPrefix(parts[0], "line-a=-1") {
		t.Fatalf("shape: %q", out)
	}
}

func TestSplitTags(t *testing.T) {
	got := splitTags(" a, b ,,c,")
	if len(got) != 3 || got[0] != "a" || got[1] != "b" || got[2] != "c" {
		t.Fatalf("%v", got)
	}
	if len(splitTags("")) != 0 {
		t.Fatal("empty")
	}
}

// The pull (2.1.2): through a live line every byte of a 64-KB range comes and the result says so; through a line whose
// server answers the headers and then nothing, the pull ends at the idle window with what came; through a blackhole it
// ends with nothing.
func TestPullInCountsWhatCameAndEndsWhenNothingComes(t *testing.T) {
	blob := bytes.Repeat([]byte("x"), 256*1024)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/blob":
			http.ServeContent(w, r, "blob", time.Time{}, bytes.NewReader(blob))
		case "/stall":
			w.Header().Set("Content-Length", "65536")
			w.WriteHeader(http.StatusOK)
			w.Write(bytes.Repeat([]byte("y"), 16*1024))
			if f, ok := w.(http.Flusher); ok {
				f.Flush()
			}
			time.Sleep(6 * time.Second)
		}
	}))
	defer srv.Close()
	config := `{
	  "log": {"loglevel": "warning"},
	  "outbounds": [{"tag": "line-a", "protocol": "freedom"}, {"tag": "line-b", "protocol": "blackhole"}]
	}`
	out, err := PullIn(config, "line-a", srv.URL+"/blob", 64, 5000, 1000)
	if err != nil {
		t.Fatal(err)
	}
	got, ms := parsePull(t, out)
	if got != 64 || ms < 0 || ms > 4000 {
		t.Fatalf("a live line should have carried the whole range: %q", out)
	}
	start := time.Now()
	out, err = PullIn(config, "line-a", srv.URL+"/stall", 64, 5000, 1000)
	if err != nil {
		t.Fatal(err)
	}
	got, ms = parsePull(t, out)
	took := time.Since(start)
	if got != 16 || took < 900*time.Millisecond || took > 3000*time.Millisecond {
		t.Fatalf("a stalling line should have ended at the idle window with the 16 KB that came: %q after %v", out, took)
	}
	out, err = PullIn(config, "line-b", srv.URL+"/blob", 64, 3000, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if got, _ = parsePull(t, out); got != 0 {
		t.Fatalf("a blackhole should have carried nothing: %q", out)
	}
}

func parsePull(t *testing.T, out string) (int64, int64) {
	t.Helper()
	pair := strings.SplitN(out, "/", 2)
	if len(pair) != 2 {
		t.Fatalf("a pull's result is got/ms: %q", out)
	}
	got, err1 := strconv.ParseInt(pair[0], 10, 64)
	ms, err2 := strconv.ParseInt(pair[1], 10, 64)
	if err1 != nil || err2 != nil {
		t.Fatalf("a pull's result is two numbers: %q", out)
	}
	return got, ms
}
