# EthaVPN for Android — privacy

**English**

The app is a VPN client. It sends your traffic through the EthaVPN servers your subscription
names and nothing else. The app itself collects no analytics, no identifiers and no logs, and
sends nothing to the developers.

What leaves your phone because the service cannot work without it:

- The subscription link you added is fetched from the EthaVPN server (every few hours, and
  when you tap Refresh). That request carries the app's name and version
  (`SkyRay/<version> (android)`). The server records the time of the last fetch per
  subscription so that support can tell whether your app has ever received the servers. The
  link is fetched with Encrypted Client Hello, so its address is not visible on the way (only
  when that gets no answer is it fetched as an ordinary request); for that the app asks three
  public DNS services (Google, Cloudflare, Quad9) for Cloudflare's public encryption key — a
  question that names Cloudflare, not you and not your link.
- Your VPN traffic goes to the EthaVPN servers. What the servers keep is described by the
  service, not by this app; the app adds nothing to it.
- Once a day the app asks the EthaVPN download page whether a newer version exists. That
  request carries no identifier.
- The Renew and Support buttons open the EthaVPN Telegram bot; from there on Telegram's
  rules apply.

Nothing is sold or shared with third parties. The app is free software (GPL-3.0), built from
[v2rayNG](https://github.com/2dust/v2rayNG) and [Xray-core](https://github.com/XTLS/Xray-core);
its source is public and every release is signed.

**فارسی**

این برنامه یک کلاینت VPN است. ترافیک شما را از سرورهای EthaVPN که در اشتراک شما نام برده
شده‌اند عبور می‌دهد و بس. خود برنامه هیچ آمار، شناسه یا لاگی جمع نمی‌کند و چیزی برای
سازندگان نمی‌فرستد.

آنچه از گوشی شما خارج می‌شود چون سرویس بدون آن کار نمی‌کند:

- لینک اشتراکی که اضافه کرده‌اید هر چند ساعت (و با زدن بروزرسانی) از سرور EthaVPN گرفته
  می‌شود. این درخواست نام و نسخه‌ی برنامه را همراه دارد. سرور زمان آخرین دریافت هر اشتراک را نگه
  می‌دارد تا پشتیبانی بداند برنامه‌ی شما سرورها را گرفته است یا نه. لینک با Encrypted Client Hello
  گرفته می‌شود تا نشانی آن در مسیر دیده نشود (فقط اگر این راه پاسخی نگیرد، با یک درخواست معمولی)؛ برای این کار برنامه کلید عمومی Cloudflare را از سه
  سرویس DNS عمومی — گوگل، Cloudflare و Quad9 — می‌پرسد، پرسشی که نام Cloudflare را دارد، نه شما
  و نه لینک شما.
- ترافیک VPN شما به سرورهای EthaVPN می‌رود. آنچه سرورها نگه می‌دارند را سرویس توضیح می‌دهد، نه
  این برنامه؛ برنامه چیزی به آن اضافه نمی‌کند.
- روزی یک بار برنامه از صفحه‌ی دانلود EthaVPN می‌پرسد نسخه‌ی تازه‌تری هست یا نه. این درخواست هیچ
  شناسه‌ای ندارد.
- دکمه‌های تمدید و پشتیبانی ربات تلگرام EthaVPN را باز می‌کنند؛ از آنجا به بعد قوانین تلگرام
  حاکم است.

هیچ چیز فروخته یا با شخص ثالثی به اشتراک گذاشته نمی‌شود. برنامه نرم‌افزار آزاد است (GPL-3.0)،
بر پایه‌ی v2rayNG و Xray-core ساخته شده، کدش عمومی است و هر نسخه امضا می‌شود.
