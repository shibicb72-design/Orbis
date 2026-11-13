package com.shibi.orbis.ui.theme

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * [n] frames of the held clock. State the test wrote on its own thread is announced first: with the clock held,
 * nothing else would tell the recomposer or a snapshotFlow about it.
 */
internal fun ComposeContentTestRule.frames(n: Int) {
    Snapshot.sendApplyNotifications()
    repeat(n) {
        mainClock.advanceTimeByFrame()
        Snapshot.sendApplyNotifications()
    }
}

/** The pixels of [image] as ARGB ints, row by row. */
internal fun ImageBitmap.argb(): IntArray {
    val map = toPixelMap()
    return IntArray(map.width * map.height) { i -> map.buffer[map.bufferOffset + (i / map.width) * map.stride + i % map.width] }
}
