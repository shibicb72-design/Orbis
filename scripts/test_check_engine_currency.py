#!/usr/bin/env python3
"""Offline tests for check_engine_currency.py (no network). Run: python3 -I scripts/test_check_engine_currency.py"""

import http.client
import importlib.util
import os
import tempfile
import unittest
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("check_engine_currency", os.path.join(_HERE, "check_engine_currency.py"))
cec = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(cec)

AC_METADATA = """<?xml version='1.0' encoding='utf-8'?>
<metadata><groupId>org.mozilla.components</groupId><artifactId>browser-engine-gecko</artifactId>
<versioning><latest>161.0b2</latest><release>161.0b2</release><versions>
<version>9.0.0</version><version>159.0</version><version>159.0.1</version><version>160.0</version>
<version>160.0.1</version><version>161.0b1</version><version>161.0b2</version>
<version>162.0a1</version><version>160.0.1-rc1</version>
</versions></versioning></metadata>"""

POM_WITH_OMNI = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <artifactId>browser-engine-gecko</artifactId>
  <version>160.0</version>
  <dependencies>
    <dependency>
      <groupId>org.mozilla.components</groupId>
      <artifactId>concept-engine</artifactId>
      <version>160.0</version>
    </dependency>
    <dependency>
      <groupId>org.mozilla.geckoview</groupId>
      <artifactId>geckoview-omni</artifactId>
      <version>160.0.20261104181552</version>
      <scope>compile</scope>
    </dependency>
  </dependencies>
</project>"""

POM_WITHOUT_OMNI = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <dependencies>
    <dependency><groupId>org.mozilla.components</groupId><artifactId>concept-engine</artifactId>
      <version>160.0</version></dependency>
  </dependencies>
</project>"""

LT4J_METADATA = """<?xml version="1.0" encoding="UTF-8"?>
<metadata><versioning><latest>2.0.4-22-RC1</latest><versions>
<version>1.2.0.22</version><version>2.0.0-beta-1</version><version>2.0.4-22-RC1</version>
<version>2.0.6-26</version><version>2.1.0-9</version><version>2.1.0-39</version><version>2.1.0-40-RC1</version>
</versions></versioning></metadata>"""

REAL_LT4J_VERSIONS = """
1.2.0.1-beta1 1.2.0.19 1.2.0.20 1.2.0.20-RC1 1.2.0.20-RC2 1.2.0.20-RC3 1.2.0.21 1.2.0.21-RC1 1.2.0.21-alpha1
1.2.0.21-alpha2 1.2.0.21-alpha3 1.2.0.21-alpha4 1.2.0.21-beta1 1.2.0.22 1.2.1.0 1.2.2.0 1.2.3.0 1.3.0-alpha-1
1.3.0-alpha-3 1.3.0-alpha-4 1.3.0-alpha-5 2.0.0-alpha-1 2.0.0-alpha-2 2.0.0-alpha-3 2.0.0-alpha-4 2.0.0-alpha-5
2.0.0-beta-1 2.0.0-beta-2 2.0.0-1 2.0.0-2 2.0.0-3 2.0.0-4 2.0.0-5 2.0.0-6 2.0.1-7 2.0.1-8 2.0.1-9 2.0.2-10 2.0.2-11
2.0.2-12 2.0.2-13 2.0.2-14 2.0.3-15 2.0.3-16 2.0.3-17 2.0.3-18 2.0.3-19 2.0.4-22-RC1 2.0.4-20 2.0.4-21 2.0.5-26-RC1
2.0.5-22 2.0.5-23 2.0.5-24 2.0.5-25 2.0.6-26 2.1.0-27 2.1.0-28 2.1.0-29 2.1.0-30 2.1.0-31 2.1.0-32 2.1.0-33 2.1.0-34
2.1.0-35 2.1.0-36 2.1.0-37 2.1.0-38 2.1.0-39
"""

OPENSSL_LS_REMOTE = """02192e014a72972a398eee3106f0ec369eabc1f1\trefs/tags/openssl-3.5.0
636dfadc70ce26f2473870570bfd9ec352806b1d\trefs/tags/openssl-3.5.0^{}
fa89bba4f482b5bd7abc892ef0093719ebed776d\trefs/tags/openssl-3.5.0-alpha1
439e2ee7a4b578daabe49d8feaa21f14ace615e4\trefs/tags/openssl-3.5.0-beta1
d0ca66a1abe52545f14eca635c648932fcde5615\trefs/tags/openssl-3.5.9
45e844fa2a14ec92d146bd8f5778ac130b6625fb\trefs/tags/openssl-3.5.9^{}
1111111111111111111111111111111111111111\trefs/tags/openssl-3.5.10
2222222222222222222222222222222222222222\trefs/tags/openssl-3.5.11-alpha1
3333333333333333333333333333333333333333\trefs/tags/openssl-3.50.1
"""

TOML = """[versions]
agp = "9.4.0"
# browser-engine-gecko 160.0 -> geckoview-omni 160.0.20261104181552
mozillaAc = "160.0"
geckoview = "160.0.20261104181552"
libtorrent4j = "2.1.0-39"

[libraries]
x = { group = "a", name = "b", version = "9.9.9" }
"""


class VersionTest(unittest.TestCase):
    def test_dot_release_sorts_after_plain(self):
        self.assertGreater(cec.version_key("160.0.1"), cec.version_key("160.0"))
        self.assertTrue(cec.is_behind("160.0", "160.0.1"))
        self.assertFalse(cec.is_behind("160.0.1", "160.0.1"))

    def test_numeric_not_lexical(self):
        self.assertGreater(cec.version_key("160.0"), cec.version_key("99.0.9"))
        self.assertGreater(cec.version_key("2.1.0-39"), cec.version_key("2.1.0-9"))

    def test_stable_ac_ignores_betas_and_release_field(self):
        versions = cec.metadata_versions(AC_METADATA)
        self.assertEqual(cec.highest(versions, cec.STABLE_AC), "160.0.1")

    def test_stable_ac_plain_major(self):
        versions = ["159.0.1", "160.0", "161.0b1"]
        self.assertEqual(cec.highest(versions, cec.STABLE_AC), "160.0")

    def test_highest_none_when_nothing_stable(self):
        self.assertIsNone(cec.highest(["161.0b1", "162.0a1"], cec.STABLE_AC))

    def test_libtorrent4j_skips_pre_releases(self):
        versions = cec.metadata_versions(LT4J_METADATA)
        self.assertEqual(cec.newest_libtorrent4j(versions), "2.1.0-39")

    def test_libtorrent4j_every_shape_on_maven_central(self):
        # The full version list of org.libtorrent4j:libtorrent4j on Maven Central, 2026-11-11.
        versions = REAL_LT4J_VERSIONS.split()
        self.assertEqual(cec.newest_libtorrent4j(versions), "2.1.0-39")

    def test_libtorrent4j_unknown_shape_is_an_error(self):
        for odd in ("2.1.1-SNAPSHOT", "2.2.0-40-preview", "v2.1.0-40", "2.1.0_40"):
            with self.assertRaises(cec.CheckError, msg=odd):
                cec.newest_libtorrent4j(["2.1.0-39", odd])

    def test_bad_xml_is_a_check_error(self):
        with self.assertRaises(cec.CheckError):
            cec.metadata_versions("<html>not maven</html")


class PomTest(unittest.TestCase):
    def test_reads_pinned_omni(self):
        self.assertEqual(cec.pom_dependency_version(POM_WITH_OMNI, "geckoview-omni"), "160.0.20261104181552")

    def test_missing_pom_entry(self):
        self.assertIsNone(cec.pom_dependency_version(POM_WITHOUT_OMNI, "geckoview-omni"))


class OpenSslTest(unittest.TestCase):
    def test_3_5_10_sorts_after_3_5_9(self):
        self.assertEqual(cec.openssl_highest_patch(OPENSSL_LS_REMOTE, "3.5"), "3.5.10")

    def test_ignores_pre_releases_peeled_and_other_series(self):
        output = "\n".join(line for line in OPENSSL_LS_REMOTE.splitlines() if "3.5.10" not in line)
        self.assertEqual(cec.openssl_highest_patch(output, "3.5"), "3.5.9")

    def test_no_tags(self):
        self.assertIsNone(cec.openssl_highest_patch("", "3.5"))


def write_repo(root, env_text, toml_text=TOML):
    os.makedirs(os.path.join(root, "gradle"))
    os.makedirs(os.path.join(root, "native", "libtorrent4j"))
    with open(os.path.join(root, "gradle", "libs.versions.toml"), "w", encoding="utf-8") as f:
        f.write(toml_text)
    with open(os.path.join(root, "native", "libtorrent4j", "versions.env"), "w", encoding="utf-8") as f:
        f.write(env_text)


class ParseFilesTest(unittest.TestCase):
    def test_toml_reads_versions_table_only(self):
        versions = cec.toml_versions(TOML)
        self.assertEqual(versions["mozillaAc"], "160.0")
        self.assertEqual(versions["geckoview"], "160.0.20261104181552")
        self.assertEqual(versions["libtorrent4j"], "2.1.0-39")
        self.assertNotIn("x", versions)

    def test_env(self):
        env = cec.env_values("# comment\nOPENSSL_VERSION=3.5.5\r\nOPENSSL_SERIES=\"3.5\"\n\n")
        self.assertEqual(env, {"OPENSSL_VERSION": "3.5.5", "OPENSSL_SERIES": "3.5"})

    def test_current_versions_from_repo_files(self):
        with tempfile.TemporaryDirectory() as root:
            write_repo(root, "LIBTORRENT4J_VERSION=2.1.0-39\nOPENSSL_VERSION=3.5.5\nOPENSSL_SERIES=3.5\n")
            current, series = cec.current_versions(root)
        self.assertEqual(series, "3.5")
        self.assertEqual(current, {"browser-engine-gecko": "160.0", "geckoview-omni": "160.0.20261104181552",
                                   "libtorrent4j": "2.1.0-39", "openssl": "3.5.5"})

    def test_missing_entry_is_a_check_error(self):
        with tempfile.TemporaryDirectory() as root:
            write_repo(root, "LIBTORRENT4J_VERSION=2.1.0-39\nOPENSSL_SERIES=3.5\n")
            with self.assertRaises(cec.CheckError):
                cec.current_versions(root)

    def test_missing_libtorrent4j_record_is_a_check_error(self):
        with tempfile.TemporaryDirectory() as root:
            write_repo(root, "OPENSSL_VERSION=3.5.5\nOPENSSL_SERIES=3.5\n")
            with self.assertRaises(cec.CheckError):
                cec.current_versions(root)

    def test_libtorrent4j_records_disagree_is_a_check_error(self):
        with tempfile.TemporaryDirectory() as root:
            write_repo(root, "LIBTORRENT4J_VERSION=2.1.0-38\nOPENSSL_VERSION=3.5.5\nOPENSSL_SERIES=3.5\n")
            with self.assertRaises(cec.CheckError) as caught:
                cec.current_versions(root)
        self.assertIn("2.1.0-38", str(caught.exception))


CURRENT = {"browser-engine-gecko": "160.0", "geckoview-omni": "160.0.20261104181552",
           "libtorrent4j": "2.1.0-39", "openssl": "3.5.5"}


class EvaluateTest(unittest.TestCase):
    def test_all_current_exits_0(self):
        newest = dict(CURRENT)
        lines, code = cec.report(cec.evaluate(CURRENT, newest), set())
        self.assertEqual(code, 0)
        self.assertEqual(len(lines), 4)
        self.assertTrue(all(line.endswith(" OK") for line in lines))

    def test_dot_release_is_behind(self):
        newest = dict(CURRENT, **{"browser-engine-gecko": "160.0.1", "geckoview-omni": "160.0.20261112000000"})
        rows = cec.evaluate(CURRENT, newest)
        self.assertEqual([r[3] for r in rows], [cec.BEHIND, cec.BEHIND, cec.OK, cec.OK])
        _, code = cec.report(rows, set())
        self.assertEqual(code, 1)

    def test_omni_mismatch_alone_is_behind(self):
        newest = dict(CURRENT, **{"geckoview-omni": "160.0.20261104999999"})
        _, code = cec.report(cec.evaluate(CURRENT, newest), set())
        self.assertEqual(code, 1)

    def test_warn_only_openssl(self):
        newest = dict(CURRENT, openssl="3.5.9")
        lines, code = cec.report(cec.evaluate(CURRENT, newest), {"openssl"})
        self.assertEqual(code, 0)
        self.assertIn("BEHIND (warning only)", lines[3])

    def test_warn_only_does_not_hide_others(self):
        newest = dict(CURRENT, openssl="3.5.9", libtorrent4j="2.1.0-40")
        _, code = cec.report(cec.evaluate(CURRENT, newest), {"openssl"})
        self.assertEqual(code, 1)


    def test_current_ahead_of_newest_is_could_not_check(self):
        for name, older in (("browser-engine-gecko", "159.0"), ("libtorrent4j", "2.1.0-38"), ("openssl", "3.5.4")):
            newest = dict(CURRENT, **{name: older})
            rows = cec.evaluate(CURRENT, newest)
            self.assertIn(cec.AHEAD, [r[3] for r in rows], name)
            lines, code = cec.report(rows, {"openssl"})
            self.assertEqual(code, 2, name)
            self.assertTrue(any("AHEAD" in line for line in lines))

    def test_ahead_wins_over_behind(self):
        newest = dict(CURRENT, **{"browser-engine-gecko": "160.0.1", "libtorrent4j": "2.1.0-38"})
        _, code = cec.report(cec.evaluate(CURRENT, newest), set())
        self.assertEqual(code, 2)

    def test_omni_is_never_ahead(self):
        newest = dict(CURRENT, **{"geckoview-omni": "160.0.20261101000000"})
        rows = cec.evaluate(CURRENT, newest)
        self.assertEqual(rows[1][3], cec.BEHIND)


class NetworkGuardTest(unittest.TestCase):
    def test_refuses_plain_http(self):
        with self.assertRaises(cec.CheckError):
            cec.fetch("http://maven.mozilla.org/")

    def test_redirect_to_http_refused_https_followed(self):
        handler = cec._HttpsOnlyRedirects()
        request = urllib.request.Request("https://repo1.maven.org/maven2/x/maven-metadata.xml")
        with self.assertRaises(cec.CheckError):
            handler.redirect_request(request, None, 302, "Found", {}, "http://example.invalid/metadata.xml")
        followed = handler.redirect_request(request, None, 301, "Moved", {}, "https://example.invalid/metadata.xml")
        self.assertEqual(followed.full_url, "https://example.invalid/metadata.xml")

    def test_http_exception_is_a_check_error(self):
        class BrokenOpener:
            def open(self, request, timeout):
                raise http.client.BadStatusLine("garbage")  # an HTTPException, not an OSError

        original = cec.urllib.request.build_opener
        cec.urllib.request.build_opener = lambda *handlers: BrokenOpener()
        try:
            with self.assertRaises(cec.CheckError) as caught:
                cec.fetch("https://repo1.maven.org/maven2/x/maven-metadata.xml")
        finally:
            cec.urllib.request.build_opener = original
        self.assertIn("BadStatusLine", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
