# SkyRay on Google Play — the listing and the console checklist

The bundle: `SkyRay_<version>_play.aab` on the GitHub release page (built and signed by CI, see
`docs/RELEASE.md` → Google Play). Graphics here: `icon-512.png` (app icon), `feature-1024x500.png`
(feature graphic), both from `tools/play_assets.py`. Screenshots come from a phone: at least two,
9:16, 1080×1920 or larger — the home screen connected, the settings screen.

## Store listing

- **App name** (30): `SkyRay VPN`
- **Short description** (80), en: `Fast, private VPN. Add your account with one tap and connect.`
- **Short description**, fa: `وی‌پی‌ان سریع و امن. با یک ضربه اکانت را اضافه کنید و وصل شوید.`
- **Full description**, en:

  SkyRay is a fast, private VPN client. Get your account link from the SkyRay bot on Telegram, tap it,
  and SkyRay adds the account by itself — then one tap on Connect. The app tests the available lines
  and picks the fastest one for you, switches when a line stops answering, and keeps your account up
  to date in the background.

  • One-tap setup from your link — no manual configuration
  • Connect: the best line is chosen for you, and re-chosen when needed
  • Days and data left on the home screen
  • Apps that bypass the VPN (banks, local services), chosen by you
  • Renew and support from inside the app
  • English, Persian and Russian

  SkyRay uses the Xray core and is based on v2rayNG (GPL-3.0). A subscription from the SkyRay bot is
  required to connect. Privacy policy: https://allionapp.com/skyray-privacy

- **Full description**, fa:

  اسکای‌ری یک وی‌پی‌ان سریع و امن است. لینک اکانت خود را از ربات SkyRay در تلگرام بگیرید، روی آن بزنید تا
  اکانت خودش اضافه شود، بعد یک ضربه روی «اتصال». برنامه سرورها را تست می‌کند و سریع‌ترین را برای شما
  انتخاب می‌کند، اگر سروری جواب ندهد عوض می‌کند و اکانت شما را در پس‌زمینه به‌روز نگه می‌دارد.

  • راه‌اندازی با یک ضربه روی لینک، بدون تنظیم دستی
  • اتصال: بهترین سرور خودش انتخاب می‌شود
  • روزها و حجم باقی‌مانده در صفحه‌ی اصلی
  • برنامه‌هایی که از وی‌پی‌ان رد نشوند (بانک‌ها و سرویس‌های داخلی)، به انتخاب شما
  • تمدید و پشتیبانی از داخل برنامه
  • فارسی، انگلیسی و روسی

  اسکای‌ری از هسته‌ی Xray استفاده می‌کند و بر پایه‌ی v2rayNG (GPL-3.0) ساخته شده است. برای اتصال به اشتراک
  از ربات SkyRay نیاز است. سیاست حریم خصوصی: https://allionapp.com/skyray-privacy

- **Category**: Tools. **Tags**: VPN, privacy. **Contact email**: the developer account's.
- **Privacy policy URL**: `https://allionapp.com/skyray-privacy`

## App content declarations (Policy → App content)

- **Privacy policy**: the URL above.
- **Ads**: no ads. **Target audience**: 18 and over. **News app**: no. **COVID-19**: no.
- **Data safety**: data collected — none sold, none shared with third parties. Declare: *Device or other
  IDs* → not collected; *App activity* → not collected; *Personal info* → not collected by the app
  (the account is a link; the Telegram bot holds the customer relationship). Data in transit is
  encrypted (the tunnel). Users can request deletion: Settings → Delete account (in-app), and through
  the Telegram bot's support. The subscription link (a token) is stored on the device only.
- **VPN apps** (the VpnService declaration): SkyRay is a VPN client whose core function is the VPN; it
  does not collect or sell traffic, does not inject ads, does not redirect traffic for its own gain.
- **Permissions** (Sensitive/restricted permissions declaration):
  `QUERY_ALL_PACKAGES` — the "apps that bypass the VPN" list (the user picks apps that go around the
  tunnel; a per-app VPN needs the installed-apps list);
  `FOREGROUND_SERVICE_SPECIAL_USE` — the VPN tunnel runs as a foreground service while connected;
  `CAMERA` — scanning a QR code with the account link (optional, on the user's tap).
  The Play build has **no** `REQUEST_INSTALL_PACKAGES` (updates come from Play).
- **Content rating**: the IARC questionnaire, "Utility" — no user-generated content, no gambling.
- **Government / financial features**: none.

## App integrity → App signing (before the first upload)

Choose "Export and upload a key from a Java keystore" and export the release keystore with Google's
PEPK tool on your own machine: one signature for Play and the direct download, and the App Links
statement on the server stays valid. If instead Google generates the key, copy the App signing key's
SHA-256 from that page and add it to `/var/www/html/.well-known/assetlinks.json` on the server.

## Testing requirement

A personal developer account created after November 2023 must run a closed test with at least 12
testers for 14 days before it can apply for production access; an organisation account can publish
straight to production. The bot's beta customers make good testers (add their Google account emails
to the closed-test list, or use a Google Group).
