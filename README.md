# Orbis

Open-source Android phone browser. No monetization, no ads in the product. Orbis needs Android 12 or newer.

**Package:** `com.shibi.orbis` (release; debug builds install as `com.shibi.orbis.debug`)  
**SDK:** minSdk 31, targetSdk 36, compileSdk 37.2 (required by GeckoView/AC 160; platform `platforms;android-37.2`)  
**License (our code):** [MPL-2.0](LICENSE)  
**Repo:** https://github.com/shibicb72-design/Orbis

## Honest engine disclosure

Orbis Path B ships **Mozilla GeckoView** (`org.mozilla.geckoview:geckoview-omni`) and uses **Mozilla Android Components** (`org.mozilla.components:browser-engine-gecko` and related modules). Those artifacts are Mozilla software under MPL-2.0 (and other notices they ship). We disclose this prominently: the browsing engine is Gecko, not a proprietary fork we pretend to own.

Pinned versions live in `gradle/libs.versions.toml` (stable matching pair from maven.mozilla.org).

## uBlock Origin

Optional install of **unmodified Mozilla-signed uBlock Origin** from Mozilla's add-on store (`addons.mozilla.org`), offered on first run.  
**Not bundled in the APK** — there is no XPI under `assets/` / `res/`. Orbis downloads it when you agree and checks Mozilla's store for updates.

Baseline defense in depth: GeckoView **ETP Strict** / ContentBlocking is enabled in the engine impl.

## Build (Alienware / local)

JDK 17. Always use the Gradle wrapper:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

Or the house brief wrapper:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gradle-brief.ps1 assembleDebug testDebugUnitTest
```

ABI splits: `arm64-v8a` (phones) and `x86_64` (emulators). Debug APKs are signed with the committed `app/debug.keystore` (android / androiddebugkey) — debug-only, non-secret, never for Play.

Play / release upload keys are **not** wired in this scaffold (no `ORBIS_UPLOAD_*`).

## Torrents

Orbis downloads torrents itself: tap a magnet link or a `.torrent` link (or open one from another app), check the
name, size and file count on the confirm sheet, and tap Download. Torrents sit in the Downloads list next to file
downloads, with pause, resume and progress in a notification. When a torrent is finished its files are copied to
`Download/Orbis/<name>` and the private working copy is deleted (a private tab's torrent is kept inside Orbis
instead; see Privacy below).

Defaults: download on any connection (Wi-Fi only can be turned on in Settings > Torrents), stop seeding when finished (nothing is uploaded once the download is done),
DHT and peer exchange on, local peer discovery off, UPnP and NAT-PMP off, no
speed limits, no seeding ratio. The engine listens on a random port picked once per install and sends libtorrent's
own client name, not "Orbis".

Privacy: peers, trackers, DHT bootstrap nodes and (if you turn local peer discovery on) devices on the same network
can see the phone's IP address, as for any peer-to-peer download. Nothing is sent to the developers. Only http, https
and udp trackers are used; WebSocket (WebTorrent) and every other kind are dropped from every torrent. A private tab's torrents use their trackers only (no DHT, peer
exchange or local peer discovery; while one runs, local peer discovery is off for the whole session), are never
written to the torrent list on disk, and leave the list when private browsing ends. A finished one is kept inside
Orbis (`<app files>/Download/.s/<name>`), not published; its row offers "Save to Downloads", which copies it to
`Download/Orbis/<name>`. When private browsing ends, or at the next start after the process died, every private file
that was not saved is deleted: partial payloads, finished torrents and private file downloads alike (private file
downloads also live in `Download/.s/`, a neutral name because it shows in Open and Share URIs).

Settings > Torrents: Wi-Fi only, download and upload speed limits (KB/s), stop seeding when finished (off: Orbis
keeps sharing the finished files until you tap Stop seeding or an optional ratio is reached; they are saved to
Download/Orbis then), DHT, peer exchange, local peers, what a tapped magnet link does (Orbis or another app), and a
note on how torrents share your IP address. Changes apply at once, also to torrents that are running.

The torrent engine is libtorrent (through libtorrent4j). Its native library is loaded the first time a torrent runs,
never at app start, and the session stops as soon as no torrent is downloading or seeding.

The torrent natives (`app/src/main/jniLibs/<abi>/libtorrent4j.so`, with libtorrent, Boost and OpenSSL inside) are built
by `.github/workflows/torrent-natives.yml`, not taken from upstream's jars. To update OpenSSL change `build.env`, run
the workflow, commit the artifacts and `CHECKSUMS.sha256` (`native/libtorrent4j/README.md`). Every build checks the
committed files against `native/libtorrent4j/CHECKSUMS.sha256` first (`verifyTorrentNatives`).

## Add-ons

Settings > Add-ons offers a fixed list of five add-ons: SponsorBlock, Consent-O-Matic, ClearURLs, Dark Reader and
Violentmonkey (listed last, under "Advanced"). Each is downloaded from `addons.mozilla.org` when you add it, after a
dialog that shows the access it asks for, and updated from there. They are made by other people. An update that asks
for more access is not applied until you remove the add-on and add it again. An add-on's button and popup show in
Orbis; a control inside a popup can open one of that add-on's own pages in a new tab, and nothing else.

## Secure DNS

Settings > Secure DNS is off by default (Orbis then uses the phone's DNS, including its Private DNS setting). When on,
Orbis sends the names of the sites you open, encrypted, to Quad9, Cloudflare, Mullvad or an `https://` address you
type. If the provider cannot be reached, Orbis uses the phone's DNS.

## Backup and restore

Settings > Backup and restore saves one file, where you choose, encrypted with a password you type (at least 10
characters). It holds bookmarks with folders, saved passwords, a fixed list of settings, site permissions, which add-ons
are installed, open tabs (optional, on) and browsing history (optional, off). It does not hold cookies and sign-ins, site
data, downloads, private tabs, add-on data or the sites where tracking protection was turned off. Orbis keeps no copy of the file or the password.
Restoring adds bookmarks and passwords to the ones on the phone, replaces the settings in the file, and never installs
add-ons. The optional fingerprint unlock keeps the password on the phone only (Android Keystore).

## Privacy and lock settings

- Clear when Orbis is closed: clears open tabs, history, cookies, site data and cache at the next start, with a list
  of sites whose cookies are kept.
- Lock private tabs: asks for your fingerprint or screen lock when you come back to Orbis.
- Both are local; nothing is sent anywhere.

## Tabs, search and looks

- Tab groups in the tab switcher and the tab strip, and Duplicate tab.
- Custom search engines: an engine you add receives what you search for from the address bar. It gets no suggestions
  requests, and its result pages stay out of Top sites and suggestions.
- Menu order and the toolbar button are your choice. Material You takes the colours from the wallpaper. These are stored on the phone only.
- Orbis is English only.

## Architecture sketch

- Thin `BrowserEngine` / `BrowserSession` interfaces; UI must not import GeckoView types.
- `GeckoEngineImpl` is the only GeckoView-touching package under `engine/`.
- `torrent/`: the engine behind `TorrentEngine` (`Libtorrent4jEngine` is the only file that imports libtorrent4j);
  `TorrentManager` owns the torrent list; settings through `TorrentSettingsStore`.
- Compose shell: address bar + `AndroidView` content host, Settings with error log (Settings-only) and the uBlock Origin onboarding.

## Agent / harness notes

Token-saving setup: `CLAUDE.md`, `AGENTS.md`, `.claude/`.  
Opus 5.5 medium harness for browser work already lives at `C:\Users\shibi\AndroidBrowser_work\harness\` (and S: mirror) — do not reinvent.

## CI

`.github/workflows/android.yml` — JDK 17, `assembleDebug` + unit tests, upload arm64 + x86_64 debug APK artifacts. No release/AAB/upload-key steps in v1.