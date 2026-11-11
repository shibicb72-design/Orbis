package com.shibi.orbis.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAllowedTest {
    @Test
    fun privateTabNeverOffersTheMicrophone() {
        assertFalse(voiceAllowed(isPrivate = true, hasRecognizer = true))
        assertFalse(voiceAllowed(isPrivate = true, hasRecognizer = false))
    }

    @Test
    fun normalTabOffersTheMicrophoneWhenARecognizerExists() {
        assertTrue(voiceAllowed(isPrivate = false, hasRecognizer = true))
        assertFalse(voiceAllowed(isPrivate = false, hasRecognizer = false))
    }
}
