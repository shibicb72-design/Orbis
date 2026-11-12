# AGENTS.md - Orbis

Open-source Android browser (Path B: GeckoView + Mozilla Android Components). Primary tool: Claude Code (config in `.claude/`). Lessons: `docs/lessons.md` (seed).

## Ground rules
- Never install on, uninstall from, or change data on Shibi's devices without explicit ask. Prefer reporting the CI / local APK artifact.
- One branch per ticket (`opus/<topic>`); PR body starts "Do not merge without Shibi's OK." Never merge without Shibi OK, never force-push, never commit secrets.
- Never kill `claude.exe` or `opencode.exe`. Never add a Play release / upload key in-repo.
- `app/debug.keystore` is DEBUG-ONLY and NON-SECRET (android / androiddebugkey), shared house pattern. Never for Play.
- **APKs for Shibi always from `main` only.** Builds always via the Gradle wrapper (`gradlew.bat` / `scripts/gradle-brief.ps1`).
- Orbis **has INTERNET** (and ACCESS_NETWORK_STATE) — it is a browser. Do not strip those permissions.
- Torrent natives: a diff touching `app/src/main/jniLibs/**` or `native/libtorrent4j/CHECKSUMS.sha256` is approved only after the run-provenance check in `native/libtorrent4j/README.md` ("Review rule"). Natives ship only from a green `workflow_dispatch` run of Torrent natives on `main`; never from a `pull_request` run.

## Project map
- Single module `:app`, package / applicationId `com.shibi.orbis` (minSdk 29, targetSdk 36, compileSdk **37** required by GeckoView/AC 160, JDK 17).
- Kotlin, Compose Material 3. Engine: thin `BrowserEngine` interface; `GeckoEngineImpl` only may touch GeckoView.
- Dependencies only through `gradle/libs.versions.toml`. Mozilla Maven repo in `settings.gradle.kts`.
- GeckoView + AC versions are pinned as a matching pair — bump them together.
- uBlock Origin: AMO onboarding (not APK-bundled). No XPI under assets. Error log lives **only** in Settings.
- `torrent/`: engine behind `TorrentEngine`, `TorrentManager` owns the list; settings through `TorrentSettingsStore`; only `Libtorrent4jEngine.kt` imports libtorrent4j (rule already in Design rules).
- Tests: `app/src/test/java/com/shibi/orbis/` (JUnit 4).

## Build and test (always via the wrapper)
```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gradle-brief.ps1 assembleDebug testDebugUnitTest
```
Prints `BUILD OK` or the first failure lines. Full log `%TEMP%\gradle-last.log` (Grep it, never read it whole).
- Dependency verification is on (`gradle/verification-metadata.xml`, SHA-256). After adding or bumping a dependency or plugin, regenerate it and review the diff: `gradlew.bat --write-verification-metadata sha256 assembleDebug assembleFixture assembleRelease testDebugUnitTest lintDebug`.
- Engine currency: `.github/workflows/engine-currency.yml` runs daily and goes red when GeckoView/AC, libtorrent4j or the bundled OpenSSL (`native/libtorrent4j/versions.env`) is behind. Ship a GeckoView/AC bump within 3 days of every Firefox release (`docs/play/RELEASE_CHECKLIST.md` section 5).
- CI actions are pinned to commit SHAs (`# vX.Y.Z` comment); bump both together. The wrapper has a `distributionSha256Sum`; update it with the Gradle version.

## Design rules
- HARD: no dark-on-dark or light-on-light text. Every surface sets its content colour from its own container; check light and dark themes.
- UI must not import `org.mozilla.geckoview.*` — only `engine/GeckoEngineImpl.kt` may.
- Nothing may import `org.libtorrent4j.*` — only `torrent/Libtorrent4jEngine.kt` may (its classes load the native library; everything else talks to `TorrentEngine`).

## Delegation (token budget)
- Builds/tests -> `gradle-runner` (Haiku). Search -> `Explore` (Haiku).
- **Hard limits: `docs/token-budget.md`**
- The main conversation never reads PNGs, full Gradle logs or `build/` files.