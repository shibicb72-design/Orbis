package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.suggest.Suggestion

internal const val SUGGESTIONS_TAG = "suggestions"

/**
 * The dropdown under the address bar while typing: pages (history or bookmark) and searches. Rows are not focusable,
 * so the keyboard stays up until one is picked; picking submits through the bar's own Go path.
 */
@Composable
internal fun SuggestionList(suggestions: List<Suggestion>, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(SUGGESTIONS_TAG),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        LazyColumn {
            items(suggestions, key = { (if (it is Suggestion.Page) "p:" else "s:") + it.submitText }) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { onPick(s) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon =
                        when {
                            s is Suggestion.Page && s.bookmarked -> Icons.Filled.Bookmark
                            s is Suggestion.Page -> Icons.Filled.History
                            else -> Icons.Filled.Search
                        }
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.padding(start = 16.dp)) {
                        when (s) {
                            is Suggestion.Page -> {
                                Text(s.title.ifBlank { s.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    s.url,
                                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            is Suggestion.Search -> Text(s.query, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
