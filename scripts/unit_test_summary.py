"""Prints a Markdown summary of JUnit XML results: one row per test class, then each test of DETAIL_CLASSES.

Usage: python3 -I scripts/unit_test_summary.py <results dir>
DETAIL_CLASSES (environment, space-separated class names) lists the classes whose tests are shown one by one.
Reads only the build's own result files. Exit code is 0 whatever the results are; the Gradle step already failed.
"""

import os
import sys
import xml.etree.ElementTree as ET


def outcome(case):
    if case.find("failure") is not None or case.find("error") is not None:
        return "FAILED"
    if case.find("skipped") is not None:
        return "skipped"
    return "passed"


def main(argv):
    if len(argv) != 2:
        print("usage: unit_test_summary.py <results dir>", file=sys.stderr)
        return 2
    results = argv[1]
    detail = set(os.environ.get("DETAIL_CLASSES", "").split())
    files = sorted(f for f in os.listdir(results) if f.startswith("TEST-") and f.endswith(".xml")) if os.path.isdir(results) else []
    if not files:
        print("No unit test results found.")
        return 0
    rows = []
    cases = {}
    totals = [0, 0, 0]
    for name in files:
        suite = ET.parse(os.path.join(results, name)).getroot()
        cls = suite.get("name", name)
        tests = int(suite.get("tests", 0))
        failed = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        skipped = int(suite.get("skipped", 0))
        totals[0] += tests
        totals[1] += failed
        totals[2] += skipped
        rows.append((cls, tests, failed, skipped))
        if cls in detail:
            cases[cls] = [(c.get("name", "?"), outcome(c)) for c in suite.iter("testcase")]
    print("## Unit tests: %d run, %d failed, %d skipped\n" % tuple(totals))
    for cls in sorted(cases):
        print("### %s\n" % cls)
        for test, result in cases[cls]:
            print("- %s: %s" % (test, result))
        print()
    missing = sorted(detail - set(cases))
    if missing:
        print("Not found in the results: %s\n" % ", ".join(missing))
    print("| Class | Tests | Failed | Skipped |")
    print("|---|---:|---:|---:|")
    for cls, tests, failed, skipped in rows:
        print("| %s | %d | %d | %d |" % (cls, tests, failed, skipped))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
