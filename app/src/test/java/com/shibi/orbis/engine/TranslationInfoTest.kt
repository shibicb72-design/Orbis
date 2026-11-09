package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationInfoTest {
    @Test
    fun sameLanguageIgnoresRegion() {
        assertTrue(sameLanguage("en-US", "en"))
        assertTrue(sameLanguage("FR", "fr-CA"))
        assertFalse(sameLanguage("fr", "en"))
    }

    @Test
    fun offersOnlyForSupportedForeignPages() {
        assertTrue(TranslationInfo("fr", "en", true).canTranslate)
        assertFalse(TranslationInfo("fr", "en", false).canTranslate)
        assertFalse(TranslationInfo("en-GB", "en-US", true).canTranslate)
        assertFalse(TranslationInfo("", "en", true).canTranslate)
        assertFalse(TranslationInfo("fr", "", true).canTranslate)
    }
}
