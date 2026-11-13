package com.shibi.orbis.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.bookmarks.Bookmark
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.home.TopSite
import com.shibi.orbis.home.tileLetter
import com.shibi.orbis.ui.secondaryReadingWidth
import com.shibi.orbis.ui.theme.SceneLayer
import com.shibi.orbis.ui.theme.SceneRegion
import com.shibi.orbis.ui.theme.SceneSurface

internal const val START_SEARCH_TAG = "start_search"

/** Bookmarks the start page shows before "All bookmarks". */
internal const val START_BOOKMARKS_LIMIT = 8

/**
 * The Orbis start page, shown on a blank NORMAL tab (private tabs keep their own notice): a search box that hands focus
 * to the address bar, Top sites from history, and the newest bookmarks. Everything is local; nothing is fetched (no
 * favicons, no remote content), so the page itself makes no network request.
 */
@Composable
fun StartPage(
    topSites: List<TopSite>,
    bookmarks: List<Bookmark>,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onAllBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
    onPin: (TopSite) -> Unit = {},
    onUnpin: (TopSite) -> Unit = {},
    onRemove: (TopSite) -> Unit = {},
    /** Voice search next to the search box; null hides it. */
    onVoice: (() -> Unit)? = null,
    /** The privacy dashboard under the search box; null hides it. */
    dashboard: (@Composable () -> Unit)? = null,
    /** The browser screen's scene, behind everything on the page; null draws none (a static theme, a private tab). */
    scene: SceneSurface? = null,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        SceneLayer(scene, SceneRegion.Page, Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .secondaryReadingWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val searchLabel = stringResource(R.string.address_bar_hint)
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(START_SEARCH_TAG)
                    .clickable(onClick = onSearch)
                    .semantics { role = Role.Button },
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text(searchLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (onVoice != null) {
                        androidx.compose.material3.IconButton(onClick = onVoice) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_search))
                        }
                    }
                }
            }
            dashboard?.invoke()
            if (topSites.isEmpty() && bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.browser_empty_state),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (topSites.isNotEmpty()) {
                Section(stringResource(R.string.start_top_sites)) {
                    TileGrid {
                        topSites.forEach { site ->
                            SiteTile(
                                site,
                                onOpen = { onOpen(site.url) },
                                onPin = { onPin(site) },
                                onUnpin = { onUnpin(site) },
                                onRemove = { onRemove(site) },
                            )
                        }
                    }
                }
            }
            if (bookmarks.isNotEmpty()) {
                Section(stringResource(R.string.menu_bookmarks)) {
                    TileGrid {
                        bookmarks.take(START_BOOKMARKS_LIMIT).forEach { b ->
                            val host = hostOfUrl(b.url) ?: b.url
                            Tile(b.title.ifBlank { host }, host, onClick = { onOpen(b.url) })
                        }
                    }
                    TextButton(onClick = onAllBookmarks) { Text(stringResource(R.string.start_all_bookmarks)) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun TileGrid(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 4,
    ) { content() }
}

/** A Top sites tile: tap opens it; long-press (or the screen reader's actions) pins, unpins or removes it. */
@Composable
private fun SiteTile(site: TopSite, onOpen: () -> Unit, onPin: () -> Unit, onUnpin: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pinLabel = stringResource(if (site.pinned) R.string.start_unpin else R.string.start_pin)
    val removeLabel = stringResource(R.string.start_remove)
    val togglePin = if (site.pinned) onUnpin else onPin
    Box {
        Tile(
            site.title,
            site.host,
            onClick = onOpen,
            onLongClick = { menu = true },
            pinned = site.pinned,
            actions = listOf(
                CustomAccessibilityAction(pinLabel) {
                    togglePin()
                    true
                },
                CustomAccessibilityAction(removeLabel) {
                    onRemove()
                    true
                },
            ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(pinLabel) }, onClick = {
                menu = false
                togglePin()
            })
            DropdownMenuItem(text = { Text(removeLabel) }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}

/** A letter avatar (no favicon fetch) and a one-line label; reads as "title, host" to screen readers. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    title: String,
    host: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    pinned: Boolean = false,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val pinnedSuffix = if (pinned) ", " + stringResource(R.string.start_pinned) else ""
    Column(
        Modifier
            .width(76.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                contentDescription = (if (title == host) host else "$title, $host") + pinnedSuffix
                role = Role.Button
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Text(tileLetter(host), style = MaterialTheme.typography.titleLarge)
                if (pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(14.dp).align(Alignment.TopEnd))
                }
            }
        }
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
