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
- libtorrent itself has newer tags (`v2.1.1`, `v2.1.2`). Moving to them is a separate PR (7b, OD-4 approved);
  the build pipeline below changes OpenSSL only.

## What the OpenSSL update fixes, plainly

Between 3.5.5 and 3.5.9 OpenSSL rated two advisories High (OpenSSL's own labels; CVSS scores not verified):
CVE-2026-45447 (use-after-free in `PKCS7_verify()`, fixed in 3.5.7) and CVE-2026-84782 (DTLS handshake
retransmission reads a stale buffer offset, fixed in 3.5.9).

What is known about reaching them:
- Both upstream's library and ours contain libdatachannel's OpenSSL DTLS transport (the string
  `Initializing DTLS transport (OpenSSL)`), `rtc_signaling` and `websocket_tracker_connection`. Upstream's
  `swig/Jamfile` builds with `<webtorrent>on`.
- libtorrent at `24a3adf` hands `ws://` and `wss://` tracker URLs to that code when built with WebTorrent support
  (`src/tracker_manager.cpp:310-311`, `protocol == "ws" || protocol == "wss"` under `TORRENT_USE_RTC`).
- Orbis does not filter tracker URLs by scheme.
- So a torrent or magnet link that names a WebSocket tracker can lead the engine into DTLS handshakes with remote
  peers through this OpenSSL. Whether the conditions of CVE-2026-84782 are met there is not verified, and it was not
  exercised on a device. Whether `PKCS7_verify()` (CVE-2026-45447) is reachable is not checked.

This update is upkeep of a network-facing library. One of the two High advisories is in code that is compiled in
and that a torrent can route traffic to; neither is shown to be exploitable in Orbis.

## Plan

1. Security PR 5 (this record): the Engine currency workflow reports OpenSSL as a warning (`--warn-only openssl`)
   so that the GeckoView and libtorrent4j checks can be red or green on their own.
2. Security PR 6 (#28): build the libtorrent4j natives ourselves with OpenSSL 3.5.9 (pipeline only, no app
   change). See "How the natives are built" below.
3. Security PR 7: ship those natives, update `versions.env` (`OPENSSL_VERSION`, `SOURCE`) and this file, and drop
   `--warn-only openssl` from the workflow so an OpenSSL release turns it red.

When the workflow reports a newer 3.5.x after PR 7: rebuild the natives with that version, update `versions.env`
and this file, and release.

## How the natives are built

Workflow `.github/workflows/torrent-natives.yml`, script `native/libtorrent4j/build.sh`, inputs
`native/libtorrent4j/build.env`. Triggers: `pull_request` (paths under `native/libtorrent4j/`, the two check
scripts, the 16 KB script, the workflow) and `workflow_dispatch`. `permissions: contents: read`, no secrets, actions
pinned to commit SHAs. Runner `ubuntu-24.04`. ABIs arm64-v8a, armeabi-v7a, x86_64 (no x86: Orbis does not ship
one), each built twice (`repeat: [1, 2]`).

### Inputs and where each hash comes from

| Input | Value | Hash checked | Source of the hash |
|---|---|---|---|
| libtorrent4j | `da7d2403f06a0939bc2c51a88e67a6c4a85512ef` (tag `v2.1.0-39`) | commit id asserted after checkout | the tag (`git ls-remote`) |
| libtorrent submodule | `24a3adf35d3e689bde700371337aad61b160bafa` (`version.hpp` 2.1.0.0) | commit id asserted (tree entry and checkout) | `git ls-tree` at the libtorrent4j commit |
| OpenSSL 3.5.9 | GitHub release tarball | SHA-256 `603f5602...859a` | `openssl-3.5.9.tar.gz.sha256` on the GitHub release and on www.openssl.org (they agree) |
| Boost 1.90.0 | `https://archives.boost.io/release/1.90.0/source/boost_1_90_0.tar.gz` (upstream's URL) | SHA-256 `5e93d582...eea9` | https://www.boost.org/releases/1.90.0/ and `boost_1_90_0.tar.gz.json` next to the file |
| NDK r29 | `https://dl.google.com/android/repository/android-ndk-r29-linux.zip` (upstream's URL) | size 783549481, SHA-1 `87e2bb7e...c30b`, SHA-256 `4abbbcdc...8ecf` | Google publishes SHA-1 only: https://dl.google.com/android/repository/repository2-3.xml ("NDK (Side by side) 29.0.14206865") and https://github.com/android/ndk/wiki/Unsupported-Downloads. The SHA-256 was computed from a download whose size and SHA-1 matched. |

Full values are in `build.env`. Any mismatch stops the build before the archive is unpacked.

### Side by side with upstream

Upstream: `aldenml/libtorrent4j` `.github/workflows/android.yml` at `da7d240`, jobs `arm-b2`, `arm64-b2`,
`x86_64-b2`.

| Upstream step | Ours | Difference |
|---|---|---|
| `actions/checkout@v5`, `submodules: recursive`, branch head | `git init`, fetch exactly `LIBTORRENT4J_COMMIT`, `git submodule update --init --recursive`, assert both commits | (d) pinned commit instead of the branch head |
| `env` `BOOST_DOT_VERSION 1.90.0`, `NDK_VERSION r29`, `OPENSSL_VERSION 3.5.5`, `OPENSSL_NO_OPTS` | `build.env`: same Boost, NDK and `OPENSSL_NO_OPTS`; `OPENSSL_VERSION=3.5.9` | (a) OpenSSL 3.5.5 -> 3.5.9 |
| setup boost: `wget` from archives.boost.io, `tar xzf`, `mv`, `./bootstrap.sh` | `curl` (HTTPS only) from the same URL, SHA-256 check, then the same commands | (b) checksum check; `curl --proto =https` instead of `wget -nv` |
| setup ndk: `wget` from dl.google.com, `unzip -qq`, `mv` | same URL, size + SHA-1 + SHA-256 check, same commands | (b) |
| setup openssl: `wget https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz`; `CC`/`AR`/`LD`/`RANLIB` from the NDK; `./Configure <target> ${OPENSSL_NO_OPTS} <abi flags> -fPIC --prefix=...`; `make`; `make install_sw`; x86_64: `mv openssl/lib64 openssl/lib` | same environment plus `SOURCE_DATE_EPOCH=${OPENSSL_SOURCE_DATE_EPOCH}`, same `Configure` line per ABI, same `make` calls, same `lib64` move; tarball from the GitHub release | (a), (b), (f); the tarball URL is OpenSSL's GitHub release (byte-identical to the www.openssl.org file, checked 2026-11-11) |
| build: exports `ANDROID_TOOLCHAIN`, `BOOST_ROOT`, `OPENSSL_ROOT`, `LIBTORRENT_ROOT`, `CXX`, `CC`, `AR`, `LD`, `RANLIB`; `cd swig`; `b2 -j2 --user-config=config/android-<x>-config.jam variant=release toolset=<t> target-os=android location=bin/release/android/<abi>` | same exports, same `b2` line | none. Upstream's `alias ranlib=...` line is left out: aliases are not expanded in a non-interactive shell, so it does nothing there either |
| strip: `llvm-objcopy --only-keep-debug`, then `llvm-strip --strip-unneeded -x` (`-g` added for arm) | same commands and flags per ABI | none |
| dependencies: `llvm-readelf -d` | same, saved to the log | none |
| `upload-artifact@v4`: `.so` and `.so.debug`, 5 days | upload `.so`, `.so.sha256`, `BUILDINFO.json` | (e). The `.so.debug` file is produced but not uploaded |
| (none) | version strings, upstream comparison, 16 KB check (64-bit), `BUILDINFO.json`, summary table | (e) added checks |
| x86-b2 job | not built | (c) |

(f) `SOURCE_DATE_EPOCH` is exported in the OpenSSL step only (not for `b2`). OpenSSL 3.5.9's `util/mkbuildinf.pl`
takes the embedded `built on:` time from it (`gmtime($ENV{'SOURCE_DATE_EPOCH'} // time())`). The value,
`OPENSSL_SOURCE_DATE_EPOCH=1790640000`, is 2026-09-29T00:00:00Z, the 3.5.9 release date. It changes that one
string and no upstream file.

Upstream hides the OpenSSL `make` output (`&> /dev/null`); ours writes it to a log file and prints its tail on a
failure. That affects logging only. Compiler flags, `b2` arguments, NDK, Boost and API level (28) are upstream's;
the user-config files were read and do not mention OpenSSL (the build checks that again), so the upstream tree is
built untouched.

Not proven: that upstream's Maven jars were produced by this `android.yml` (likely, not shown).

### Checks on every build

- `scripts/check_torrent_natives.py --min-openssl 3.5.9 --libtorrent 2.1.0.0 --abis <abi>`: exactly one OpenSSL
  version string, not below the minimum, and `libtorrent/2.1.0.0`.
- `scripts/compare_torrent_natives.py --abi <abi>`: downloads upstream's native jar for the ABI from Maven Central,
  checks the jar SHA-256 and the inner `.so` SHA-256 against `build.env`, then requires the same exported
  `Java_org_libtorrent4j_swig_*` set, the same `JNI_OnLoad` presence, the same full exported dynamic symbol set
  (name, binding, type, visibility), the same imported symbol set, and the same ELF class, machine and
  `DT_NEEDED` list. The size difference is reported only.
- `buildinfo.py summary --require-identical` (summary job): every ABI's two builds must be byte-identical.
- `scripts/check_16kb_alignment.py --strict` on arm64-v8a and x86_64.

### How to re-run, and which runs may be shipped

Actions > Torrent natives > Run workflow, on the branch or commit to check. Each artifact
`libtorrent4j-<abi>-<repeat>` holds the `.so`, its `.sha256` and `BUILDINFO.json` (the `build.env` values, hash,
size, version strings, comparison result, submodule commits, run id, commit, runner image version). The run summary
has all hashes in one table and says, per ABI, whether repeat 1 and 2 are byte-identical.

Shipped natives come only from a `workflow_dispatch` run on `main` in which every job is green. Check each
`BUILDINFO.json`: `workflow.event` is `workflow_dispatch`, `workflow.ref` is `refs/heads/main`, and
`workflow.commit` is a commit on `main`. Never ship artifacts from a `pull_request` run. The repository is public,
and a pull request from a fork runs its own `build.sh` and uploads artifacts with the same names into a run of this
repository.

### Result of the first run (PR #28, run 43052679812, runner image ubuntu24 20261101.360.1)

| ABI | SHA-256 (repeat 1) | SHA-256 (repeat 2) | size | upstream size | JNI symbols ours / upstream |
|---|---|---|---|---|---|
| arm64-v8a | `6a324a1370f446f4da31c64e8fb99a23a2df5b8f332e36c418c7c29a7bd07bc4` | `7658792623f0f6cb9256b658c88214f3336732bc15015dac145bfe18aa380278` | 15900928 | 15883152 (+0.11%) | 3659 / 3659 |
| armeabi-v7a | `43b2e91af79b4f76f55bc5be7de4a93bff867862233172f0c1c4a16bb6683bdf` | `5e98ff8f807ae2df1d181552845d9b95ab81084d65911b9044e63b949c8208da` | 13751940 | 13734884 (+0.12%) | 3659 / 3659 |
| x86_64 | `43d0c92e83af173dc2a70672c4fe79c7b2c2a674f041bd4b2b0da7156be0048b` | `2e5859da1c0736287e0345b91224cd9b389e16ac69d3692644790da187f365c6` | 16374728 | 16358808 (+0.10%) | 3659 / 3659 |

Every library contains exactly one OpenSSL string, `OpenSSL 3.5.9 29 Sep 2026`, and `libtorrent/2.1.0.0`. The
JNI symbol sets, `JNI_OnLoad` (absent in both), ELF class and machine, and `DT_NEEDED` (`libdl.so`, `libm.so`,
`libc.so`) equal upstream's for all three ABIs. The two 64-bit libraries pass the strict 16 KB check. These hashes
are of a PR build; PR 7 ships the artifacts of a run on main, whose hashes will differ (see below).

Where upstream's binaries came from: the released `.so` files embed source paths under
`/home/runner/work/libtorrent4j/libtorrent4j/` (`boost/`, `android-ndk/`, `openssl-…`, `swig/`) and OpenSSL
`built on: Sat Feb  7 22:37:2x 2026 UTC`. That is the directory layout of upstream's `android.yml` on a GitHub
runner (not the Dockerfile route), on the day of the 2.1.0-39 release. This supports, but does not prove, that the
Maven jars were built by that workflow.

### What "reproducible" means here

The inputs are pinned and hash-checked and the runner label is fixed, so anyone can re-run the workflow from the
same commit. The runner image is not pinned by digest (GitHub updates `ubuntu-24.04` in place); `BUILDINFO.json`
records its version.

Two runs are **not** byte-identical. Within each ABI the two builds have the same size and the same section layout;
they differ in `.note.gnu.build-id` and in parts of `.rodata`, `.text`, `.data.rel.ro` and `.data`. One input
certainly varies: OpenSSL embeds its build time (`built on: Wed Nov 11 15:55:00 2026 UTC` in one build,
`15:54:55` in the other), and a changed string moves merged constants and the code that refers to them. Whether that
is the only cause is not verified. Our libraries also embed different source paths from upstream's
(`/home/runner/work/_temp/lt4j/src/…`), which does not change behaviour.

So the guarantee is: "built by this workflow run from these pinned inputs", checked by SHA-256 against that run's
artifact and job summary. It is not "anyone gets the same bytes". OpenSSL reads `SOURCE_DATE_EPOCH` for the build
time (not verified for this version); setting it would be a difference from upstream's steps and needs a review
decision before it is added.
