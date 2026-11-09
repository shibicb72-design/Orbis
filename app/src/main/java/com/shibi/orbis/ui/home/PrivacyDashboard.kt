package com.shibi.orbis.ui.home

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
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
import com.shibi.orbis.stats.BlockKind
import com.shibi.orbis.stats.StatsSummary
import java.text.NumberFormat

internal const val PRIVACY_DASHBOARD_TAG = "privacy_dashboard"

@StringRes
private fun kindLabel(k: BlockKind): Int =
    when (k) {
        BlockKind.AD -> R.string.stats_ads
        BlockKind.TRACKER -> R.string.stats_trackers
        BlockKind.SOCIAL -> R.string.stats_social
        BlockKind.FINGERPRINTER -> R.string.stats_fingerprinters
        BlockKind.CRYPTOMINER -> R.string.stats_cryptominers
        BlockKind.COOKIE -> R.string.stats_cookies
    }

/** "1 h 5 min", "3 min", "40 s": a short duration for the time saved. */
internal fun shortDuration(millis: Long): Pair<Long, Int> {
    val s = millis / 1000
    return when {
        s >= 3600 -> s / 3600 to 2
        s >= 60 -> s / 60 to 1
        else -> s to 0
    }
}

/**
 * The start page's privacy dashboard: what Orbis blocked (by kind), estimated data and time saved, data used, a bar per
 * day, and the trackers blocked most. Week or all time. All counts come from this phone only.
 */
@Composable
internal fun PrivacyDashboard(
    week: StatsSummary,
    allTime: StatsSummary,
    uboActive: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val s = if (showAll) allTime else week
    val context = LocalContext.current
    val nf = remember { NumberFormat.getIntegerInstance() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag(PRIVACY_DASHBOARD_TAG),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(
                    stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text(stringResource(R.string.stats_week)) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text(stringResource(R.string.stats_all_time)) })
            }
            // The headline number.
            Column {
                Text(nf.format(s.totalBlocked), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text(
                    pluralStringResource(R.plurals.stats_blocked_headline, s.totalBlocked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Saved and used.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (n, unit) = shortDuration(s.estimatedMillisSaved)
                StatTile(
                    value =
                        when (unit) {
                            2 -> stringResource(R.string.stats_hours, n)
                            1 -> stringResource(R.string.stats_minutes, n)
                            else -> if (n == 0L && s.estimatedMillisSaved > 0) stringResource(R.string.stats_under_second) else stringResource(R.string.stats_seconds, n)
                        },
                    label = stringResource(R.string.stats_time_saved),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = Formatter.formatShortFileSize(context, s.estimatedBytesSaved),
                    label = stringResource(R.string.stats_data_saved),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = Formatter.formatShortFileSize(context, s.bytesUsed),
                    label = stringResource(R.string.stats_data_used),
                    modifier = Modifier.weight(1f),
                )
            }
            // Breakdown.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockKind.entries.forEach { k ->
                    val n = s.byKind[k] ?: 0L
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            stringResource(kindLabel(k)) + "  " + nf.format(n),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (!showAll) DayBars(week)
            if (s.topTrackers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.stats_top_trackers), style = MaterialTheme.typography.titleSmall)
                    s.topTrackers.forEach { (host, n) ->
                        Row {
                            Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(nf.format(n), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(
                stringResource(if (uboActive) R.string.stats_note_ubo else R.string.stats_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.since?.let {
                    Text(stringResource(R.string.stats_since, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text(stringResource(R.string.stats_reset)) }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

/** Seven bars, one per day (blocked items), with the weekday initial under each. Read as one sentence by screen readers. */
@Composable
private fun DayBars(week: StatsSummary) {
    val max = week.days.maxOfOrNull { it.totalBlocked }?.coerceAtLeast(1L) ?: 1L
    val description = week.days.joinToString { "${it.day}: ${it.totalBlocked}" }
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surface
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth().height(84.dp).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.days.forEach { d ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth().background(track, RoundedCornerShape(6.dp)), contentAlignment = Alignment.BottomCenter) {
                    val frac = (d.totalBlocked.toFloat() / max).coerceIn(0f, 1f)
                    if (frac > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(bar, RoundedCornerShape(6.dp)))
                }
                Text(
                    runCatching { java.time.LocalDate.parse(d.day).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale) }.getOrDefault(""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun <T> remember(calculation: () -> T): T = androidx.compose.runtime.remember(calculation)
