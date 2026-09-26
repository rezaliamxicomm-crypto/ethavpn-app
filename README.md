# EthaVPN for Android

The EthaVPN client: install it, tap your subscription link in Telegram, tap Connect.
It picks the best line for you, switches by itself when one stops working, shows your
remaining days and data, and its Renew and Support buttons bring you back to the bot.

A fork of [v2rayNG](https://github.com/2dust/v2rayNG) (GPL-3.0) — the full v2rayNG user
interface is still there under **Advanced** — with [Xray-core](https://github.com/XTLS/Xray-core)
through [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) and
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel). Android 7.0 (API 24) and up.

## Download

From the EthaVPN download page (the 🤖 button in the bot sends the same file) or the
[releases](https://github.com/rezaliamxicomm-crypto/ethavpn-app/releases) here. Every release
carries a detached GPG signature by the EthaVPN release key; the key's fingerprint is
published in the release itself and in the EthaVPN channel — compare them before trusting a
download from anywhere else. The APK signing certificate's SHA-256 is in every release as
`signing-cert-sha256.txt`.

## What the fork changes

- `HomeActivity`: one screen — paste / scan / receive the link, Connect, the server choice
  (`Auto (fastest)` by default, or a line picked by hand — `ServerPicker`), account card
  (days, data, notice), Renew, Support.
- `EthaSettingsActivity`: eight rows — server, apps that bypass the VPN, language, update, send
  logs, About, privacy policy (opens allionapp.com/skyray-privacy), delete account (wipes the
  subscription, its servers and the account data from the phone after a confirmation). The full v2rayNG interface is reachable only after seven taps on the version
  line in About (`Advanced (v2rayNG)` appears in Settings); nothing else of it is exposed.
- The app is called **SkyRay** (launcher name, title, notification channel, `SkyRay/<version> (android)`
  User-Agent, `SkyRay_<version>_<abi>.apk` files); the application id is `com.allion.skyray` (the id registered on
  Google Play; the App Store bundle id is the same). Two flavors of the same code: `direct` (the
  /dl/ APKs, in-app updater) and `play` (the Play bundle, no installer permission, `Updates.open()`
  sends to the listing). The icon is a
  comet over a night sky: vector drawables `ic_launcher_*_skyray` for the adaptive icon, and
  `tools/skyray_icon.py` paints the same design into the fallback PNGs (launcher, status bar, TV banner).
- First launch with no account: if the clipboard holds one of our links (the landing page copies it when the
  customer taps Download), the account is added by itself (`HomeActivity.onWindowFocusChanged`).
- Deep links: `ethavpn://install-sub?url=…` (the scheme did not change with the id) and verified App Links for
  `https://<host>/sub/<token>` (`UrlSchemeActivity`).
- Subscription headers (`Subscription-Userinfo`, `Profile-Title`, `Profile-Update-Interval`,
  `Announce`, `Support-Url`, `Profile-Web-Page-Url`) parsed and shown (`EthaSubscription`).
- The subscription refreshes by itself: the upstream per-subscription WorkManager job
  (`SubscriptionUpdater`, quiet for our link) every `Profile-Update-Interval` hours — 3 — scheduled
  as soon as the link is imported, plus a silent refresh when the screen comes up after an hour.
- Auto-select on Connect (`AutoSelect`) and a watchdog while connected that switches lines
  after two failed probes (`CoreServiceManager`), unless the customer keeps a line pinned.
- Updates from the service's own `/dl/latest.json`, verified by sha256 before install
  (`UpdateCheckerManager`, `CheckUpdateActivity`); GitHub releases as the fallback.
- Defaults for Iran: the Iran routing preset, Iranian geo files, a domestic resolver for the
  direct-routed traffic, no fragment, no mux; `EthaVPN/<version> (android)` User-Agent.

Everything else is upstream v2rayNG 2.2.6. Rebases onto newer upstream releases are expected;
keep the diff small.

## Building

GitHub Actions builds every push to `main` and runs the unit tests
(`.github/workflows/build.yml`); a manual run with `release_tag` makes a release. Locally:
Android Studio or `./gradlew assembleDirectRelease` inside `V2rayNG/` after placing
`libv2ray.aar` in `V2rayNG/app/libs/` and running `compile-hevtun.sh` (needs the NDK).
`docs/RELEASE.md` describes a release end to end.

## Privacy

[PRIVACY.md](PRIVACY.md). The app collects nothing and reports nothing to anyone.

## License

GPL-3.0, like v2rayNG. See [LICENSE](LICENSE).
