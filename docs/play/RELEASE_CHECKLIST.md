# Orbis on Google Play: release checklist

Status: prepared, not submitted. Everything Google needs from the owner is marked **Owner**.

## 1. Signing (done locally, never in this repository)
- Upload key: `C:\Users\shibi\orbis-signing\orbis-upload.jks` (PKCS12, RSA 4096, alias `orbis-upload`, valid to 2054).
- Passwords: `C:\Users\shibi\orbis-signing\keystore.properties` on the same machine. Gradle reads it from there (or from
  the path in `ORBIS_SIGNING_PROPERTIES`). Without it the release build is unsigned (CI).
- Upload certificate SHA-256: `58:D7:74:C2:30:70:BB:4A:4C:9F:80:D9:77:FC:73:C6:06:7F:2F:92:FD:EC:8B:C8:0C:8F:6A:45:2E:57:FE:7E`
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
- targetSdk 36 meets Play's current target API requirement.
- Extensions: only uBlock Origin from addons.mozilla.org. Play allows browsers to run web content and extensions
  (JavaScript); no native code is downloaded.
- Updates: on Play builds the in-app GitHub update check turns itself off (installer = Play).
- Size: the AAB is about 280 MB with both ABIs; Play serves only the device's ABI (roughly the arm64 split).
