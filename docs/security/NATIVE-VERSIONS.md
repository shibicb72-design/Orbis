# Native library versions (torrent engine)

The machine-readable record is `native/libtorrent4j/versions.env`. `scripts/check_engine_currency.py` reads it, and
the daily Engine currency workflow compares it with the newest upstream releases. Change both files together.

## What is bundled today (2026-11-11)

| Component | Version | Source |
|---|---|---|
| libtorrent4j (Java API and natives) | 2.1.0-39 | Maven Central, `org.libtorrent4j` (upstream build) |
| libtorrent | 2.1.0.0 (a 2.1.0-line snapshot, commit `24a3adf`, 2026-01-25) | inside the libtorrent4j native library |
| OpenSSL | 3.5.5 (27 Jan 2026) | statically linked into the libtorrent4j native library |

How it was measured: the `libtorrent4j-android-arm64-2.1.0-39.jar` and `libtorrent4j-android-x86_64-2.1.0-39.jar`
files from the Gradle cache were unpacked, and `lib/arm64-v8a/libtorrent4j.so` and `lib/x86_64/libtorrent4j.so`
were byte-scanned (`python -I`, a regex over the file). Both contain the strings `OpenSSL 3.5.5 27 Jan 2026` and
`libtorrent/2.1.0.0`. Upstream's `swig/android-build/Dockerfile` sets `OPENSSL_VERSION="3.5.5"`, and the libtorrent
submodule's `version.hpp` defines `LIBTORRENT_VERSION "2.1.0.0"`, which matches.

## Upstream state

- libtorrent4j 2.1.0-39 (2026-02-07) is the newest release on Maven Central and GitHub. Upgrading is not possible.
- The newest OpenSSL 3.5.x tag is `openssl-3.5.9` (2026-09-29). Advisories were fixed in 3.5.6 to 3.5.9, so the
  bundled 3.5.5 is behind.
- libtorrent itself has newer tags (`v2.1.1`, `v2.1.2`). Moving to them is a separate decision (OD-4).

## Plan

1. Security PR 5 (this record): the Engine currency workflow reports OpenSSL as a warning (`--warn-only openssl`)
   so that the GeckoView and libtorrent4j checks can be red or green on their own.
2. Security PR 6: build the libtorrent4j natives ourselves with a current OpenSSL 3.5.x (pipeline only, no app
   change).
3. Security PR 7: ship those natives, update `versions.env` (`OPENSSL_VERSION`, `SOURCE`) and this file, and drop
   `--warn-only openssl` from the workflow so an OpenSSL release turns it red.

When the workflow reports a newer 3.5.x after PR 7: rebuild the natives with that version, update `versions.env`
and this file, and release.
