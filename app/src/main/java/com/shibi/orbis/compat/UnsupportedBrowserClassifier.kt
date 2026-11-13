package com.shibi.orbis.compat

/** What the page showed, as collected by the content script. */
data class PageSample(
    val title: String,
    val headlines: List<String>,
    val visibleText: String,
    val blockPath: Boolean,
)

private const val MAX_VISIBLE_TEXT = 800
private const val REMEDY_WINDOW = 80

private val WHITESPACE = Regex("\\s+")

// Block phrases: the browser is the subject and lack of support is the claim.
private val BLOCK_PATTERNS: List<Regex> = listOf(
    // "this/your/the/current browser (version) is (not) unsupported/outdated/incompatible..."
    Regex(
        "\\b(?:(?:this|your|the|current) )?browser(?: version)? (?:is|isn't|is not) " +
            "(?:unsupported|not supported|no longer supported|out of date|outdated|incompatible|not compatible)\\b",
    ),
    // Short headline form: "Unsupported browser".
    Regex("\\bunsupported browser\\b"),
    // Short headline form: "Browser not supported".
    Regex("\\bbrowser not supported\\b"),
    // Short headline form: "Incompatible browser".
    Regex("\\bincompatible browser\\b"),
    // "We (no longer) support your browser" negated: "doesn't support this browser".
    Regex("\\b(?:doesn't|does not|don't|do not|no longer) supports? (?:this|your) browser\\b"),
)

// Remedy verbs; the names that follow are counted in hasRemedy.
private val REMEDY_VERB = Regex("\\b(?:switch to|use|try|open|download|install|update|upgrade)\\b")

// Browser names that make a remedy sentence credible when at least two distinct ones appear.
private val BROWSER_NAMES = listOf("chrome", "firefox", "safari", "edge", "opera")
    .map { Regex("\\b$it\\b") }

// Path segments that name an unsupported-browser page.
private val BLOCK_SEGMENTS = setOf(
    "unsupported",
    "unsupported-browser",
    "browser-not-supported",
    "browser-unsupported",
    "outdated-browser",
    "upgrade-browser",
    "browser-update",
    "incompatible-browser",
)

private fun normalize(s: String): String =
    s.lowercase().replace('’', '\'').replace('‘', '\'').trim().replace(WHITESPACE, " ")

private fun hasBlockPhrase(s: String): Boolean = BLOCK_PATTERNS.any { it.containsMatchIn(s) }

private fun hasRemedy(text: String): Boolean =
    REMEDY_VERB.findAll(text).any { verb ->
        val tail = text.substring(verb.range.last + 1).take(REMEDY_WINDOW)
        BROWSER_NAMES.count { it.containsMatchIn(tail) } >= 2
    }

fun isUnsupportedBrowserPage(sample: PageSample): Boolean {
    if (sample.visibleText.length > MAX_VISIBLE_TEXT) return false
    val text = normalize(sample.visibleText)
    val title = normalize(sample.title)
    val headlines = sample.headlines.map(::normalize)

    val blockInHeading = hasBlockPhrase(title) || headlines.any(::hasBlockPhrase)
    if (!blockInHeading && !hasBlockPhrase(text)) return false

    return blockInHeading || hasRemedy(text) || sample.blockPath
}

/** Segment match on the URL path; the content script has its own copy of this. */
fun isBlockPath(path: String): Boolean =
    path.substringBefore('#').substringBefore('?').lowercase().split('/')
        .any { seg -> seg.substringBeforeLast('.') in BLOCK_SEGMENTS }
