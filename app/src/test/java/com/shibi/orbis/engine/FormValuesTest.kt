package com.shibi.orbis.engine

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormValuesTest {
    @Test
    fun dateRoundTripAndInvalid() {
        val d = LocalDate.of(2026, 11, 5)
        assertEquals("2026-11-05", FormValues.formatDate(d))
        assertEquals(d, FormValues.parseDate("2026-11-05"))
        assertEquals("0042-01-02", FormValues.formatDate(LocalDate.of(42, 1, 2)))
        assertEquals(LocalDate.of(42, 1, 2), FormValues.parseDate("0042-01-02"))
        assertEquals(LocalDate.of(2024, 2, 29), FormValues.parseDate("2024-02-29"))
        listOf(null, "", " ", "2026-02-30", "2025-02-29", "2026-13-01", "2026-00-10", "2026-1-05", "26-11-05", "0000-01-01",
            "2026-11-05T10:00", " 2026-11-05", "2026/11/05", "abcd-ef-gh", "10000-01-01").forEach {
            assertNull("date $it", FormValues.parseDate(it))
        }
    }

    @Test
    fun monthRoundTripAndInvalid() {
        assertEquals(YearMonth.of(2026, 3), FormValues.parseMonth("2026-03"))
        assertEquals("2026-03", FormValues.formatMonth(YearMonth.of(2026, 3)))
        assertEquals("0007-12", FormValues.formatMonth(YearMonth.of(7, 12)))
        listOf(null, "", "2026-13", "2026-00", "2026-3", "0000-05", "2026-03-01").forEach {
            assertNull("month $it", FormValues.parseMonth(it))
        }
    }

    @Test
    fun weekRoundTrip() {
        val w = FormValues.parseWeek("2026-W45")
        assertEquals(WeekValue(2026, 45), w)
        assertEquals("2026-W45", FormValues.formatWeek(w!!))
        assertEquals("0001-W01", FormValues.formatWeek(WeekValue(1, 1)))
    }

    @Test
    fun week53OnlyForYearsThatHaveIt() {
        assertEquals(WeekValue(2026, 53), FormValues.parseWeek("2026-W53"))
        assertNull(FormValues.parseWeek("2025-W53"))
        assertEquals(WeekValue(2020, 53), FormValues.parseWeek("2020-W53"))
        assertNull(FormValues.parseWeek("2021-W53"))
        assertNull(FormValues.parseWeek("2026-W54"))
        assertNull(FormValues.parseWeek("2026-W00"))
        listOf(null, "", "2026-45", "2026-w45", "2026-W5", "0000-W01").forEach {
            assertNull("week $it", FormValues.parseWeek(it))
        }
    }

    @Test
    fun weekOfUsesIsoWeekYearAtBoundaries() {
        assertEquals(WeekValue(2026, 53), FormValues.weekOf(LocalDate.of(2027, 1, 1)))
        assertEquals(WeekValue(2026, 53), FormValues.weekOf(LocalDate.of(2027, 1, 3)))
        assertEquals(WeekValue(2027, 1), FormValues.weekOf(LocalDate.of(2027, 1, 4)))
        assertEquals(WeekValue(2025, 1), FormValues.weekOf(LocalDate.of(2024, 12, 30)))
        assertEquals(WeekValue(2024, 52), FormValues.weekOf(LocalDate.of(2024, 12, 29)))
        assertEquals(WeekValue(2026, 45), FormValues.weekOf(LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun firstDayOfWeekIsMonday() {
        assertEquals(LocalDate.of(2026, 11, 2), FormValues.firstDayOf(WeekValue(2026, 45)))
        assertEquals(LocalDate.of(2026, 12, 28), FormValues.firstDayOf(WeekValue(2026, 53)))
        assertEquals(LocalDate.of(2024, 12, 30), FormValues.firstDayOf(WeekValue(2025, 1)))
        assertEquals(LocalDate.of(2018, 1, 1), FormValues.firstDayOf(WeekValue(2018, 1)))
        // Round trip across a full year, including both boundaries.
        var d = LocalDate.of(2020, 12, 20)
        repeat(60) {
            val w = FormValues.weekOf(d)
            val monday = FormValues.firstDayOf(w)
            assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
            assertTrue(!d.isBefore(monday) && d.isBefore(monday.plusDays(7)))
            assertEquals(w, FormValues.parseWeek(FormValues.formatWeek(w)))
            d = d.plusDays(1)
        }
    }

    @Test
    fun timeParsesAndFormatsOmittingZeroParts() {
        assertEquals(LocalTime.of(9, 5), FormValues.parseTime("09:05"))
        assertEquals(LocalTime.of(9, 5, 7), FormValues.parseTime("09:05:07"))
        assertEquals(LocalTime.of(9, 5, 7, 120_000_000), FormValues.parseTime("09:05:07.12"))
        assertEquals(LocalTime.of(9, 5, 7, 5_000_000), FormValues.parseTime("09:05:07.005"))
        assertEquals("09:05", FormValues.formatTime(LocalTime.of(9, 5)))
        assertEquals("09:05:07", FormValues.formatTime(LocalTime.of(9, 5, 7)))
        assertEquals("09:05:07.120", FormValues.formatTime(LocalTime.of(9, 5, 7, 120_000_000)))
        assertEquals("09:05:00.500", FormValues.formatTime(LocalTime.of(9, 5, 0, 500_000_000)))
        assertEquals("00:00", FormValues.formatTime(LocalTime.MIDNIGHT))
        listOf(null, "", "24:00", "12:60", "12:00:60", "9:05", "12", "12:00:", "12:00.5", "12:00:00.1234", "12:00:00.", "ab:cd")
            .forEach { assertNull("time $it", FormValues.parseTime(it)) }
    }

    @Test
    fun dateTimeLocalAcceptsTAndSpace() {
        val expected = LocalDateTime.of(2026, 11, 5, 14, 30)
        assertEquals(expected, FormValues.parseDateTimeLocal("2026-11-05T14:30"))
        assertEquals(expected, FormValues.parseDateTimeLocal("2026-11-05 14:30"))
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 30, 15), FormValues.parseDateTimeLocal("2026-11-05T14:30:15"))
        assertEquals("2026-11-05T14:30", FormValues.formatDateTimeLocal(expected))
        assertEquals("2026-11-05T14:30:15", FormValues.formatDateTimeLocal(expected.withSecond(15)))
        listOf(null, "", "2026-11-05", "2026-11-05T", "2026-11-05T25:00", "2026-02-30T10:00", "14:30", "2026-11-05X14:30")
            .forEach { assertNull("datetime $it", FormValues.parseDateTimeLocal(it)) }
    }

    @Test
    fun wantsSecondsOnlyForPositiveStepBelowSixty() {
        assertTrue(FormValues.wantsSeconds("1"))
        assertTrue(FormValues.wantsSeconds("0.5"))
        assertTrue(FormValues.wantsSeconds(" 30 "))
        assertTrue(FormValues.wantsSeconds("59.9"))
        listOf(null, "", "  ", "any", "ANY", "60", "300", "0", "-1", "abc", "NaN", "Infinity", "1e1", "0x10", "5s")
            .forEach { assertFalse("step $it", FormValues.wantsSeconds(it)) }
    }

    @Test
    fun timeRangeMayWrapOvernight() {
        val t = { h: Int, m: Int -> LocalTime.of(h, m) }
        assertTrue(FormValues.timeInRange(t(12, 0), null, null))
        assertTrue(FormValues.timeInRange(t(9, 0), t(9, 0), t(17, 0)))
        assertTrue(FormValues.timeInRange(t(17, 0), t(9, 0), t(17, 0)))
        assertFalse(FormValues.timeInRange(t(8, 59), t(9, 0), t(17, 0)))
        assertFalse(FormValues.timeInRange(t(17, 1), t(9, 0), t(17, 0)))
        // min > max: overnight window 22:00..06:00
        assertTrue(FormValues.timeInRange(t(23, 0), t(22, 0), t(6, 0)))
        assertTrue(FormValues.timeInRange(t(3, 0), t(22, 0), t(6, 0)))
        assertFalse(FormValues.timeInRange(t(12, 0), t(22, 0), t(6, 0)))
        // one-sided
        assertTrue(FormValues.timeInRange(t(10, 0), t(9, 0), null))
        assertFalse(FormValues.timeInRange(t(8, 0), t(9, 0), null))
        assertTrue(FormValues.timeInRange(t(8, 0), null, t(9, 0)))
        assertFalse(FormValues.timeInRange(t(10, 0), null, t(9, 0)))
    }

    @Test
    fun colorParseAndFormat() {
        assertEquals(0x00FF00, FormValues.parseColor("#00ff00"))
        assertEquals(0xABCDEF, FormValues.parseColor("#ABCDEF"))
        assertEquals(0, FormValues.parseColor("#000000"))
        assertEquals(0xFFFFFF, FormValues.parseColor("#ffffff"))
        listOf(null, "", "#fff", "00ff00", "#00ff0", "#00ff000", "#00ff00ff", "#gg0000", "red", "#00ff00 ", " #00ff00")
            .forEach { assertNull("color $it", FormValues.parseColor(it)) }
        assertEquals("#00ff00", FormValues.formatColor(0x00FF00))
        assertEquals("#000000", FormValues.formatColor(0))
        assertEquals("#0000ff", FormValues.formatColor(0xFF))
        assertEquals("#abcdef", FormValues.formatColor(0xABCDEF))
        // alpha bits are masked away
        assertEquals("#123456", FormValues.formatColor(0xFF123456.toInt()))
        assertEquals("#ffffff", FormValues.formatColor(-1))
    }
}
