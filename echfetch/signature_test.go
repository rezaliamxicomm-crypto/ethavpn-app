package libv2ray

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"testing"
)

type sigVectors struct {
	PublicKey string `json:"publicKey"`
	KeyID     string `json:"keyId"`
	Body      string `json:"body"`
	Canonical string `json:"canonical"`
	Cases     []struct {
		Name    string            `json:"name"`
		Headers map[string]string `json:"headers"`
		Body    string            `json:"body"`
		Valid   bool              `json:"valid"`
	} `json:"cases"`
}

func loadSigVectors(t *testing.T) sigVectors {
	var v sigVectors
	if err := json.Unmarshal([]byte(sigVectorsJSON), &v); err != nil {
		t.Fatal(err)
	}
	return v
}

// The box's vectors: the canonical text byte for byte, the signature accepted as sent and with header names in any
// case, refused when the body, a steering header or the set of x-etha headers changed, indifferent to the rest.
func TestSignatureVectorsFromTheBox(t *testing.T) {
	v := loadSigVectors(t)
	keys := []string{v.PublicKey}
	for _, c := range v.Cases {
		body := v.Body
		if c.Body != "" {
			body = c.Body
		}
		verdict, at, id := sigVerify(c.Headers, []byte(body), keys)
		if c.Valid && verdict != sigOK {
			t.Errorf("%s: want ok, got %s", c.Name, verdict)
		}
		if !c.Valid && verdict == sigOK {
			t.Errorf("%s: want a refusal, got ok", c.Name)
		}
		if c.Valid && (at != 1800000000 || id != v.KeyID) {
			t.Errorf("%s: at %d, key %s", c.Name, at, id)
		}
	}
	first := v.Cases[0]
	if got := sigCanonical(1800000000, []byte(v.Body), first.Headers); got != v.Canonical {
		t.Errorf("canonical text differs from the box's:\n%q\n%q", got, v.Canonical)
	}
}

// Our own key: no signature is "none", no pinned key is "nokeys", a key id we do not pin is "unknown-key", a second
// pinned key is tried when no id names one, and a forged signature is "bad".
func TestSignatureVerdicts(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	other, _, _ := ed25519.GenerateKey(rand.Reader)
	body := []byte("dmxlc3M6Ly8=")
	headers := map[string]string{"Profile-Title": "base64:QQ==", "X-Etha-Auto": "round=180", "Content-Type": "text/plain"}
	sig := ed25519.Sign(priv, []byte(sigCanonical(1700000000, body, headers)))
	signed := map[string]string{}
	for k, v := range headers {
		signed[k] = v
	}
	signed["X-Etha-Sig-At"] = "1700000000"
	signed["X-Etha-Sig"] = base64.RawURLEncoding.EncodeToString(sig)
	signed["X-Etha-Sig-Key"] = sigKeyID(pub)
	pubB64, otherB64 := base64.StdEncoding.EncodeToString(pub), base64.StdEncoding.EncodeToString(other)

	if v, _, _ := sigVerify(headers, body, []string{pubB64}); v != sigNone {
		t.Errorf("unsigned: %s", v)
	}
	if v, at, _ := sigVerify(signed, body, nil); v != sigNoKeys || at != 1700000000 {
		t.Errorf("no keys: %s %d", v, at)
	}
	if v, _, _ := sigVerify(signed, body, []string{otherB64}); v != sigUnknownKey {
		t.Errorf("another key only: %s", v)
	}
	if v, _, id := sigVerify(signed, body, []string{otherB64, pubB64}); v != sigOK || id != sigKeyID(pub) {
		t.Errorf("two keys pinned: %s %s", v, id)
	}
	delete(signed, "X-Etha-Sig-Key")
	if v, _, _ := sigVerify(signed, body, []string{otherB64, pubB64}); v != sigOK {
		t.Errorf("no key id, every key tried: %s", v)
	}
	if v, _, _ := sigVerify(signed, body, []string{otherB64}); v != sigBad {
		t.Errorf("no key id, the wrong key: %s", v)
	}
	signed["X-Etha-Sig"] = base64.RawURLEncoding.EncodeToString(append([]byte{}, sig[:63]...)) + "A"
	if v, _, _ := sigVerify(signed, body, []string{pubB64}); v != sigBad {
		t.Errorf("a byte changed: %s", v)
	}
	signed["X-Etha-Sig"] = "not base64 at all!"
	if v, _, _ := sigVerify(signed, body, []string{pubB64}); v != sigBad {
		t.Errorf("garbage: %s", v)
	}
	signed["X-Etha-Sig"] = base64.RawURLEncoding.EncodeToString(sig)
	signed["X-Etha-Sig-At"] = "soon"
	if v, _, _ := sigVerify(signed, body, []string{pubB64}); v != sigBad {
		t.Errorf("a time that is no number: %s", v)
	}
}
