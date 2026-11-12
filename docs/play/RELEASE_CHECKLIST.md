# Orbis on Google Play: release checklist

Status: prepared, not submitted. Everything Google needs from the owner is marked **Owner**.

## 1. Signing (done locally, never in this repository)
- Upload key: `C:\Users\shibi\AndroidBrowser_work\ORBIS_UPLOAD_KEY_DO_NOT_DELETE\orbis-upload.jks` (PKCS12, RSA 4096, alias `orbis-upload`, valid to 2056; see the README there).
- Passwords: `orbis-upload.properties` in the same folder. Gradle reads it from there (or from the path in
  `ORBIS_SIGNING_PROPERTIES`). Without it the release build is unsigned (CI).
- Upload certificate SHA-256: `35:96:7B:31:7A:80:3B:8E:AA:F9:7E:40:5A:E6:27:37:DB:94:11:3A:71:67:B8:D8:47:6F:E3:3B:4D:33:1D:66`
- **Owner:** back up both files (an encrypted USB stick or a password manager). With Play App Signing a lost upload
  key can be reset through Play support, but it takes days. Never commit them, never send them in chat.
- Play App Signing (Google holds the app signing key) is the default for new apps: accept it.

## 2. Build
```
gradlew.bat :app:bundleRelease      # app/build/outputs/bundle/release/app-release.aab (signed with the upload key)
gradlew.bat :app:assembleR8check    # the same shrunk build, own app id, debug-signed: install and smoke test first
```
- Bump `versionCode` (must grow every upload) and `versionName` in `app/build.gradle.kts`.
- Smoke test the r8check APK: first run, a page, tabs, settings, uBlock Origin install, passwords, a download.
- After `bundleRelease`, run
  `python scripts/check_torrent_natives.py app/build/outputs/bundle/release/app-release.aab --min-openssl <version>
  --libtorrent 2.1.0.0 --abis arm64-v8a,armeabi-v7a,x86_64 --checksums native/libtorrent4j/CHECKSUMS.sha256`
  (`<version>` is `OPENSSL_VERSION` in `native/libtorrent4j/versions.env`); it must pass before upload. It checks the
  version strings and that each packaged `lib/<abi>/libtorrent4j.so` is byte for byte the file `CHECKSUMS.sha256`
  lists. Run it the same way on any APK that goes out (`app-<abi>-release.apk`, `--abis <abi>`).

## 3. Play Console (Owner)
- [ ] Developer account: https://play.google.com/console (one-time US$25 registration fee, identity verification).
      New personal accounts must run a closed test with at least 12 testers for 14 days before production.
- [ ] Create app: name "Orbis", default language English (US), app (not game), free.
- [ ] Store listing: texts in `STORE_LISTING.md`; graphics: 512x512 icon, 1024x500 feature graphic, 2-8 phone
      screenshots (and 7"/10" tablet ones for the Fold/large-screen badge).
- [ ] Privacy policy URL: publish `PRIVACY_POLICY.md` (e.g. GitHub Pages of this repository) and paste the URL.
- [ ] App content: Data safety (answers in `DATA_SAFETY.md`), ads: none, content rating questionnaire (a browser:
      "unrestricted internet access" = yes), target audience 13+ (or 18+), news app: no, government app: no.
- [ ] Permissions declarations: camera/microphone (websites, QR), location (websites), foreground service types
      `mediaPlayback` and `dataSync` (playing media, downloads) need the short explanation Play asks for.
- [ ] Default browser: Orbis declares the browser role; no special declaration is needed.
- [ ] Upload `app-release.aab` to Internal testing first, then Closed testing, then Production.

## 4. Policy notes
- Requires Android 12 or newer (minSdk 31).
- targetSdk 36 meets Play's current target API requirement.
- Extensions: only uBlock Origin from addons.mozilla.org. Play allows browsers to run web content and extensions
  (JavaScript); no native code is downloaded.
- Updates: Google Play delivers them. There is no in-app update check.
- Size: the AAB is about 280 MB with both ABIs; Play serves only the device's ABI (roughly the arm64 split).

## 5. Engine currency
- Ship a GeckoView/AC bump within 3 days of every Firefox release, major or dot. The daily Engine currency
  workflow (`.github/workflows/engine-currency.yml`) goes red when one is out.
- Bump = PR 1's recipe (#24): change the `mozillaAc` / `geckoview` pair in `gradle/libs.versions.toml`, regenerate
  verification metadata, CI, smoke test H-D11, release.
- OpenSSL: when the workflow reports a newer 3.5.x, rebuild the torrent natives and release. The torrent natives
  are built by `.github/workflows/torrent-natives.yml`; to update OpenSSL change `build.env`, run the workflow,
  commit the artifacts and `CHECKSUMS.sha256` (`docs/security/NATIVE-VERSIONS.md`, `native/libtorrent4j/README.md`).
- Before every release: run the Engine currency workflow by hand (Actions > Engine currency > Run workflow, or
  `gh workflow run engine-currency.yml --ref main`); it must be green.
- GitHub turns scheduled workflows off in a public repository after 60 days with no repository activity. If the
  Actions tab shows "This scheduled workflow is disabled", press **Enable workflow** (or
  `gh workflow enable engine-currency.yml`) and run it once by hand.
- The failure mail for a scheduled run goes to the user who last changed the `cron:` line in the workflow file.
  If that is not the owner's account, have the owner make the next edit to that line.
