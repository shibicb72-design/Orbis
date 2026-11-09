package com.shibi.orbis.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    /** A camera-like Y plane: the code drawn [scale]x on white, rows padded to [stride]; [inverted] draws light on dark. */
    private fun frame(text: String, scale: Int = 4, stride: Int? = null, inverted: Boolean = false): Triple<ByteArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val w = (m.width + 8) * scale
        val h = (m.height + 8) * scale
        val rowStride = stride ?: w
        val y = ByteArray(rowStride * h) { 0xFF.toByte() }
        for (py in 0 until h) for (px in 0 until w) {
            val mx = px / scale - 4
            val my = py / scale - 4
            val dark = mx in 0 until m.width && my in 0 until m.height && m.get(mx, my)
            val v = if (dark != inverted) 0x10 else 0xF0
            y[py * rowStride + px] = v.toByte()
        }
        return Triple(y, w, h).also { if (stride != null) check(stride >= w) }.let { Triple(it.first, w, h) }
    }

    @Test
    fun decodesACodeFromALuminancePlane() {
        val (y, w, h) = frame("https://example.org/qr?x=1")
        assertEquals("https://example.org/qr?x=1", decodeQr(y, w, h))
    }

    @Test
    fun honoursRowPaddingAndInvertedCodes() {
        val (y, w, h) = frame("hello orbis", stride = 300)
        assertEquals("hello orbis", decodeQr(y, w, h, rowStride = 300))
        val (yi, wi, hi) = frame("dark mode code", inverted = true)
        assertEquals("dark mode code", decodeQr(yi, wi, hi))
    }

    @Test
    fun noCodeOrBadSizesGiveNull() {
        assertNull(decodeQr(ByteArray(100 * 100) { 0x80.toByte() }, 100, 100))
        assertNull(decodeQr(ByteArray(10), 100, 100))
        assertNull(decodeQr(ByteArray(10000), 100, 100, rowStride = 50))
    }

    @Test
    fun onlyExplicitWebAddressesAreLinks() {
        assertEquals(QrContent.Web("https://example.org/a"), classifyQr(" https://example.org/a "))
        assertEquals(QrContent.Web("HTTP://example.org/"), classifyQr("HTTP://example.org/"))
        assertEquals(QrContent.Text("example.org"), classifyQr("example.org"))
        assertEquals(QrContent.Text("javascript:alert(1)"), classifyQr("javascript:alert(1)"))
        assertEquals(QrContent.Text("intent://x#Intent;end"), classifyQr("intent://x#Intent;end"))
        assertEquals(QrContent.Text("WIFI:S:home;T:WPA;P:secret;;"), classifyQr("WIFI:S:home;T:WPA;P:secret;;"))
    }
}
