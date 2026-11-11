#!/usr/bin/env python3
"""Offline tests for compare_torrent_natives.py. Run: python3 -I scripts/test_compare_torrent_natives.py"""

import contextlib
import hashlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest
import urllib.request
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("compare_torrent_natives",
                                               os.path.join(_HERE, "compare_torrent_natives.py"))
ctn = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ctn)

EM_ARM, EM_AARCH64 = 40, 183
GLOBAL, WEAK, LOCAL = 1, 2, 0
DEFAULT, HIDDEN = 0, 2


def make_elf(is64, machine, symbols, needed, soname="libtorrent4j.so"):
    """A minimal little-endian ELF shared object with .dynstr, .dynsym, .dynamic and .shstrtab.

    symbols: [(name, defined, bind, visibility)].
    """
    dynstr = bytearray(b"\0")

    def add(name):
        offset = len(dynstr)
        dynstr.extend(name.encode() + b"\0")
        return offset

    sym_fmt = "<IBBHQQ" if is64 else "<IIIBBH"
    dynsym = bytearray(struct.calcsize(sym_fmt))  # entry 0 is the null symbol
    for name, defined, bind, vis in symbols:
        st_name, st_info, st_shndx = add(name), (bind << 4) | 2, 5 if defined else 0
        if is64:
            dynsym += struct.pack(sym_fmt, st_name, st_info, vis, st_shndx, 0x1000, 0)
        else:
            dynsym += struct.pack(sym_fmt, st_name, 0x1000, 0, st_info, vis, st_shndx)

    dyn_fmt = "<qQ" if is64 else "<iI"
    dynamic = bytearray()
    for lib in needed:
        dynamic += struct.pack(dyn_fmt, 1, add(lib))
    if soname:
        dynamic += struct.pack(dyn_fmt, 14, add(soname))
    dynamic += struct.pack(dyn_fmt, 0, 0)

    shstrtab = b"\0.dynstr\0.dynsym\0.dynamic\0.shstrtab\0"
    ehsize = 64 if is64 else 52
    body = bytearray()
    offsets = []
    for blob in (bytes(dynstr), bytes(dynsym), bytes(dynamic), shstrtab):
        offsets.append(ehsize + len(body))
        body += blob
        body += b"\0" * (-len(body) % 8)
    shoff = ehsize + len(body)

    sh_fmt = "<IIQQQQIIQQ" if is64 else "<IIIIIIIIII"
    sizes = (len(dynstr), len(dynsym), len(dynamic), len(shstrtab))
    # index: 0 null, 1 .dynstr (3 STRTAB), 2 .dynsym (11, link 1), 3 .dynamic (6, link 1), 4 .shstrtab
    headers = struct.pack(sh_fmt, *([0] * 10))
    for name, sh_type, i, link in ((1, 3, 0, 0), (9, 11, 1, 1), (17, 6, 2, 1), (26, 3, 3, 0)):
        headers += struct.pack(sh_fmt, name, sh_type, 0, 0, offsets[i], sizes[i], link, 0, 1, 0)
    shentsize = struct.calcsize(sh_fmt)

    ident = b"\x7fELF" + bytes([2 if is64 else 1, 1, 1]) + b"\0" * 9
    if is64:
        header = ident + struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, 0, shoff, 0, ehsize, 0, 0,
                                     shentsize, 5, 4)
    else:
        header = ident + struct.pack("<HHIIIIIHHHHHH", 3, machine, 1, 0, 0, shoff, 0, ehsize, 0, 0,
                                     shentsize, 5, 4)
    assert len(header) == ehsize
    return bytes(header + body + headers)


JNI_A = "Java_org_libtorrent4j_swig_libtorrent_1jni_new_1session"
JNI_B = "Java_org_libtorrent4j_swig_libtorrent_1jni_delete_1session"
NEEDED = ["libdl.so", "libm.so", "libc.so"]
UPSTREAM_SYMBOLS = [(JNI_A, True, GLOBAL, DEFAULT), (JNI_B, True, GLOBAL, DEFAULT), ("SSL_new", True, GLOBAL, DEFAULT),
                    ("memcpy", False, GLOBAL, DEFAULT)]

# Fixture 1: an upstream-like 64-bit library. Fixture 2: a 32-bit ARM library.
FIXTURE_64 = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS, NEEDED)
FIXTURE_32 = make_elf(False, EM_ARM, UPSTREAM_SYMBOLS, NEEDED)


class ReadElfTest(unittest.TestCase):
    def test_reads_64_bit_fixture(self):
        info = ctn.read_elf(FIXTURE_64)
        self.assertEqual(info["class"], 64)
        self.assertEqual(info["machine"], EM_AARCH64)
        self.assertEqual(info["exported"], {JNI_A, JNI_B, "SSL_new"})
        self.assertEqual(info["needed"], NEEDED)
        self.assertEqual(info["soname"], "libtorrent4j.so")

    def test_reads_32_bit_fixture(self):
        info = ctn.read_elf(FIXTURE_32)
        self.assertEqual(info["class"], 32)
        self.assertEqual(info["machine"], EM_ARM)
        self.assertEqual(info["exported"], {JNI_A, JNI_B, "SSL_new"})
        self.assertEqual(info["needed"], NEEDED)

    def test_undefined_local_and_hidden_symbols_are_not_exported(self):
        data = make_elf(True, EM_AARCH64, [(JNI_A, True, GLOBAL, DEFAULT), ("undef", False, GLOBAL, DEFAULT),
                                           ("local", True, LOCAL, DEFAULT), ("hidden", True, GLOBAL, HIDDEN),
                                           ("weak", True, WEAK, DEFAULT)], NEEDED)
        self.assertEqual(ctn.read_elf(data)["exported"], {JNI_A, "weak"})

    def test_not_elf(self):
        with self.assertRaises(ctn.CompareError):
            ctn.read_elf(b"PK\x03\x04" + b"\0" * 100)

    def test_truncated(self):
        with self.assertRaises(ctn.CompareError):
            ctn.read_elf(FIXTURE_64[:200])


class CompareTest(unittest.TestCase):
    def run_compare(self, ours_bytes, upstream_bytes=FIXTURE_64):
        return ctn.compare(ctn.read_elf(ours_bytes), ctn.read_elf(upstream_bytes), len(ours_bytes), len(upstream_bytes))

    def test_identical_passes(self):
        lines, failures = self.run_compare(FIXTURE_64)
        self.assertEqual(failures, [])
        self.assertTrue(any("same set" in line for line in lines))

    def test_non_jni_export_difference_is_reported_only(self):
        ours = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS + [("SSL_new_ex", True, GLOBAL, DEFAULT)], NEEDED)
        _, failures = self.run_compare(ours)
        self.assertEqual(failures, [])

    def test_missing_jni_symbol_fails(self):
        ours = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS[1:], NEEDED)
        lines, failures = self.run_compare(ours)
        self.assertIn("Java_org_libtorrent4j_swig_* symbol sets differ", failures)
        self.assertTrue(any(("only in upstream: " + JNI_A) in line for line in lines))

    def test_extra_jni_symbol_fails(self):
        extra = "Java_org_libtorrent4j_swig_libtorrent_1jni_extra"
        ours = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS + [(extra, True, GLOBAL, DEFAULT)], NEEDED)
        _, failures = self.run_compare(ours)
        self.assertIn("Java_org_libtorrent4j_swig_* symbol sets differ", failures)

    def test_jni_onload_in_one_only_fails(self):
        ours = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS + [("JNI_OnLoad", True, GLOBAL, DEFAULT)], NEEDED)
        _, failures = self.run_compare(ours)
        self.assertIn("JNI_OnLoad present in one library only", failures)

    def test_needed_difference_fails(self):
        ours = make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS, NEEDED + ["liblog.so"])
        _, failures = self.run_compare(ours)
        self.assertIn("DT_NEEDED list differs", failures)

    def test_class_and_machine_difference_fails(self):
        _, failures = self.run_compare(FIXTURE_32)
        self.assertIn("ELF class differs", failures)
        self.assertIn("ELF machine differs", failures)

    def test_size_change(self):
        self.assertEqual(ctn.size_change(110, 100), "+10.00%")
        self.assertEqual(ctn.size_change(95, 100), "-5.00%")


def make_jar(abi, so_bytes):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        jar.writestr("lib/%s/libtorrent4j.so" % abi, so_bytes)
    return buffer.getvalue()


class UpstreamJarTest(unittest.TestCase):
    def setUp(self):
        self.jar = make_jar("arm64-v8a", FIXTURE_64)
        self.jar_sha = hashlib.sha256(self.jar).hexdigest()
        self.so_sha = hashlib.sha256(FIXTURE_64).hexdigest()

    def test_hashes_match(self):
        self.assertEqual(ctn.upstream_library(self.jar, "arm64-v8a", self.jar_sha, self.so_sha), FIXTURE_64)

    def test_jar_hash_mismatch(self):
        with self.assertRaises(ctn.CompareError):
            ctn.upstream_library(self.jar, "arm64-v8a", "0" * 64, self.so_sha)

    def test_inner_hash_mismatch(self):
        with self.assertRaises(ctn.CompareError):
            ctn.upstream_library(self.jar, "arm64-v8a", self.jar_sha, "0" * 64)

    def test_wrong_abi(self):
        with self.assertRaises(ctn.CompareError):
            ctn.upstream_library(self.jar, "x86_64", self.jar_sha, self.so_sha)


class MainTest(unittest.TestCase):
    def run_main(self, ours_bytes):
        jar = make_jar("arm64-v8a", FIXTURE_64)
        with tempfile.TemporaryDirectory() as root:
            env = os.path.join(root, "build.env")
            with open(env, "w", encoding="utf-8") as handle:
                handle.write("UPSTREAM_JAR_VERSION=2.1.0-39\nUPSTREAM_JAR_SHA256_arm64-v8a=%s\n"
                             "UPSTREAM_SO_SHA256_arm64-v8a=%s\n" % (hashlib.sha256(jar).hexdigest(),
                                                                   hashlib.sha256(FIXTURE_64).hexdigest()))
            jar_path, ours_path = os.path.join(root, "up.jar"), os.path.join(root, "ours.so")
            with open(jar_path, "wb") as handle:
                handle.write(jar)
            with open(ours_path, "wb") as handle:
                handle.write(ours_bytes)
            with contextlib.redirect_stdout(io.StringIO()):
                return ctn.main([ours_path, "--abi", "arm64-v8a", "--build-env", env, "--upstream-jar", jar_path])

    def test_main_ok(self):
        self.assertEqual(self.run_main(FIXTURE_64), 0)

    def test_main_difference(self):
        self.assertEqual(self.run_main(make_elf(True, EM_AARCH64, UPSTREAM_SYMBOLS[1:], NEEDED)), 1)

    def test_main_unreadable_ours(self):
        self.assertEqual(self.run_main(b"not an elf file at all, just text padding" * 3), 2)


class NetworkGuardTest(unittest.TestCase):
    def test_refuses_plain_http(self):
        with self.assertRaises(ctn.CompareError):
            ctn.fetch("http://repo1.maven.org/")

    def test_redirect_to_http_refused_https_followed(self):
        handler = ctn._HttpsOnlyRedirects()
        request = urllib.request.Request("https://repo1.maven.org/a.jar")
        with self.assertRaises(ctn.CompareError):
            handler.redirect_request(request, None, 302, "Found", {}, "http://example.invalid/a.jar")
        followed = handler.redirect_request(request, None, 302, "Found", {}, "https://example.invalid/a.jar")
        self.assertEqual(followed.full_url, "https://example.invalid/a.jar")


if __name__ == "__main__":
    unittest.main()
