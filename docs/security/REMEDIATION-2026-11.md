# Security remediation, 2026-11

Tracks the findings of the 2026-11-11 security review (F-01 to F-15, the S-09 non-web-scheme gap, and the ten
"needs device test" items). Each remediation PR updates its rows: status, PR number and the device evidence.

Status values: `open` (nothing merged), `partial` (a first step is merged), `fixed` (merged and device-checked),
`test only` (no code change expected; the device test is the deliverable), `owner decision` (waiting for Shibi).

## Findings

| Finding | Severity | Summary | Planned branch | Status | PR | Device evidence |
|---|---|---|---|---|---|---|
| F-01 | medium | Chromecast pin keyed by an attacker-supplied id; first use pins silently | `opus/sec2-cast-identity` | open | | |
| F-02 | medium | Torrent natives bundle OpenSSL 3.5.5 and a libtorrent 2.1.0 snapshot | `opus/sec2-engine-currency`, `opus/sec2-torrent-natives-build`, `opus/sec2-torrent-natives-ship` | partial (record and daily monitor in #26; build pipeline with OpenSSL 3.5.9 in #28, not shipped yet; the app still bundles OpenSSL 3.5.5) | #26, #28 | none needed (no app change) |
| F-03 | medium | No routine keeps GeckoView current | `opus/sec2-gecko-160` (first step), `opus/sec2-engine-currency` | partial (160 shipped by #24; daily Engine currency workflow and 3-day rule in #26; fixed after its first green manual run on main) | #24, #26 | H-D11 (PR 1); no device test for PR 5 |
| F-04 | low | "Is this our own provider" check can be bypassed | `opus/sec2-local-content-gates` | open | #27 | device-checked 2026-11-11 on API 36 (H-D1, H-D9 before/after; uBO dashboard and panes; error pages) |
| F-05 | low | Top-level loads are only screened for `file:` | `opus/sec2-local-content-gates` | open | #27 | device-checked 2026-11-11 on API 36 (H-D1, H-D9 before/after; uBO dashboard and panes; error pages) |
| F-06 | low | Private-tab torrents are published to the public Download folder | `opus/sec2-private-torrents` | open (fix in review: a finished private-tab torrent is kept inside Orbis, `<downloads>/private/<name>`, never MediaStore; its row offers Save to Downloads and Remove; OD-3: unsaved private-tab files, torrents and file downloads, are deleted when private browsing ends and at the next start after a crash; private-tab torrents use trackers only, local peer discovery is off session-wide while one is in the session and the DHT node is off while only private-tab torrents run; ws:// and wss:// trackers dropped from every source) | #35 | pending H-D17 |
| F-07 | low | Clear browsing data does not clear torrents | `opus/sec2-clear-torrents` | open | | |
| F-08 | low | Deeply nested JSON on the cast channel can crash the app | `opus/sec2-cast-identity` | open | | |
| F-09 | low | On Android 10 other apps can read and replace downloads and torrent payloads | `opus/sec2-storage-api29` | open | | |
| F-10 | low | Debug builds use the production package name and a public signing key | `opus/sec2-debug-appid` | open | | |
| F-11 | low | Torrent defaults reveal more than needed | `opus/sec2-torrent-exposure` | open (code in review: no "Orbis" user agent, random listen port per install, local peers off by default and never for private-tab torrents, `ssrf_mitigation` set) | #30 | pending H-D16 (wire check), H-D3, H-D5 |
| F-12 | low | Calls into another app's provider have no timeout | `opus/sec2-provider-timeouts`, `opus/sec2-provider-timeouts-2` | fixed for intent-driven calls and uploads (#29: 3 s deadline on `getType` and the torrent size query, at most 8 provider threads; #32: the calls listed in the 12b row). Not covered: the password and bookmark import pickers (`PasswordsScreen.kt:137`, `BookmarksScreen.kt:420`) and the two export writers (`PasswordsScreen.kt:158`, `BookmarksScreen.kt:405`) still call the provider on `Dispatchers.IO` with no deadline; each is one user-picked file at a time, one IO thread, no shared slots | #29, #32 | none needed (no UI; `ProviderDeadlineTest`, `TorrentIntakeReaderTest`, `UploadCacheTest`, `ImportedDocumentsTest`) |
| F-12 (12b) | low | Remaining provider calls: torrent open, shared-document import, upload picker; thread-slot leak when a provider thread cannot be built | `opus/sec2-provider-timeouts-2` | fixed (two pools of 8 provider threads: `intents` for `getType`, the torrent sheet and imports, `picked` for uploads, so a hostile app cannot block uploads; torrent open: 3 s deadline; import query and open: no dispatcher thread waits; upload name, size and type: 10 s each, open: 120 s, copy: 30 s idle watchdog, closed on cancel) | #32 | none needed (no UI; unit tests above) |
| F-13 | info | Password store hardening | `opus/sec2-passwords` | open | | |
| F-14 | info | Lock icon follows the URL scheme (HTTPS-only already defaults to all tabs) | `opus/sec2-security-indicator` | open (fix in #31: the lock follows Gecko's `onSecurityChange` report, not the scheme; no lock before the report or with a certificate exception; a load that does not commit gives the old page its lock back; mixed active content would drop it, but Gecko blocks it at the default `security.mixed_content.block_active_content`; engine HTTPS-only initialiser matches the default) | #31 | pending H-D21: `https://example.com` and `https://mixed-script.badssl.com/` (script blocked, page stays secure) show the lock, `http://http.badssl.com/` does not, a Custom Tab the same (`SecurityIndicatorTest`, `CustomTabLockComposeTest`) |
| F-15 | info | Android 10/11 overlay and task-hijack protections are not set | `opus/sec2-overlay-tapguard` | open | | |
| S-09 gap | low | Imported documents may navigate to non-web schemes | `opus/sec2-local-content-gates` | open | #27 | pending H-D1, H-D14 |

## Device test items

| Item | Topic | Planned branch | Status | PR | Device evidence |
|---|---|---|---|---|---|
| 1 | `content:` navigation from an imported HTML file (F-05) | `opus/sec2-local-content-gates` | open | #27 | pending H-D1 before and after |
| 2 | Safe Browsing really active | none (test only) | open | | |
| 3 | BEP 27 private torrents stay off DHT, PEX and LSD | `opus/sec2-torrent-exposure`, `opus/sec2-private-torrents` | open (libtorrent 2.1.0 at 24a3adf: DHT `torrent.cpp:698`, `:2896`; LSD `torrent.cpp:2750`, `session_impl.cpp:5727`; PEX `ut_pex.cpp:605`, which is decided when the torrent is added, so a private magnet kept PEX; PR 9 sets DISABLE_DHT/PEX/LSD for every BEP 27 torrent and logs `bep27=true`) | #30, #35 | pending H-D3 |
| 4 | Nothing is written before approval (hostile torrent names) | none (test only) | open | | |
| 5 | LAN requests caused by a torrent | `opus/sec2-torrent-exposure` | open (`ssrf_mitigation` stated explicitly; it covers loopback only, so LAN trackers and web seeds are what H-D5 checks) | #30 | pending H-D5 |
| 6 | Tap timing on confirm sheets | `opus/sec2-overlay-tapguard` | open | | |
| 7 | OpenSSL and libtorrent version strings in the shipped `.so` (F-02) | `opus/sec2-torrent-natives-ship` | open | | |
| 8 | Reader view on a hostile page | `opus/sec2-reader-scan` | open | | |
| 9 | Non-PDF file imported as `application/pdf` | `opus/sec2-local-content-gates` | open | #27 | pending H-D9 before and after |
| 10 | Android 10 storage (F-09) | `opus/sec2-storage-api29` | open | | |
