package com.shibi.orbis.ui.browser

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.shibi.orbis.R
import com.shibi.orbis.customtabs.CustomTabActivity
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.tabs.TabUi

/** Windows at least this wide (the unfolded Fold, tablets, a wide split) show a tab strip above the toolbar. */
internal const val TAB_STRIP_MIN_WIDTH_DP = 600

internal fun showsTabStrip(windowWidthDp: Int): Boolean = windowWidthDp >= TAB_STRIP_MIN_WIDTH_DP

internal const val TAB_STRIP_TAG = "tab_strip"

/**
 * A row of the open tabs (title, close button; the current one marked in text for screen readers and by its frame),
 * with New tab at the end. A tab can be dragged out (its address, to another app or window); a link or text dropped on
 * the strip opens in a new tab.
 */
@Composable
internal fun TabStrip(
    tabs: List<TabUi>,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onDropOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val selectedIndex = tabs.indexOfFirst { it.selected }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex) }
    val drop = rememberLinkDropTarget(onDropOpen)
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp)
            .testTag(TAB_STRIP_TAG)
            .dragAndDropTarget(shouldStartDragAndDrop = ::carriesLinkOrText, target = drop),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabChip(tab, onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
            }
        }
        IconButton(onClick = onNewTab) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tabs_new)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(tab: TabUi, onSelect: () -> Unit, onClose: () -> Unit) {
    val host = hostOfUrl(tab.url)
    val title = tab.title.ifBlank { host ?: stringResource(R.string.tabs_blank_title) }
    val container =
        when {
            tab.selected -> MaterialTheme.colorScheme.secondaryContainer
            tab.isPrivate -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
    val content =
        when {
            tab.selected -> MaterialTheme.colorScheme.onSecondaryContainer
            tab.isPrivate -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val currentLabel = stringResource(R.string.tabs_selected_label)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        border = if (tab.selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .width(180.dp)
            .height(36.dp)
            .semantics { selected = tab.selected }
            .then(
                // A private tab's address never leaves the app by drag and drop.
                if (!tab.isPrivate && isWebUrl(tab.url)) {
                    Modifier.dragAndDropSource { _ ->
                        DragAndDropTransferData(
                            ClipData.newPlainText(title, tab.url),
                            flags = View.DRAG_FLAG_GLOBAL,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClickLabel = if (tab.selected) currentLabel else null, onClick = onSelect),
    ) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.isPrivate) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(R.string.tabs_private_mark),
                    modifier = Modifier.size(16.dp).padding(end = 2.dp),
                )
            }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            val closeDescription = stringResource(if (tab.isPrivate) R.string.tabs_close_private_named else R.string.tabs_close_named, title)
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = closeDescription, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A drag that carries text or a link (from another app, a page, or a tab dragged out). */
internal fun carriesLinkOrText(event: DragAndDropEvent): Boolean =
    event.mimeTypes().any { it == ClipDescription.MIMETYPE_TEXT_PLAIN || it == ClipDescription.MIMETYPE_TEXT_URILIST || it.startsWith("text/") }

/** The first text or link in a drop, trimmed, at most 2000 characters; null when there is none. */
internal fun droppedText(event: DragAndDropEvent): String? {
    val clip = event.toAndroidDragEvent().clipData ?: return null
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val text = item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: item.uri?.toString()
        if (!text.isNullOrBlank()) return text.take(2000)
    }
    return null
}

/** A drop target that hands the dropped link or text to [onText] (once per drop). */
@Composable
internal fun rememberLinkDropTarget(onText: (String) -> Unit): DragAndDropTarget {
    val handler = androidx.compose.runtime.rememberUpdatedState(onText)
    return remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val text = droppedText(event) ?: return false
                handler.value(text)
                return true
            }
        }
    }
}

/**
 * Opens [url] in a second Orbis window beside this one (split screen; Android 12L+ on large screens and foldables,
 * else on top). The window is a single page with Close and "Open in Orbis", like a page opened from another app.
 */
internal fun openInSplitScreen(context: Context, url: String): Boolean {
    if (!isWebUrl(url)) return false
    val intent =
        Intent(context, CustomTabActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}
