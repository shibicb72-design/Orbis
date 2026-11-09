package com.shibi.orbis.passwords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun hasEveryClassAndNoLookAlikes() {
        repeat(200) {
            val p = generatePassword()
            assertEquals(GENERATED_PASSWORD_LENGTH, p.length)
            assertTrue(p.any { it.isLowerCase() })
            assertTrue(p.any { it.isUpperCase() })
            assertTrue(p.any { it.isDigit() })
            assertTrue(p.any { !it.isLetterOrDigit() })
            assertTrue(p.none { it in "0O1lI" })
        }
    }

    @Test
    fun neverShorterThanTwelveAndNotRepeated() {
        assertEquals(12, generatePassword(4).length)
        assertNotEquals(generatePassword(), generatePassword())
    }
}
