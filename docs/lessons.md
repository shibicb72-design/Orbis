# Lessons (Orbis)

- Debug keystore is committed and shared so CI and local APKs update in place. Never use `~/.android/debug.keystore` as the app signing source of truth.
- UI must not call GeckoView types; keep a thin `BrowserEngine` boundary.
- uBlock Origin is AMO-onboarded, not APK-bundled, for v1.
- Error / debug log UI lives only under Settings.
- GeckoView and `browser-engine-gecko` must stay on a matching major/channel pair from maven.mozilla.org.
- `GeckoSessionSettings.useTrackingProtection` defaults to false: set it true on every session or ETP does nothing there; build `ContentBlocking.Settings` via its Builder, not just `setEnhancedTrackingProtectionLevel`.
- `WebExtensionController.install()` hangs forever (no error) if no `PromptDelegate` is set or `onInstallPromptRequest` returns null; set the delegate at runtime creation and always return a result.
- `Application.onCreate` also runs in Gecko child processes (`:tab`, `:gpu`); return early when `Application.getProcessName() != packageName` so no runtime or container is created there.
- The browser session must outlive the composition: own it in `AppContainer` (via `TabManager`), never `close()` it in a composable `onDispose`, or opening Settings destroys the page.
- Every `PromptDelegate` callback returns a `GeckoResult<PromptResponse>` and a `BasePrompt` can also be dismissed by Gecko itself (`setDelegate(...).onPromptDismiss`, `isComplete()`): complete each result exactly once (a once-only wrapper), and complete all pending ones on session close and on crash/kill, or the page hangs.
- `ButtonPrompt` in this GeckoView carries only `message` and POSITIVE/NEGATIVE; there are no button labels to show. `GeckoSession.SessionState` round-trips through `toString()`/`fromString()` (that string is what the tab list persists; `fromString` can fail, so keep a url fallback).
- `WebResponse.body` (from `ContentDelegate.onExternalResponse`) is a live `InputStream`: every response must end in a read-to-end or a close, even when nobody wants it (no host, background tab, user says no).
- One `GeckoView` shows one session: release the attached one (`releaseSession()`) before `setSession(other)`, and make the swap idempotent (Compose `update` re-runs); a popup child from `onNewSession` is handed to Gecko unopened, so never call `open` on it.
- Popup gating holds because Gecko calls `NavigationDelegate.onLoadRequest` (via `LoadURIDelegate.load` in `GeckoViewNavigation`) before `onNewSession` for every window-open path, so the user-gesture and http(s) check in `onLoadRequest` is the primary gate; `onNewSession` re-checks the scheme only as defense in depth.
