#!/usr/bin/env python3
"""Check the OpenSSL and libtorrent versions inside libtorrent4j.so.

Inputs (any mix):
  * a bare libtorrent4j.so (its ABI is read from the ELF header),
  * an APK (lib/<abi>/libtorrent4j.so),
  * an AAB (base/lib/<abi>/libtorrent4j.so).

In each library it finds the byte strings `OpenSSL <x.y.z> <d Mon yyyy>` and `libtorrent/<a.b.c.d>`.
Exits 1 when:
  * the inputs hold no libtorrent4j.so at all,
  * an ABI listed with --abis has no libtorrent4j.so in the inputs,
  * a library has no OpenSSL or no libtorrent version string,
  * more than one distinct OpenSSL version string is found (in one library or across them),
  * the OpenSSL version is below --min-openssl,
  * the libtorrent version differs from --libtorrent.
Exits 2 on a usage error or an unreadable input.

Usage: check_torrent_natives.py [--min-openssl X.Y.Z] [--libtorrent A.B.C.D] [--abis a,b] [--json OUT] INPUT ...
"""

import argparse
import json
import re
import struct
import sys
import zipfile

LIB_NAME = "libtorrent4j.so"
OPENSSL_RE = re.compile(rb"OpenSSL (\d+\.\d+\.\d+) (\d{1,2} [A-Z][a-z]{2} \d{4})")
LIBTORRENT_RE = re.compile(rb"libtorrent/(\d+\.\d+\.\d+\.\d+)")
ELF_MACHINE_ABI = {40: "armeabi-v7a", 183: "arm64-v8a", 62: "x86_64", 3: "x86"}
APK_LIB_RE = re.compile(r"^lib/([^/]+)/" + re.escape(LIB_NAME) + r"$")
AAB_LIB_RE = re.compile(r"^base/lib/([^/]+)/" + re.escape(LIB_NAME) + r"$")


class InputError(Exception):
    """An input could not be read."""


# ---------------------------------------------------------------------------
# Pure functions
# ---------------------------------------------------------------------------

def version_key(version):
    return tuple(int(part) for part in version.split("."))


def elf_abi(data):
    """Android ABI name of an ELF image from e_machine, or None if not an ELF file or unknown machine."""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return None
    endian = "<" if data[5] == 1 else ">"
    machine, = struct.unpack_from(endian + "H", data, 18)
    return ELF_MACHINE_ABI.get(machine)


def scan(data):
    """Version strings in a library: (sorted list of 'x.y.z dd Mon yyyy', sorted list of libtorrent versions)."""
    openssl = sorted({"%s %s" % (m.group(1).decode(), m.group(2).decode()) for m in OPENSSL_RE.finditer(data)})
    libtorrent = sorted({m.group(1).decode() for m in LIBTORRENT_RE.finditer(data)})
    return openssl, libtorrent


def libraries_in_archive(names, read):
    """[(abi, data)] for every lib/<abi>/libtorrent4j.so (APK) or base/lib/<abi>/libtorrent4j.so (AAB) entry."""
    found = []
    for name in sorted(names):
        match = APK_LIB_RE.match(name) or AAB_LIB_RE.match(name)
        if match:
            found.append((match.group(1), read(name)))
    return found


def evaluate(libraries, min_openssl=None, libtorrent=None, abis=()):
    """Check scanned libraries.

    libraries: [(source, abi, openssl_strings, libtorrent_strings)].
    Returns (report_lines, problems). No problems means the check passes.
    """
    lines = []
    problems = []
    all_openssl = set()
    for source, abi, openssl, lt in libraries:
        lines.append("%-12s %s: %s; %s" % (
            abi, source,
            ", ".join("OpenSSL " + s for s in openssl) or "no OpenSSL string",
            ", ".join("libtorrent/" + s for s in lt) or "no libtorrent string"))
        if not openssl:
            problems.append("%s (%s): no OpenSSL version string" % (abi, source))
        if len(openssl) > 1:
            problems.append("%s (%s): %d distinct OpenSSL version strings" % (abi, source, len(openssl)))
        if not lt:
            problems.append("%s (%s): no libtorrent version string" % (abi, source))
        all_openssl.update(openssl)
        for s in openssl:
            version = s.split(" ", 1)[0]
            if min_openssl and version_key(version) < version_key(min_openssl):
                problems.append("%s (%s): OpenSSL %s is below %s" % (abi, source, version, min_openssl))
        if libtorrent:
            for v in lt:
                if v != libtorrent:
                    problems.append("%s (%s): libtorrent %s, expected %s" % (abi, source, v, libtorrent))
    if len(all_openssl) > 1:
        problems.append("more than one OpenSSL version across the libraries: %s" % ", ".join(sorted(all_openssl)))
    if not libraries:
        problems.append("no %s in the inputs" % LIB_NAME)
    present = {abi for _, abi, _, _ in libraries}
    for abi in abis:
        if abi not in present:
            problems.append("%s: no %s in the inputs" % (abi, LIB_NAME))
    return lines, problems


# ---------------------------------------------------------------------------
# File access
# ---------------------------------------------------------------------------

def load(path):
    """[(source, abi, data)] for one input path."""
    try:
        with open(path, "rb") as handle:
            head = handle.read(4)
        if head == b"\x7fELF":
            with open(path, "rb") as handle:
                data = handle.read()
            abi = elf_abi(data)
            if abi is None:
                raise InputError("%s: unknown ELF machine" % path)
            return [(path, abi, data)]
        if head[:2] == b"PK":
            with zipfile.ZipFile(path) as archive:
                return [("%s!%s" % (path, abi), abi, data)
                        for abi, data in libraries_in_archive(archive.namelist(), archive.read)]
    except (OSError, zipfile.BadZipFile) as exc:
        raise InputError("%s: cannot read (%s)" % (path, type(exc).__name__)) from exc
    raise InputError("%s: neither an ELF file nor a zip (APK/AAB)" % path)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check OpenSSL and libtorrent versions in libtorrent4j.so.")
    parser.add_argument("inputs", nargs="+", help=".so, .apk or .aab files")
    parser.add_argument("--min-openssl", help="lowest acceptable OpenSSL version, e.g. 3.5.9")
    parser.add_argument("--libtorrent", help="required libtorrent version, e.g. 2.1.0.0")
    parser.add_argument("--abis", default="", help="comma-separated ABIs that must each have the library")
    parser.add_argument("--json", help="write the found version strings to this file")
    args = parser.parse_args(argv)

    for value, pattern in ((args.min_openssl, r"^\d+\.\d+\.\d+$"), (args.libtorrent, r"^\d+\.\d+\.\d+\.\d+$")):
        if value and not re.match(pattern, value):
            parser.error("bad version: %s" % value)
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]

    try:
        loaded = [lib for path in args.inputs for lib in load(path)]
    except InputError as exc:
        print("could not check: %s" % exc)
        return 2

    libraries = [(source, abi) + scan(data) for source, abi, data in loaded]
    lines, problems = evaluate(libraries, args.min_openssl, args.libtorrent, abis)
    for line in lines:
        print(line)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump([{"source": s, "abi": a, "openssl": o, "libtorrent": t} for s, a, o, t in libraries],
                      handle, indent=2)
            handle.write("\n")
    for problem in problems:
        print("FAIL: %s" % problem)
    print("RESULT: %s" % ("FAIL" if problems else "OK"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
