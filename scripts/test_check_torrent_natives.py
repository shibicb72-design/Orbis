#!/usr/bin/env python3
"""Offline tests for check_torrent_natives.py. Run: python3 -I scripts/test_check_torrent_natives.py"""

import contextlib
import hashlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("check_torrent_natives", os.path.join(_HERE, "check_torrent_natives.py"))
ctn = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ctn)

MACHINE = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86_64": (2, 62)}


def fake_so(abi, *strings):
    """An ELF header for the ABI's machine followed by the given strings."""
    elf_class, machine = MACHINE[abi]
    header = b"\x7fELF" + bytes([elf_class, 1, 1]) + b"\0" * 9 + struct.pack("<HH", 3, machine)
    return header + b"\0" * 44 + b"\0".join(s.encode() for s in strings) + b"\0"


GOOD = ("OpenSSL 3.5.9 29 Sep 2026", "libtorrent/2.1.0.0")
OLD = ("OpenSSL 3.5.5 27 Jan 2026", "libtorrent/2.1.0.0")


class ScanTest(unittest.TestCase):
    def test_finds_versions(self):
        openssl, lt = ctn.scan(fake_so("arm64-v8a", "x", *GOOD, "OpenSSL 3.5.9 29 Sep 2026"))
        self.assertEqual(openssl, ["3.5.9 29 Sep 2026"])
        self.assertEqual(lt, ["2.1.0.0"])

    def test_ignores_non_version_mentions(self):
        openssl, lt = ctn.scan(b"OpenSSL error\0OpenSSL 3.x\0libtorrent/\0libtorrent/2.1\0")
        self.assertEqual((openssl, lt), ([], []))

    def test_elf_abi(self):
        for abi in MACHINE:
            self.assertEqual(ctn.elf_abi(fake_so(abi)), abi)
        self.assertIsNone(ctn.elf_abi(b"PK\x03\x04" + b"\0" * 40))

    def test_archive_layouts(self):
        names = ["lib/x86_64/libtorrent4j.so", "base/lib/arm64-v8a/libtorrent4j.so", "lib/x86_64/libxul.so",
                 "assets/lib/x86/libtorrent4j.so", "base/lib/x86_64/libtorrent4j.so.debug"]
        found = ctn.libraries_in_archive(names, lambda name: name.encode())
        self.assertEqual([abi for abi, _ in found], ["arm64-v8a", "x86_64"])


def libs(*entries):
    return [(abi, abi) + ctn.scan(fake_so(abi, *strings)) for abi, strings in entries]


class EvaluateTest(unittest.TestCase):
    def test_pass(self):
        _, problems = ctn.evaluate(libs(("arm64-v8a", GOOD), ("x86_64", GOOD)), "3.5.9", "2.1.0.0",
                                   ["arm64-v8a", "x86_64"])
        self.assertEqual(problems, [])

    def test_below_minimum(self):
        _, problems = ctn.evaluate(libs(("arm64-v8a", OLD)), "3.5.9", "2.1.0.0", ["arm64-v8a"])
        self.assertEqual(len(problems), 1)
        self.assertIn("below 3.5.9", problems[0])

    def test_3_5_10_is_above_3_5_9(self):
        newer = ("OpenSSL 3.5.10 1 Dec 2026", "libtorrent/2.1.0.0")
        _, problems = ctn.evaluate(libs(("arm64-v8a", newer)), "3.5.9", "2.1.0.0", ["arm64-v8a"])
        self.assertEqual(problems, [])

    def test_wrong_libtorrent(self):
        _, problems = ctn.evaluate(libs(("x86_64", ("OpenSSL 3.5.9 29 Sep 2026", "libtorrent/2.1.2.0"))),
                                   "3.5.9", "2.1.0.0", [])
        self.assertIn("libtorrent 2.1.2.0, expected 2.1.0.0", problems[0])

    def test_missing_strings(self):
        _, problems = ctn.evaluate(libs(("x86_64", ("nothing",))), "3.5.9", "2.1.0.0", [])
        self.assertEqual(len(problems), 2)

    def test_two_openssl_versions_in_one_library(self):
        _, problems = ctn.evaluate(libs(("x86_64", GOOD + ("OpenSSL 3.5.5 27 Jan 2026",))), None, None, [])
        self.assertTrue(any("2 distinct OpenSSL" in p for p in problems))

    def test_two_openssl_versions_across_libraries(self):
        _, problems = ctn.evaluate(libs(("x86_64", GOOD), ("arm64-v8a", OLD)), None, None, [])
        self.assertTrue(any("across the libraries" in p for p in problems))

    def test_listed_abi_missing(self):
        _, problems = ctn.evaluate(libs(("x86_64", GOOD)), "3.5.9", "2.1.0.0", ["x86_64", "armeabi-v7a"])
        self.assertEqual(problems, ["armeabi-v7a: no libtorrent4j.so in the inputs"])

    def test_no_library_at_all(self):
        _, problems = ctn.evaluate([], None, None, [])
        self.assertEqual(problems, ["no libtorrent4j.so in the inputs"])


def sha(data):
    return hashlib.sha256(data).hexdigest()


class ChecksumsTest(unittest.TestCase):
    def test_parse_repository_layout(self):
        text = ("# header\n\n"
                "%s  app/src/main/jniLibs/arm64-v8a/libtorrent4j.so\n"
                "%s *app/src/main/jniLibs/x86_64/libtorrent4j.so\n"
                "%s  app/src/main/jniLibs/x86_64/libother.so\n") % ("a" * 64, "b" * 64, "c" * 64)
        self.assertEqual(ctn.parse_checksums(text), {"arm64-v8a": "a" * 64, "x86_64": "b" * 64})

    def test_parse_the_committed_file(self):
        path = os.path.join(_HERE, os.pardir, "native", "libtorrent4j", "CHECKSUMS.sha256")
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(sorted(ctn.parse_checksums(handle.read())), ["arm64-v8a", "armeabi-v7a", "x86_64"])

    def test_parse_rejects(self):
        for text in ("nothing listed\n", "# only a comment\n",
                     "%s  libtorrent4j.so\n" % ("a" * 64),
                     "%s  a/x86_64/libtorrent4j.so\n%s  b/x86_64/libtorrent4j.so\n" % ("a" * 64, "b" * 64),
                     "%s  a/x86_64/libtorrent4j.so\n" % ("A" * 64)):
            with self.assertRaises(ValueError, msg=text):
                ctn.parse_checksums(text)

    def test_check_hashes(self):
        expected = {"x86_64": "a" * 64}
        self.assertEqual(ctn.check_hashes([("s", "x86_64", "a" * 64)], expected)[1], [])
        self.assertIn("expected " + "a" * 64, ctn.check_hashes([("s", "x86_64", "b" * 64)], expected)[1][0])
        self.assertIn("no SHA-256 listed", ctn.check_hashes([("s", "arm64-v8a", "a" * 64)], expected)[1][0])


class MainTest(unittest.TestCase):
    """The three input layouts end to end."""

    def run_main(self, files, *args):
        with tempfile.TemporaryDirectory() as root:
            paths = []
            for name, content in files:
                path = os.path.join(root, name)
                if isinstance(content, dict):
                    with zipfile.ZipFile(path, "w") as archive:
                        for entry, data in content.items():
                            archive.writestr(entry, data)
                else:
                    with open(path, "wb") as handle:
                        handle.write(content)
                paths.append(path)
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = ctn.main(paths + list(args))
            return code, out.getvalue()

    def test_bare_so(self):
        code, out = self.run_main([("libtorrent4j.so", fake_so("armeabi-v7a", *GOOD))],
                                  "--min-openssl", "3.5.9", "--libtorrent", "2.1.0.0", "--abis", "armeabi-v7a")
        self.assertEqual(code, 0, out)

    def test_apk(self):
        apk = {"lib/x86_64/libtorrent4j.so": fake_so("x86_64", *GOOD), "lib/x86_64/libxul.so": b"x"}
        code, out = self.run_main([("app.apk", apk)], "--min-openssl", "3.5.9", "--abis", "x86_64")
        self.assertEqual(code, 0, out)

    def test_aab_all_abis(self):
        aab = {"base/lib/%s/libtorrent4j.so" % abi: fake_so(abi, *GOOD) for abi in MACHINE}
        code, out = self.run_main([("app.aab", aab)], "--min-openssl", "3.5.9", "--libtorrent", "2.1.0.0",
                                  "--abis", "arm64-v8a,armeabi-v7a,x86_64")
        self.assertEqual(code, 0, out)

    def test_apk_without_library_fails(self):
        apk = {"lib/arm64-v8a/libxul.so": b"x"}
        code, out = self.run_main([("app.apk", apk)], "--min-openssl", "3.5.9", "--abis", "arm64-v8a")
        self.assertEqual(code, 1)
        self.assertIn("arm64-v8a: no libtorrent4j.so", out)

    def test_old_openssl_fails(self):
        code, _ = self.run_main([("libtorrent4j.so", fake_so("arm64-v8a", *OLD))], "--min-openssl", "3.5.9")
        self.assertEqual(code, 1)

    def test_unreadable_input(self):
        code, _ = self.run_main([("notes.txt", b"hello")])
        self.assertEqual(code, 2)

    def checksums_file(self, root, entries):
        path = os.path.join(root, "CHECKSUMS.sha256")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("# run 1\n")
            for abi, data in entries:
                handle.write("%s  app/src/main/jniLibs/%s/libtorrent4j.so\n" % (sha(data), abi))
        return path

    def test_checksums_match_in_apk_and_aab(self):
        libs_by_abi = {abi: fake_so(abi, *GOOD) for abi in MACHINE}
        with tempfile.TemporaryDirectory() as root:
            sums = self.checksums_file(root, libs_by_abi.items())
            apk = {"lib/x86_64/libtorrent4j.so": libs_by_abi["x86_64"]}
            code, out = self.run_main([("app.apk", apk)], "--abis", "x86_64", "--checksums", sums)
            self.assertEqual(code, 0, out)
            self.assertIn("matches", out)
            aab = {"base/lib/%s/libtorrent4j.so" % abi: data for abi, data in libs_by_abi.items()}
            code, out = self.run_main([("app.aab", aab)], "--abis", "arm64-v8a,armeabi-v7a,x86_64",
                                      "--checksums", sums)
            self.assertEqual(code, 0, out)

    def test_checksums_mismatch_fails(self):
        # Same version strings, other bytes: the version check alone passes, the checksum check does not.
        committed = fake_so("arm64-v8a", *GOOD)
        packaged = fake_so("arm64-v8a", "rebuilt", *GOOD)
        with tempfile.TemporaryDirectory() as root:
            sums = self.checksums_file(root, [("arm64-v8a", committed)])
            apk = {"lib/arm64-v8a/libtorrent4j.so": packaged}
            code, out = self.run_main([("app.apk", apk)], "--min-openssl", "3.5.9", "--checksums", sums)
            self.assertEqual(code, 1, out)
            self.assertIn("FAIL: arm64-v8a", out)
            code, out = self.run_main([("app.apk", apk)], "--min-openssl", "3.5.9")
            self.assertEqual(code, 0, out)

    def test_checksums_abi_not_listed_fails(self):
        with tempfile.TemporaryDirectory() as root:
            sums = self.checksums_file(root, [("arm64-v8a", fake_so("arm64-v8a", *GOOD))])
            code, out = self.run_main([("libtorrent4j.so", fake_so("x86_64", *GOOD))], "--checksums", sums)
            self.assertEqual(code, 1, out)
            self.assertIn("no SHA-256 listed", out)

    def test_checksums_unreadable_is_usage_error(self):
        with tempfile.TemporaryDirectory() as root:
            missing = os.path.join(root, "CHECKSUMS.sha256")
            code, out = self.run_main([("libtorrent4j.so", fake_so("x86_64", *GOOD))], "--checksums", missing)
        self.assertEqual(code, 2, out)

    def test_checksums_malformed_is_usage_error(self):
        with tempfile.TemporaryDirectory() as root:
            bad = os.path.join(root, "CHECKSUMS.sha256")
            with open(bad, "w", encoding="utf-8") as handle:
                handle.write("not-a-hash  app/src/main/jniLibs/x86_64/libtorrent4j.so\n")
            code, out = self.run_main([("libtorrent4j.so", fake_so("x86_64", *GOOD))], "--checksums", bad)
        self.assertEqual(code, 2, out)
        self.assertIn("could not check", out)

    def test_json_output(self):
        with tempfile.TemporaryDirectory() as root:
            so, out_json = os.path.join(root, "libtorrent4j.so"), os.path.join(root, "v.json")
            with open(so, "wb") as handle:
                handle.write(fake_so("x86_64", *GOOD))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ctn.main([so, "--json", out_json]), 0)
            with open(out_json, encoding="utf-8") as handle:
                text = handle.read()
        self.assertIn('"3.5.9 29 Sep 2026"', text)
        self.assertIn('"2.1.0.0"', text)


if __name__ == "__main__":
    unittest.main()
