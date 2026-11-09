package com.shibi.orbis.ui.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.shibi.orbis.MainActivity
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.isWebUrl

/** The letter on a home screen shortcut: the site's first letter without "www.", or "#" when there is none. */
internal fun shortcutLetter(url: String): String {
    val host = hostOfUrl(url)?.removePrefix("www.").orEmpty()
    return host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
}

/** The shortcut's label: the page title, else the site's host. Launchers cut long labels themselves. */
internal fun shortcutLabel(url: String, title: String): String =
    title.trim().ifEmpty { hostOfUrl(url)?.removePrefix("www.").orEmpty() }.ifEmpty { url }.take(60)

/**
 * Asks the launcher to pin a shortcut that opens [url] in Orbis. Returns false when the launcher cannot pin shortcuts
 * or the address is not a web page. The launcher shows its own confirmation.
 */
internal fun requestHomeShortcut(context: Context, url: String, title: String): Boolean {
    if (!isWebUrl(url) || !ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).setClass(context, MainActivity::class.java)
    val info =
        ShortcutInfoCompat.Builder(context, "site-" + url.hashCode().toUInt().toString(16))
            .setShortLabel(shortcutLabel(url, title))
            .setIcon(IconCompat.createWithAdaptiveBitmap(letterIcon(shortcutLetter(url))))
            .setIntent(intent)
            .build()
    return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
}

/** An adaptive-icon bitmap (108dp at 2x): the letter on the app's teal, inside the safe zone. */
private fun letterIcon(letter: String): Bitmap {
    val size = 216
    val bmp = createBitmap(size, size)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF006A66.toInt())
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(letter, size / 2f, y, paint)
    return bmp
}
