//go:build darwin

package libv2ray

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestEchBoundToAnInterface(t *testing.T) {
	list, key := testECHKey(t, 35)
	port, accepts, plain := testServer(t, key)
	resolver, asked := fakeResolverAsked(t, "good", list)
	req := echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		Resolvers: []string{resolver},
		Interface: "lo0",
		TimeoutMs: 5000,
	}
	// Bound to the loopback interface, both sockets — the resolver's and the server's — work.
	res := fetchJSON(t, req)
	if res.Error != "" || res.Status != 200 || !res.EchAccepted || res.KeySource != "dns:"+resolver {
		t.Fatalf("unexpected result: %+v", res)
	}
	if len(asked()) != 1 || accepts.Load() != 1 || plain.Load() != 0 {
		t.Fatalf("asked=%v connections=%d plain=%d", asked(), accepts.Load(), plain.Load())
	}
	// An interface that does not exist: an error, and nothing is sent.
	req.Interface = "nosuch7"
	req.PinnedKey = base64.StdEncoding.EncodeToString(list)
	res = fetchJSON(t, req)
	if !strings.Contains(res.Error, "interface nosuch7") || accepts.Load() != 1 || len(asked()) != 1 {
		t.Fatalf("unexpected result: %+v (connections=%d, asked=%v)", res, accepts.Load(), asked())
	}
}
