# Torrent natives (libtorrent4j.so)

Orbis's torrent engine is libtorrent4j: a Java API (`org.libtorrent4j:libtorrent4j`, from Maven Central) and a
native library, `libtorrent4j.so`, that contains libtorrent, Boost and a statically linked OpenSSL. The Java side
loads it by name (`System.loadLibrary("torrent4j")`), so a `lib/<abi>/libtorrent4j.so` is found the same way
whether it comes from upstream's native jar or from `app/src/main/jniLibs/<abi>/`.

This directory builds that library ourselves, so the bundled OpenSSL can be kept current without waiting for an
upstream release.

| File | What it is |
|---|---|
| `build.env` | Every input, pinned: libtorrent4j commit, libtorrent submodule commit, OpenSSL, Boost, NDK (URL and published checksum each), runner label, upstream baseline hashes. |
| `build.sh` | Builds one ABI: fetches the pinned commit, asserts the submodule commit, downloads and hash-checks each archive, then runs the steps of upstream's `android.yml` job for that ABI. |
| `buildinfo.py` | Writes `BUILDINFO.json` per build and the hash table of a run. |
| `versions.env` | What the app bundles today (read by the daily Engine currency check). |

The workflow is `.github/workflows/torrent-natives.yml`. It builds arm64-v8a, armeabi-v7a and x86_64, each twice,
and for every build checks the version strings (`scripts/check_torrent_natives.py`), compares the library with
upstream's released one (`scripts/compare_torrent_natives.py`: same JNI symbols, same ELF class and machine, same
`DT_NEEDED`) and checks 16 KB alignment for the 64-bit libraries. Artifacts `libtorrent4j-<abi>-<repeat>` hold the
`.so`, its `.sha256` and `BUILDINFO.json`; the run summary has all hashes in one table.

## Rule

Shipped torrent natives only ever come from a run of this workflow. Never commit a library built anywhere else
(not on a laptop, not from a Docker image), and never edit a committed library.

## Update OpenSSL

1. In `build.env`, change `OPENSSL_VERSION`, `OPENSSL_URL` and `OPENSSL_SHA256`. Take the SHA-256 from the
   `.sha256` file OpenSSL publishes next to the tarball (GitHub release assets, or `https://www.openssl.org/source/`).
2. Open a PR. The workflow runs on it (`pull_request`, paths under `native/libtorrent4j/`), or run it by hand
   (Actions > Torrent natives > Run workflow).
3. Every job must be green: the version strings show the new OpenSSL, the JNI symbol sets equal upstream's.
4. Shipping the result is a separate step (copy the repeat-1 artifacts into `app/src/main/jniLibs/`, record their
   hashes); see `docs/security/NATIVE-VERSIONS.md`.

Only OpenSSL changes in such an update. The libtorrent4j commit, the libtorrent commit, Boost and the NDK stay
upstream's unless a separate, reviewed change moves them.
