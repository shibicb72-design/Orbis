#!/usr/bin/env python3
"""Every extension folder whose files changed since a base ref must raise "version" in its manifest.json.

Usage: python3 -I scripts/check_extension_versions.py --base <ref> [--repo <dir>]

Phones keep an installed extension while its manifest version is unchanged (lessons L20), so a changed file with the
same version never reaches them. Compares the git trees of <ref> and HEAD; nothing is checked out. A new folder
passes, a deleted folder passes. Exit 0 = ok, 1 = a folder needs a new version, 2 = bad input.
"""

import argparse
import json
import subprocess
import sys

EXT_DIR = "app/src/main/assets/extensions"


def _git(repo, *args, check=True):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, check=check)


def _tree(repo, ref):
    """{extension folder: {path: blob id}} for one commit."""
    out = _git(repo, "ls-tree", "-r", "-z", ref, "--", EXT_DIR + "/").stdout
    folders = {}
    for entry in filter(None, out.split("\0")):
        meta, path = entry.split("\t", 1)
        folder = path[len(EXT_DIR) + 1:].split("/", 1)[0]
        folders.setdefault(folder, {})[path] = meta.split()[2]
    return folders


def _version(repo, ref, folder):
    res = _git(repo, "show", "%s:%s/%s/manifest.json" % (ref, EXT_DIR, folder), check=False)
    if res.returncode != 0:
        return None
    try:
        return json.loads(res.stdout).get("version")
    except ValueError:
        return None


def check(repo, base):
    """Returns a list of failure messages (empty = ok)."""
    before, after = _tree(repo, base), _tree(repo, "HEAD")
    failures = []
    for folder in sorted(after):
        if folder not in before or before[folder] == after[folder]:
            continue
        old, new = _version(repo, base, folder), _version(repo, "HEAD", folder)
        if old == new:
            failures.append(
                "extension %s: files changed but manifest.json version is %s on both sides; "
                "raise the version in manifest.json" % (folder, old)
            )
    return failures


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", required=True, help="git ref (or sha) to compare HEAD against")
    ap.add_argument("--repo", default=".", help="repository directory (default: current)")
    args = ap.parse_args(argv)
    if _git(args.repo, "rev-parse", "--verify", "--quiet", args.base + "^{commit}", check=False).returncode != 0:
        print("base ref not found: %s" % args.base, file=sys.stderr)
        return 2
    failures = check(args.repo, args.base)
    for line in failures:
        print("FAIL " + line)
    if not failures:
        print("ok: no extension changed without a new manifest version")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
