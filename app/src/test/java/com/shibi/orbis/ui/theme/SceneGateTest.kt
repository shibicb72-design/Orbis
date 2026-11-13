package com.shibi.orbis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * All 512 inputs of [sceneMode] against a table written out by hand from the spec, not computed by calling it.
 *
 * Rows: hasScene, privateTab, userAnimated, motionEnabled (as bits, most significant first, so row 11 = 1011).
 * Columns: resumed, inPip, fullScreen, mediaPlaying, pageShowing (column 16 = 10000: resumed and nothing else).
 * H Hidden, S Static, P Paused, R Running.
 */
class SceneGateTest {
    private val table =
        listOf(
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0000 no scene
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0001
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0010
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0011
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0100
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0101
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0110
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 0111
            "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS", // 1000 user Off, animator scale 0
            "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS", // 1001 user Off
            "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS", // 1010 animator scale 0
            "PPPPPPPPPPPPPPPPRPPPPPPPPPPPPPPP", // 1011 moves only when resumed with no PiP, fullscreen, media or page
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 1100 private tab
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 1101
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 1110
            "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH", // 1111
        )

    private fun bit(n: Int, b: Int) = (n shr b) and 1 == 1

    @Test
    fun everyInputGivesTheModeInTheTable() {
        val letters = mapOf('H' to SceneMode.Hidden, 'S' to SceneMode.Static, 'P' to SceneMode.Paused, 'R' to SceneMode.Running)
        var running = 0
        for (row in 0 until 16) {
            for (col in 0 until 32) {
                val ambient = SceneAmbient(motionEnabled = bit(row, 0), resumed = bit(col, 4), inPip = bit(col, 3), fullScreen = bit(col, 2), mediaPlaying = bit(col, 1), pageShowing = bit(col, 0))
                val actual = sceneMode(hasScene = bit(row, 3), userAnimated = bit(row, 1), privateTab = bit(row, 2), ambient = ambient)
                assertEquals("row ${row.toString(2).padStart(4, '0')} column ${col.toString(2).padStart(5, '0')}", letters.getValue(table[row][col]), actual)
                if (actual == SceneMode.Running) running++
            }
        }
        assertEquals("exactly one input moves", 1, running)
    }
}
