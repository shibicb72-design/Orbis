package com.shibi.orbis.about

/**
 * Lays a verbatim licence text out for a phone screen, at display time only (the assets stay byte for byte as
 * published). Text that was hard-wrapped at ~72 columns becomes [Block.Paragraph]s the screen wraps, and list items
 * with their wrapped continuation lines become [Block.Bullet]s (wrapped too, with a hanging indent). Anything whose
 * layout carries meaning (a centered title, an underline, a table-like block, an indented notice line, a URL on its
 * own) stays a [Block.Preformatted] block. Pure, so every rule is unit tested.
 */
object LicenceText {
    sealed interface Block {
        val text: String

        /** Wrapped by the screen. Line breaks inside are kept only for a block of short lines ("Copyright X" / "All rights reserved."). */
        data class Paragraph(override val text: String) : Block

        /** Shown as written, in a monospace font that does not wrap. */
        data class Preformatted(override val text: String) : Block

        /** One list item: its [marker] ("*", "(a)", "1.") hangs, its [body] wraps beside it. */
        data class Bullet(val marker: String, val body: String) : Block {
            override val text: String get() = "$marker $body"
        }
    }

    /** A line under a title: "=====" or "-----". */
    private val UNDERLINE = Regex("""^\s*(=|-){3,}\s*$""")

    /** "1.1. " and deeper (MPL's definition and section headers), at the start of a line. */
    private val SECTION_HEADER = Regex("""^\d+(\.\d+)+\.\s+\S.*""")

    /** "- x", "* x", "(a) x", "1. x", "1.1. x", "a) x". */
    private val LIST_ITEM = Regex("""^([-*]|\([0-9a-zA-Z]{1,4}\)|\d+(\.\d+)*\.|[a-zA-Z]\))\s+\S.*""")

    /** The marker of a [LIST_ITEM] line (trimmed) and the gap after it. */
    private val MARKER = Regex("""^([-*]|\([0-9a-zA-Z]{1,4}\)|\d+(\.\d+)*\.|[a-zA-Z]\))([ \t]+)""")

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
        // List items, each with its continuation lines set in to its text column: one wrapped bullet per item.
        bullets(lines)?.let {
            out += it
            return
        }
        val min = indents.minOf { it }
        val body = lines.map { dedent(it, min) }
        // Any other block with a marker below its first line is laid out by hand (a lead line over a list, a
        // commented header): it keeps its layout.
        if (body.drop(1).any { LIST_ITEM.matches(it.trimStart()) && indentOf(it) <= 2 }) {
            out += Block.Preformatted(lines.joinToString("\n"))
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

    /**
     * The block as bullets, or null when it is not a clean list: the first line is an item, every item sits at the
     * first item's indent, and every other line is set in at least to the text column of the item above it.
     */
    private fun bullets(lines: List<String>): List<Block.Bullet>? {
        val itemIndent = indentOf(lines[0])
        val out = ArrayList<Block.Bullet>()
        var marker: String? = null
        val body = StringBuilder()
        var textColumn = 0
        for (line in lines) {
            val indent = indentOf(line)
            val trimmed = line.trimStart()
            val m = MARKER.find(trimmed)
            if (indent == itemIndent && m != null && LIST_ITEM.matches(trimmed)) {
                marker?.let { out += Block.Bullet(it, collapse(body.toString())) }
                marker = m.groupValues[1]
                body.setLength(0)
                body.append(trimmed.substring(m.range.last + 1))
                textColumn = indent + m.groupValues[1].length + m.groupValues[3].sumOf { if (it == '\t') 4L else 1L }.toInt()
            } else {
                if (marker == null || indent < textColumn) return null
                body.append(' ').append(trimmed)
            }
        }
        marker?.let { out += Block.Bullet(it, collapse(body.toString())) }
        return out.takeIf { it.isNotEmpty() }
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
