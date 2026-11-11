#!/usr/bin/env python3
"""Check that the bundled browser and torrent engines are current.

Components and sources (official release sources only, HTTPS only):
  * browser-engine-gecko (`mozillaAc` in gradle/libs.versions.toml): the highest plain
    version (`160.0`, `160.0.1`; betas are ignored) in maven.mozilla.org's metadata.
    The metadata's <release> field can name a beta, so it is not used.
  * geckoview-omni (`geckoview`): the version that newest browser-engine-gecko's .pom pins.
  * libtorrent4j (`libtorrent4j`): the highest release version on Maven Central.
  * OpenSSL (`OPENSSL_VERSION` in native/libtorrent4j/versions.env): the highest
    `openssl-<series>.<patch>` tag on github.com/openssl/openssl.

Prints one line per component and exits:
  0  everything is current (or only --warn-only components are behind)
  1  a component is behind
  2  could not check (network failure, unexpected response, missing pom entry, a libtorrent4j version of an
     unknown shape, the two libtorrent4j records disagreeing, or a current version newer than the newest
     one found, which means the check is looking at the wrong source)

A failed check is not a pass: exit 2 also fails the job.

Usage: check_engine_currency.py [--warn-only COMPONENT ...] [--repo-root DIR]
"""

import argparse
import http.client
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

TIMEOUT_SECONDS = 20
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
USER_AGENT = "engine-currency-check"

AC_METADATA_URL = (
    "https://maven.mozilla.org/maven2/org/mozilla/components/browser-engine-gecko/maven-metadata.xml"
)
AC_POM_URL = (
    "https://maven.mozilla.org/maven2/org/mozilla/components/browser-engine-gecko/"
    "{v}/browser-engine-gecko-{v}.pom"
)
LIBTORRENT4J_METADATA_URL = "https://repo1.maven.org/maven2/org/libtorrent4j/libtorrent4j/maven-metadata.xml"
OPENSSL_REPO_URL = "https://github.com/openssl/openssl"

COMPONENTS = ("browser-engine-gecko", "geckoview-omni", "libtorrent4j", "openssl")

STABLE_AC = re.compile(r"^\d+\.\d+(\.\d+)?$")
# 2.1.0-39 and 1.2.0.22 are releases; 2.0.4-22-RC1, 2.0.0-beta-1 and 1.2.0.21-alpha1 are not.
STABLE_LIBTORRENT4J = re.compile(r"^\d+(\.\d+)+(-\d+)?$")
# Pre-release shapes seen on Maven Central: 1.2.0.1-beta1, 1.2.0.20-RC1, 1.3.0-alpha-1, 2.0.4-22-RC1.
PRERELEASE_LIBTORRENT4J = re.compile(r"^\d+(\.\d+)+(-\d+)?-(RC|alpha|beta)-?\d+$")


class CheckError(Exception):
    """The check could not be completed. The message never contains response bodies."""


# ---------------------------------------------------------------------------
# Pure functions (tested offline by test_check_engine_currency.py)
# ---------------------------------------------------------------------------

def version_key(version):
    """Numeric sort key: '160.0.1' -> (160, 0, 1); '2.1.0-39' -> (2, 1, 0, 39)."""
    return tuple(int(part) for part in re.split(r"[.-]", version))


def highest(versions, pattern):
    """Highest version matching pattern, or None if none matches."""
    matching = [v.strip() for v in versions if pattern.match(v.strip())]
    if not matching:
        return None
    return max(matching, key=version_key)


def metadata_versions(xml_text):
    """All <versioning><versions><version> entries of a maven-metadata.xml."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise CheckError("maven-metadata.xml is not valid XML") from exc
    return [el.text or "" for el in root.findall("./versioning/versions/version")]


def pom_dependency_version(pom_text, artifact_id):
    """Version of the dependency with this artifactId in a .pom, or None if absent."""
    try:
        root = ET.fromstring(pom_text)
    except ET.ParseError as exc:
        raise CheckError("pom is not valid XML") from exc
    for dep in root.iter():
        if _local(dep.tag) != "dependency":
            continue
        fields = {_local(child.tag): (child.text or "").strip() for child in dep}
        if fields.get("artifactId") == artifact_id and fields.get("version"):
            return fields["version"]
    return None


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def newest_libtorrent4j(versions):
    """Highest release version; a version of neither a release nor a known pre-release shape is an error."""
    unknown = [v.strip() for v in versions
               if not (STABLE_LIBTORRENT4J.match(v.strip()) or PRERELEASE_LIBTORRENT4J.match(v.strip()))]
    if unknown:
        raise CheckError("libtorrent4j version of an unknown shape in the metadata: %s" % ", ".join(unknown[:5]))
    return highest(versions, STABLE_LIBTORRENT4J)


def openssl_highest_patch(ls_remote_output, series):
    """Highest 'series.N' from `git ls-remote --tags` output; pre-releases and ^{} lines are ignored."""
    tag = re.compile(r"^refs/tags/openssl-" + re.escape(series) + r"\.(\d+)$")
    patches = []
    for line in ls_remote_output.splitlines():
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        match = tag.match(parts[1].strip())
        if match:
            patches.append(int(match.group(1)))
    if not patches:
        return None
    return "%s.%d" % (series, max(patches))


def is_behind(current, newest):
    return version_key(newest) > version_key(current)


OK, BEHIND, AHEAD = "ok", "behind", "ahead"


def toml_versions(toml_text):
    """The [versions] table of a Gradle version catalog as a dict of plain string values."""
    versions = {}
    in_versions = False
    for raw in toml_text.splitlines():
        line = raw.strip()
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if not in_versions or not line or line.startswith("#"):
            continue
        match = re.match(r'^([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"', line)
        if match:
            versions[match.group(1)] = match.group(2)
    return versions


def env_values(env_text):
    """KEY=VALUE lines of versions.env (comments and blank lines ignored)."""
    values = {}
    for raw in env_text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"')
    return values


def evaluate(current, newest):
    """Compare current and newest versions.

    current: browser-engine-gecko, geckoview-omni, libtorrent4j, openssl.
    newest: the same keys; geckoview-omni is the version newest browser-engine-gecko pins.
    Returns a list of (component, current, newest, state) in COMPONENTS order; state is OK, BEHIND or AHEAD.
    AHEAD (current newer than the newest found) applies to the ordered components only.
    """
    rows = []
    for name in COMPONENTS:
        if name == "geckoview-omni":
            # Must equal the exact build the newest AC pins; there is no ordering to trust.
            state = BEHIND if current[name] != newest[name] else OK
        elif is_behind(current[name], newest[name]):
            state = BEHIND
        elif is_behind(newest[name], current[name]):
            state = AHEAD
        else:
            state = OK
        rows.append((name, current[name], newest[name], state))
    return rows


def report(rows, warn_only):
    """Output lines and exit code for evaluated rows: 0, 1 (behind) or 2 (ahead: could not check)."""
    lines = []
    code = 0
    for name, cur, new, state in rows:
        if state == OK:
            status = "OK"
        elif state == AHEAD:
            # Ahead of the source means the check is reading the wrong thing; warn-only does not hide it.
            status = "AHEAD of the newest release found: could not check"
            code = 2
        elif name in warn_only:
            status = "BEHIND (warning only)"
        else:
            status = "BEHIND"
            code = max(code, 1)
        lines.append("%-21s current %-22s newest %-22s %s" % (name, cur, new, status))
    return lines, code


# ---------------------------------------------------------------------------
# Network (the only impure part)
# ---------------------------------------------------------------------------

class _HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise CheckError("refused a redirect away from HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url):
    if not url.startswith("https://"):
        raise CheckError("refused a non-HTTPS URL")
    opener = urllib.request.build_opener(_HttpsOnlyRedirects())
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError) as exc:
        raise CheckError("request failed (%s)" % type(exc).__name__) from exc
    if len(body) > MAX_RESPONSE_BYTES:
        raise CheckError("response larger than the 2 MB cap")
    return body.decode("utf-8", errors="replace")


def ls_remote_tags(series):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    try:
        result = subprocess.run(
            ["git", "ls-remote", "--tags", OPENSSL_REPO_URL, "openssl-%s.*" % series],
            capture_output=True, timeout=TIMEOUT_SECONDS, env=env, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise CheckError("git ls-remote failed (%s)" % type(exc).__name__) from exc
    if result.returncode != 0:
        raise CheckError("git ls-remote exited %d" % result.returncode)
    if len(result.stdout) > MAX_RESPONSE_BYTES:
        raise CheckError("git ls-remote output larger than the 2 MB cap")
    return result.stdout.decode("utf-8", errors="replace")


def newest_versions(openssl_series):
    ac = highest(metadata_versions(fetch(AC_METADATA_URL)), STABLE_AC)
    if ac is None:
        raise CheckError("no stable browser-engine-gecko version in the metadata")
    omni = pom_dependency_version(fetch(AC_POM_URL.format(v=ac)), "geckoview-omni")
    if omni is None:
        raise CheckError("browser-engine-gecko %s pom has no geckoview-omni dependency" % ac)
    lt4j = newest_libtorrent4j(metadata_versions(fetch(LIBTORRENT4J_METADATA_URL)))
    if lt4j is None:
        raise CheckError("no release libtorrent4j version in the metadata")
    openssl = openssl_highest_patch(ls_remote_tags(openssl_series), openssl_series)
    if openssl is None:
        raise CheckError("no openssl-%s.N tag found" % openssl_series)
    return {"browser-engine-gecko": ac, "geckoview-omni": omni, "libtorrent4j": lt4j, "openssl": openssl}


def current_versions(repo_root):
    def read(rel):
        try:
            with open(os.path.join(repo_root, rel), encoding="utf-8") as handle:
                return handle.read()
        except OSError as exc:
            raise CheckError("cannot read %s" % rel) from exc

    toml = toml_versions(read(os.path.join("gradle", "libs.versions.toml")))
    env = env_values(read(os.path.join("native", "libtorrent4j", "versions.env")))
    wanted = {
        "browser-engine-gecko": toml.get("mozillaAc"),
        "geckoview-omni": toml.get("geckoview"),
        "libtorrent4j": toml.get("libtorrent4j"),
        "openssl": env.get("OPENSSL_VERSION"),
    }
    missing = [name for name, value in wanted.items() if not value]
    missing += [key for key in ("OPENSSL_SERIES", "LIBTORRENT4J_VERSION") if not env.get(key)]
    if missing:
        raise CheckError("missing version entries: %s" % ", ".join(missing))
    if env["LIBTORRENT4J_VERSION"] != wanted["libtorrent4j"]:
        raise CheckError("versions.env LIBTORRENT4J_VERSION %s differs from libs.versions.toml libtorrent4j %s"
                         % (env["LIBTORRENT4J_VERSION"], wanted["libtorrent4j"]))
    for name, value in wanted.items():
        try:
            version_key(value)
        except ValueError as exc:
            raise CheckError("unreadable current version for %s" % name) from exc
    return wanted, env["OPENSSL_SERIES"]


def main(argv=None):
    parser = argparse.ArgumentParser(description="Fail when GeckoView, libtorrent4j or OpenSSL is behind.")
    parser.add_argument("--warn-only", action="append", default=[], choices=COMPONENTS,
                        help="report this component as a warning instead of failing (repeatable)")
    parser.add_argument("--repo-root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    args = parser.parse_args(argv)

    try:
        current, series = current_versions(args.repo_root)
        newest = newest_versions(series)
    except CheckError as exc:
        print("could not check: %s" % exc)
        return 2

    lines, code = report(evaluate(current, newest), set(args.warn_only))
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
