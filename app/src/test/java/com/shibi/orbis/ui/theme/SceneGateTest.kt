package com.shibi.orbis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * All 256 inputs of [sceneMode] against a table written out by hand from the spec, not computed by calling it.
 *
 * Rows: hasScene, privateTab, userAnimated, motionEnabled (as bits, most significant first, so row 11 = 1011).
 * Columns: resumed, inPip, fullScreen, mediaPlaying (column 8 = 1000: resumed and nothing else).
 * H Hidden, S Static, P Paused, R Running.
 */
class SceneGateTest {
    private val table =
        listOf(
            "HHHHHHHHHHHHHHHH", // 0000 no scene
            "HHHHHHHHHHHHHHHH", // 0001
            "HHHHHHHHHHHHHHHH", // 0010
            "HHHHHHHHHHHHHHHH", // 0011
            "HHHHHHHHHHHHHHHH", // 0100
            "HHHHHHHHHHHHHHHH", // 0101
            "HHHHHHHHHHHHHHHH", // 0110
            "HHHHHHHHHHHHHHHH", // 0111
            "SSSSSSSSSSSSSSSS", // 1000 user Off, animator scale 0
            "SSSSSSSSSSSSSSSS", // 1001 user Off
            "SSSSSSSSSSSSSSSS", // 1010 animator scale 0
            "PPPPPPPPRPPPPPPP", // 1011 moves only when resumed with no PiP, fullscreen or media
            "HHHHHHHHHHHHHHHH", // 1100 private tab
            "HHHHHHHHHHHHHHHH", // 1101
            "HHHHHHHHHHHHHHHH", // 1110
            "HHHHHHHHHHHHHHHH", // 1111
        )

    private fun bit(n: Int, b: Int) = (n shr b) and 1 == 1

    @Test
    fun everyInputGivesTheModeInTheTable() {
        val letters = mapOf('H' to SceneMode.Hidden, 'S' to SceneMode.Static, 'P' to SceneMode.Paused, 'R' to SceneMode.Running)
        var running = 0
        for (row in 0 until 16) {
            for (col in 0 until 16) {
                val ambient = SceneAmbient(motionEnabled = bit(row, 0), resumed = bit(col, 3), inPip = bit(col, 2), fullScreen = bit(col, 1), mediaPlaying = bit(col, 0))
                val actual = sceneMode(hasScene = bit(row, 3), userAnimated = bit(row, 1), privateTab = bit(row, 2), ambient = ambient)
                assertEquals("row ${row.toString(2).padStart(4, '0')} column ${col.toString(2).padStart(4, '0')}", letters.getValue(table[row][col]), actual)
                if (actual == SceneMode.Running) running++
            }
        }
        assertEquals("exactly one input moves", 1, running)
    }
}
