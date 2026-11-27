package com.shibi.orbis.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.stats.MemoryReading
import com.shibi.orbis.stats.MemorySample
import com.shibi.orbis.stats.ProcessKind
import com.shibi.orbis.tabs.TabActivity
import com.shibi.orbis.tabs.TabOverview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val MEMORY_SECTION_TAG = "memory_section"
internal const val MEMORY_TAB_ROW_TAG = "memory_tab_row"
internal const val MEMORY_PROCESS_ROW_TAG = "memory_process_row"

/** Rows shown before "Show all". */
internal const val MEMORY_ROWS_SHOWN = 10

/** The chart's three periods. */
internal enum class MemoryRange(val millis: Long) {
    DAY(24L * 60 * 60 * 1000),
    WEEK(7L * 24 * 60 * 60 * 1000),
    MONTH(30L * 24 * 60 * 60 * 1000),
}

/** Kilobytes as whole megabytes (1 MB = 1024 kB, as Android's memory tools count). */
internal fun megabytes(kb: Long): Long = (kb + 512) / 1024

/** The tab list's order: the tab not looked at for longest first; a tab with no time counts as oldest. Pure. */
internal fun memoryTabOrder(tabs: List<TabOverview>): List<TabOverview> =
    tabs.sortedWith(compareBy<TabOverview, Long?>(nullsFirst()) { it.lastLookedAt }.thenBy { it.id })

/** The chart's right edge: the later of [now] and the newest sample, so a sample newer than the screen's clock is still drawn. Pure. */
internal fun chartEnd(now: Long, history: List<MemorySample>): Long = maxOf(now, history.maxOfOrNull { it.at } ?: now)

/** How long ago, as (unit, n): 0 just now, 1 minutes, 2 hours, 3 days. Pure. */
internal fun lookedAgo(now: Long, at: Long): Pair<Int, Long> {
    val minutes = ((now - at).coerceAtLeast(0L)) / 60_000
    return when {
        minutes < 1 -> 0 to 0L
        minutes < 60 -> 1 to minutes
        minutes < 24 * 60 -> 2 to minutes / 60
        else -> 3 to minutes / (24 * 60)
    }
}

/**
 * The dashboard's Memory section: Orbis's measured memory and when it was measured, the split by kind of process, the
 * largest web-page processes (no site is named: the engine does not say which tabs a process holds), the open tabs with
 * what Orbis knows about each (no memory figure: none can be measured per tab), and the history chart.
 */
@Composable
internal fun MemorySection(
    reading: MemoryReading?,
    tabs: List<TabOverview>,
    history: List<MemorySample>,
    now: Long,
    range: MemoryRange,
    onRange: (MemoryRange) -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onDeleteHistory: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var allProcesses by rememberSaveable { mutableStateOf(false) }
    var allTabs by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(MEMORY_SECTION_TAG),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            // a. The measured total.
            Column {
                Text(stringResource(R.string.memory_used), style = MaterialTheme.typography.bodyLarge)
                if (reading == null) {
                    Text(stringResource(R.string.memory_measuring), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.memory_mb, megabytes(reading.totalKb)),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(reading.measuredAt).atZone(zone))
                    Text(stringResource(R.string.memory_measured_at, time), style = MaterialTheme.typography.bodyMedium)
                    if (reading.rateLimited) Text(stringResource(R.string.memory_rate_limited), style = MaterialTheme.typography.bodySmall)
                }
            }
            // b. The split by kind of process.
            if (reading != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    KindLine(stringResource(R.string.memory_app), reading.kb(ProcessKind.APP))
                    KindLine(stringResource(R.string.memory_web), reading.kb(ProcessKind.WEB))
                    KindLine(stringResource(R.string.memory_other), reading.kb(ProcessKind.OTHER))
                }
            }
            // c. The largest web-page processes, by number only.
            val web = reading?.webProcessesKb.orEmpty()
            if (web.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.memory_processes_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.memory_processes_note), style = MaterialTheme.typography.bodySmall)
                    val shown = if (allProcesses) web else web.take(MEMORY_ROWS_SHOWN)
                    shown.forEachIndexed { i, kb ->
                        Row(Modifier.fillMaxWidth().testTag(MEMORY_PROCESS_ROW_TAG)) {
                            Text(stringResource(R.string.memory_process_row, i + 1), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.memory_mb, megabytes(kb)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (web.size > MEMORY_ROWS_SHOWN) ShowAllButton(allProcesses) { allProcesses = !allProcesses }
                }
            }
            // d. The open tabs: state and when last looked at. Deliberately no memory figure.
            val ordered = memoryTabOrder(tabs)
            if (ordered.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.memory_tabs_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    val shown = if (allTabs) ordered else ordered.take(MEMORY_ROWS_SHOWN)
                    shown.forEach { tab -> TabRow(tab, now, onSelectTab, onCloseTab) }
                    if (ordered.size > MEMORY_ROWS_SHOWN) ShowAllButton(allTabs) { allTabs = !allTabs }
                }
            }
            // e. The chart and its delete button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemoryRange.entries.forEach { r ->
                    FilterChip(selected = r == range, onClick = { onRange(r) }, label = { Text(stringResource(rangeLabel(r))) })
                    Spacer(Modifier.width(6.dp))
                }
            }
            val end = chartEnd(now, history)
            val points = history.filter { it.at > end - range.millis && it.at <= end }
            if (points.isEmpty()) {
                Text(stringResource(R.string.memory_chart_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                MemoryChart(points, end - range.millis, end)
            }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.memory_delete_history)) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteHistory()
                }) { Text(stringResource(R.string.memory_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.prompt_cancel)) } },
        )
    }
}

private fun rangeLabel(r: MemoryRange): Int =
    when (r) {
        MemoryRange.DAY -> R.string.memory_range_day
        MemoryRange.WEEK -> R.string.memory_range_week
        MemoryRange.MONTH -> R.string.memory_range_month
    }

@Composable
private fun KindLine(label: String, kb: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.memory_mb, megabytes(kb)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ShowAllButton(expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(if (expanded) R.string.memory_show_fewer else R.string.memory_show_all)) }
}

/** One tab: its title ("Private tab" for a private one, nothing else), its state, and when it was last looked at. */
@Composable
private fun TabRow(tab: TabOverview, now: Long, onSelect: (Long) -> Unit, onClose: (Long) -> Unit) {
    val title =
        when {
            tab.isPrivate -> stringResource(R.string.memory_tab_private)
            tab.title.isBlank() -> stringResource(R.string.memory_tab_untitled)
            else -> tab.title
        }
    val state =
        stringResource(
            when (tab.activity) {
                TabActivity.PLAYING -> R.string.memory_state_playing
                TabActivity.AWAKE -> R.string.memory_state_awake
                TabActivity.ASLEEP -> R.string.memory_state_asleep
            },
        )
    val looked =
        tab.lastLookedAt?.let { at ->
            val (unit, n) = lookedAgo(now, at)
            when (unit) {
                0 -> stringResource(R.string.memory_looked_now)
                1 -> stringResource(R.string.memory_looked_minutes, n)
                2 -> stringResource(R.string.memory_looked_hours, n)
                else -> pluralStringResource(R.plurals.memory_looked_days, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)
            }
        } ?: stringResource(R.string.memory_looked_unknown)
    Row(
        Modifier.fillMaxWidth().testTag(MEMORY_TAB_ROW_TAG).clickable { onSelect(tab.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.memory_tab_detail, state, looked), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onClose(tab.id) }) { Text(stringResource(R.string.memory_close)) }
    }
}

/** Total memory over the period from [from] to [to], as a line; read as one sentence by screen readers. */
@Composable
private fun MemoryChart(points: List<MemorySample>, from: Long, to: Long) {
    val mb = points.map { megabytes(it.totalKb) }
    val description = stringResource(R.string.memory_chart_description, mb.min(), mb.max())
    val line = MaterialTheme.colorScheme.primary
    val max = (points.maxOf { it.totalKb }).coerceAtLeast(1L).toFloat()
    val span = (to - from).coerceAtLeast(1L).toFloat()
    Canvas(Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { contentDescription = description }) {
        fun at(s: MemorySample) = Offset(((s.at - from) / span) * size.width, size.height - (s.totalKb / max) * size.height)
        if (points.size == 1) {
            drawCircle(line, radius = 4.dp.toPx(), center = at(points[0]))
        } else {
            val path = Path()
            points.forEachIndexed { i, s -> at(s).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
