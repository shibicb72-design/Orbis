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
| `versions.env` | What the app bundles today, and the workflow run it came from (read by the daily Engine currency check and by Android CI). |
| `CHECKSUMS.sha256` | SHA-256 of every file under `app/src/main/jniLibs/`, with the run id and commit in the header. The Gradle task `verifyTorrentNatives` checks it before every build: a changed, missing or extra file fails it. |
| `BUILDINFO-<abi>.json` | The shipped run's `BUILDINFO.json` for each ABI, unmodified. |

The workflow is `.github/workflows/torrent-natives.yml`. It builds arm64-v8a, armeabi-v7a and x86_64, each twice,
and for every build checks the version strings (`scripts/check_torrent_natives.py`), compares the library with
upstream's released one (`scripts/compare_torrent_natives.py`: same JNI symbols, same ELF class and machine, same
`DT_NEEDED`) and checks 16 KB alignment for the 64-bit libraries. Artifacts `libtorrent4j-<abi>-<repeat>` hold the
`.so`, its `.sha256` and `BUILDINFO.json`; the run summary has all hashes in one table.

## Rule

Shipped torrent natives come only from a `workflow_dispatch` run of this workflow on `main` in which every job
is green. Before using an artifact, check its `BUILDINFO.json`: `workflow.event` is `workflow_dispatch`,
`workflow.ref` is `refs/heads/main`, and `workflow.commit` is a commit on `main`. Never use artifacts from a
`pull_request` run: a pull request from a fork runs its own `build.sh` and can upload artifacts with the same
names. Never commit a library built anywhere else (not on a laptop, not from a Docker image), and never edit a
committed library.

## Update OpenSSL

1. In `build.env`, change `OPENSSL_VERSION`, `OPENSSL_URL` and `OPENSSL_SHA256`. Take the SHA-256 from the
   `.sha256` file OpenSSL publishes next to the tarball (GitHub release assets, or `https://www.openssl.org/source/`).
   Set `OPENSSL_SOURCE_DATE_EPOCH` to 00:00:00 UTC of that release's date (`date -u -d YYYY-MM-DD +%s`).
2. Open a PR. The workflow runs on it (`pull_request`, paths under `native/libtorrent4j/`). This run is a test only.
3. Every job must be green: the version strings show the new OpenSSL, the symbol sets equal upstream's, and the
   two builds of each ABI are byte-identical.
4. Merge the PR. Then run the workflow on `main` (Actions > Torrent natives > Run workflow, branch `main`). Ship
   only that run's repeat-1 artifacts, after checking the run is green and `BUILDINFO.json` says
   `workflow_dispatch`, `refs/heads/main` and a commit on `main` (see "Rule"). Shipping is a separate PR:
   - `gh run download <run-id>` into a new, empty directory; check each `.so` against its `.sha256`, the run's
     summary table and the other repeat.
   - Copy each repeat-1 `libtorrent4j.so` to `app/src/main/jniLibs/<abi>/` and its `BUILDINFO.json` to
     `BUILDINFO-<abi>.json` here, unmodified.
   - Rewrite `CHECKSUMS.sha256` (hashes and header: run id, commit) and `versions.env` (`OPENSSL_VERSION`,
     `RUN_ID`), and the OpenSSL version in `app/.../about/Licences.kt` (`LicencesTest` checks it).
   - Update `docs/security/NATIVE-VERSIONS.md`. Android CI then checks the version strings in every APK and the
     bundle.

Only OpenSSL changes in such an update. The libtorrent4j commit, the libtorrent commit, Boost and the NDK stay
upstream's unless a separate, reviewed change moves them.
