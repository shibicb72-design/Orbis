package com.shibi.orbis.ui.browser

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.shibi.orbis.R
import com.shibi.orbis.engine.ReaderArticle
import com.shibi.orbis.settings.ReaderTheme
import com.shibi.orbis.ui.secondaryReadingWidth

internal const val READER_TAG = "reader_view"

/** Colors of a reader theme: background, text, link. */
internal fun readerColors(theme: ReaderTheme): Triple<Color, Color, Color> =
    when (theme) {
        ReaderTheme.LIGHT -> Triple(Color(0xFFFFFFFF), Color(0xFF1B1B1B), Color(0xFF0B5CAD))
        ReaderTheme.SEPIA -> Triple(Color(0xFFF4ECD8), Color(0xFF3B2F22), Color(0xFF7A4B12))
        ReaderTheme.DARK -> Triple(Color(0xFF15171A), Color(0xFFE3E3E3), Color(0xFF8AB4F8))
    }

/**
 * The article HTML without images and embedded media (reader view shows text; no remote image is fetched from here)
 * and without anything Html.fromHtml would show as an object box.
 */
internal fun readerHtmlWithoutMedia(html: String): String =
    html.replace(Regex("(?is)<(img|picture|source|video|audio|iframe|svg|figure)\\b[^>]*>(.*?</\\1>)?"), "")
        .replace(Regex("(?is)<(img|source)\\b[^>]*/?>"), "")

/**
 * Reader view over the page: the article as plain formatted text (Readability's cleaned HTML through Html.fromHtml: no
 * script, no style, no remote fetch). Links open in the tab and close the reader. Text size, serif and theme come from
 * the browser settings.
 */
@Composable
internal fun ReaderView(
    article: ReaderArticle,
    textSizeSp: Int,
    serif: Boolean,
    theme: ReaderTheme,
    onTextSize: (Int) -> Unit,
    onSerif: (Boolean) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, link) = readerColors(theme)
    var controls by remember { mutableStateOf(false) }
    val family = if (serif) FontFamily.Serif else FontFamily.SansSerif
    Surface(modifier.fillMaxSize().testTag(READER_TAG), color = bg, contentColor = fg) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reader_close)) }
                Text(stringResource(R.string.reader_title), modifier = Modifier.weight(1f))
                IconButton(onClick = { controls = !controls }) {
                    Icon(Icons.Filled.TextFields, contentDescription = stringResource(R.string.reader_appearance))
                }
            }
            if (controls) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onTextSize(textSizeSp - 2) }, enabled = textSizeSp > READER_MIN_SP) { Text(stringResource(R.string.reader_smaller)) }
                    TextButton(onClick = { onTextSize(textSizeSp + 2) }, enabled = textSizeSp < READER_MAX_SP) { Text(stringResource(R.string.reader_larger)) }
                    FilterChip(selected = serif, onClick = { onSerif(!serif) }, label = { Text(stringResource(R.string.reader_serif)) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTheme.entries.forEach { t ->
                        FilterChip(selected = t == theme, onClick = { onTheme(t) }, label = { Text(stringResource(themeLabel(t))) })
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).secondaryReadingWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (article.siteName.isNotBlank()) Text(article.siteName, fontSize = (textSizeSp - 4).sp, fontFamily = family)
                Text(
                    article.title,
                    fontSize = (textSizeSp + 8).sp,
                    lineHeight = (textSizeSp + 14).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = family,
                    modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
                )
                if (article.byline.isNotBlank()) Text(article.byline, fontSize = (textSizeSp - 2).sp, fontFamily = family)
                val body = remember(article.contentHtml) { readerHtmlWithoutMedia(article.contentHtml) }
                AndroidView(
                    factory = { ctx -> TextView(ctx).apply { movementMethod = LinkMovementMethod.getInstance() } },
                    update = { tv ->
                        tv.textSize = textSizeSp.toFloat()
                        tv.setLineSpacing(0f, 1.45f)
                        tv.typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
                        tv.setTextColor(fg.toArgb())
                        tv.setLinkTextColor(link.toArgb())
                        tv.text = linkifiedForReader(HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY), onOpenLink)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 48.dp),
                )
            }
        }
    }
}

internal const val READER_MIN_SP = 12
internal const val READER_MAX_SP = 32

private fun themeLabel(t: ReaderTheme): Int =
    when (t) {
        ReaderTheme.LIGHT -> R.string.reader_theme_light
        ReaderTheme.SEPIA -> R.string.reader_theme_sepia
        ReaderTheme.DARK -> R.string.reader_theme_dark
    }

/** Replaces URL spans with ones that open web links in the tab (and ignore any other scheme). */
private fun linkifiedForReader(text: CharSequence, onOpenLink: (String) -> Unit): CharSequence {
    val out = SpannableStringBuilder(text)
    for (span in out.getSpans(0, out.length, URLSpan::class.java)) {
        val url = span.url
        val start = out.getSpanStart(span)
        val end = out.getSpanEnd(span)
        out.removeSpan(span)
        if (url.startsWith("http://") || url.startsWith("https://")) {
            out.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onOpenLink(url)
                },
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return out
}
