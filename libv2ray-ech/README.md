# libv2ray-ech: the subscription fetch with Encrypted Client Hello

`echfetch.go` adds `FetchSubscriptionEch(requestJSON) -> resultJSON` to the core library
(`package libv2ray`, the `AndroidLibXrayLite` submodule). The app calls it for its own link
(`handler/EthaEchFetch.kt`): the link host is filtered by its name inside Iran, and this fetch
never states the name in the clear. The file has no dependency beyond Go's standard library.

- The ECH key comes from the host's HTTPS DNS record, asked of public resolvers over plain UDP
  port 53, all at once; an answer without the key (an injected one) is skipped and the socket
  keeps listening for the real one. No resolver: the pinned key, which the server's retry key
  replaces. So no DNS over HTTPS, and no DNS at all when it comes to it.
- The connection goes to a Cloudflare address the app already knows (the stored lines' clean
  addresses, the record's hints, a pinned list), never to the host's poisoned A record.
- When the server does not accept ECH, no request is sent and the next address is tried.

The workflow (`.github/workflows/build.yml`) runs `echfetch_test.go` in a scratch module, copies
`echfetch.go` into the submodule and builds `libv2ray.aar` from it with the submodule's own
`gomobile bind` recipe, instead of downloading upstream's prebuilt file. The result is cached
by the submodule commit and this directory's content.

Locally, with Go 1.24 or newer and no Android SDK:

    mkdir -p /tmp/echmod && cp libv2ray-ech/*.go /tmp/echmod/ && cd /tmp/echmod
    printf 'module example.invalid/libv2ray\n\ngo 1.24\n' > go.mod && go test ./...
