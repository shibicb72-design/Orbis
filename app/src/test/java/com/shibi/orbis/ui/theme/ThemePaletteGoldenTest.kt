package com.shibi.orbis.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orbis Teal must look exactly as it did before themes existed. `golden/orbis_teal_original.txt` was written from
 * the colours in Theme.kt on main before the refactor and is never regenerated. `golden/theme_palettes.txt` pins
 * every theme: regenerate it on purpose with UPDATE_GOLDEN=true.
 */
class ThemePaletteGoldenTest {
    private fun resource(name: String): List<String> =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("golden/$name")) { "missing golden/$name" }
            .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun orbisTealIsUnchangedFromBeforeThemes() {
        val golden = resource("orbis_teal_original.txt")
        val set = golden.map { it.substringBefore("=") }.toSet()
        val actual = dump(AppThemes.byId("orbis_teal")).filter { it.substringBefore("=") in set }
        assertEquals(golden.sorted(), actual.sorted())
    }

    /** The error roles were never set by Orbis, so they come from the Material defaults, as before. */
    @Test
    fun orbisTealErrorRolesAreStillTheMaterialDefaults() {
        val teal = AppThemes.byId("orbis_teal")
        assertEquals(lightColorScheme().roles().filter { it.first.contains("rror") }, teal.light.scheme.roles().filter { it.first.contains("rror") })
        assertEquals(darkColorScheme().roles().filter { it.first.contains("rror") }, teal.dark.scheme.roles().filter { it.first.contains("rror") })
    }

    @Test
    fun orbisTealIsTheDefaultAndTheFallback() {
        assertEquals("orbis_teal", AppThemes.Default.id)
        assertEquals("orbis_teal", AppThemes.byId(null).id)
        assertEquals("orbis_teal", AppThemes.byId("no_such_theme").id)
        assertEquals("orbis_teal", AppThemes.all.first().id)
    }

    @Test
    fun everyThemePaletteMatchesTheGolden() {
        val actual = AppThemes.all.flatMap { dump(it) }
        if (System.getenv("UPDATE_GOLDEN") == "true") {
            File("src/test/resources/golden/theme_palettes.txt").writeText(actual.joinToString("\n") + "\n")
        }
        assertEquals(resource("theme_palettes.txt"), actual)
    }
}
