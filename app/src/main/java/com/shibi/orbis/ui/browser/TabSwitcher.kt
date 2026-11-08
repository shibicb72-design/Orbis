package com.shibi.orbis.ui.browser

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.theme.rememberMotionEnabled

/** How a tab sheet ended; decides where focus goes next (see [tabSheetReturnFocus]). */
internal enum class TabSheetOutcome { SELECTED, NEW_TAB, CLOSED_PRIVATE, DISMISSED }

/**
 * One sheet, one outcome. Selecting a tab, making a new one, closing the private tabs and dismissing all end the sheet;
 * only the first of them wins, so a double tap, or a selection followed by the sheet's own dismiss callback, cannot run
 * two terminal actions (select two tabs, select then open a new tab, ...). Closing a single row does not end the sheet.
 * No timer: the gate is the sheet's own state, and a new sheet starts with a new gate.
 */
internal class TabSheetGate {
    var outcome: TabSheetOutcome? = null
        private set

    fun claim(outcome: TabSheetOutcome): Boolean {
        if (this.outcome != null) return false
        this.outcome = outcome
        return true
    }
}

/** Where focus returns after a sheet ended, in keyboard navigation. In touch mode nothing is focused on purpose. */
internal enum class ReturnFocus { TABS_BUTTON, PAGE, NEW_TAB_ADDRESS }

/**
 * Outside tap, Back or swipe-down returns to the Tabs button that opened the sheet; selecting a tab (or closing the
 * private ones) returns to the page, never to an old address editor; a new tab focuses its own address bar (handled by
 * the new tab itself, only when it was really created).
 */
internal fun tabSheetReturnFocus(outcome: TabSheetOutcome): ReturnFocus =
    when (outcome) {
        TabSheetOutcome.DISMISSED -> ReturnFocus.TABS_BUTTON
        TabSheetOutcome.SELECTED, TabSheetOutcome.CLOSED_PRIVATE -> ReturnFocus.PAGE
        TabSheetOutcome.NEW_TAB -> ReturnFocus.NEW_TAB_ADDRESS
    }

/**
 * Bottom sheet listing every tab: title and host, the selected one marked with a check and a visible "Current tab"
 * label (not color alone), a close button per row that names its tab. Private tabs carry a mark and a tint; the second
 * header row opens a private tab and, while any exist, closes them all. Terminal actions go through [TabSheetGate];
 * [onEnded] reports which one won, once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabSwitcher(
    tabs: List<TabUi>,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onDismiss: () -> Unit,
    onEnded: (TabSheetOutcome) -> Unit = {},
) {
    val motionEnabled = rememberMotionEnabled()
    val gate = remember { TabSheetGate() }
    fun end(outcome: TabSheetOutcome, action: () -> Unit) {
        if (gate.claim(outcome)) {
            action()
            onEnded(outcome)
        }
    }
    // Fully expanded: half-open, the lower part of a long list (often the current tab) sits below the screen edge.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { end(TabSheetOutcome.DISMISSED, onDismiss) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.tabs_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = { end(TabSheetOutcome.NEW_TAB, onNewTab) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.tabs_new), modifier = Modifier.padding(start = 4.dp))
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { end(TabSheetOutcome.NEW_TAB, onNewPrivateTab) }) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                Text(stringResource(R.string.tabs_new_private), modifier = Modifier.padding(start = 4.dp))
            }
            if (tabs.any { it.isPrivate }) {
                TextButton(onClick = { end(TabSheetOutcome.CLOSED_PRIVATE, onClosePrivateTabs) }) {
                    Text(stringResource(R.string.private_close_all))
                }
            }
        }
        // Opens scrolled to the current tab: with many tabs it would otherwise sit below the fold of the half-open sheet.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = tabs.indexOfFirst { it.selected }.coerceAtLeast(0))
        LazyColumn(
            Modifier.weight(1f, fill = false),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                val container =
                    when {
                        tab.selected -> MaterialTheme.colorScheme.secondaryContainer
                        tab.isPrivate -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                val content =
                    when {
                        tab.selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        tab.isPrivate -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                val privateDescription = stringResource(R.string.tabs_private_mark)
                val host = hostOfUrl(tab.url)
                Surface(
                    color = container,
                    contentColor = content,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = if (motionEnabled) tween(180) else null,
                            fadeOutSpec = null,
                        )
                        .fillMaxWidth()
                        .testTag(tabRowTag(tab.id))
                        .semantics { selected = tab.selected }
                        .clickable { end(TabSheetOutcome.SELECTED) { onSelect(tab.id) } },
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tab.isPrivate) {
                            Icon(
                                Icons.Filled.VisibilityOff,
                                contentDescription = privateDescription,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        if (tab.selected) {
                            // The selected row says so in text and shape, not only by its tint.
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                        }
                        val title = tab.title.ifBlank { host ?: stringResource(R.string.tabs_blank_title) }
                        Column(Modifier.weight(1f)) {
                            if (tab.selected) {
                                Text(
                                    stringResource(R.string.tabs_selected_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (host != null) {
                                Text(
                                    host,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val closeDescription =
                            stringResource(if (tab.isPrivate) R.string.tabs_close_private_named else R.string.tabs_close_named, title)
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.testTag(tabCloseTag(tab.id))) {
                            Icon(Icons.Filled.Close, contentDescription = closeDescription)
                        }
                    }
                }
            }
        }
    }
}

internal fun tabRowTag(id: Long) = "tab_row_$id"

internal fun tabCloseTag(id: Long) = "tab_close_$id"
