#!/usr/bin/env python3
"""Offline tests for check_extension_versions.py (temporary git repository). Run: python3 -I scripts/test_check_extension_versions.py"""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("check_extension_versions", os.path.join(_HERE, "check_extension_versions.py"))
cev = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(cev)

EXT = cev.EXT_DIR


def _manifest(version):
    return json.dumps({"manifest_version": 2, "name": "x", "version": version})


class CheckExtensionVersionsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = self._tmp.name
        self.addCleanup(self._tmp.cleanup)
        self.git("init", "-q")
        self.write("compat/manifest.json", _manifest("1.0"))
        self.write("compat/content.js", "a")
        self.write("darken/manifest.json", _manifest("2.0"))
        self.write("darken/content.js", "b")
        self.commit()
        self.base = self.git("rev-parse", "HEAD").stdout.strip()

    def git(self, *args):
        return subprocess.run(
            ["git", "-C", self.repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid",
             "-c", "commit.gpgsign=false", *args],
            capture_output=True, text=True, check=True)

    def write(self, rel, text):
        path = os.path.join(self.repo, *(EXT + "/" + rel).split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", newline="\n") as f:
            f.write(text)

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "c")

    def result(self):
        return cev.check(self.repo, self.base)

    def test_changed_file_same_version_fails(self):
        self.write("compat/content.js", "changed")
        self.commit()
        failures = self.result()
        self.assertEqual(len(failures), 1)
        self.assertIn("compat", failures[0])
        self.assertIn("1.0", failures[0])
        self.assertIn("raise the version in manifest.json", failures[0])

    def test_changed_file_raised_version_passes(self):
        self.write("compat/content.js", "changed")
        self.write("compat/manifest.json", _manifest("1.1"))
        self.commit()
        self.assertEqual(self.result(), [])

    def test_untouched_passes(self):
        self.write("../../java/Other.kt", "x")
        self.commit()
        self.assertEqual(self.result(), [])

    def test_new_folder_passes(self):
        self.write("fresh/manifest.json", _manifest("1.0"))
        self.write("fresh/content.js", "n")
        self.commit()
        self.assertEqual(self.result(), [])

    def test_deleted_folder_passes(self):
        self.git("rm", "-rq", EXT + "/darken")
        self.commit()
        self.assertEqual(self.result(), [])

    def test_added_file_same_version_fails(self):
        self.write("darken/extra.js", "e")
        self.commit()
        self.assertEqual(len(self.result()), 1)

    def test_only_the_changed_folder_fails(self):
        self.write("compat/content.js", "changed")
        self.write("darken/manifest.json", _manifest("2.1"))
        self.write("darken/content.js", "also changed")
        self.commit()
        failures = self.result()
        self.assertEqual(len(failures), 1)
        self.assertIn("compat", failures[0])

    def test_main_exit_codes(self):
        self.write("compat/content.js", "changed")
        self.commit()
        self.assertEqual(cev.main(["--base", self.base, "--repo", self.repo]), 1)
        self.assertEqual(cev.main(["--base", "no-such-ref", "--repo", self.repo]), 2)
        self.assertEqual(cev.main(["--base", "HEAD", "--repo", self.repo]), 0)


if __name__ == "__main__":
    unittest.main()
