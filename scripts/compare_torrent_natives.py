#!/usr/bin/env python3
"""Compare a self-built libtorrent4j.so with upstream's released one for the same ABI.

Upstream's library comes from its Maven Central native jar (org.libtorrent4j:libtorrent4j-android-<x>). The jar's
SHA-256 and the inner lib/<abi>/libtorrent4j.so's SHA-256 must equal the pinned values in build.env
(UPSTREAM_JAR_SHA256_<abi>, UPSTREAM_SO_SHA256_<abi>), otherwise nothing is compared.

Then, reading the ELF dynamic symbol table directly (no binutils):
  * the set of exported Java_org_libtorrent4j_swig_* symbols must be identical (the Java jar from Maven Central
    and our library must fit together); a difference fails,
  * JNI_OnLoad must be exported by both or by neither; a difference fails,
  * the full exported dynamic symbol set (name, binding, type, visibility) must be identical; a difference fails,
  * the imported (undefined) symbol set must be upstream's with exactly the THREADS_IMPORT_CHANGE applied (our
    OpenSSL is built with thread support, upstream's with no-threads); any other difference, or a missing part
    of that change, fails,
  * ELF class, machine and the DT_NEEDED list must be identical; a difference fails,
  * the file size difference is reported as a percentage (no threshold).

Exits 0 when everything matches, 1 on a difference, 2 when the comparison could not be made.

Usage: compare_torrent_natives.py --abi ABI [--build-env FILE] [--upstream-jar JAR] [--json OUT] OURS.so
"""

import argparse
import hashlib
import http.client
import io
import json
import os
import struct
import sys
import urllib.error
import urllib.request
import zipfile

JNI_PREFIX = "Java_org_libtorrent4j_swig_"
JAR_ARTIFACT = {"arm64-v8a": "libtorrent4j-android-arm64", "armeabi-v7a": "libtorrent4j-android-arm",
                "x86_64": "libtorrent4j-android-x86_64"}
JAR_URL = "https://repo1.maven.org/maven2/org/libtorrent4j/{a}/{v}/{a}-{v}.jar"
TIMEOUT_SECONDS = 60
MAX_JAR_BYTES = 128 * 1024 * 1024
USER_AGENT = "torrent-natives-compare"

SHT_DYNAMIC = 6
SHT_DYNSYM = 11
DT_NULL = 0
DT_NEEDED = 1
DT_SONAME = 14
SHN_UNDEF = 0
EXPORTED_BINDS = (1, 2, 10)        # STB_GLOBAL, STB_WEAK, STB_GNU_UNIQUE
EXPORTED_VISIBILITIES = (0, 3)     # STV_DEFAULT, STV_PROTECTED

# How building OpenSSL with thread support changes the imports (build.env OPENSSL_NO_OPTS without no-threads;
# docs/security/NATIVE-VERSIONS.md, difference g). Upstream's OpenSSL is configured with no-threads. Where each
# name comes from in OpenSSL 3.5.9, all compiled only when threads are on:
#   gmtime_r instead of gmtime: crypto/o_time.c, OPENSSL_gmtime() uses gmtime_r() if OPENSSL_THREADS is defined.
#   pthread_equal: crypto/threads_pthread.c, CRYPTO_THREAD_compare_id(); also crypto/thread/arch/thread_posix.c.
#   pthread_attr_init, pthread_attr_setdetachstate, pthread_attr_destroy, pthread_exit:
#     crypto/thread/arch/thread_posix.c, OpenSSL's internal threads (thread pool, QUIC).
# The other pthread functions OpenSSL uses (mutex, rwlock, once, key, cond, create, self) are imported by
# upstream's library already, through libc++, Boost and libtorrent. Found in Torrent natives run 43140935475,
# identical for arm64-v8a, armeabi-v7a and x86_64.
THREADS_IMPORT_CHANGE = {
    "added": frozenset({"gmtime_r", "pthread_attr_destroy", "pthread_attr_init", "pthread_attr_setdetachstate",
                        "pthread_equal", "pthread_exit"}),
    "removed": frozenset({"gmtime"}),
}


class CompareError(Exception):
    """The comparison could not be made."""


# ---------------------------------------------------------------------------
# ELF reading (pure)
# ---------------------------------------------------------------------------

def _cstr(blob, offset):
    end = blob.find(b"\0", offset)
    if offset >= len(blob) or end < 0:
        raise CompareError("ELF string offset out of range")
    return blob[offset:end].decode("utf-8", errors="replace")


def read_elf(data):
    """Dynamic linking facts of an ELF image.

    Returns a dict: class (32 or 64), machine (e_machine), exported (set of exported dynamic symbol names),
    exported_full (set of (name, binding, type, visibility)), imported (set of undefined symbol names),
    needed (DT_NEEDED list, in order), soname (or None).
    """
    if len(data) < 52 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise CompareError("not an ELF file")
    is64 = data[4] == 2
    e = "<" if data[5] == 1 else ">"
    try:
        machine, = struct.unpack_from(e + "H", data, 18)
        if is64:
            shoff, = struct.unpack_from(e + "Q", data, 0x28)
            shentsize, shnum = struct.unpack_from(e + "HH", data, 0x3A)
            sh_fmt, sym_fmt, sym_size, dyn_fmt, dyn_size = e + "IIQQQQIIQQ", e + "IBBHQQ", 24, e + "qQ", 16
        else:
            shoff, = struct.unpack_from(e + "I", data, 0x20)
            shentsize, shnum = struct.unpack_from(e + "HH", data, 0x2E)
            sh_fmt, sym_fmt, sym_size, dyn_fmt, dyn_size = e + "IIIIIIIIII", e + "IIIBBH", 16, e + "iI", 8
        if shoff == 0 or shnum == 0:
            raise CompareError("ELF file has no section headers")
        sections = []
        for i in range(shnum):
            (_name, sh_type, _flags, _addr, offset, size, link, _info, _align, _entsize) = struct.unpack_from(
                sh_fmt, data, shoff + i * shentsize)
            if offset + size > len(data) and sh_type != 8:  # SHT_NOBITS occupies no file space
                raise CompareError("ELF section %d runs past the end of the file" % i)
            sections.append((sh_type, offset, size, link))

        def blob(index):
            if index >= len(sections):
                raise CompareError("ELF section link out of range")
            _t, offset, size, _l = sections[index]
            return data[offset:offset + size]

        dynsyms = [s for s in sections if s[0] == SHT_DYNSYM]
        dynamics = [s for s in sections if s[0] == SHT_DYNAMIC]
        if len(dynsyms) != 1 or len(dynamics) != 1:
            raise CompareError("expected one .dynsym and one .dynamic section")

        _t, offset, size, link = dynsyms[0]
        strtab = blob(link)
        exported = set()
        exported_full = set()
        imported = set()
        for pos in range(offset, offset + size - sym_size + 1, sym_size):
            fields = struct.unpack_from(sym_fmt, data, pos)
            if is64:
                st_name, st_info, st_other, st_shndx = fields[0], fields[1], fields[2], fields[3]
            else:
                st_name, st_info, st_other, st_shndx = fields[0], fields[3], fields[4], fields[5]
            if not st_name:
                continue
            bind, kind, vis = st_info >> 4, st_info & 0xF, st_other & 3
            if st_shndx == SHN_UNDEF:
                imported.add(_cstr(strtab, st_name))
            elif bind in EXPORTED_BINDS and vis in EXPORTED_VISIBILITIES:
                name = _cstr(strtab, st_name)
                exported.add(name)
                exported_full.add((name, bind, kind, vis))

        _t, offset, size, link = dynamics[0]
        strtab = blob(link)
        needed = []
        soname = None
        for pos in range(offset, offset + size - dyn_size + 1, dyn_size):
            tag, value = struct.unpack_from(dyn_fmt, data, pos)
            if tag == DT_NULL:
                break
            if tag == DT_NEEDED:
                needed.append(_cstr(strtab, value))
            elif tag == DT_SONAME:
                soname = _cstr(strtab, value)
    except struct.error as exc:
        raise CompareError("truncated ELF file") from exc
    return {"class": 64 if is64 else 32, "machine": machine, "exported": exported, "exported_full": exported_full,
            "imported": imported, "needed": needed, "soname": soname}


def jni_symbols(exported):
    return {name for name in exported if name.startswith(JNI_PREFIX)}


def compare(ours, upstream, ours_size, upstream_size, limit=20, import_change=THREADS_IMPORT_CHANGE):
    """Compare two read_elf() results. Returns (report_lines, failures).

    import_change: {"added": names, "removed": names}; ours must import exactly upstream's set with it applied.
    """
    lines = []
    failures = []

    for key in ("class", "machine"):
        same = ours[key] == upstream[key]
        lines.append("%-12s ours %-10s upstream %-10s %s" % (key, ours[key], upstream[key], "same" if same else "DIFF"))
        if not same:
            failures.append("ELF %s differs" % key)

    same = ours["needed"] == upstream["needed"]
    lines.append("DT_NEEDED    ours %s upstream %s %s" % (ours["needed"], upstream["needed"], "same" if same else "DIFF"))
    if not same:
        failures.append("DT_NEEDED list differs")
    lines.append("DT_SONAME    ours %s upstream %s (reported only)" % (ours["soname"], upstream["soname"]))

    ours_jni, up_jni = jni_symbols(ours["exported"]), jni_symbols(upstream["exported"])
    only_ours, only_up = sorted(ours_jni - up_jni), sorted(up_jni - ours_jni)
    lines.append("JNI symbols  ours %d upstream %d, only in ours %d, only in upstream %d %s" % (
        len(ours_jni), len(up_jni), len(only_ours), len(only_up), "same set" if not (only_ours or only_up) else "DIFF"))
    for name in only_ours[:limit]:
        lines.append("  only in ours:     %s" % name)
    for name in only_up[:limit]:
        lines.append("  only in upstream: %s" % name)
    if only_ours or only_up:
        failures.append("Java_org_libtorrent4j_swig_* symbol sets differ")
    if not up_jni:
        failures.append("upstream library exports no %s* symbols (wrong file?)" % JNI_PREFIX)

    ours_onload, up_onload = "JNI_OnLoad" in ours["exported"], "JNI_OnLoad" in upstream["exported"]
    lines.append("JNI_OnLoad   ours %s upstream %s %s" % (ours_onload, up_onload,
                                                         "same" if ours_onload == up_onload else "DIFF"))
    if ours_onload != up_onload:
        failures.append("JNI_OnLoad present in one library only")

    only_ours = sorted(ours["exported_full"] - upstream["exported_full"])
    only_up = sorted(upstream["exported_full"] - ours["exported_full"])
    same = not (only_ours or only_up)
    lines.append("%-12s ours %d upstream %d, only in ours %d, only in upstream %d %s" % (
        "exported", len(ours["exported_full"]), len(upstream["exported_full"]), len(only_ours), len(only_up),
        "same set" if same else "DIFF"))
    for item in only_ours[:limit]:
        lines.append("  only in ours:     %s" % (item,))
    for item in only_up[:limit]:
        lines.append("  only in upstream: %s" % (item,))
    if not same:
        failures.append("exported dynamic symbol sets differ")

    added, removed = import_change["added"], import_change["removed"]
    expected = (upstream["imported"] - removed) | added
    only_ours = sorted(ours["imported"] - expected)
    only_expected = sorted(expected - ours["imported"])
    same = not (only_ours or only_expected)
    lines.append("%-12s ours %d upstream %d, upstream %s %s, only in ours %d, only in expected %d %s" % (
        "imported", len(ours["imported"]), len(upstream["imported"]),
        "+" + ",".join(sorted(added)) if added else "+none", "-" + ",".join(sorted(removed)) if removed else "-none",
        len(only_ours), len(only_expected), "expected set" if same else "DIFF"))
    for item in only_ours[:limit]:
        lines.append("  only in ours:     %s" % (item,))
    for item in only_expected[:limit]:
        lines.append("  only in expected: %s" % (item,))
    if not same:
        failures.append("imported (undefined) symbol set is not upstream's with the OpenSSL threads change")
    lines.append("size         ours %d upstream %d bytes, %s" % (ours_size, upstream_size, size_change(ours_size, upstream_size)))
    return lines, failures


def size_change(ours, upstream):
    if upstream == 0:
        return "n/a"
    return "%+.2f%%" % ((ours - upstream) * 100.0 / upstream)


def env_values(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def upstream_library(jar_bytes, abi, jar_sha256, so_sha256):
    """The inner lib/<abi>/libtorrent4j.so of upstream's jar, after both hashes are checked."""
    got = sha256(jar_bytes)
    if got != jar_sha256:
        raise CompareError("upstream jar SHA-256 %s, expected %s" % (got, jar_sha256))
    try:
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
            so = jar.read("lib/%s/libtorrent4j.so" % abi)
    except (zipfile.BadZipFile, KeyError) as exc:
        raise CompareError("upstream jar has no lib/%s/libtorrent4j.so" % abi) from exc
    got = sha256(so)
    if got != so_sha256:
        raise CompareError("upstream lib/%s/libtorrent4j.so SHA-256 %s, expected %s" % (abi, got, so_sha256))
    return so


# ---------------------------------------------------------------------------
# Network (the only impure part besides file reads)
# ---------------------------------------------------------------------------

class _HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise CompareError("refused a redirect away from HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url):
    if not url.startswith("https://"):
        raise CompareError("refused a non-HTTPS URL")
    opener = urllib.request.build_opener(_HttpsOnlyRedirects())
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read(MAX_JAR_BYTES + 1)
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError) as exc:
        raise CompareError("download failed (%s)" % type(exc).__name__) from exc
    if len(body) > MAX_JAR_BYTES:
        raise CompareError("download larger than the cap")
    return body


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Compare our libtorrent4j.so with upstream's release.")
    parser.add_argument("ours", help="our libtorrent4j.so")
    parser.add_argument("--abi", required=True, choices=sorted(JAR_ARTIFACT))
    parser.add_argument("--build-env", default=os.path.join(here, "..", "native", "libtorrent4j", "build.env"))
    parser.add_argument("--upstream-jar", help="use this jar instead of downloading it (still hash-checked)")
    parser.add_argument("--json", help="write the result to this file")
    args = parser.parse_args(argv)

    try:
        with open(args.build_env, encoding="utf-8") as handle:
            env = env_values(handle.read())
        version = env["UPSTREAM_JAR_VERSION"]
        jar_sha = env["UPSTREAM_JAR_SHA256_" + args.abi]
        so_sha = env["UPSTREAM_SO_SHA256_" + args.abi]
    except (OSError, KeyError) as exc:
        print("could not compare: build.env is unreadable or lacks the upstream pins (%s)" % type(exc).__name__)
        return 2

    url = JAR_URL.format(a=JAR_ARTIFACT[args.abi], v=version)
    try:
        if args.upstream_jar:
            with open(args.upstream_jar, "rb") as handle:
                jar_bytes = handle.read()
        else:
            jar_bytes = fetch(url)
        upstream_so = upstream_library(jar_bytes, args.abi, jar_sha, so_sha)
        with open(args.ours, "rb") as handle:
            ours_so = handle.read()
        ours, upstream = read_elf(ours_so), read_elf(upstream_so)
    except (CompareError, OSError) as exc:
        print("could not compare: %s" % exc)
        return 2

    print("upstream: %s (jar and inner .so SHA-256 match build.env)" % url)
    lines, failures = compare(ours, upstream, len(ours_so), len(upstream_so))
    for line in lines:
        print(line)
    for failure in failures:
        print("FAIL: %s" % failure)
    print("RESULT: %s" % ("FAIL" if failures else "OK"))

    if args.json:
        result = {
            "abi": args.abi, "upstream_jar_url": url, "upstream_so_sha256": so_sha, "ours_sha256": sha256(ours_so),
            "ours_size": len(ours_so), "upstream_size": len(upstream_so),
            "size_change": size_change(len(ours_so), len(upstream_so)),
            "jni_symbols_ours": len(jni_symbols(ours["exported"])),
            "jni_symbols_upstream": len(jni_symbols(upstream["exported"])),
            "exported_ours": len(ours["exported_full"]), "exported_upstream": len(upstream["exported_full"]),
            "imported_ours": len(ours["imported"]), "imported_upstream": len(upstream["imported"]),
            "imported_expected_change": {"added": sorted(THREADS_IMPORT_CHANGE["added"]),
                                         "removed": sorted(THREADS_IMPORT_CHANGE["removed"])},
            "needed_ours": ours["needed"], "needed_upstream": upstream["needed"],
            "failures": failures,
        }
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2)
            handle.write("\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
