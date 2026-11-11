#!/usr/bin/env python3
"""BUILDINFO.json for one torrent natives build, and the run summary across builds.

  buildinfo.py write --abi ABI --repeat N --so FILE --versions FILE --compare FILE --submodules FILE --out FILE
  buildinfo.py summary DIR        (DIR holds one sub-directory per artifact, each with a BUILDINFO.json)

Used by .github/workflows/torrent-natives.yml. Python 3 standard library only.
"""

import argparse
import glob
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def env_values(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def read_json(path, missing=None):
    """JSON content of path; `missing` when the file does not exist (a check step failed before writing it)."""
    if missing is not None and not os.path.exists(path):
        return missing
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def write(args):
    with open(os.path.join(HERE, "build.env"), encoding="utf-8") as handle:
        build_env = env_values(handle.read())
    with open(args.so, "rb") as handle:
        so = handle.read()
    with open(args.submodules, encoding="utf-8") as handle:
        submodules = [line.strip() for line in handle if line.strip()]
    versions = read_json(args.versions, missing=[])
    info = {
        "abi": args.abi,
        "repeat": args.repeat,
        "file": "libtorrent4j.so",
        "sha256": hashlib.sha256(so).hexdigest(),
        "size": len(so),
        "openssl_strings": sorted({s for lib in versions for s in lib["openssl"]}),
        "libtorrent_strings": sorted({s for lib in versions for s in lib["libtorrent"]}),
        "baseline_comparison": read_json(args.compare, missing={"failures": ["comparison did not run"]}),
        "submodules": submodules,
        "build_env": build_env,
        "workflow": {
            "repository": os.environ.get("GITHUB_REPOSITORY", ""),
            "run_id": os.environ.get("GITHUB_RUN_ID", ""),
            "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
            "event": os.environ.get("GITHUB_EVENT_NAME", ""),
            "ref": os.environ.get("GITHUB_REF", ""),
            "commit": os.environ.get("GITHUB_SHA", ""),
            "head_commit": os.environ.get("HEAD_SHA", ""),
        },
        "runner": {
            "image_os": os.environ.get("ImageOS", ""),
            "image_version": os.environ.get("ImageVersion", ""),
            "label": build_env.get("RUNNER", ""),
        },
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(info, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return 0


def summary_lines(infos):
    """Markdown lines for the job summary from a list of BUILDINFO dicts (pure)."""
    lines = ["## Torrent natives", "",
             "| ABI | repeat | SHA-256 | size | OpenSSL | libtorrent | JNI symbols (ours/upstream) | size vs upstream "
             "| baseline |",
             "|---|---|---|---|---|---|---|---|---|"]
    by_abi = {}
    for info in sorted(infos, key=lambda i: (i["abi"], int(i["repeat"]))):
        cmp_ = info.get("baseline_comparison", {})
        lines.append("| %s | %s | `%s` | %d | %s | %s | %s/%s | %s | %s |" % (
            info["abi"], info["repeat"], info["sha256"], info["size"], ", ".join(info["openssl_strings"]),
            ", ".join(info["libtorrent_strings"]), cmp_.get("jni_symbols_ours", "?"),
            cmp_.get("jni_symbols_upstream", "?"), cmp_.get("size_change", "?"),
            "FAIL: " + "; ".join(cmp_["failures"]) if cmp_.get("failures") else "same JNI set, ELF, DT_NEEDED"))
        by_abi.setdefault(info["abi"], []).append(info["sha256"])
    lines += ["", "Reproducibility (reported, not failed):", ""]
    for abi in sorted(by_abi):
        hashes = by_abi[abi]
        if len(hashes) < 2:
            verdict = "only %d build finished" % len(hashes)
        elif len(set(hashes)) == 1:
            verdict = "repeat 1 and 2 are byte-identical"
        else:
            verdict = "repeat 1 and 2 DIFFER"
        lines.append("- %s: %s" % (abi, verdict))
    if infos:
        runner = infos[0].get("runner", {})
        lines += ["", "Runner image: %s %s" % (runner.get("image_os", ""), runner.get("image_version", ""))]
    return lines


def summary(args):
    infos = [read_json(p) for p in sorted(glob.glob(os.path.join(args.dir, "*", "BUILDINFO.json")))]
    for line in summary_lines(infos):
        print(line)
    return 0 if infos else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    w = sub.add_parser("write")
    for name in ("abi", "repeat", "so", "versions", "compare", "submodules", "out"):
        w.add_argument("--" + name, required=True)
    s = sub.add_parser("summary")
    s.add_argument("dir")
    args = parser.parse_args(argv)
    return write(args) if args.command == "write" else summary(args)


if __name__ == "__main__":
    sys.exit(main())
