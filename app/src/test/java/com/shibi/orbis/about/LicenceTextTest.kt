package com.shibi.orbis.about

import com.shibi.orbis.about.LicenceText.Block
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenceTextTest {
    // Unit tests run in the module directory (app/).
    private val assets = File("src/main/assets")

    private fun blocks(kind: LicenceKind): List<Block> = LicenceText.reflow(File(assets, kind.assetPath).readText())

    private fun paragraph(blocks: List<Block>, start: String): Block = blocks.single { it.text.startsWith(start) }

    @Test
    fun mplDefinitionsBecomeOneParagraphEach() {
        val b = blocks(LicenceKind.MPL_2_0)
        // The header stays its own paragraph, the definition below it is one paragraph with no mid-sentence break.
        assertEquals(Block.Paragraph("1.1. \"Contributor\""), b.single { it.text == "1.1. \"Contributor\"" })
        assertEquals(
            Block.Paragraph("means each individual or legal entity that creates, contributes to the creation of, or owns Covered Software."),
            paragraph(b, "means each individual"),
        )
        assertEquals(
            Block.Paragraph(
                "means Source Code Form to which the initial Contributor has attached the notice in Exhibit A, the Executable " +
                    "Form of such Source Code Form, and Modifications of such Source Code Form, in each case including portions thereof.",
            ),
            paragraph(b, "means Source Code Form to which"),
        )
        // Every "1.x." definition body is a paragraph on one line.
        val bodies = b.filter { it.text.startsWith("means ") }
        assertTrue(bodies.size >= 12)
        assertTrue(bodies.all { it is Block.Paragraph && '\n' !in it.text && "  " !in it.text })
        // Hard-wrapped body text reflows too.
        assertTrue(paragraph(b, "The licenses granted in Section 2.1 with respect").text.endsWith("distributes such Contribution."))
        // A title over its underline keeps its layout.
        assertEquals(Block.Preformatted("Mozilla Public License Version 2.0\n=================================="), b.first())
    }

    @Test
    fun apacheTitleAndAppendixKeepTheirLayout() {
        val b = blocks(LicenceKind.APACHE_2_0)
        val header = b.first()
        assertTrue(header is Block.Preformatted)
        assertTrue(header.text.contains("Apache License\n"))
        assertTrue(header.text.contains("Version 2.0, January 2004"))
        assertTrue(header.text.startsWith(" ".repeat(10)))
        val appendix = b.single { it.text.contains("APPENDIX: How to apply the Apache License") }
        assertTrue(appendix is Block.Preformatted)
        assertEquals("   APPENDIX: How to apply the Apache License to your work.", appendix.text)
    }

    @Test
    fun theLibtorrentConditionsBecomeThreeWrappedBullets() {
        val raw = File(assets, LicenceKind.BSD_3_CLAUSE.assetPath).readText()
        val b = LicenceText.reflow(raw)
        val conditions = b.filterIsInstance<Block.Bullet>().filter { it.body.startsWith("Redistributions") || it.body.startsWith("Neither") }
        assertEquals(
            listOf(
                Block.Bullet("*", "Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer."),
                Block.Bullet(
                    "*",
                    "Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the " +
                        "following disclaimer in the documentation and/or other materials provided with the distribution.",
                ),
                Block.Bullet(
                    "*",
                    "Neither the name of the author nor the names of its contributors may be used to endorse or promote products " +
                        "derived from this software without specific prior written permission.",
                ),
            ),
            conditions,
        )
        // Every word of the three conditions, in order, exactly as in the asset.
        val start = raw.indexOf("* Redistributions of source code")
        val end = raw.indexOf("written permission.") + "written permission.".length
        val words = raw.substring(start, end).split(Regex("\\s+"))
        assertEquals(words, conditions.joinToString(" ") { it.text }.split(" "))
        // No line break inside a bullet: the screen wraps it.
        assertTrue(conditions.none { '\n' in it.body })
        // The route.h comment block (every line starts with " *") is laid out by hand and keeps its layout.
        assertTrue(b.single { it.text.contains("@APPLE_OSREFERENCE_LICENSE_HEADER_START@") } is Block.Preformatted)
        // The sentence before them and the disclaimer after them wrap to the screen.
        assertEquals(
            Block.Paragraph("Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:"),
            paragraph(b, "Redistribution and use"),
        )
        assertTrue(paragraph(b, "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS") is Block.Paragraph)
        // Short lines (a copyright holder over "All rights reserved.") keep their breaks.
        assertEquals(Block.Paragraph("Copyright (c) 2003-2020, Arvid Norberg\nAll rights reserved."), b.first())
    }

    @Test
    fun rulesOnSmallInputs() {
        assertEquals(emptyList<Block>(), LicenceText.reflow(""))
        assertEquals(listOf(Block.Paragraph("a b")), LicenceText.reflow("a   b\r\n"))
        assertEquals(
            listOf(Block.Paragraph("First sentence that was wrapped at a fixed column by its author and goes on and on."), Block.Paragraph("Second.")),
            LicenceText.reflow("First sentence that was wrapped at a fixed column by its author and goes\non and on.\n\n\nSecond.\n"),
        )
        // A list below a lead line keeps its layout.
        val list = LicenceText.reflow("Conditions:\n- one\n- two\n")
        assertEquals(listOf(Block.Preformatted("Conditions:\n- one\n- two")), list)
        // A line set in on its own (a URL) is not reflowed.
        assertEquals(listOf(Block.Preformatted("   https://example.org/")), LicenceText.reflow("   https://example.org/"))
        // A hanging list item wraps as one bullet; two items are two bullets.
        assertEquals(
            listOf(Block.Bullet("1.", "one two"), Block.Bullet("2.", "three")),
            LicenceText.reflow("  1. one\n     two\n  2. three\n"),
        )
        // A continuation left of the text column is not a clean list: kept as written.
        assertEquals(listOf(Block.Preformatted("  - one\n two")), LicenceText.reflow("  - one\n two\n"))
        assertEquals(
            listOf(Block.Bullet("(a)", "under intellectual property rights (other than patent or trademark) Licensable by such Contributor")),
            LicenceText.reflow("(a) under intellectual property rights (other than patent or trademark)\n    Licensable by such Contributor\n"),
        )
    }

    @Test
    fun theAssetsAreUntouched() {
        // SHA-256 of each asset as published (LF line ends, as git stores them), equal to origin/main 58002cf.
        val expected =
            mapOf(
                LicenceKind.APACHE_2_0 to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
                LicenceKind.BSD_3_CLAUSE to "77d319baf9b855d3f41e7a8a05ce90d407be1396b16a362bea2351bff7e3ce46",
                LicenceKind.BSL_1_0 to "c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566",
                LicenceKind.MIT to "2e9e1fc1ea3cb3fb2431c5250b653c29e2977545fa7e77ae6f741a0611767f04",
                LicenceKind.MPL_2_0 to "3f3d9e0024b1921b067d6f7f88deb4a60cbe7a78e76c64e3f1d7fc3b779b9d04",
            )
        assertEquals(LicenceKind.entries.toSet(), expected.keys)
        for ((kind, sha) in expected) {
            // A Windows checkout may have CRLF line ends; the bytes compared are the ones in the repository.
            val bytes = File(assets, kind.assetPath).readText().replace("\r\n", "\n").toByteArray()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(kind.name, sha, actual)
        }
        // Reflow never changes the words: every word of the text is still there, in order.
        for (kind in LicenceKind.entries) {
            val raw = File(assets, kind.assetPath).readText()
            val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val shown = LicenceText.reflow(raw).joinToString(" ") { it.text }.split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertEquals(kind.name, words, shown)
        }
        assertFalse(blocks(LicenceKind.MIT).isEmpty())
    }
}
