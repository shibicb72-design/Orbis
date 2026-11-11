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
            listOf("libtorrent", "libtorrent4j", "Boost", "OpenSSL", "GeckoView / Android Components", "Readability", "AndroidX / Kotlin", "ZXing"),
            Licences.entries.map { it.name },
        )
        val byName = Licences.entries.associateBy { it.name }
        assertEquals(LicenceKind.BSD_3_CLAUSE, byName.getValue("libtorrent").licence)
        assertEquals(LicenceKind.MIT, byName.getValue("libtorrent4j").licence)
        assertEquals(LicenceKind.BSL_1_0, byName.getValue("Boost").licence)
        assertEquals(LicenceKind.APACHE_2_0, byName.getValue("OpenSSL").licence)
        assertEquals(LicenceKind.MPL_2_0, byName.getValue("GeckoView / Android Components").licence)
    }

    @Test
    fun versionsMatchTheVersionCatalog() {
        val toml = File("../gradle/libs.versions.toml").readText()

        fun version(key: String): String = Regex("""(?m)^$key\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertEquals(version("libtorrent4j"), Licences.LIBTORRENT4J_VERSION)
        assertEquals(version("mozillaAc"), Licences.ANDROID_COMPONENTS_VERSION)
        assertEquals(version("zxing"), Licences.ZXING_VERSION)
    }
}
