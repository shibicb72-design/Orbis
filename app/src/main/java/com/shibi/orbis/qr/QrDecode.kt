package com.shibi.orbis.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.shibi.orbis.engine.isWebUrl

/**
 * Decodes a QR code from a camera frame's luminance (Y) plane: [width] x [height] pixels, rows [rowStride] bytes apart.
 * Tries the normal and the inverted image (light-on-dark codes). Returns the code's text, or null when none was found.
 */
fun decodeQr(y: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
    if (width <= 0 || height <= 0 || rowStride < width || y.size < (height - 1) * rowStride + width) return null
    val source = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
    val reader = QRCodeReader()
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    for (candidate in listOf(source, source.invert())) {
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
        } catch (_: ReaderException) {
            // Not found in this one; try the next.
        } finally {
            reader.reset()
        }
    }
    return null
}

/** What a scanned code holds: a web address Orbis can open, or any other text (offered for search or copy). */
sealed interface QrContent {
    data class Web(val url: String) : QrContent

    data class Text(val text: String) : QrContent
}

/**
 * Only an explicit http(s) address counts as a link: anything else (other schemes such as intent:, javascript:, file:,
 * tel:, or bare words) is shown as text and never opened on its own.
 */
fun classifyQr(raw: String): QrContent {
    val t = raw.trim()
    val lower = t.lowercase()
    return if ((lower.startsWith("http://") || lower.startsWith("https://")) && isWebUrl(t)) QrContent.Web(t) else QrContent.Text(t)
}
