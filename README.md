# Orbis

Open-source Android phone browser. No monetization, no ads in the product.

**Package:** `com.shibi.orbis`  
**SDK:** minSdk 29, targetSdk 36, compileSdk 37.2 (required by GeckoView/AC 159; platform `platforms;android-37.2`)  
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

## Architecture sketch

- Thin `BrowserEngine` / `BrowserSession` interfaces; UI must not import GeckoView types.
- `GeckoEngineImpl` is the only GeckoView-touching package under `engine/`.
- Compose shell: address bar + `AndroidView` content host, Settings with error log (Settings-only) and uBO onboarding stub.

## Agent / harness notes

Token-saving setup: `CLAUDE.md`, `AGENTS.md`, `.claude/`.  
Opus 5.5 medium harness for browser work already lives at `C:\Users\shibi\AndroidBrowser_work\harness\` (and S: mirror) — do not reinvent.

## CI

`.github/workflows/android.yml` — JDK 17, `assembleDebug` + unit tests, upload arm64 + x86_64 debug APK artifacts. No release/AAB/upload-key steps in v1.