package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.tabs.TabUi

/**
 * Bottom sheet listing every tab: title and host, the selected one highlighted, a close button per row. Private tabs
 * carry a mark and a tint; the second header row opens a private tab and, while any exist, closes them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcher(
    tabs: List<TabUi>,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.tabs_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onNewTab) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.tabs_new), modifier = Modifier.padding(start = 4.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onNewPrivateTab) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                Text(stringResource(R.string.tabs_new_private), modifier = Modifier.padding(start = 4.dp))
            }
            if (tabs.any { it.isPrivate }) {
                TextButton(onClick = onClosePrivateTabs) { Text(stringResource(R.string.private_close_all)) }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
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
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(tab.id) },
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { host ?: stringResource(R.string.tabs_blank_title) },
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
                        IconButton(onClick = { onClose(tab.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tabs_close))
                        }
                    }
                }
            }
        }
    }
}
