package com.shibi.orbis.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesDottedVersions() {
        assertTrue(isNewer("v0.4.6", "0.4.5"))
        assertTrue(isNewer("0.5", "0.4.9"))
        assertTrue(isNewer("1.0.0", "0.10.3"))
        assertFalse(isNewer("0.4.5", "0.4.5"))
        assertFalse(isNewer("0.4.5", "0.4.10"))
        assertFalse(isNewer("nightly", "0.4.5"))
        assertEquals(listOf(0, 4, 6), versionParts("v0.4.6-beta"))
    }

    @Test
    fun readsOnlyPublishedReleasesOfOrbis() {
        val ok = """{"tag_name":"v0.5.0","html_url":"https://github.com/shibicb72-design/Orbis/releases/tag/v0.5.0","draft":false,"prerelease":false}"""
        assertEquals(ReleaseInfo("0.5.0", "https://github.com/shibicb72-design/Orbis/releases/tag/v0.5.0"), parseRelease(ok))
        assertNull(parseRelease("""{"tag_name":"v0.5.0","prerelease":true}"""))
        assertNull(parseRelease("""{"tag_name":"latest"}"""))
        assertNull(parseRelease("not json"))
        // A page elsewhere is never offered: the releases list instead.
        val odd = """{"tag_name":"v0.5.0","html_url":"https://evil.example/x"}"""
        assertEquals(RELEASES_PAGE, parseRelease(odd)?.pageUrl)
    }

    @Test
    fun playInstallsSkipTheCheck() {
        assertTrue(installedFromPlay("com.android.vending"))
        assertFalse(installedFromPlay(null))
        assertFalse(installedFromPlay("com.google.android.packageinstaller"))
    }
}
