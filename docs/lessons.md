# Lessons (Orbis)

- Debug keystore is committed and shared so CI and local APKs update in place. Never use `~/.android/debug.keystore` as the app signing source of truth.
- UI must not call GeckoView types; keep a thin `BrowserEngine` boundary.
- uBlock Origin is AMO-onboarded, not APK-bundled, for v1.
- Error / debug log UI lives only under Settings.
- GeckoView and `browser-engine-gecko` must stay on a matching major/channel pair from maven.mozilla.org.
- `GeckoSessionSettings.useTrackingProtection` defaults to false: set it true on every session or ETP does nothing there; build `ContentBlocking.Settings` via its Builder, not just `setEnhancedTrackingProtectionLevel`.
- `WebExtensionController.install()` hangs forever (no error) if no `PromptDelegate` is set or `onInstallPromptRequest` returns null; set the delegate at runtime creation and always return a result.
- `Application.onCreate` also runs in Gecko child processes (`:tab`, `:gpu`); return early when `Application.getProcessName() != packageName` so no runtime or container is created there.
- The browser session must outlive the composition: own it in `AppContainer`, never `close()` it in a composable `onDispose`, or opening Settings destroys the page.