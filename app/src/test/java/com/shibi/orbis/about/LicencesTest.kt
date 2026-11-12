package com.shibi.orbis.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {
    // Unit tests run in the module directory (app/).
    private val assets = File("src/main/assets")

    private fun firstLine(kind: LicenceKind): String = File(assets, kind.assetPath).readLines().first { it.isNotBlank() }.trim()

    @Test
    fun everyLicenceHasItsVerbatimText() {
        val expected =
            mapOf(
                LicenceKind.BSD_3_CLAUSE to "Copyright (c) 2003-2020, Arvid Norberg",
                LicenceKind.MIT to "Copyright (c) 2018-2025 Alden Torres",
                LicenceKind.BSL_1_0 to "Boost Software License - Version 1.0 - August 17th, 2003",
                LicenceKind.APACHE_2_0 to "Apache License",
                LicenceKind.MPL_2_0 to "Mozilla Public License Version 2.0",
                LicenceKind.MPL_2_0_LIBDATACHANNEL to "Mozilla Public License Version 2.0",
                LicenceKind.MPL_2_0_LIBJUICE to "Mozilla Public License Version 2.0",
                LicenceKind.BSD_3_CLAUSE_USRSCTP to "Copyright (c) 2015,  Randall Stewart and Michael Tuexen",
                LicenceKind.MIT_PLOG to "MIT License",
                LicenceKind.BSD_3_CLAUSE_TRY_SIGNAL to "BSD 3-Clause License",
            )
        assertEquals(LicenceKind.entries.toSet(), expected.keys)
        for (entry in Licences.entries) assertTrue(entry.name, File(assets, entry.licence.assetPath).isFile)
        for ((kind, line) in expected) assertEquals(kind.name, line, firstLine(kind))
        val bsd = File(assets, LicenceKind.BSD_3_CLAUSE.assetPath).readText()
        assertTrue(bsd.contains("Redistribution and use in source and binary forms"))
        assertTrue(File(assets, LicenceKind.MIT.assetPath).readText().contains("Permission is hereby granted, free of charge"))
    }

    @Test
    fun theOwnersFourAndWhatTheAppShipsAreListed() {
        assertEquals(
            listOf(
                "libtorrent",
                "libtorrent4j",
                "Boost",
                "OpenSSL",
                "libdatachannel",
                "libjuice",
                "usrsctp",
                "plog",
                "try_signal",
                "GeckoView / Android Components",
                "Readability",
                "Firefox webcompat: YouTube picture-in-picture fix",
                "AndroidX / Kotlin",
                "ZXing",
            ),
            Licences.entries.map { it.name },
        )
        val byName = Licences.entries.associateBy { it.name }
        assertEquals(LicenceKind.BSD_3_CLAUSE, byName.getValue("libtorrent").licence)
        assertEquals(LicenceKind.MIT, byName.getValue("libtorrent4j").licence)
        assertEquals(LicenceKind.BSL_1_0, byName.getValue("Boost").licence)
        assertEquals(LicenceKind.APACHE_2_0, byName.getValue("OpenSSL").licence)
        assertEquals(LicenceKind.MPL_2_0, byName.getValue("GeckoView / Android Components").licence)
        assertEquals(LicenceKind.MPL_2_0_LIBDATACHANNEL, byName.getValue("libdatachannel").licence)
        assertEquals(LicenceKind.MPL_2_0_LIBJUICE, byName.getValue("libjuice").licence)
        assertEquals(LicenceKind.BSD_3_CLAUSE_USRSCTP, byName.getValue("usrsctp").licence)
        assertEquals(LicenceKind.MIT_PLOG, byName.getValue("plog").licence)
        assertEquals(LicenceKind.BSD_3_CLAUSE_TRY_SIGNAL, byName.getValue("try_signal").licence)
        // Every licence text is shown by some component.
        assertEquals(LicenceKind.entries.toSet(), Licences.entries.map { it.licence }.toSet())
    }

    @Test
    fun theBsdAndMitTextsCarryTheirUpstreamCopyrightLines() {
        // Exactly as in each upstream LICENSE file (usrsctp has two spaces after the comma).
        val expected =
            mapOf(
                LicenceKind.BSD_3_CLAUSE_USRSCTP to "Copyright (c) 2015,  Randall Stewart and Michael Tuexen",
                LicenceKind.MIT_PLOG to "Copyright (c) 2022 Sergey Podobry",
                LicenceKind.BSD_3_CLAUSE_TRY_SIGNAL to "Copyright (c) 2016, Arvid Norberg",
            )
        for ((kind, line) in expected) assertTrue(kind.name, File(assets, kind.assetPath).readLines().contains(line))
        for (kind in listOf(LicenceKind.BSD_3_CLAUSE_USRSCTP, LicenceKind.BSD_3_CLAUSE_TRY_SIGNAL)) {
            val text = File(assets, kind.assetPath).readText()
            assertTrue(kind.name, text.contains("All rights reserved."))
            assertTrue(kind.name, text.contains("Redistributions in binary form must reproduce the above copyright notice"))
        }
        val plog = File(assets, LicenceKind.MIT_PLOG.assetPath).readText()
        assertTrue(plog.contains("The above copyright notice and this permission notice shall be included"))
    }

    @Test
    fun theWebTorrentLibrariesPointAtTheCommitsOfOurNativesBuild() {
        // The submodule commits the torrent natives were built from, as native/libtorrent4j/build.sh recorded them.
        val expected =
            mapOf(
                "libdatachannel" to ("libdatachannel" to "https://github.com/paullouisageneau/libdatachannel"),
                "libjuice" to ("libdatachannel/deps/libjuice" to "https://github.com/paullouisageneau/libjuice"),
                "usrsctp" to ("libdatachannel/deps/usrsctp" to "https://github.com/paullouisageneau/usrsctp"),
                "plog" to ("libdatachannel/deps/plog" to "https://github.com/SergiusTheBest/plog"),
                "try_signal" to ("try_signal" to "https://github.com/arvidn/try_signal"),
            )
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            val info = File("../native/libtorrent4j/BUILDINFO-$abi.json").readText()
            for ((name, where) in expected) {
                val (path, repo) = where
                val line = Regex("\"([0-9a-f]{40}) " +Regex.escape("swig/deps/libtorrent/deps/$path") + " ")
                val commit = line.find(info)!!.groupValues[1]
                val entry = Licences.entries.single { it.name == name }
                assertEquals("$abi $name", "$repo/tree/$commit", entry.source)
                // The version says which commit, except libjuice's, which is exactly tag v1.7.0.
                if (name != "libjuice") assertTrue("$abi $name", entry.version!!.contains("(${commit.take(7)})"))
            }
        }
        assertEquals("1.7.0", Licences.entries.single { it.name == "libjuice" }.version)
        // MPL-2.0 3.2: say where the source is. Every MPL component inside the natives has its source line.
        val mpl = Licences.entries.filter { it.licence.family == LicenceFamily.MPL_2_0 && it.note == LicenceNote.INSIDE_LIBTORRENT4J }
        assertEquals(listOf("libdatachannel", "libjuice"), mpl.map { it.name })
        for (entry in mpl) assertTrue(entry.name, entry.source!!.startsWith("https://github.com/"))
    }

    @Test
    fun versionsMatchTheVersionCatalog() {
        val toml = File("../gradle/libs.versions.toml").readText()

        fun version(key: String): String = Regex("""(?m)^$key\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertEquals(version("libtorrent4j"), Licences.LIBTORRENT4J_VERSION)
        assertEquals(version("mozillaAc"), Licences.ANDROID_COMPONENTS_VERSION)
        assertEquals(version("zxing"), Licences.ZXING_VERSION)
    }

    @Test
    fun theOpenSslVersionShownIsTheBundledOne() {
        val env = File("../native/libtorrent4j/versions.env").readText()
        val bundled = Regex("""(?m)^OPENSSL_VERSION=(\S+)""").find(env)!!.groupValues[1]
        assertEquals(bundled, Licences.OPENSSL_VERSION)
        assertEquals(bundled, Licences.entries.single { it.name == "OpenSSL" }.version)
    }
}
