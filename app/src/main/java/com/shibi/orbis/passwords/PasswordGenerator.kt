package com.shibi.orbis.passwords

import java.security.SecureRandom

/** Characters for generated passwords: no look-alikes (0/O, 1/l/I) so a password can be typed from the screen. */
private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val DIGITS = "23456789"
private const val SYMBOLS = "-_.!@#%+=?"

const val GENERATED_PASSWORD_LENGTH = 20

/**
 * A strong random password ([length] characters, at least 12): at least one lower case letter, upper case letter,
 * digit and symbol, the rest from all four, shuffled. SecureRandom only; nothing is stored or logged.
 */
fun generatePassword(length: Int = GENERATED_PASSWORD_LENGTH, random: SecureRandom = SecureRandom()): String {
    val n = length.coerceAtLeast(12)
    val all = LOWER + UPPER + DIGITS + SYMBOLS
    val chars = CharArray(n)
    chars[0] = LOWER[random.nextInt(LOWER.length)]
    chars[1] = UPPER[random.nextInt(UPPER.length)]
    chars[2] = DIGITS[random.nextInt(DIGITS.length)]
    chars[3] = SYMBOLS[random.nextInt(SYMBOLS.length)]
    for (i in 4 until n) chars[i] = all[random.nextInt(all.length)]
    // Fisher-Yates, so the guaranteed classes are not always at the front.
    for (i in n - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val t = chars[i]
        chars[i] = chars[j]
        chars[j] = t
    }
    return String(chars)
}
