package com.shibi.orbis.engine

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Which kind of date/time-like `<input>` a [SitePrompt.DateTime] edits. */
enum class DateTimeKind { DATE, MONTH, WEEK, TIME, DATETIME_LOCAL }

/** ISO week: [weekYear] is the week-based year (2027-01-01 is in week-year 2026), [week] is 1..53. */
data class WeekValue(val weekYear: Int, val week: Int)

/**
 * Typed form of an HTML value. Parsers return null for null, empty or invalid text, and never throw.
 * Years outside 1..9999 parse to null; formatters pad the year to 4 digits. Pure JVM so it is unit tested.
 */
object FormValues {
    private const val MIN_YEAR = 1
    private const val MAX_YEAR = 9999

    private val dateRe = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val monthRe = Regex("""(\d{4})-(\d{2})""")
    private val weekRe = Regex("""(\d{4})-W(\d{2})""")
    private val timeRe = Regex("""(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?""")
    private val dateTimeRe = Regex("""(\d{4}-\d{2}-\d{2})[T ](.+)""")
    private val stepRe = Regex("""\d+\.?\d*|\.\d+""")
    private val colorRe = Regex("""#[0-9a-fA-F]{6}""")

    private fun yearOk(year: Int) = year in MIN_YEAR..MAX_YEAR

    fun parseDate(s: String?): LocalDate? {
        val m = s?.let { dateRe.matchEntire(it) } ?: return null
        val (y, mo, d) = m.destructured
        val year = y.toInt()
        if (!yearOk(year)) return null
        return try {
            LocalDate.of(year, mo.toInt(), d.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    fun formatDate(d: LocalDate): String = "%04d-%02d-%02d".format(Locale.ROOT, d.year, d.monthValue, d.dayOfMonth)

    fun parseMonth(s: String?): YearMonth? {
        val m = s?.let { monthRe.matchEntire(it) } ?: return null
        val (y, mo) = m.destructured
        val year = y.toInt()
        if (!yearOk(year)) return null
        return try {
            YearMonth.of(year, mo.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    fun formatMonth(m: YearMonth): String = "%04d-%02d".format(Locale.ROOT, m.year, m.monthValue)

    /** The highest week number of an ISO week-based year: 52 or 53. Dec 28 always lies in the last week. */
    private fun weeksIn(weekYear: Int): Int =
        LocalDate.of(weekYear, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    fun parseWeek(s: String?): WeekValue? {
        val m = s?.let { weekRe.matchEntire(it) } ?: return null
        val (y, w) = m.destructured
        val year = y.toInt()
        val week = w.toInt()
        if (!yearOk(year) || week < 1 || week > weeksIn(year)) return null
        return WeekValue(year, week)
    }

    fun formatWeek(w: WeekValue): String = "%04d-W%02d".format(Locale.ROOT, w.weekYear, w.week)

    fun weekOf(d: LocalDate): WeekValue =
        WeekValue(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** Monday of the week. Jan 4 is always in week 1 of its year. */
    fun firstDayOf(w: WeekValue): LocalDate =
        LocalDate.of(w.weekYear, 1, 4)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks((w.week - 1).toLong())

    fun parseTime(s: String?): LocalTime? {
        val m = s?.let { timeRe.matchEntire(it) } ?: return null
        val (h, mi, sec, frac) = m.destructured
        val millis = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toInt()
        return try {
            LocalTime.of(h.toInt(), mi.toInt(), if (sec.isEmpty()) 0 else sec.toInt(), millis * 1_000_000)
        } catch (e: DateTimeException) {
            null
        }
    }

    /** "HH:mm", plus ":ss" when seconds are set, plus ".SSS" when milliseconds are set. */
    fun formatTime(t: LocalTime): String {
        val millis = t.nano / 1_000_000
        val base = "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)
        return when {
            millis != 0 -> "%s:%02d.%03d".format(Locale.ROOT, base, t.second, millis)
            t.second != 0 -> "%s:%02d".format(Locale.ROOT, base, t.second)
            else -> base
        }
    }

    fun parseDateTimeLocal(s: String?): LocalDateTime? {
        val m = s?.let { dateTimeRe.matchEntire(it) } ?: return null
        val (date, time) = m.destructured
        val d = parseDate(date) ?: return null
        val t = parseTime(time) ?: return null
        return LocalDateTime.of(d, t)
    }

    fun formatDateTimeLocal(dt: LocalDateTime): String = formatDate(dt.toLocalDate()) + "T" + formatTime(dt.toLocalTime())

    /** True if the input wants sub-minute precision: step parses to a positive number below 60. Null, empty, "any": false. */
    fun wantsSeconds(step: String?): Boolean {
        val text = step?.trim() ?: return false
        if (!stepRe.matches(text)) return false
        val n = text.toDoubleOrNull() ?: return false
        return n > 0.0 && n < 60.0
    }

    /** HTML time ranges may wrap (min > max means overnight): valid when t >= min OR t <= max. */
    fun timeInRange(t: LocalTime, min: LocalTime?, max: LocalTime?): Boolean =
        when {
            min == null && max == null -> true
            min == null -> t <= max!!
            max == null -> t >= min
            min <= max -> t >= min && t <= max
            else -> t >= min || t <= max
        }

    fun parseColor(s: String?): Int? {
        if (s == null || !colorRe.matches(s)) return null
        return s.substring(1).toInt(16)
    }

    fun formatColor(rgb: Int): String = "#%06x".format(Locale.ROOT, rgb and 0xFFFFFF)
}
