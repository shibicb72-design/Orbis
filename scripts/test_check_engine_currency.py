#!/usr/bin/env python3
"""Offline tests for check_engine_currency.py (no network). Run: python3 -I scripts/test_check_engine_currency.py"""

import importlib.util
import os
import tempfile
import unittest

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
        self.assertEqual(cec.highest(versions, cec.STABLE_LIBTORRENT4J), "2.1.0-39")

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
            os.makedirs(os.path.join(root, "gradle"))
            os.makedirs(os.path.join(root, "native", "libtorrent4j"))
            with open(os.path.join(root, "gradle", "libs.versions.toml"), "w", encoding="utf-8") as f:
                f.write(TOML)
            with open(os.path.join(root, "native", "libtorrent4j", "versions.env"), "w", encoding="utf-8") as f:
                f.write("OPENSSL_VERSION=3.5.5\nOPENSSL_SERIES=3.5\n")
            current, series = cec.current_versions(root)
        self.assertEqual(series, "3.5")
        self.assertEqual(current, {"browser-engine-gecko": "160.0", "geckoview-omni": "160.0.20261104181552",
                                   "libtorrent4j": "2.1.0-39", "openssl": "3.5.5"})

    def test_missing_entry_is_a_check_error(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "gradle"))
            os.makedirs(os.path.join(root, "native", "libtorrent4j"))
            with open(os.path.join(root, "gradle", "libs.versions.toml"), "w", encoding="utf-8") as f:
                f.write(TOML)
            with open(os.path.join(root, "native", "libtorrent4j", "versions.env"), "w", encoding="utf-8") as f:
                f.write("OPENSSL_SERIES=3.5\n")
            with self.assertRaises(cec.CheckError):
                cec.current_versions(root)


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
        self.assertEqual([r[3] for r in rows], [True, True, False, False])
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


class NetworkGuardTest(unittest.TestCase):
    def test_refuses_plain_http(self):
        with self.assertRaises(cec.CheckError):
            cec.fetch("http://maven.mozilla.org/")


if __name__ == "__main__":
    unittest.main()
