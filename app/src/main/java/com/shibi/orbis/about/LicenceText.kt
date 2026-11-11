package com.shibi.orbis.about

/**
 * Lays a verbatim licence text out for a phone screen, at display time only (the assets stay byte for byte as
 * published). Text that was hard-wrapped at ~72 columns becomes [Block.Paragraph]s the screen wraps; anything whose
 * layout carries meaning (a centered title, an underline, a list, an indented notice line, a URL on its own) stays a
 * [Block.Preformatted] block. Pure, so every rule is unit tested.
 */
object LicenceText {
    sealed interface Block {
        val text: String

        /** Wrapped by the screen. Line breaks inside are kept only for a block of short lines ("Copyright X" / "All rights reserved."). */
        data class Paragraph(override val text: String) : Block

        /** Shown as written, in a monospace font that does not wrap. */
        data class Preformatted(override val text: String) : Block
    }

    /** A line under a title: "=====" or "-----". */
    private val UNDERLINE = Regex("""^\s*(=|-){3,}\s*$""")

    /** "1.1. " and deeper (MPL's definition and section headers), at the start of a line. */
    private val SECTION_HEADER = Regex("""^\d+(\.\d+)+\.\s+\S.*""")

    /** "- x", "* x", "(a) x", "1. x", "1.1. x", "a) x". */
    private val LIST_ITEM = Regex("""^([-*]|\([0-9a-zA-Z]{1,4}\)|\d+(\.\d+)*\.|[a-zA-Z]\))\s+\S.*""")

    /** Lines shorter than this are a short-line block (names, addresses), not a hard-wrapped paragraph. */
    private const val SHORT_LINE = 50

    fun reflow(raw: String): List<Block> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }
        val out = ArrayList<Block>()
        val block = ArrayList<String>()
        for (line in lines + "") {
            if (line.isBlank()) {
                if (block.isNotEmpty()) layout(block.toList(), out)
                block.clear()
            } else {
                block += line
            }
        }
        return out
    }

    private fun layout(lines: List<String>, out: MutableList<Block>) {
        if (lines.any { UNDERLINE.matches(it) }) {
            out += Block.Preformatted(lines.joinToString("\n"))
            return
        }
        val indents = lines.map { indentOf(it) }
        if (lines.size == 1) {
            // A title or a notice line set in by 3+ spaces (Apache's "APPENDIX: ...", a URL) keeps its place.
            out += if (indents[0] >= 3) Block.Preformatted(lines[0]) else Block.Paragraph(collapse(lines[0]))
            return
        }
        val rest = indents.drop(1)
        // MPL: "1.1. \"Contributor\"" over an indented definition: the header is its own paragraph, then the body.
        if (indents[0] == 0 && SECTION_HEADER.matches(lines[0]) && rest.all { it >= 2 }) {
            out += Block.Paragraph(collapse(lines[0]))
            val cut = rest.minOf { it }
            layout(lines.drop(1).map { dedent(it, cut) }, out)
            return
        }
        val min = indents.minOf { it }
        val body = lines.map { dedent(it, min) }
        // A list (a marker below the first line) keeps its layout, one block per item when items wrap.
        if (body.drop(1).any { LIST_ITEM.matches(it.trimStart()) && indentOf(it) <= 2 }) {
            splitItems(lines, out)
            return
        }
        val first = indents[0]
        val uniform = indents.all { it == first }
        val hanging = rest.all { it == rest[0] } && rest[0] > first
        if (uniform || hanging) {
            out += paragraph(lines)
            return
        }
        // Lines set in by different amounts: a centered title, or something laid out by hand.
        out += Block.Preformatted(lines.joinToString("\n"))
    }

    /** One Preformatted block per list item, when every line is an item or a deeper continuation of one. */
    private fun splitItems(lines: List<String>, out: MutableList<Block>) {
        val itemIndent = lines.filter { LIST_ITEM.matches(it.trimStart()) }.minOf { indentOf(it) }
        val isItem = lines.map { LIST_ITEM.matches(it.trimStart()) && indentOf(it) == itemIndent }
        val continuation = lines.indices.filter { !isItem[it] }
        val splittable = isItem[0] && continuation.isNotEmpty() && continuation.all { indentOf(lines[it]) > itemIndent }
        if (!splittable) {
            out += Block.Preformatted(lines.joinToString("\n"))
            return
        }
        var current = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            if (isItem[i] && current.isNotEmpty()) {
                out += Block.Preformatted(current.joinToString("\n"))
                current = ArrayList()
            }
            current += line
        }
        if (current.isNotEmpty()) out += Block.Preformatted(current.joinToString("\n"))
    }

    private fun paragraph(lines: List<String>): Block.Paragraph {
        val trimmed = lines.map { collapse(it) }
        val shortLines = trimmed.dropLast(1).all { it.length < SHORT_LINE }
        return Block.Paragraph(trimmed.joinToString(if (shortLines) "\n" else " "))
    }

    private fun collapse(s: String): String = s.trim().replace(Regex("""\s+"""), " ")

    /** Leading spaces; a tab counts as four. */
    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            when (c) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    private fun dedent(line: String, by: Int): String {
        var removed = 0
        var i = 0
        while (i < line.length && removed < by && (line[i] == ' ' || line[i] == '\t')) {
            removed += if (line[i] == '\t') 4 else 1
            i++
        }
        return line.substring(i)
    }
}
