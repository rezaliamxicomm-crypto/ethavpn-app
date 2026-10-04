//go:build !darwin

package libv2ray

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestEchInterfaceNeedsThePlatformFile(t *testing.T) {
	// Binding to an interface is written for iOS (echfetch_darwin.go). Anywhere else a request that
	// names one fails before anything is sent: it must not leave by another way than asked.
	list, key := testECHKey(t, 33)
	port, accepts, _ := testServer(t, key)
	res := fetchJSON(t, echFetchRequest{
		URL:       "https://" + testHost + ":" + port + "/sub/abc",
		Addresses: []string{"127.0.0.1"},
		PinnedKey: base64.StdEncoding.EncodeToString(list),
		Interface: "lo",
		TimeoutMs: 3000,
	})
	if !strings.Contains(res.Error, "not supported") || accepts.Load() != 0 {
		t.Fatalf("unexpected result: %+v (connections=%d)", res, accepts.Load())
	}
}
