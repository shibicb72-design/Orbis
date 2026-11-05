#!/usr/bin/env python3
"""Report 16 KB page-size readiness of the native libraries in APKs.

For every lib/<abi>/*.so entry it checks:
  * ELF: each PT_LOAD segment has p_align >= 16384 and congruent offset/vaddr.
    This is what decides whether the library loads on a 16 KB-page device.
  * Zip: an uncompressed (stored) .so must start on a 16 KB boundary in the APK.
    Compressed entries are extracted on install, so zip alignment does not apply.

It never fails the build unless --strict is given. Unaligned libraries are
printed as GitHub Actions ::warning:: lines so they show on the run summary.

Usage: check_16kb_alignment.py [--strict] APK [APK ...]
"""

import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1


def load_segments(data):
    """Yield (p_offset, p_vaddr, p_align) for each PT_LOAD segment of an ELF image."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    for i in range(phnum):
        base = phoff + i * phentsize
        if is64:
            p_type, _flags, p_offset, p_vaddr, _paddr, _filesz, _memsz, p_align = struct.unpack_from(
                endian + "IIQQQQQQ", data, base)
        else:
            p_type, p_offset, p_vaddr, _paddr, _filesz, _memsz, _flags, p_align = struct.unpack_from(
                endian + "IIIIIIII", data, base)
        if p_type == PT_LOAD:
            yield p_offset, p_vaddr, p_align


def zip_data_offset(apk_path, info):
    """Offset of the entry's data in the APK (after its local file header)."""
    with open(apk_path, "rb") as f:
        f.seek(info.header_offset)
        header = f.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_apk(apk_path):
    """Return (rows, unaligned_count) for one APK."""
    rows = []
    unaligned = 0
    with zipfile.ZipFile(apk_path) as apk:
        libs = [i for i in apk.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        for info in sorted(libs, key=lambda i: i.filename):
            segments = list(load_segments(apk.read(info)))
            min_align = min((a for _, _, a in segments), default=0)
            elf_ok = bool(segments) and all(
                align >= PAGE and (offset % PAGE) == (vaddr % PAGE) for offset, vaddr, align in segments)
            if info.compress_type == zipfile.ZIP_STORED:
                zip_ok = zip_data_offset(apk_path, info) % PAGE == 0
                zip_note = "stored, 16 KB aligned" if zip_ok else "stored, NOT 16 KB aligned"
            else:
                zip_ok = True
                zip_note = "compressed (extracted on install)"
            ok = elf_ok and zip_ok
            if not ok:
                unaligned += 1
            rows.append((info.filename, min_align, elf_ok, zip_note, ok))
    return rows, unaligned


def main(argv):
    strict = "--strict" in argv
    apks = [a for a in argv if a != "--strict"]
    if not apks:
        print(__doc__)
        return 2
    total_unaligned = 0
    for apk_path in apks:
        rows, unaligned = check_apk(apk_path)
        total_unaligned += unaligned
        print(f"== {apk_path}: {len(rows)} native libraries")
        for name, min_align, elf_ok, zip_note, ok in rows:
            status = "OK  " if ok else "FAIL"
            elf = "ELF LOAD ok" if elf_ok else "ELF LOAD < 16 KB"
            print(f"  {status} {name}  min p_align={min_align} ({min_align // 1024} KB), {elf}; {zip_note}")
            if not ok:
                print(f"::warning file={apk_path}::{name} is not 16 KB page aligned (min p_align {min_align})")
        if not rows:
            print("  (no native libraries)")
    if total_unaligned:
        print(f"RESULT: {total_unaligned} native libraries are NOT 16 KB aligned. "
              "They load on 4 KB-page devices (Fold 7 today) but would fail on a 16 KB-page device.")
    else:
        print("RESULT: all native libraries are 16 KB aligned.")
    return 1 if strict and total_unaligned else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
