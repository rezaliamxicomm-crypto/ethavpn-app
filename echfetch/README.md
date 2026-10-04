# echfetch: the subscription fetch with Encrypted Client Hello

`echfetch.go` is `FetchSubscriptionEch(requestJSON) -> resultJSON`: how both SkyRay apps fetch their
own link. Here ECH is enforced — once a key is set Go's TLS never falls back to a server name in the
clear, and the request is written only after the server has accepted ECH; nothing in this directory
states the link host's name in the clear. Standard library only.

The apps call it twice at most (the two ways out below). Only when neither call got an answer does
an app fetch the link once more as it did before, without ECH: the operator's last resort for a
network that blocks ECH itself. That last fetch is the app's own code, not this directory's.

This directory is the same, file for file, in both repositories (`ethavpn-app`, `skyray-ios`): change
it in both. Android compiles `echfetch.go` into `libv2ray` (`package libv2ray`, the
`AndroidLibXrayLite` submodule, bound by gomobile as `Libv2ray.fetchSubscriptionEch`); iOS compiles
it and `echfetch_darwin.go` into libXray's C bridge (`package main`, exported as
`CGoFetchSubscriptionEch`).

- **The key** (Cloudflare's ECH configuration, the same for every zone) is asked over plain UDP DNS,
  port 53, of public resolvers, all at once: the HTTPS record of `lookupName` — the ECH public name,
  `cloudflare-ech.com`, which is also what the lines' own ECH lookups ask, so the link host is never
  put into a DNS query. The first answer that carries a key wins; an answer without one (an injected
  one) is skipped and the socket keeps listening. Never the phone's resolver, never DNS over HTTPS.
- **No resolver answers:** the key the app carries is offered. A key the server no longer knows is
  answered with a retry key, and the fetch goes again with that — the carried key never has to be
  fresh, and no DNS is needed at all.
- **The address** is a Cloudflare address the app already knows — the stored lines' clean addresses
  (the selected line's first), then a pinned list — never the host's A record. Each address gets a
  bounded time for its handshake, so one that stalls leaves time for the next. The apps end the
  pinned list with a name instead of an address, the ECH public name, which the phone's own
  resolver turns into addresses: on a network without IPv4 (IPv6 only, NAT64) no IPv4 address can
  be dialled, only a name, and any Cloudflare address serves the link host.
- **Two ways out, ECH on both.** `interface` binds every socket to that interface
  (`echfetch_darwin.go`; iOS, where a running tunnel takes the app's own traffic: bound to the
  phone's interface the fetch leaves beside it). `proxy` opens the connection through that local HTTP
  proxy with CONNECT (Android, the running tunnel's own); no UDP travels there, so no resolver is
  asked and the key is the carried one.

The tests (`echfetch_test.go`: an ECH-enabled local server, a fake resolver, a CONNECT proxy): fresh
key, stale key recovered with the retry key, no key means no connection, a plain server refused,
injected and malformed answers skipped, the lookup name is what the resolvers are asked, through a
proxy and a proxy that refuses, a stalled address leaves time for the next, address order, a name
dialled like an address.
`echfetch_darwin_test.go` runs on macOS only (the binding), `echfetch_other_test.go` everywhere else.

With Go 1.24 or newer and no Android SDK or Xcode:

    mkdir -p /tmp/echmod && cp echfetch/*.go /tmp/echmod/ && cd /tmp/echmod
    printf 'module example.invalid/libv2ray\n\ngo 1.24\n' > go.mod && go test ./...
