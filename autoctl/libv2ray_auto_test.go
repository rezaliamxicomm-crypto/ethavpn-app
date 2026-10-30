package libv2ray

import (
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
