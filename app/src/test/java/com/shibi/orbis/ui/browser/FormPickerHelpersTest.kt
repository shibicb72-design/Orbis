package com.shibi.orbis.ui.browser

import java.time.LocalDate
import java.time.YearMonth
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FormPickerHelpersTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private val millisPerDay = 24L * 60 * 60 * 1000

    private val zones = listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "America/Detroit", "UTC", "Asia/Kolkata")

    private val dates = listOf(
        LocalDate.of(2026, 3, 7),
        LocalDate.of(2026, 3, 8), // Detroit spring forward
        LocalDate.of(2026, 3, 9),
        LocalDate.of(2026, 11, 1), // Detroit fall back
        LocalDate.of(2026, 11, 2),
        LocalDate.of(2028, 2, 29), // leap day
        LocalDate.of(2000, 1, 1),
        LocalDate.of(1970, 1, 1),
        LocalDate.of(1900, 1, 1),
        LocalDate.of(1, 1, 1),
        LocalDate.of(2100, 12, 31),
    )

    private fun inEachZone(block: (String) -> Unit) {
        for (id in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block(id)
        }
    }

    // ---- toPickerMillis / toPickerDate ----

    @Test
    fun roundTripNeverShiftsTheDayInAnyZone() {
        inEachZone { id ->
            for (d in dates) {
                assertEquals("zone=$id date=$d", d, d.toPickerMillis().toPickerDate())
            }
        }
    }

    @Test
    fun millisAreExactlyUtcMidnight() {
        inEachZone { id ->
            for (d in dates) {
                val ms = d.toPickerMillis()
                assertEquals("zone=$id date=$d", 0L, Math.floorMod(ms, millisPerDay))
                assertEquals("zone=$id date=$d", d.toEpochDay() * millisPerDay, ms)
            }
        }
    }

    @Test
    fun knownEpochValues() {
        inEachZone { id ->
            assertEquals(id, 0L, LocalDate.of(1970, 1, 1).toPickerMillis())
            assertEquals(id, 1_772_928_000_000L, LocalDate.of(2026, 3, 8).toPickerMillis())
            assertEquals(id, LocalDate.of(2026, 3, 8), 1_772_928_000_000L.toPickerDate())
        }
    }

    @Test
    fun anyInstantWithinTheUtcDayMapsToThatDay() {
        inEachZone { id ->
            val d = LocalDate.of(2026, 11, 1)
            val start = d.toPickerMillis()
            assertEquals(id, d, start.toPickerDate())
            assertEquals(id, d, (start + millisPerDay - 1).toPickerDate())
            assertEquals(id, d.plusDays(1), (start + millisPerDay).toPickerDate())
            assertEquals(id, d.minusDays(1), (start - 1).toPickerDate())
        }
    }

    @Test
    fun yearOneAndLeapDay() {
        inEachZone { id ->
            val one = LocalDate.of(1, 1, 1)
            assertEquals(id, one, one.toPickerMillis().toPickerDate())
            assertEquals(id, 0L, Math.floorMod(one.toPickerMillis(), millisPerDay))
            val leap = LocalDate.of(2028, 2, 29)
            assertEquals(id, leap, leap.toPickerMillis().toPickerDate())
            assertEquals(id, leap.plusDays(1), LocalDate.of(2028, 3, 1))
            assertEquals(id, millisPerDay, leap.plusDays(1).toPickerMillis() - leap.toPickerMillis())
        }
    }

    @Test
    fun consecutiveDaysAreExactlyOneDayApartAcrossDstChanges() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Detroit"))
        for (start in listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 10, 31))) {
            for (i in 0L until 3L) {
                val a = start.plusDays(i)
                val b = a.plusDays(1)
                assertEquals("$a", millisPerDay, b.toPickerMillis() - a.toPickerMillis())
            }
        }
    }

    // ---- widenedYearRange ----

    @Test
    fun noYearsGivesDefaultRange() {
        assertEquals(1900..2100, widenedYearRange())
    }

    @Test
    fun nullsAreIgnored() {
        assertEquals(1900..2100, widenedYearRange(null))
        assertEquals(1900..2100, widenedYearRange(null, null, null))
    }

    @Test
    fun yearsInsideDefaultDoNotNarrow() {
        assertEquals(1900..2100, widenedYearRange(1950, 2000, 2050))
        assertEquals(1900..2100, widenedYearRange(1900, 2100))
    }

    @Test
    fun widensDownward() {
        assertEquals(1800..2100, widenedYearRange(1800))
        assertEquals(1..2100, widenedYearRange(1))
    }

    @Test
    fun widensUpward() {
        assertEquals(1900..2200, widenedYearRange(2200))
        assertEquals(1900..9999, widenedYearRange(9999))
    }

    @Test
    fun widensBothDirectionsAndIgnoresNullsAndOrder() {
        assertEquals(1800..2200, widenedYearRange(2200, null, 1800, 2000))
        assertEquals(1800..2200, widenedYearRange(1800, 2000, null, 2200))
    }

    // ---- clampTo ----

    @Test
    fun clampLocalDate() {
        val min = LocalDate.of(2026, 3, 1)
        val max = LocalDate.of(2026, 3, 31)
        assertEquals(min, clampTo(LocalDate.of(2026, 2, 28), min, max))
        assertEquals(max, clampTo(LocalDate.of(2026, 4, 1), min, max))
        assertEquals(LocalDate.of(2026, 3, 8), clampTo(LocalDate.of(2026, 3, 8), min, max))
        assertEquals(min, clampTo(min, min, max))
        assertEquals(max, clampTo(max, min, max))
    }

    @Test
    fun clampLocalDateWithNullBounds() {
        val min = LocalDate.of(2026, 3, 1)
        val max = LocalDate.of(2026, 3, 31)
        val v = LocalDate.of(2026, 6, 15)
        assertEquals(v, clampTo(v, null, null))
        assertEquals(v, clampTo(v, min, null))
        assertEquals(min, clampTo(LocalDate.of(2026, 1, 1), min, null))
        assertEquals(v, clampTo(v, null, LocalDate.of(2027, 1, 1)))
        assertEquals(max, clampTo(v, null, max))
    }

    @Test
    fun clampYearMonth() {
        val min = YearMonth.of(2026, 3)
        val max = YearMonth.of(2026, 11)
        assertEquals(min, clampTo(YearMonth.of(2025, 12), min, max))
        assertEquals(max, clampTo(YearMonth.of(2027, 1), min, max))
        assertEquals(YearMonth.of(2026, 7), clampTo(YearMonth.of(2026, 7), min, max))
        assertEquals(min, clampTo(min, min, max))
        assertEquals(max, clampTo(max, min, max))
    }

    @Test
    fun clampYearMonthWithNullBounds() {
        val min = YearMonth.of(2026, 3)
        val max = YearMonth.of(2026, 11)
        val v = YearMonth.of(2030, 1)
        assertEquals(v, clampTo(v, null, null))
        assertEquals(v, clampTo(v, min, null))
        assertEquals(min, clampTo(YearMonth.of(2020, 1), min, null))
        assertEquals(max, clampTo(v, null, max))
        assertEquals(YearMonth.of(2020, 1), clampTo(YearMonth.of(2020, 1), null, max))
    }
}
