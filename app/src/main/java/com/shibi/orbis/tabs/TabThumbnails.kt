package com.shibi.orbis.tabs

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small previews of tab pages for the tab grid. Memory only (never written to disk, so a private page leaves no
 * picture behind), dropped with their tab. Main thread.
 */
class TabThumbnails {
    private val _all = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val all: StateFlow<Map<Long, Bitmap>> = _all.asStateFlow()

    /** Stores a preview of tab [id], made from a full-size capture of its page (which is recycled). */
    fun put(id: Long, capture: Bitmap) {
        val small = thumbnailOf(capture) ?: return
        if (small !== capture) capture.recycle()
        _all.value = _all.value + (id to small)
    }

    /** Keeps only the previews of tabs still open. */
    fun retain(ids: Set<Long>) {
        val now = _all.value
        if (now.keys.all { it in ids }) return
        _all.value = now.filterKeys { it in ids }
    }

    fun clear() {
        _all.value = emptyMap()
    }
}

/** Preview width in pixels; the height keeps the top of the page at 4:5. */
internal const val THUMBNAIL_WIDTH = 240

/** The size of the top-of-page crop for a capture of [w] x [h]: [THUMBNAIL_WIDTH] wide at 4:5, never taller than the page. */
internal fun thumbnailCrop(w: Int, h: Int): Pair<Int, Int>? {
    if (w <= 0 || h <= 0) return null
    val cropH = minOf(h, w * 5 / 4)
    return w to cropH
}

private fun thumbnailOf(capture: Bitmap): Bitmap? =
    runCatching {
        val (w, cropH) = thumbnailCrop(capture.width, capture.height) ?: return null
        val top = Bitmap.createBitmap(capture, 0, 0, w, cropH)
        val scaledH = (cropH.toLong() * THUMBNAIL_WIDTH / w).toInt().coerceAtLeast(1)
        val scaled = top.scale(THUMBNAIL_WIDTH, scaledH)
        if (top !== capture && top !== scaled) top.recycle()
        scaled
    }.getOrNull()
