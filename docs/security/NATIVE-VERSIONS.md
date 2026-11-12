# Native library versions (torrent engine)

The machine-readable record is `native/libtorrent4j/versions.env`. `scripts/check_engine_currency.py` reads it, and
the daily Engine currency workflow compares it with the newest upstream releases. Change both files together.

## What is bundled today (security PR 7)

| Component | Version | Source |
|---|---|---|
| libtorrent4j Java API | 2.1.0-39 | Maven Central, `org.libtorrent4j:libtorrent4j` (upstream build) |
| libtorrent4j native library (`libtorrent4j.so`) | built from libtorrent4j `da7d240` (tag `v2.1.0-39`) | ours: `app/src/main/jniLibs/<abi>/`, Torrent natives run 43070920772 on `main` |
| libtorrent | 2.1.0.0 (a 2.1.0-line snapshot, commit `24a3adf`, 2026-01-25) | inside `libtorrent4j.so` |
| OpenSSL | 3.5.9 (29 Sep 2026) | statically linked into `libtorrent4j.so` |

Upstream's native jars (`libtorrent4j-android-arm64`, `-arm`, `-x86_64`) are no longer dependencies. The Java jar
loads the library by name (`System.loadLibrary("torrent4j")`), so `jniLibs/<abi>/libtorrent4j.so` takes their place
with no code change; the JNI symbol sets are identical to upstream's (see "Result" below).

The shipped files below still have OpenSSL built with `no-threads`, as upstream's do. Since the threads change
(difference g, PR #46) the pipeline builds OpenSSL with thread support; those libraries ship only from a
`workflow_dispatch` run on `main`, in a separate PR that also updates this table.

### Shipped files

From Torrent natives run [43070920772](https://github.com/shibicb72-design/Orbis/actions/runs/43070920772)
(`workflow_dispatch`, `refs/heads/main`, commit `629c992f834d20aa02af7f2d43b2ef65c272a08d`, runner image ubuntu24
20261101.360.1, every job green), repeat-1 artifacts, unmodified:

| ABI | File | SHA-256 | size |
|---|---|---|---|
| arm64-v8a | `app/src/main/jniLibs/arm64-v8a/libtorrent4j.so` | `ba9096e45f9da3f9c75cd38f8d7f501b5788ddb23f64caef56c7b9ec480e0801` | 15900928 |
| armeabi-v7a | `app/src/main/jniLibs/armeabi-v7a/libtorrent4j.so` | `f0578398073c79a7ec25182b9f2eefac395cb26425ed33eacf534210924becb2` | 13751940 |
| x86_64 | `app/src/main/jniLibs/x86_64/libtorrent4j.so` | `9634a939ee35b4dd66850645dbbc6ff9bafdc56877a547010f00e5ea1a019bf1` | 16374728 |

Before they were committed: each `BUILDINFO.json` said `workflow_dispatch`, `refs/heads/main`, that commit and that
run id; each `.so` matched its `.sha256`, the run's summary table and the repeat-2 build; and
`scripts/check_torrent_natives.py` (`OpenSSL 3.5.9 29 Sep 2026`, `libtorrent/2.1.0.0`) and
`scripts/compare_torrent_natives.py` (against upstream's jars, hash-checked) passed on each. The hashes are the same
as those of PR run 43064341148 at `8dae273` on the same runner image, so the build reproduced across the two runs.

Where it is checked:
- `native/libtorrent4j/CHECKSUMS.sha256` lists the three files. The Gradle task `verifyTorrentNatives`
  (`app/build.gradle.kts`, before `preBuild`) hashes every file under `app/src/main/jniLibs/` and fails the build on
  a mismatch, a missing file or an extra file. That covers local release builds too. A `jniLibs` directory in any
  source set other than `main` (`app/src/debug/jniLibs`, ...) fails it as well.
- Android CI runs `scripts/check_torrent_natives.py --min-openssl <OPENSSL_VERSION from versions.env> --libtorrent
  2.1.0.0 --checksums native/libtorrent4j/CHECKSUMS.sha256` on the arm64-v8a and x86_64 debug and release APKs and,
  after a separate `bundleRelease`, on the bundle with `--abis arm64-v8a,armeabi-v7a,x86_64`. A missing library
  fails it, and so does a packaged `lib/<abi>/libtorrent4j.so` that is not byte for byte the file
  `CHECKSUMS.sha256` lists for its ABI. The release checklist runs the same command before an upload.
- Review rule: a diff touching `app/src/main/jniLibs/**` or `CHECKSUMS.sha256` gets the run-provenance check in
  `native/libtorrent4j/README.md` ("Review rule") before approval.
- `native/libtorrent4j/BUILDINFO-<abi>.json` are the run's `BUILDINFO.json` files.

The 32-bit arm library cannot be run on the emulators at hand. It is covered by its version strings, the JNI and
symbol comparison with upstream and the build itself, not by a device test.

### Before PR 7 (upstream's natives)

Measured 2026-11-11: the `libtorrent4j-android-arm64-2.1.0-39.jar` and `libtorrent4j-android-x86_64-2.1.0-39.jar`
files from the Gradle cache were unpacked, and `lib/arm64-v8a/libtorrent4j.so` and `lib/x86_64/libtorrent4j.so`
were byte-scanned (`python -I`, a regex over the file). Both contain the strings `OpenSSL 3.5.5 27 Jan 2026` and
`libtorrent/2.1.0.0`. Upstream's `swig/android-build/Dockerfile` sets `OPENSSL_VERSION="3.5.5"`, and the libtorrent
submodule's `version.hpp` defines `LIBTORRENT_VERSION "2.1.0.0"`, which matches.

## Upstream state

- libtorrent4j 2.1.0-39 (2026-02-07) is the newest release on Maven Central and GitHub. Upgrading is not possible.
- The newest OpenSSL 3.5.x tag is `openssl-3.5.9` (2026-09-29). Advisories were fixed in 3.5.6 to 3.5.9; upstream's
  natives (3.5.5) are behind, ours (3.5.9) are current.
- libtorrent itself has newer tags (`v2.1.1`, `v2.1.2`). Moving to them was PR 7b (OD-4 approved). It is stopped:
  the libtorrent4j 2.1.0-39 bindings do not fit libtorrent 2.1.2 without a libtorrent4j fork. See "libtorrent
  2.1.2 (PR 7b): stopped" below. The build pipeline changes OpenSSL only.

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
- Since #35, Orbis filters tracker URLs by scheme before the engine sees them: `TorrentTrackers.isAllowed`
  (`app/src/main/java/com/shibi/orbis/torrent/TorrentTrackers.kt`) lets in only `http://`, `https://` and `udp://`,
  judged by the text before the first ':' as libtorrent does, so no `ws:`/`wss:` spelling gets in. It is applied to
  a magnet's `tr` keys, to a `.torrent` file's announce list and to the trackers in resume data
  (`Libtorrent4jEngine.kt`, `dropRefusedTrackers`). Before #35 there was no such filter.
- So on `main` a torrent or magnet link can no longer name a WebSocket tracker, and the WebTorrent path (the
  libdatachannel DTLS transport on this OpenSSL) is not reached through trackers. The code is still compiled in.
  Whether any other route reaches it is not verified, and it was not exercised on a device. Whether the conditions of
  CVE-2026-84782 would be met on that path is not verified. Whether `PKCS7_verify()` (CVE-2026-45447) is reachable
  is not checked.

This update is upkeep of a network-facing library. One of the two High advisories is in code that is compiled in;
since #35 torrents cannot route traffic to it through a WebSocket tracker. Neither is shown to be exploitable in
Orbis.

## Plan

1. Security PR 5 (this record): the Engine currency workflow reports OpenSSL as a warning (`--warn-only openssl`)
   so that the GeckoView and libtorrent4j checks can be red or green on their own.
2. Security PR 6 (#28): build the libtorrent4j natives ourselves with OpenSSL 3.5.9 (pipeline only, no app
   change). See "How the natives are built" below.
3. Security PR 7 (done): ship those natives (run 43070920772), update `versions.env` (`OPENSSL_VERSION=3.5.9`,
   `SOURCE=orbis-ci`, `RUN_ID`) and this file, and drop `--warn-only openssl` from the Engine currency workflow, so
   an OpenSSL release turns it red.
4. Security PR 7b: libtorrent 2.1.2 (OD-4). Stopped before any build (below): it needs a libtorrent4j fork, a new
   owner decision. Its other half, the #37 follow-ups (checksum check on the packaged libraries, no `jniLibs`
   outside `src/main`, the review rule), shipped on its own.

### libtorrent 2.1.2 (PR 7b): stopped

Checked 2026-11-11 by reading the sources (no build was run): libtorrent `24a3adf` (what ships) against tag `v2.1.2`
(`6da363d2994f17c0b3c0450d124cf73a31a73847`, 727 commits later), and libtorrent4j `da7d240` (`v2.1.0-39`, still
upstream's `master`).

- The committed JNI source does not compile unedited. `swig/libtorrent_jni.cpp:3552` (from `%extend renamed_files`
  in `swig/libtorrent/file_storage.i:64-66`) calls `renamed_files::export_filenames()` with no argument. In
  `v2.1.2` that function is `export_filenames(file_storage const& fs) const` (`include/libtorrent/file_storage.hpp`),
  with no default. Fixing it means editing the `.i` file and regenerating the bindings, and the Java method
  `export_filenames_ex()` would change too: a libtorrent4j fork.
- The Java jar reads some values from the library and computes the rest. In the generated `settings_pack.java` a
  name that has an initializer in the header is read through JNI (`libtorrent_jni.settings_pack_user_agent_get()`);
  every other name is the previous value plus one, computed in Java. `v2.1.2` inserts `natpmp_gateway` before `webtorrent_stun_server`
  and `natpmp_lease_duration` before `min_websocket_announce_interval`, so in the old jar those two names, and
  `webtorrent_connection_timeout` and the three `max_*_setting_internal` names, would address the wrong native
  setting. `allow_multiple_connections_per_pid`, `apply_filter_to_dht` and `max_webtorrent_offers` are appended.
  None of the names `Libtorrent4jEngine.kt` sets moves (`enable_upnp`, `enable_natpmp`, `ssrf_mitigation`,
  `enable_dht`, `enable_lsd`, `listen_interfaces`, `max_metadata_size`, rate limits, `active_downloads`,
  `active_seeds`, `stop_tracker_timeout`, and `alert_mask` and `dht_bootstrap_nodes` set by `SessionManager`), but
  the jar's own `SettingsPack.getWebtorrentStunServer()` and `setWebtorrentStunServer()` would.
- Alert types: no existing number moves; `v2.1.2` adds `file_priorities_alert` (105), `file_status_alert` (106)
  and `ip_ban_alert` (107), and `num_alert_types` goes from 105 to 108. The jar sizes its tables from the native
  `num_alert_types` but fills entries 0 to 104 only, so `AlertType.fromSwig(107)` returns null and
  `SessionManager`'s alert loop switches on it (a `NullPointerException` on the `SessionManager-alertsLoop`
  thread). `ip_ban_alert` is in the `ip_block` category, which `SessionManager` always enables, and `v2.1.2` posts
  it when an incoming peer names an info-hash the DHT made up (`peer_connection.cpp:1333`,
  `session_impl::ban_ip`). The exception is not caught: the loop runs on a plain `Thread`, and Orbis sets no default
  uncaught-exception handler, so Android's default handler would kill the app process. With DHT on, a remote peer
  could then crash Orbis. Not run; read from the code.
- Torrent flags: no value moves (`disable_v1_hashes = 25_bit` is added). The WebSocket tracker path is still chosen
  by the text before the first ':' (`tracker_manager.cpp:290`, `ws`/`wss` at `:326`), and the magnet `tr` key rules
  are unchanged (`magnet_uri.cpp:305-307`, `:314`), so the tracker allowlist from #30/#35 still holds. The
  `deps/json` submodule is gone and the Jamfile no longer links Boost.JSON; not built.

What staying on `24a3adf` leaves unfixed. `24a3adf..v2.1.2` contains these memory-safety fixes, by commit. Each
one is **not assessed for reachability in Orbis**:

- `ca4e621c2`: out-of-bounds write in `hash_picker::hashes_rejected` on block hash requests.
- `507c60005`: use-after-free in the uTP failure socket shutdown path.
- `634105823` (bounds check in `merkle_tree`) and related merkle tree and proof fixes: `58f793b4c` (stricter check
  in `merkle_validate_and_insert_proofs()`), `aa07b2349` (`merkle_tree::add_hashes()`), `114f65efc`
  (`merkle_tree::get_hashes()` edge case).
- `7c0b819ae` (loading a hybrid torrent with no pieces) and `56382ae79` (bdecode on a truncated list or dict):
  out-of-bounds reads.
- `578c9c987`: stack buffer overflow in the DHT `canonical_string` on a large salt. In the shipped snapshot an
  incoming DHT put already rejects a salt over 64 bytes (`src/kademlia/node.cpp:1000` at `24a3adf`).
- `4ec307a31` (signed overflow computing bitfield sizes in `read_resume_data`), `6e37fc954` (socks5 auth
  credential bounds), `ad48c19b3` and `8184fdbc3` (bound WebSocket RTC offers; offer IDs exactly 20 bytes).

No published GitHub security advisory or CVE was found for any of them (checked 2026-11-11: the repository
security advisories of `arvidn/libtorrent` list none, and the GitHub advisory database has no libtorrent entry for
them).

What would unblock it: an upstream libtorrent4j release built on libtorrent 2.1.2 (Java jar and bindings
together), or an owner decision to maintain a fork.

When the workflow reports a newer 3.5.x: the torrent natives are built by `.github/workflows/torrent-natives.yml`;
to update OpenSSL change `build.env`, run the workflow, commit the artifacts and `CHECKSUMS.sha256`
(`native/libtorrent4j/README.md`, "Update OpenSSL"), update `versions.env` and this file, and release.

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
| `env` `BOOST_DOT_VERSION 1.90.0`, `NDK_VERSION r29`, `OPENSSL_VERSION 3.5.5`, `OPENSSL_NO_OPTS` | `build.env`: same Boost and NDK; `OPENSSL_VERSION=3.5.9`; `OPENSSL_NO_OPTS` without `no-threads` | (a) OpenSSL 3.5.5 -> 3.5.9; (g) OpenSSL with thread support |
| setup boost: `wget` from archives.boost.io, `tar xzf`, `mv`, `./bootstrap.sh` | `curl` (HTTPS only) from the same URL, SHA-256 check, then the same commands | (b) checksum check; `curl --proto =https` instead of `wget -nv` |
| setup ndk: `wget` from dl.google.com, `unzip -qq`, `mv` | same URL, size + SHA-1 + SHA-256 check, same commands | (b) |
| setup openssl: `wget https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz`; `CC`/`AR`/`LD`/`RANLIB` from the NDK; `./Configure <target> ${OPENSSL_NO_OPTS} <abi flags> -fPIC --prefix=...`; `make`; `make install_sw`; x86_64: `mv openssl/lib64 openssl/lib` | same environment plus `SOURCE_DATE_EPOCH=${OPENSSL_SOURCE_DATE_EPOCH}`, same `Configure` line per ABI (without `no-threads`), same `make` calls, then a check that `configuration.h` defines `OPENSSL_THREADS`, same `lib64` move; tarball from the GitHub release | (a), (b), (f), (g); the tarball URL is OpenSSL's GitHub release (byte-identical to the www.openssl.org file, checked 2026-11-11) |
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

(g) `no-threads` is removed from `OPENSSL_NO_OPTS`; every other option is upstream's. The same change is proposed
upstream in aldenml/libtorrent4j#315 (open, not reviewed as of 2026-11-12). Sources, read 2026-11-12:
- Why. libtorrent `24a3adf` calls OpenSSL from several threads at once. `hasher` and `hasher256`
  (`src/hasher.cpp`: `EVP_MD_CTX_new`, `EVP_DigestInit_ex(..., EVP_sha1() / EVP_sha256())`, `EVP_MD_CTX_free`) run
  on the disk threads (`src/mmap_disk_io.cpp`, `do_hash`; pools sized by `aio_threads`, default 10, and
  `hashing_threads`, default 1, `src/settings_pack.cpp`), on the network thread, and on any thread that parses a
  torrent (`src/torrent_info.cpp:1027-1028`; Orbis calls `TorrentInfo.bdecode` from `Libtorrent4jEngine.kt`).
  `aux::crypto_random_bytes()` calls `RAND_bytes()` (`src/random.cpp`), for example for the DHT secrets
  (`src/kademlia/node.cpp:116`). libtorrent4j builds with `<webtorrent>on` (`swig/Jamfile`), so libdatachannel's
  OpenSSL DTLS code is linked in and runs on its own threads. OpenSSL 3.5.9 `INSTALL.md`, "Notes on
  multi-threading": where `Configure` knows the options, threads are on by default and `no-threads` "should never
  be necessary". With `no-threads`, `crypto/threads_none.c` is built: no-op locks, one global slot for
  "thread-local" data, and `include/internal/refcount.h` falls back to plain `++`/`--` reference counts.
- Nothing else is needed. `build.sh` configures with `linux-armv4`, `linux-aarch64` and `linux-x86_64` (upstream's
  targets, not the `android-*` ones). In OpenSSL 3.5.9 `Configurations/10-main.conf` all three inherit
  `linux-generic32`: `thread_scheme => "pthreads"`, `cflags => threads("-pthread")`, `ex_libs => add("-ldl",
  threads("-pthread"))`; `linux-armv4` inherits `linux-latomic`, which adds `threads("-latomic")` to `ex_libs`.
  `android-arm`, `android-arm64` and `android-x86_64` (`Configurations/15-android.conf`) inherit the same
  `linux-generic32`; only `android-x86`, not built here, adds `-latomic`. So `Configure` turns threads on by
  itself (`$auto_threads`, `Configure` lines 1570-1591) and writes `OPENSSL_THREADS` into `configuration.h`
  (line 1617). `INSTALL.md` asks for `threads` plus a system option such as `-D_REENTRANT` only on systems where
  `Configure` does not know them; this is not one. Clang defines `_REENTRANT` itself under `-pthread` on Linux and
  Android targets (`clang/lib/Basic/Targets/OSTargets.h`, `LinuxTargetInfo::getOSDefines`). The atomics in
  `crypto/threads_pthread.c` and `refcount.h` are the compiler's `__atomic` builtins. The `-latomic` and
  `-pthread` in `ex_libs` apply to OpenSSL's own programs, not to the `b2` link of `libtorrent4j.so`; the built
  libraries import no `__atomic_*` function and their `DT_NEEDED` is unchanged (run 43140935475).
- `thread-pool` and `default-thread-pool` follow `threads` and are now on (OpenSSL's default). OpenSSL starts no
  thread of its own unless the application calls `OSSL_set_max_threads()` or uses QUIC's thread-assisted mode;
  `git grep` finds neither in libtorrent `24a3adf`, its libdatachannel, libjuice and usrsctp submodules, or
  libtorrent4j's `swig/` and `src/` at `da7d240`. The only effect on the library's interface is the import change
  below.
- `build.sh` stops if `configuration.h` does not define `OPENSSL_THREADS` and prints the line to the log.

Upstream hides the OpenSSL `make` output (`&> /dev/null`); ours writes it to a log file and prints its tail on a
failure. That affects logging only. Compiler flags (except the `-pthread` that `Configure` adds to OpenSSL's
for (g)), `b2` arguments, NDK, Boost and API level (28) are upstream's;
the user-config files were read and do not mention OpenSSL (the build checks that again), so the upstream tree is
built untouched.

Not proven: that upstream's Maven jars were produced by this `android.yml` (likely, not shown).

### Checks on every build

- `scripts/check_torrent_natives.py --min-openssl 3.5.9 --libtorrent 2.1.0.0 --abis <abi>`: exactly one OpenSSL
  version string, not below the minimum, and `libtorrent/2.1.0.0`.
- `scripts/compare_torrent_natives.py --abi <abi>`: downloads upstream's native jar for the ABI from Maven Central,
  checks the jar SHA-256 and the inner `.so` SHA-256 against `build.env`, then requires the same exported
  `Java_org_libtorrent4j_swig_*` set, the same `JNI_OnLoad` presence, the same full exported dynamic symbol set
  (name, binding, type, visibility), and the same ELF class, machine and `DT_NEEDED` list. The imported symbol
  set must be upstream's with exactly the OpenSSL threads change applied (`THREADS_IMPORT_CHANGE` in the script,
  difference g): `gmtime_r`, `pthread_attr_destroy`, `pthread_attr_init`, `pthread_attr_setdetachstate`,
  `pthread_equal` and `pthread_exit` added, `gmtime` removed. Any other difference fails, and so does a library
  without that change (a `no-threads` OpenSSL). The size difference is reported only.
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

### Result (PR #28, run 43064341148 at `8dae273`, runner image ubuntu24 20261101.360.1)

| ABI | SHA-256 (repeat 1 = repeat 2) | size | upstream size | JNI / exported / imported symbols, ours = upstream |
|---|---|---|---|---|
| arm64-v8a | `ba9096e45f9da3f9c75cd38f8d7f501b5788ddb23f64caef56c7b9ec480e0801` | 15900928 | 15883152 (+0.11%) | 3659 / 7303 / 275 |
| armeabi-v7a | `f0578398073c79a7ec25182b9f2eefac395cb26425ed33eacf534210924becb2` | 13751940 | 13734884 (+0.12%) | 3659 / 7248 / 265 |
| x86_64 | `9634a939ee35b4dd66850645dbbc6ff9bafdc56877a547010f00e5ea1a019bf1` | 16374728 | 16358808 (+0.10%) | 3659 / 7328 / 272 |

Every library contains exactly one OpenSSL string, `OpenSSL 3.5.9 29 Sep 2026`, `built on: Tue Sep 29 00:00:00
2026 UTC` and `libtorrent/2.1.0.0`. The JNI symbol sets, the full exported and imported symbol sets,
`JNI_OnLoad` (absent in both), ELF class and machine, and `DT_NEEDED` (`libdl.so`, `libm.so`, `libc.so`) equal
upstream's for all three ABIs. The two 64-bit libraries pass the strict 16 KB check. These are the artifacts of a
`pull_request` run and must not be shipped; PR 7 ships those of a `workflow_dispatch` run on `main`.

Where upstream's binaries came from: the released `.so` files embed source paths under
`/home/runner/work/libtorrent4j/libtorrent4j/` (`boost/`, `android-ndk/`, `openssl-…`, `swig/`) and OpenSSL
`built on: Sat Feb  7 22:37:2x 2026 UTC`. That is the directory layout of upstream's `android.yml` on a GitHub
runner (not the Dockerfile route), on the day of the 2.1.0-39 release. This supports, but does not prove, that the
Maven jars were built by that workflow.

### Result with thread support (PR #46, run 43140935475 at `86f7f31`, `pull_request`, not shipped)

| ABI | SHA-256 (repeat 1 = repeat 2) | size | upstream size | JNI / exported / imported symbols, ours (upstream) |
|---|---|---|---|---|
| arm64-v8a | `f89748e987ad237f85dfeb41d1fc1fa46e65761240abccf2e15603ace08f5d45` | 15917336 | 15883152 (+0.22%) | 3659 / 7303 / 280 (3659 / 7303 / 275) |
| armeabi-v7a | `ac79d372b9ab219d8bf87767d48272178b117b106a103282dd566a9b159e6313` | 13766304 | 13734884 (+0.23%) | 3659 / 7248 / 270 (3659 / 7248 / 265) |
| x86_64 | `dbaf0fd5da46202fd90c0c79416961cca896aafe00a1a1e86be5f62c97eb47f9` | 16385928 | 16358808 (+0.17%) | 3659 / 7328 / 277 (3659 / 7328 / 272) |

All six builds succeeded and each ABI's two repeats are byte-identical. The OpenSSL string is unchanged
(`OpenSSL 3.5.9 29 Sep 2026`), and so are `libtorrent/2.1.0.0`, the JNI and exported symbol sets and `DT_NEEDED`.
The imports differ from upstream's by the same seven names on all three ABIs: `gmtime_r`, `pthread_attr_destroy`,
`pthread_attr_init`, `pthread_attr_setdetachstate`, `pthread_equal`, `pthread_exit` added, `gmtime` removed. In
that run the comparison still required identical imports and failed on this; the rule above was added with the
sources for each name, and later runs must pass it. Evidence that thread support is on: each build log prints
`#  define OPENSSL_THREADS` from `configuration.h`; the embedded `compiler:` string (`OpenSSL_version(OPENSSL_CFLAGS)`)
contains `-pthread`, upstream's does not; `gmtime_r` and `pthread_equal` are referenced only by code built when
`OPENSSL_THREADS` is defined (`crypto/o_time.c`, `crypto/threads_pthread.c`).

### What "reproducible" means here

The inputs are pinned and hash-checked and the runner label is fixed, so anyone can re-run the workflow from the
same commit. The runner image is not pinned by digest (GitHub updates `ubuntu-24.04` in place); `BUILDINFO.json`
records its version.

Before `SOURCE_DATE_EPOCH` was set (runs 43052679812 and 43054692624), the two builds of each ABI had the same
size and section layout but different bytes. The varying input was OpenSSL's embedded build time
(`built on: ... 15:55:00` vs `15:54:55`): the string moved merged constants, and the references to them changed.
With the epoch fixed (difference f), the two builds of every ABI, on separate runners, are byte-identical (run
43064341148), and the workflow now fails if they are not (`buildinfo.py summary --require-identical`).

So the guarantee is: the same commit gives the same bytes on the same runner image, and every shipped file is
checked by SHA-256 against the artifact and job summary of the `workflow_dispatch` run on `main` that built it.
Whether a newer runner image gives the same bytes is not tested. Our libraries embed different source paths from
upstream's (`/home/runner/work/_temp/lt4j/src/…`), which does not change behaviour.
