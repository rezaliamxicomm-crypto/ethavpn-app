package libv2ray

// The signed subscription answer (SkyRay 2.1). The box signs every /sub/ answer — the body's sha256 and every header
// that steers the app — with an Ed25519 key only it holds (the API's app/signing.py); the apps pin the public keys and
// take an answer whose signature is missing, forged or made with a key they do not know as no answer at all. The link
// hop inside Iran terminates TLS and proxies the answer, so a seized hop could alter it (other lines, a herding hint,
// a phishing notice); this makes that visible. The canonical text, byte for byte the box's:
//
//	v1\n<at: unix seconds>\n<sha256 of the body, hex>\n<name>:<value>\n…
//
// one line per signed header present, sorted by lower-cased name: every x-etha-* header but the three signature
// headers, and the named ones in sigSignedHeaders. Headers: X-Etha-Sig-At (the seconds), X-Etha-Sig (base64url, no
// padding), X-Etha-Sig-Key (the first 8 hex of sha256 of the raw public key: which pinned key to verify with).
// One implementation for both apps (this file is the same in the Android and the iOS repository); the vectors in
// signature_vectors_test.go come from the box's own test (/opt/staging/auto-2.1/test.py, a TEST key).

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"sort"
	"strconv"
	"strings"
)

// The verdicts of sigVerify, as the result's "signed" field carries them.
const (
	sigOK         = "ok"          // the signature is one pinned key's
	sigNone       = "none"        // the answer carries no signature (a box before 2.1, or no key in place)
	sigBad        = "bad"         // a signature that does not verify: altered in transit
	sigUnknownKey = "unknown-key" // signed with a key this build does not pin (a rotation: update the app)
	sigNoKeys     = "nokeys"      // the request pinned no key: nothing was checked (a development build)
)

var sigSignedHeaders = map[string]bool{
	"announce": true, "support-url": true, "profile-web-page-url": true,
	"profile-update-interval": true, "profile-title": true, "subscription-userinfo": true,
}

func sigIsSignedHeader(lowerName string) bool {
	if strings.HasPrefix(lowerName, "x-etha-") {
		return lowerName != "x-etha-sig" && lowerName != "x-etha-sig-at" && lowerName != "x-etha-sig-key"
	}
	return sigSignedHeaders[lowerName]
}

// sigCanonical is the text the box signed: the version, the time, the body's hash, the signed headers sorted.
func sigCanonical(at int64, body []byte, headers map[string]string) string {
	sum := sha256.Sum256(body)
	lower := make(map[string]string, len(headers))
	names := make([]string, 0, len(headers))
	for k, v := range headers {
		n := strings.ToLower(k)
		if sigIsSignedHeader(n) {
			if _, seen := lower[n]; !seen {
				names = append(names, n)
			}
			lower[n] = v
		}
	}
	sort.Strings(names)
	var b strings.Builder
	b.WriteString("v1\n")
	b.WriteString(strconv.FormatInt(at, 10))
	b.WriteString("\n")
	b.WriteString(hex.EncodeToString(sum[:]))
	b.WriteString("\n")
	for _, n := range names {
		b.WriteString(n)
		b.WriteString(":")
		b.WriteString(lower[n])
		b.WriteString("\n")
	}
	return b.String()
}

// sigKeyID is X-Etha-Sig-Key's form of a raw public key.
func sigKeyID(pub []byte) string {
	s := sha256.Sum256(pub)
	return hex.EncodeToString(s[:])[:8]
}

// sigVerify checks an answer (its headers as the result carries them, names in any case; its body) against the pinned
// public keys (base64, raw 32 bytes each). It returns the verdict, the signed time (0 when there is none) and the id
// of the key that verified. A key id header names the key to use; without one every pinned key is tried.
func sigVerify(headers map[string]string, body []byte, keysB64 []string) (verdict string, at int64, keyID string) {
	lower := make(map[string]string, len(headers))
	for k, v := range headers {
		lower[strings.ToLower(k)] = v
	}
	sig, atText := strings.TrimSpace(lower["x-etha-sig"]), strings.TrimSpace(lower["x-etha-sig-at"])
	if sig == "" || atText == "" {
		return sigNone, 0, ""
	}
	ts, err := strconv.ParseInt(atText, 10, 64)
	if err != nil || ts <= 0 {
		return sigBad, 0, ""
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(sig, "="))
	if err != nil || len(raw) != ed25519.SignatureSize {
		return sigBad, ts, ""
	}
	if len(keysB64) == 0 {
		return sigNoKeys, ts, ""
	}
	msg := []byte(sigCanonical(ts, body, lower))
	wanted := strings.ToLower(strings.TrimSpace(lower["x-etha-sig-key"]))
	tried := false
	for _, k := range keysB64 {
		pub, err := base64.StdEncoding.DecodeString(strings.TrimSpace(k))
		if err != nil || len(pub) != ed25519.PublicKeySize {
			continue
		}
		id := sigKeyID(pub)
		if wanted != "" && wanted != id {
			continue
		}
		tried = true
		if ed25519.Verify(ed25519.PublicKey(pub), msg, raw) {
			return sigOK, ts, id
		}
	}
	if !tried {
		return sigUnknownKey, ts, ""
	}
	return sigBad, ts, ""
}
