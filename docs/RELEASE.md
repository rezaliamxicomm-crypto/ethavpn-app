# Releasing EthaVPN for Android

## Once: keys and secrets (the operator, off the server)

1. **Release keystore** — never reuse anything, never keep it on the server:
   ```bash
   keytool -genkeypair -v -keystore ethavpn-release.jks -alias ethavpn -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 ethavpn-release.jks > ethavpn-release.jks.b64
   ```
   A lost keystore means a new package id for every customer; a stolen one means trojan updates.
   Back it up encrypted (`age -p ethavpn-release.jks > ethavpn-release.jks.age`) somewhere that
   is not the server.
2. **Release GPG key** (signs the APKs, `latest.json` and identifies the download page's files):
   ```bash
   gpg --quick-generate-key "EthaVPN release <release@example.invalid>" ed25519 sign 5y
   gpg --armor --export-secret-keys <fingerprint> > ethavpn-release-gpg.asc     # → secret GPG_PRIVATE_KEY
   gpg --armor --export <fingerprint> > ethavpn-release-key.asc                 # published with every release
   ```
   Publish the fingerprint in the channel once; the server pins it (`/etc/ethavpn-app.fpr`).
3. **Repository secrets** (Settings → Secrets and variables → Actions): `APP_KEYSTORE_BASE64`
   (the .b64 file's content), `APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS` (`ethavpn`),
   `APP_KEY_PASSWORD`, `GPG_PRIVATE_KEY` (the armored secret key), and `GPG_PASSPHRASE` when the
   GPG key has one (leave it out for a key without a passphrase).
4. **Deploy key** for the server's working copy (`/opt/ethavpn-app`, remote `github`), like the
   other three repos: `ssh-keygen -t ed25519 -f /root/.ssh/gh-ethavpn-app -N ''`, add the public
   key as a deploy key with write access, alias `gh-ethavpn-app` in `/root/.ssh/config`.

## Every release

1. In `V2rayNG/app/build.gradle.kts` bump `versionCode` (+1) and `versionName` (semver). If old
   versions must stop working (a server-side change they cannot follow), raise
   `min_supported.txt` to the lowest version that still works — the app then insists on the
   update. Write two lines in `RELEASE_NOTES.md` (shown in the update dialog).
2. Commit and push `main`; the push build must be green (unit tests + APKs).
3. Tag and run the release workflow:
   ```bash
   git tag vX.Y.Z && git push github vX.Y.Z
   ```
   Actions → Build APK → Run workflow → `release_tag` = `vX.Y.Z`. The release gets
   `SkyRay_X.Y.Z_<abi>.apk` (+ `.sig`), `latest.json` (+ `.sig`), `ethavpn-release-key.asc`,
   `release-key-fingerprint.txt`, `signing-cert-sha256.txt`.
4. On the server: `ethavpn-app-publish vX.Y.Z` (verifies the signatures against the pinned key
   and every sha256, installs under `/var/www/html/dl/`, prints the channel post). The bot picks
   the new APK up by itself; phones learn about it within a day.
5. First release only: put `signing-cert-sha256.txt`'s value into
   `/var/www/html/.well-known/assetlinks.json` (App Links) — see the server runbook
   (`/opt/staging/app-launch/APPLY.md`, step 5).

## Rolling back

`ethavpn-app-publish vX.Y.(Z-1)` on the server: the previous files are still there, the
symlinks and `latest.json` point back. Phones that already updated keep the newer version
(Android does not downgrade); make the next release fix forward.

## Rebasing on upstream

```bash
git remote add upstream https://github.com/2dust/v2rayNG.git
git fetch upstream --tags
git rebase <new tag>          # resolve conflicts in the files README.md lists, run the unit tests
git submodule update --init --recursive
```
Upstream's `master` moved to a Compose UI after 2.2.6; the fork's `HomeActivity` is plain
ViewBinding and does not depend on `MainActivity`'s internals, only on `MainViewModel`,
`CoreServiceManager` and `AngConfigManager`.
