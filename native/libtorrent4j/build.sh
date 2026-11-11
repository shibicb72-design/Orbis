#!/usr/bin/env bash
# Build libtorrent4j.so for one Android ABI from the pinned inputs in build.env.
#
# Usage: build.sh <arm64-v8a|armeabi-v7a|x86_64> <work-dir>
# Output: <work-dir>/out/libtorrent4j.so (stripped, as upstream ships it), plus
#         <work-dir>/out/submodules.txt and <work-dir>/out/readelf-d.txt.
#
# The build steps reproduce the *-b2 jobs of upstream's .github/workflows/android.yml at the pinned commit.
# Each step below names the upstream step it reproduces. The differences are listed in
# docs/security/NATIVE-VERSIONS.md ("Side by side with upstream"); add none without updating that list.
# Linux x86_64 only (the NDK host toolchain is linux-x86_64). Runs on CI (ubuntu-24.04), not on Windows.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${HERE}/build.env"

die() { echo "build.sh: $*" >&2; exit 1; }

[ "$#" -eq 2 ] || die "usage: build.sh <abi> <work-dir>"
ABI="$1"
WORK="$2"

# build.env is KEY=VALUE without quotes. Some keys contain '-', so it is read, not sourced.
env_get() {
  local value
  value="$(sed -n "s/^$1=//p" "${ENV_FILE}")"
  [ -n "${value}" ] || die "build.env has no $1"
  printf '%s' "${value}"
}

LIBTORRENT4J_REPO="$(env_get LIBTORRENT4J_REPO)"
LIBTORRENT4J_COMMIT="$(env_get LIBTORRENT4J_COMMIT)"
LIBTORRENT_COMMIT="$(env_get LIBTORRENT_COMMIT)"
LIBTORRENT_VERSION="$(env_get LIBTORRENT_VERSION)"
OPENSSL_VERSION="$(env_get OPENSSL_VERSION)"
OPENSSL_URL="$(env_get OPENSSL_URL)"
OPENSSL_SHA256="$(env_get OPENSSL_SHA256)"
OPENSSL_NO_OPTS="$(env_get OPENSSL_NO_OPTS)"
BOOST_VERSION="$(env_get BOOST_VERSION)"
BOOST_URL="$(env_get BOOST_URL)"
BOOST_SHA256="$(env_get BOOST_SHA256)"
NDK_VERSION="$(env_get NDK_VERSION)"
NDK_URL="$(env_get NDK_URL)"
NDK_SHA1="$(env_get NDK_SHA1)"
NDK_SHA256="$(env_get NDK_SHA256)"
NDK_SIZE="$(env_get NDK_SIZE)"
BOOST_UNDERSCORE="${BOOST_VERSION//./_}"

# Per-ABI values, copied from upstream android.yml (jobs arm-b2, arm64-b2, x86_64-b2). No x86 job.
case "${ABI}" in
  armeabi-v7a)
    CLANG_PREFIX=armv7a-linux-androideabi28
    OPENSSL_TARGET=linux-armv4
    OPENSSL_ABI_OPTS="-march=armv7-a -mfpu=neon"
    USER_CONFIG=config/android-arm-config.jam
    TOOLSET=clang-linux-arm
    STRIP_OPTS="--strip-unneeded -x -g"
    OPENSSL_LIB64_MOVE=no
    ;;
  arm64-v8a)
    CLANG_PREFIX=aarch64-linux-android28
    OPENSSL_TARGET=linux-aarch64
    OPENSSL_ABI_OPTS="-march=armv8-a+crypto"
    USER_CONFIG=config/android-arm64-config.jam
    TOOLSET=clang-arm64
    STRIP_OPTS="--strip-unneeded -x"
    OPENSSL_LIB64_MOVE=no
    ;;
  x86_64)
    CLANG_PREFIX=x86_64-linux-android28
    OPENSSL_TARGET=linux-x86_64
    OPENSSL_ABI_OPTS=""
    USER_CONFIG=config/android-x86_64-config.jam
    TOOLSET=clang-x86_64
    STRIP_OPTS="--strip-unneeded -x"
    OPENSSL_LIB64_MOVE=yes
    ;;
  *) die "unsupported ABI '${ABI}' (arm64-v8a, armeabi-v7a, x86_64)" ;;
esac

mkdir -p "${WORK}"
WORK="$(cd "${WORK}" && pwd)"
SRC="${WORK}/src"
DL="${WORK}/dl"
LOGS="${WORK}/logs"
OUT="${WORK}/out"
[ ! -e "${SRC}" ] || die "${SRC} already exists; use an empty work directory"
mkdir -p "${DL}" "${LOGS}" "${OUT}"

# --- Source: the pinned commit, not a branch head (upstream: actions/checkout with submodules: recursive) ---
echo "== source ${LIBTORRENT4J_REPO} @ ${LIBTORRENT4J_COMMIT}"
git init -q "${SRC}"
git -C "${SRC}" remote add origin "${LIBTORRENT4J_REPO}"
git -C "${SRC}" -c protocol.version=2 fetch -q --depth 1 origin "${LIBTORRENT4J_COMMIT}"
git -C "${SRC}" -c advice.detachedHead=false checkout -q FETCH_HEAD
git -C "${SRC}" submodule update -q --init --recursive

got="$(git -C "${SRC}" rev-parse HEAD)"
[ "${got}" = "${LIBTORRENT4J_COMMIT}" ] || die "libtorrent4j HEAD is ${got}, expected ${LIBTORRENT4J_COMMIT}"
got="$(git -C "${SRC}" ls-tree HEAD swig/deps/libtorrent | awk '{print $3}')"
[ "${got}" = "${LIBTORRENT_COMMIT}" ] || die "swig/deps/libtorrent is pinned to ${got}, expected ${LIBTORRENT_COMMIT}"
got="$(git -C "${SRC}/swig/deps/libtorrent" rev-parse HEAD)"
[ "${got}" = "${LIBTORRENT_COMMIT}" ] || die "swig/deps/libtorrent checkout is ${got}, expected ${LIBTORRENT_COMMIT}"
grep -q "^#define LIBTORRENT_VERSION \"${LIBTORRENT_VERSION}\"" \
  "${SRC}/swig/deps/libtorrent/include/libtorrent/version.hpp" \
  || die "libtorrent version.hpp does not say ${LIBTORRENT_VERSION}"
git -C "${SRC}" submodule status --recursive > "${OUT}/submodules.txt"
cat "${OUT}/submodules.txt"

# The OpenSSL version must reach the build only through OPENSSL_ROOT. Stop if the user-config hard-codes it.
if grep -qi openssl "${SRC}/swig/${USER_CONFIG}"; then
  die "${USER_CONFIG} mentions OpenSSL; the upstream tree would need a patch. Stop and report."
fi

# --- Downloads: HTTPS only, each checked against build.env before it is unpacked ---
download() {  # url file
  curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL --retry 3 --retry-delay 5 -o "$2" "$1"
}
check_sha256() {  # file expected
  local got
  got="$(sha256sum "$1" | awk '{print $1}')"
  [ "${got}" = "$2" ] || die "$(basename "$1"): SHA-256 ${got}, expected $2"
  echo "   SHA-256 OK $(basename "$1") $2"
}

echo "== downloads"
download "${BOOST_URL}" "${DL}/boost.tar.gz"
check_sha256 "${DL}/boost.tar.gz" "${BOOST_SHA256}"

download "${NDK_URL}" "${DL}/android-ndk.zip"
got="$(stat -c %s "${DL}/android-ndk.zip")"
[ "${got}" = "${NDK_SIZE}" ] || die "android-ndk.zip: size ${got}, expected ${NDK_SIZE}"
got="$(sha1sum "${DL}/android-ndk.zip" | awk '{print $1}')"
[ "${got}" = "${NDK_SHA1}" ] || die "android-ndk.zip: SHA-1 ${got}, expected ${NDK_SHA1} (Google's published value)"
echo "   SHA-1 OK android-ndk.zip ${NDK_SHA1}"
check_sha256 "${DL}/android-ndk.zip" "${NDK_SHA256}"

download "${OPENSSL_URL}" "${DL}/openssl.tar.gz"
check_sha256 "${DL}/openssl.tar.gz" "${OPENSSL_SHA256}"

cd "${SRC}"

# --- upstream step "setup boost" ---
echo "== setup boost ${BOOST_VERSION}"
(
  tar xzf "${DL}/boost.tar.gz"
  mv "boost_${BOOST_UNDERSCORE}" boost
  cd boost
  ./bootstrap.sh > "${LOGS}/boost-bootstrap.log" 2>&1 || { tail -n 50 "${LOGS}/boost-bootstrap.log"; exit 1; }
)

# --- upstream step "setup ndk" ---
echo "== setup ndk ${NDK_VERSION}"
(
  unzip -qq "${DL}/android-ndk.zip"
  mv "android-ndk-${NDK_VERSION}" android-ndk
)

# --- upstream step "setup openssl" ---
echo "== setup openssl ${OPENSSL_VERSION} (${OPENSSL_TARGET})"
(
  export TOOLCHAIN="${PWD}/android-ndk/toolchains/llvm/prebuilt/linux-x86_64"
  export CC="${TOOLCHAIN}/bin/${CLANG_PREFIX}-clang"
  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export LD="${TOOLCHAIN}/bin/ld"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  tar xzf "${DL}/openssl.tar.gz"
  cd "openssl-${OPENSSL_VERSION}"
  # Word splitting of the option strings is intended (upstream passes them unquoted).
  # shellcheck disable=SC2086
  ./Configure "${OPENSSL_TARGET}" ${OPENSSL_NO_OPTS} ${OPENSSL_ABI_OPTS} -fPIC --prefix="${PWD}/../openssl" \
    > "${LOGS}/openssl-configure.log" 2>&1 || { tail -n 50 "${LOGS}/openssl-configure.log"; exit 1; }
  make > "${LOGS}/openssl-make.log" 2>&1 || { tail -n 80 "${LOGS}/openssl-make.log"; exit 1; }
  make install_sw > "${LOGS}/openssl-install.log" 2>&1 || { tail -n 50 "${LOGS}/openssl-install.log"; exit 1; }
  cd ..
  if [ "${OPENSSL_LIB64_MOVE}" = yes ]; then
    mv "${PWD}/openssl/lib64" "${PWD}/openssl/lib"
  fi
)

# --- upstream step "build" ---
echo "== build (b2 ${TOOLSET})"
(
  export ANDROID_TOOLCHAIN="${PWD}/android-ndk/toolchains/llvm/prebuilt/linux-x86_64"
  export BOOST_ROOT="${PWD}/boost"
  export OPENSSL_ROOT="${PWD}/openssl"
  export LIBTORRENT_ROOT="${PWD}/swig/deps/libtorrent"
  export CXX="${ANDROID_TOOLCHAIN}/bin/${CLANG_PREFIX}-clang++"
  export CC="${ANDROID_TOOLCHAIN}/bin/${CLANG_PREFIX}-clang"
  export AR="${ANDROID_TOOLCHAIN}/bin/llvm-ar"
  export LD="${ANDROID_TOOLCHAIN}/bin/ld"
  export RANLIB="${ANDROID_TOOLCHAIN}/bin/llvm-ranlib"
  # Upstream also runs `alias ranlib=...` here; aliases are not expanded in a non-interactive shell, so it has
  # no effect there either and is left out.
  cd swig
  "${BOOST_ROOT}/b2" -j2 "--user-config=${USER_CONFIG}" variant=release "toolset=${TOOLSET}" target-os=android \
    "location=bin/release/android/${ABI}" > "${LOGS}/b2.log" 2>&1 || { tail -n 120 "${LOGS}/b2.log"; exit 1; }
  tail -n 5 "${LOGS}/b2.log"
)

SO="${SRC}/swig/bin/release/android/${ABI}/libtorrent4j.so"
[ -f "${SO}" ] || die "b2 finished but ${SO} is missing"

# --- upstream step "strip" ---
echo "== strip (${STRIP_OPTS})"
(
  export ANDROID_TOOLCHAIN="${PWD}/android-ndk/toolchains/llvm/prebuilt/linux-x86_64"
  "${ANDROID_TOOLCHAIN}/bin/llvm-objcopy" --only-keep-debug "${SO}" "${SO}.debug"
  # shellcheck disable=SC2086
  "${ANDROID_TOOLCHAIN}/bin/llvm-strip" ${STRIP_OPTS} "${SO}"
)

# --- upstream step "dependencies" ---
echo "== dependencies"
"${SRC}/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "${SO}" | tee "${OUT}/readelf-d.txt"

cp "${SO}" "${OUT}/libtorrent4j.so"
echo "== done: ${OUT}/libtorrent4j.so $(sha256sum "${OUT}/libtorrent4j.so" | awk '{print $1}')"
