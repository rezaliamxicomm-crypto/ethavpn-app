package libv2ray

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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
	if parts[1] != "line-b=-1" {
		t.Fatalf("the blackhole should have failed: %q", out)
	}
	for _, p := range []string{parts[0], parts[2]} {
		ms := strings.TrimPrefix(p[len("line-x="):], "")
		if ms == "-1" || ms == "" {
			t.Fatalf("a freedom outbound should have answered: %q", out)
		}
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
