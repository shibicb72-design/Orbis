@file:OptIn(ExperimentalMaterial3Api::class)

package com.shibi.orbis.ui.browser

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shibi.orbis.R
import com.shibi.orbis.engine.DateTimeKind
import com.shibi.orbis.engine.FormValues
import com.shibi.orbis.engine.SitePrompt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import java.time.format.TextStyle as DateTextStyle

/*
 * Pickers for date/time-like inputs and `<input type=color>`. Same rules as the other site prompts: the title names the
 * committed origin (never page text), outside tap and back dismiss the prompt, every colour is a theme role, and the
 * action buttons stay pinned below scrolling content so they are reachable in landscape and on small windows.
 */

private val PickerDialogMaxWidth = 360.dp

// M3's own DatePicker range; widened below to include whatever the page asked for.
private const val DEFAULT_FIRST_YEAR = 1900
private const val DEFAULT_LAST_YEAR = 2100

/** M3 DatePicker millis are UTC midnight of the day. Convert ONLY with UTC, never the device zone. */
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Inverse of [toPickerMillis]. */
internal fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** The 1900..2100 default range, widened to include every year the page's default, min and max mention. */
internal fun widenedYearRange(vararg years: Int?): IntRange {
    var first = DEFAULT_FIRST_YEAR
    var last = DEFAULT_LAST_YEAR
    for (y in years) {
        if (y == null) continue
        if (y < first) first = y
        if (y > last) last = y
    }
    return first..last
}

/** [value] pulled into [min]..[max]; either bound may be absent. */
internal fun <T : Comparable<T>> clampTo(value: T, min: T?, max: T?): T {
    var r = value
    if (min != null && r < min) r = min
    if (max != null && r > max) r = max
    return r
}

/** Day and year bounds for the DatePicker. A week input passes the first day of its min week and the last of its max. */
private class DateBounds(private val min: LocalDate?, private val max: LocalDate?) : SelectableDates {
    fun contains(d: LocalDate): Boolean = (min == null || d >= min) && (max == null || d <= max)

    override fun isSelectableDate(utcTimeMillis: Long): Boolean = contains(utcTimeMillis.toPickerDate())

    override fun isSelectableYear(year: Int): Boolean =
        (min == null || year >= min.year) && (max == null || year <= max.year)
}

/** Date, week, month, time and date-time prompts. */
@Composable
fun DateTimePromptDialog(prompt: SitePrompt.DateTime, title: String) {
    // key(prompt): every piece of picker state below starts fresh for each prompt.
    key(prompt) {
        when (prompt.kind) {
            DateTimeKind.DATE -> DatePromptDialog(prompt, title)
            DateTimeKind.WEEK -> WeekPromptDialog(prompt, title)
            DateTimeKind.MONTH -> MonthPromptDialog(prompt, title)
            DateTimeKind.TIME -> TimePromptDialog(prompt, title)
            DateTimeKind.DATETIME_LOCAL -> DateTimeLocalPromptDialog(prompt, title)
        }
    }
}

/**
 * Shared shell. Title on top, [content] in the middle (scrolls, so a tall picker never pushes the buttons off a short
 * landscape window), an optional [footer] and the button row pinned below. Colours come from the surface itself.
 * Buttons: Clear (only when [onClear] is given), Back (only with [onBack]), Cancel, and the confirm action.
 */
@Composable
private fun PickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    confirmLabel: String = stringResource(R.string.prompt_ok),
    onClear: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = PickerDialogMaxWidth).fillMaxWidth().padding(vertical = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                if (footer != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) { footer() }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.prompt_clear)) }
                    Spacer(Modifier.weight(1f))
                    if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.prompt_back)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) }
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun pickerDateColors(): DatePickerColors =
    DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)

@Composable
private fun OutOfRangeText() {
    Text(
        stringResource(R.string.prompt_picker_out_of_range),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

// ---- DATE ----

@Composable
private fun DatePromptDialog(prompt: SitePrompt.DateTime, title: String) {
    val min = remember(prompt) { FormValues.parseDate(prompt.min) }
    val max = remember(prompt) { FormValues.parseDate(prompt.max) }
    val initial = remember(prompt) { FormValues.parseDate(prompt.defaultValue) ?: clampTo(LocalDate.now(), min, max) }
    val bounds = remember(prompt) { DateBounds(min, max) }
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.toPickerMillis(),
            yearRange = widenedYearRange(initial.year, min?.year, max?.year),
            selectableDates = bounds,
        )
    // A default outside min..max starts selected but is not valid: OK stays off until the user picks a real day.
    val picked = state.selectedDateMillis?.toPickerDate()?.takeIf(bounds::contains)
    PickerDialog(
        title = title,
        onDismiss = prompt::dismiss,
        onConfirm = { picked?.let { prompt.submit(FormValues.formatDate(it)) } },
        confirmEnabled = picked != null,
        onClear = if (prompt.defaultValue.isNotEmpty()) prompt::clear else null,
    ) {
        DatePicker(state = state, showModeToggle = false, colors = pickerDateColors())
    }
}

// ---- WEEK ----

@Composable
private fun WeekPromptDialog(prompt: SitePrompt.DateTime, title: String) {
    // A week is selectable when all of it lies inside min..max, which is the same as its days lying inside
    // [Monday of the min week, Sunday of the max week].
    val minDay = remember(prompt) { FormValues.parseWeek(prompt.min)?.let { FormValues.firstDayOf(it) } }
    val maxDay = remember(prompt) { FormValues.parseWeek(prompt.max)?.let { FormValues.firstDayOf(it).plusDays(6) } }
    val initial =
        remember(prompt) {
            FormValues.parseWeek(prompt.defaultValue)?.let { FormValues.firstDayOf(it) }
                ?: clampTo(LocalDate.now(), minDay, maxDay)
        }
    val bounds = remember(prompt) { DateBounds(minDay, maxDay) }
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.toPickerMillis(),
            yearRange =
                widenedYearRange(
                    initial.year,
                    FormValues.weekOf(initial).weekYear,
                    minDay?.year,
                    maxDay?.year,
                ),
            selectableDates = bounds,
        )
    val picked = state.selectedDateMillis?.toPickerDate()?.takeIf(bounds::contains)
    val week = picked?.let { FormValues.weekOf(it) }
    PickerDialog(
        title = title,
        onDismiss = prompt::dismiss,
        onConfirm = { week?.let { prompt.submit(FormValues.formatWeek(it)) } },
        confirmEnabled = week != null,
        onClear = if (prompt.defaultValue.isNotEmpty()) prompt::clear else null,
        footer =
            if (week != null) {
                ({
                    Text(
                        stringResource(R.string.prompt_picker_week_summary, week.week, week.weekYear),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                })
            } else {
                null
            },
    ) {
        DatePicker(state = state, showModeToggle = false, colors = pickerDateColors())
    }
}

// ---- MONTH ----

@Composable
private fun MonthPromptDialog(prompt: SitePrompt.DateTime, title: String) {
    val min = remember(prompt) { FormValues.parseMonth(prompt.min) }
    val max = remember(prompt) { FormValues.parseMonth(prompt.max) }
    val initial = remember(prompt) { FormValues.parseMonth(prompt.defaultValue) ?: clampTo(YearMonth.now(), min, max) }
    val minYear = min?.year ?: 1
    val maxYear = maxOf(minYear, max?.year ?: 9999)
    var year by remember(prompt) { mutableIntStateOf(initial.year.coerceIn(minYear, maxYear)) }
    var selected by remember(prompt) { mutableStateOf(initial) }
    val locale = currentLocale()
    fun inRange(m: YearMonth) = (min == null || m >= min) && (max == null || m <= max)

    PickerDialog(
        title = title,
        onDismiss = prompt::dismiss,
        onConfirm = { prompt.submit(FormValues.formatMonth(selected)) },
        confirmEnabled = inRange(selected),
        onClear = if (prompt.defaultValue.isNotEmpty()) prompt::clear else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year -= 1 }, enabled = year > minYear) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prompt_picker_previous_year))
                }
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { year += 1 }, enabled = year < maxYear) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.prompt_picker_next_year))
                }
            }
            for (row in 0 until 4) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 3) {
                        val number = row * 3 + col + 1
                        val month = YearMonth.of(year, number)
                        MonthCell(
                            label = Month.of(number).getDisplayName(DateTextStyle.SHORT, locale),
                            selected = month == selected,
                            enabled = inRange(month),
                            onClick = { selected = month },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    // A disabled month is never drawn as chosen, so the filled style always has full-strength text on it.
    val shown = selected && enabled
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .padding(4.dp)
            .height(48.dp)
            .clip(shape)
            .background(if (shown) colors.primary else Color.Transparent)
            .border(1.dp, if (shown) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color =
                when {
                    shown -> colors.onPrimary
                    enabled -> colors.onSurface
                    else -> colors.onSurface.copy(alpha = 0.38f)
                },
            fontWeight = if (shown) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---- TIME ----

@Composable
private fun TimePromptDialog(prompt: SitePrompt.DateTime, title: String) {
    val min = remember(prompt) { FormValues.parseTime(prompt.min) }
    val max = remember(prompt) { FormValues.parseTime(prompt.max) }
    val default = remember(prompt) { FormValues.parseTime(prompt.defaultValue) }
    val initial =
        remember(prompt) {
            default
                ?: LocalTime.now().withSecond(0).withNano(0).let { now ->
                    if (FormValues.timeInRange(now, min, max)) now else min ?: now
                }
        }
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
    val showSeconds = remember(prompt) { FormValues.wantsSeconds(prompt.step) || initial.second != 0 }
    var secondsText by remember(prompt) { mutableStateOf(initial.second.toString()) }

    val time = readTime(state, showSeconds, secondsText, default)
    val inRange = time == null || FormValues.timeInRange(time, min, max)
    PickerDialog(
        title = title,
        onDismiss = prompt::dismiss,
        onConfirm = { time?.let { prompt.submit(FormValues.formatTime(it)) } },
        confirmEnabled = time != null && inRange,
        onClear = if (prompt.defaultValue.isNotEmpty()) prompt::clear else null,
        footer = if (inRange) null else ({ OutOfRangeText() }),
    ) {
        TimeEntry(state, showSeconds, secondsText) { secondsText = it }
    }
}

/** The picked time, or null while the seconds field holds something invalid. */
private fun readTime(state: TimePickerState, showSeconds: Boolean, secondsText: String, default: LocalTime?): LocalTime? {
    val seconds = if (showSeconds) (parseSeconds(secondsText) ?: return null) else 0
    val value = LocalTime.of(state.hour, state.minute, seconds)
    // An untouched value keeps the page's own sub-second part instead of silently dropping it.
    return if (default != null && value == default.withNano(0)) default else value
}

private fun parseSeconds(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..59 }

@Composable
private fun TimeEntry(state: TimePickerState, showSeconds: Boolean, secondsText: String, onSecondsChange: (String) -> Unit) {
    // Vertical layout always: the horizontal one is wider than the dialog, and the dialog content scrolls anyway.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TimePicker(state = state, layoutType = TimePickerLayoutType.Vertical)
    }
    if (showSeconds) {
        OutlinedTextField(
            value = secondsText,
            onValueChange = { raw -> onSecondsChange(raw.filter { it in '0'..'9' }.take(2)) },
            singleLine = true,
            isError = parseSeconds(secondsText) == null,
            label = { Text(stringResource(R.string.prompt_picker_seconds)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

// ---- DATETIME_LOCAL ----

@Composable
private fun DateTimeLocalPromptDialog(prompt: SitePrompt.DateTime, title: String) {
    val min = remember(prompt) { FormValues.parseDateTimeLocal(prompt.min) }
    val max = remember(prompt) { FormValues.parseDateTimeLocal(prompt.max) }
    val default = remember(prompt) { FormValues.parseDateTimeLocal(prompt.defaultValue) }
    val initial =
        remember(prompt) { default ?: clampTo(LocalDateTime.now().withSecond(0).withNano(0), min, max) }
    val bounds = remember(prompt) { DateBounds(min?.toLocalDate(), max?.toLocalDate()) }
    // Both states live above the step switch, so Back keeps what the user already chose.
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().toPickerMillis(),
            yearRange = widenedYearRange(initial.year, min?.year, max?.year),
            selectableDates = bounds,
        )
    val timeState = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
    val showSeconds = remember(prompt) { FormValues.wantsSeconds(prompt.step) || initial.second != 0 }
    var secondsText by remember(prompt) { mutableStateOf(initial.second.toString()) }
    var timeStep by remember(prompt) { mutableStateOf(false) }

    val date = dateState.selectedDateMillis?.toPickerDate()?.takeIf(bounds::contains)
    val time = readTime(timeState, showSeconds, secondsText, default?.toLocalTime())
    val combined = if (date != null && time != null) date.atTime(time) else null
    val inRange = combined == null || ((min == null || !combined.isBefore(min)) && (max == null || !combined.isAfter(max)))

    if (!timeStep) {
        PickerDialog(
            title = title,
            onDismiss = prompt::dismiss,
            onConfirm = { timeStep = true },
            confirmEnabled = date != null,
            confirmLabel = stringResource(R.string.prompt_next),
            onClear = if (prompt.defaultValue.isNotEmpty()) prompt::clear else null,
        ) {
            DatePicker(state = dateState, showModeToggle = false, colors = pickerDateColors())
        }
    } else {
        // Clear is offered on the date step only: four buttons would not fit a narrow dialog.
        PickerDialog(
            title = title,
            onDismiss = prompt::dismiss,
            onConfirm = { combined?.let { prompt.submit(FormValues.formatDateTimeLocal(it)) } },
            confirmEnabled = combined != null && inRange,
            onBack = { timeStep = false },
            footer = if (inRange) null else ({ OutOfRangeText() }),
        ) {
            if (date != null) {
                Text(
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale())),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            TimeEntry(timeState, showSeconds, secondsText) { secondsText = it }
        }
    }
}

// ---- COLOUR ----

// Fixed palette, 4 rows of 6: greys, then the hues, then a few extras. Values are 0xRRGGBB.
private val ColorPalette: List<Int> =
    listOf(
        0x000000, 0x444444, 0x888888, 0xCCCCCC, 0xFFFFFF, 0x795548,
        0xF44336, 0xE91E63, 0x9C27B0, 0x673AB7, 0x3F51B5, 0x2196F3,
        0x03A9F4, 0x00BCD4, 0x009688, 0x4CAF50, 0x8BC34A, 0xCDDC39,
        0xFFEB3B, 0xFFC107, 0xFF9800, 0xFF5722, 0x607D8B, 0xFFC0CB,
    )

private const val PALETTE_COLUMNS = 6
private const val MAX_SUGGESTIONS = 24

private fun hexToRgb(text: String): Int? = FormValues.parseColor(if (text.startsWith("#")) text else "#$text")

private fun withChannel(rgb: Int, shift: Int, value: Int): Int = (rgb and (0xFF shl shift).inv()) or (value shl shift)

@Composable
fun ColorPromptDialog(prompt: SitePrompt.ColorPick, title: String) {
    var rgb by remember(prompt) { mutableIntStateOf(FormValues.parseColor(prompt.defaultValue) ?: 0) }
    var hex by remember(prompt) { mutableStateOf(FormValues.formatColor(rgb)) }
    val suggestions = remember(prompt) { prompt.suggestions.mapNotNull { FormValues.parseColor(it) }.distinct().take(MAX_SUGGESTIONS) }
    // The field is valid when it parses; rgb is always the last valid value, so swatches and sliders never see junk.
    val typed = hexToRgb(hex)

    fun choose(value: Int) {
        rgb = value
        hex = FormValues.formatColor(value)
    }

    PickerDialog(
        title = title,
        onDismiss = prompt::dismiss,
        onConfirm = { typed?.let { prompt.submit(FormValues.formatColor(it)) } },
        confirmEnabled = typed != null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(
                    rgb = rgb,
                    selected = false,
                    onClick = null,
                    description = stringResource(R.string.prompt_color_preview),
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    FormValues.formatColor(rgb),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            if (suggestions.isNotEmpty()) {
                Text(stringResource(R.string.prompt_color_suggestions), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in suggestions) {
                        ColorSwatch(c, selected = c == rgb, onClick = { choose(c) }, modifier = Modifier.size(40.dp))
                    }
                }
            }

            Text(stringResource(R.string.prompt_color_palette), style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in ColorPalette.chunked(PALETTE_COLUMNS)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in row) {
                            ColorSwatch(
                                c,
                                selected = c == rgb,
                                onClick = { choose(c) },
                                modifier = Modifier.weight(1f).widthIn(max = 40.dp),
                            )
                        }
                    }
                }
            }

            ChannelSlider(stringResource(R.string.prompt_color_red), (rgb shr 16) and 0xFF) { choose(withChannel(rgb, 16, it)) }
            ChannelSlider(stringResource(R.string.prompt_color_green), (rgb shr 8) and 0xFF) { choose(withChannel(rgb, 8, it)) }
            ChannelSlider(stringResource(R.string.prompt_color_blue), rgb and 0xFF) { choose(withChannel(rgb, 0, it)) }

            OutlinedTextField(
                value = hex,
                onValueChange = { raw ->
                    val cleaned = raw.filter { it == '#' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(7)
                    hex = cleaned
                    hexToRgb(cleaned)?.let { rgb = it }
                },
                singleLine = true,
                isError = typed == null,
                label = { Text(stringResource(R.string.prompt_color_hex)) },
                supportingText = if (typed == null) ({ Text(stringResource(R.string.prompt_color_hex_invalid)) }) else null,
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            value.toString(),
            Modifier.widthIn(min = 36.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * One colour chip. A 1dp outline border keeps white and black visible on either theme; the selected chip also gets a
 * thick ring and a check mark, so the choice never rests on colour alone. The check takes black or white by the
 * chip's own luminance, since it sits on the chip and not on the dialog surface. [onClick] null means display only.
 */
@Composable
private fun ColorSwatch(
    rgb: Int,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String = FormValues.formatColor(rgb),
) {
    val fill = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
    val colors = MaterialTheme.colorScheme
    val base =
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.onSurface else colors.outline, CircleShape)
    val interactive =
        if (onClick != null) base.selectable(selected = selected, role = Role.RadioButton, onClick = onClick) else base
    Box(interactive.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = if (fill.luminance() > 0.5f) Color.Black else Color.White)
        }
    }
}

/** The UI locale, observed: a configuration change recomposes with the new one. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
