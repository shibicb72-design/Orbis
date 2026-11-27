#!/usr/bin/env python3
"""Content hashes of an APK's code, native libraries and extension files, for comparing two builds of one commit.

Usage: python3 -I scripts/apk_content_hashes.py <apk> [--commit <sha>] > r8check-hashes.txt

First line: the commit sha (--commit, else $GITHUB_SHA, else "unknown"). Then one line "sha256  size  name" for every
classes*.dex, every lib/x86_64/*.so, assets/omni.ja and every file under assets/extensions/, sorted by name.
Reads the zip in memory; nothing is extracted to disk.
"""

import argparse
import hashlib
import os
import re
import sys
import zipfile

_WANTED = re.compile(r"^(classes[0-9]*\.dex|lib/x86_64/[^/]+\.so|assets/omni\.ja|assets/extensions/.+[^/])$")


def hash_lines(apk):
    lines = []
    with zipfile.ZipFile(apk) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            if not _WANTED.match(info.filename):
                continue
            h = hashlib.sha256()
            with z.open(info) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            lines.append("%s  %d  %s" % (h.hexdigest(), info.file_size, info.filename))
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--commit", default=os.environ.get("GITHUB_SHA", "unknown"))
    args = ap.parse_args(argv)
    lines = hash_lines(args.apk)
    if not lines:
        print("no matching entries in %s" % args.apk, file=sys.stderr)
        return 2
    sys.stdout.write("\n".join([args.commit] + lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
