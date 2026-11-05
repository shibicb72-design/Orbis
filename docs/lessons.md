# Lessons (Orbis)

- Debug keystore is committed and shared so CI and local APKs update in place. Never use `~/.android/debug.keystore` as the app signing source of truth.
- UI must not call GeckoView types; keep a thin `BrowserEngine` boundary.
- uBlock Origin is AMO-onboarded, not APK-bundled, for v1.
- Error / debug log UI lives only under Settings.
- GeckoView and `browser-engine-gecko` must stay on a matching major/channel pair from maven.mozilla.org.