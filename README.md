# Orbis

Open-source Android phone browser. No monetization, no ads in the product.

**Package:** `com.shibi.orbis`  
**SDK:** minSdk 29, targetSdk 36, compileSdk 37.2 (required by GeckoView/AC 160; platform `platforms;android-37.2`)  
**License (our code):** [MPL-2.0](LICENSE)  
**Repo:** https://github.com/shibicb72-design/Orbis

## Honest engine disclosure

Orbis Path B ships **Mozilla GeckoView** (`org.mozilla.geckoview:geckoview-omni`) and uses **Mozilla Android Components** (`org.mozilla.components:browser-engine-gecko` and related modules). Those artifacts are Mozilla software under MPL-2.0 (and other notices they ship). We disclose this prominently: the browsing engine is Gecko, not a proprietary fork we pretend to own.

Pinned versions live in `gradle/libs.versions.toml` (stable matching pair from maven.mozilla.org).

## uBlock Origin (v1 plan)

Default-on **AMO onboarding install** of **unmodified Mozilla-signed uBlock Origin**.  
**Not bundled in the APK for v1** — there is no XPI under `assets/` / `res/`. The Settings screen has a UI stub + TODO for the network install path.

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
`Download/Orbis/<name>` and the private working copy is deleted.

Defaults: download over Wi-Fi only, stop seeding when finished (nothing is uploaded once the download is done),
DHT and peer exchange on, local peer discovery off (a private tab's torrents never use it), UPnP and NAT-PMP off, no
speed limits, no seeding ratio. The engine listens on a random port picked once per install and sends libtorrent's
own client name, not "Orbis".

Privacy: peers, trackers, DHT bootstrap nodes and (if you turn local peer discovery on) devices on the same network
can see the phone's IP address, as for any peer-to-peer download. Nothing is sent to the developers. A private tab's torrents
are never written to the torrent list on disk and leave the list when private browsing ends (one that finished and
was still seeding is saved to Download/Orbis first, like a private file download). A private torrent that is still
seeding when the app's process dies is removed unsaved at the next start, because nothing about it is written to disk.

Settings > Torrents: Wi-Fi only, download and upload speed limits (KB/s), stop seeding when finished (off: Orbis
keeps sharing the finished files until you tap Stop seeding or an optional ratio is reached; they are saved to
Download/Orbis then), DHT, peer exchange, local peers, what a tapped magnet link does (Orbis or another app), and a
note on how torrents share your IP address. Changes apply at once, also to torrents that are running.

The torrent engine is libtorrent (through libtorrent4j). Its native library is loaded the first time a torrent runs,
never at app start, and the session stops as soon as no torrent is downloading or seeding.

## Architecture sketch

- Thin `BrowserEngine` / `BrowserSession` interfaces; UI must not import GeckoView types.
- `GeckoEngineImpl` is the only GeckoView-touching package under `engine/`.
- `torrent/`: the engine behind `TorrentEngine` (`Libtorrent4jEngine` is the only file that imports libtorrent4j);
  `TorrentManager` owns the torrent list; settings through `TorrentSettingsStore`.
- Compose shell: address bar + `AndroidView` content host, Settings with error log (Settings-only) and uBO onboarding stub.

## Agent / harness notes

Token-saving setup: `CLAUDE.md`, `AGENTS.md`, `.claude/`.  
Opus 5.5 medium harness for browser work already lives at `C:\Users\shibi\AndroidBrowser_work\harness\` (and S: mirror) — do not reinvent.

## CI

`.github/workflows/android.yml` — JDK 17, `assembleDebug` + unit tests, upload arm64 + x86_64 debug APK artifacts. No release/AAB/upload-key steps in v1.