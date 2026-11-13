package com.shibi.orbis.ui.browser

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import com.shibi.orbis.R
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.ui.theme.TAB_ENTER_MS
import com.shibi.orbis.ui.theme.TAB_EXIT_MS
import com.shibi.orbis.ui.theme.TAB_PLACEMENT_MS
import com.shibi.orbis.ui.theme.motionTweenOrNull
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
    /** Page previews by tab id; a tab without one shows its site's letter. */
    thumbnails: Map<Long, ImageBitmap> = emptyMap(),
    /** Opens the recently closed tabs list; null hides the button (nothing closed). */
    onRecentlyClosed: (() -> Unit)? = null,
    /** The "Tab closed / Undo" bar, shown inside the sheet (the screen's own one is behind it). */
    snackbars: SnackbarHostState? = null,
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
            if (onRecentlyClosed != null) {
                TextButton(onClick = { end(TabSheetOutcome.DISMISSED, onRecentlyClosed) }) {
                    Icon(Icons.Filled.History, contentDescription = null)
                    Text(stringResource(R.string.tabs_recently_closed), modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (tabs.any { it.isPrivate }) {
                TextButton(onClick = { end(TabSheetOutcome.CLOSED_PRIVATE, onClosePrivateTabs) }) {
                    Text(stringResource(R.string.private_close_all))
                }
            }
        }
        // Opens scrolled to the current tab: with many tabs it would otherwise sit below the fold.
        val selectedIndex = tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex)
        // The first placement happens before the sheet has its final height; once it is open, bring the current tab fully
        // into view if it is not (never moves a grid the user can already see the current tab in).
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.currentValue }.first { it == SheetValue.Expanded }
            val info = gridState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            val fullyVisible =
                item != null && item.offset.y >= 0 && item.offset.y + item.size.height <= info.viewportEndOffset - info.afterContentPadding
            if (!fullyVisible) gridState.scrollToItem(selectedIndex)
        }
        // Above the grid: below it, the grid's remaining height would push it off the sheet.
        snackbars?.let { SnackbarHost(it, Modifier.fillMaxWidth()) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.weight(1f, fill = false).selectableGroup(),
            state = gridState,
            // The last row (often the current tab, scrolled to) must clear the gesture/navigation bar.
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                val title = tab.title.ifBlank { host ?: stringResource(R.string.tabs_blank_title) }
                Surface(
                    color = container,
                    contentColor = content,
                    shape = MaterialTheme.shapes.medium,
                    // The current tab has a visible frame as well as its label (not color alone).
                    border = if (tab.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = motionTweenOrNull(motionEnabled, TAB_ENTER_MS),
                            placementSpec = motionTweenOrNull(motionEnabled, TAB_PLACEMENT_MS),
                            fadeOutSpec = motionTweenOrNull(motionEnabled, TAB_EXIT_MS),
                        )
                        .fillMaxWidth()
                        .testTag(tabRowTag(tab.id))
                        .selectable(selected = tab.selected, role = Role.Tab) {
                            end(TabSheetOutcome.SELECTED) { onSelect(tab.id) }
                        },
                ) {
                    Column {
                        Row(
                            Modifier.padding(start = 10.dp, end = 0.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tab.isPrivate) {
                                Icon(
                                    Icons.Filled.VisibilityOff,
                                    contentDescription = privateDescription,
                                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                                )
                            }
                            if (tab.selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                if (tab.selected) {
                                    Text(
                                        stringResource(R.string.tabs_selected_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // The card already announces itself as the selected tab. The words stay
                                        // visible. Clearing semantics keeps TalkBack from reading them again.
                                        modifier = Modifier.clearAndSetSemantics { this[SemanticsProperties.TestTag] = CURRENT_TAB_LABEL_TAG },
                                    )
                                }
                                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val closeDescription =
                                stringResource(if (tab.isPrivate) R.string.tabs_close_private_named else R.string.tabs_close_named, title)
                            IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.testTag(tabCloseTag(tab.id))) {
                                Icon(Icons.Filled.Close, contentDescription = closeDescription)
                            }
                        }
                        TabPreview(
                            image = thumbnails[tab.id]?.takeIf { host != null },
                            letter = (host?.removePrefix("www.")?.firstOrNull()?.uppercaseChar() ?: '+').toString(),
                            host = host,
                        )
                    }
                }
            }
        }
    }
}

/** A tab's page preview, or (none taken yet, e.g. a restored tab) its site's letter and host. Decorative for screen readers. */
@Composable
private fun TabPreview(image: ImageBitmap?, letter: String, host: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp).fillMaxWidth().aspectRatio(0.8f).clearAndSetSemantics {},
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(letter, style = MaterialTheme.typography.displaySmall)
                if (host != null) {
                    Text(
                        host,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

internal const val CURRENT_TAB_LABEL_TAG = "current_tab_label"

internal fun tabRowTag(id: Long) = "tab_row_$id"

internal fun tabCloseTag(id: Long) = "tab_close_$id"
