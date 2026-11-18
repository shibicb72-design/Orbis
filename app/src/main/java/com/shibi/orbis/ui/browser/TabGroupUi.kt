package com.shibi.orbis.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.tabs.MAX_TAB_GROUP_NAME_CHARS
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import com.shibi.orbis.tabs.TabUi
import com.shibi.orbis.tabs.tabGroupName

/** What the tab switcher can do to groups. Each call goes straight to the tab manager. */
internal class TabGroupActions(
    val onCreate: (tabId: Long, name: String, color: TabGroupColor) -> Unit,
    val onAdd: (tabId: Long, groupId: Long) -> Unit,
    val onRemove: (tabId: Long) -> Unit,
    val onRename: (groupId: Long, name: String) -> Unit,
    val onColor: (groupId: Long, color: TabGroupColor) -> Unit,
    val onCollapsed: (groupId: Long, collapsed: Boolean) -> Unit,
    val onUngroup: (groupId: Long) -> Unit,
    val onCloseGroup: (groupId: Long) -> Unit,
)

/** The dialog the switcher is showing, if any. */
internal sealed interface GroupDialogState {
    data class NewGroup(val tabId: Long) : GroupDialogState
    data class Rename(val group: TabGroup) : GroupDialogState
    data class Recolour(val group: TabGroup) : GroupDialogState
    data class CloseGroup(val group: TabGroup, val tabCount: Int) : GroupDialogState
}

/**
 * The colour as drawn: dark shades on a light surface, light shades on a dark one, so a 3 dp border and a dot stay
 * visible against either (at least 3:1 with the surface). The colour is never the only cue: see [tabGroupColorName].
 */
internal fun TabGroupColor.displayColor(darkSurface: Boolean): Color = Color(
    when (this) {
        TabGroupColor.GREY -> if (darkSurface) 0xFFBDBDBD else 0xFF616161
        TabGroupColor.BLUE -> if (darkSurface) 0xFF90CAF9 else 0xFF1565C0
        TabGroupColor.RED -> if (darkSurface) 0xFFEF9A9A else 0xFFC62828
        TabGroupColor.YELLOW -> if (darkSurface) 0xFFF9E076 else 0xFF8D6E00
        TabGroupColor.GREEN -> if (darkSurface) 0xFFA5D6A7 else 0xFF2E7D32
        TabGroupColor.PINK -> if (darkSurface) 0xFFF48FB1 else 0xFFC2185B
        TabGroupColor.PURPLE -> if (darkSurface) 0xFFCE93D8 else 0xFF6A1B9A
        TabGroupColor.CYAN -> if (darkSurface) 0xFF80DEEA else 0xFF00717D
    },
)

@Composable
internal fun TabGroupColor.themedColor(): Color = displayColor(MaterialTheme.colorScheme.surface.luminance() < 0.5f)

@Composable
internal fun tabGroupColorName(color: TabGroupColor): String = stringResource(
    when (color) {
        TabGroupColor.GREY -> R.string.tab_group_colour_grey
        TabGroupColor.BLUE -> R.string.tab_group_colour_blue
        TabGroupColor.RED -> R.string.tab_group_colour_red
        TabGroupColor.YELLOW -> R.string.tab_group_colour_yellow
        TabGroupColor.GREEN -> R.string.tab_group_colour_green
        TabGroupColor.PINK -> R.string.tab_group_colour_pink
        TabGroupColor.PURPLE -> R.string.tab_group_colour_purple
        TabGroupColor.CYAN -> R.string.tab_group_colour_cyan
    },
)

internal fun groupHeaderTag(groupId: Long) = "tab_group_header_$groupId"

internal fun groupCollapseTag(groupId: Long) = "tab_group_collapse_$groupId"

internal fun groupMenuTag(groupId: Long) = "tab_group_menu_$groupId"

internal fun tabMenuTag(tabId: Long) = "tab_menu_$tabId"

internal const val GROUP_NAME_FIELD_TAG = "tab_group_name_field"

internal fun groupColorTag(color: TabGroupColor) = "tab_group_color_${color.name}"

/** A coloured dot that says its colour in words for a screen reader. */
@Composable
private fun ColorDot(color: TabGroupColor, size: Int = 14) {
    val name = tabGroupColorName(color)
    Box(
        Modifier
            .size(size.dp)
            .background(color.themedColor(), CircleShape)
            .semantics { contentDescription = name },
    )
}

/** The row that opens a group in the grid: colour dot, name, tab count, collapse arrow and the options menu. */
@Composable
internal fun TabGroupHeader(
    group: TabGroup,
    tabCount: Int,
    actions: TabGroupActions,
    onDialog: (GroupDialogState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp).testTag(groupHeaderTag(group.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(group.color)
        Text(
            group.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        )
        Text(
            pluralStringResource(R.plurals.tab_group_count, tabCount, tabCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        val collapseLabel = stringResource(if (group.collapsed) R.string.tab_group_expand else R.string.tab_group_collapse, group.name)
        IconButton(
            onClick = { actions.onCollapsed(group.id, !group.collapsed) },
            modifier = Modifier.testTag(groupCollapseTag(group.id)),
        ) {
            Icon(
                if (group.collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = collapseLabel,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(groupMenuTag(group.id))) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tab_group_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_group_rename)) },
                    onClick = {
                        menuOpen = false
                        onDialog(GroupDialogState.Rename(group))
                    },
                )
                DropdownMenuItem(
                    // The current colour is named in the item itself, so it is never colour alone.
                    text = { Text(stringResource(R.string.tab_group_change_colour)) },
                    onClick = {
                        menuOpen = false
                        onDialog(GroupDialogState.Recolour(group))
                    },
                    leadingIcon = { ColorDot(group.color) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_group_ungroup)) },
                    onClick = {
                        menuOpen = false
                        actions.onUngroup(group.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_group_close)) },
                    onClick = {
                        menuOpen = false
                        onDialog(GroupDialogState.CloseGroup(group, tabCount))
                    },
                )
            }
        }
    }
}

/** The menu of a normal tab card, opened by a long press. [onDismiss] runs for every way it ends. */
@Composable
internal fun TabGroupMenu(
    tab: TabUi,
    groups: List<TabGroup>,
    actions: TabGroupActions,
    onNewGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.testTag(tabMenuTag(tab.id))) {
        if (!picking) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_group_add)) },
                onClick = { picking = true },
            )
            if (tab.groupId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_group_remove)) },
                    onClick = {
                        onDismiss()
                        actions.onRemove(tab.id)
                    },
                )
            }
        } else {
            for (group in groups) {
                if (group.id == tab.groupId) continue
                DropdownMenuItem(
                    text = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { ColorDot(group.color) },
                    onClick = {
                        onDismiss()
                        actions.onAdd(tab.id, group.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_group_new)) },
                onClick = {
                    onDismiss()
                    onNewGroup()
                },
            )
        }
    }
}

/**
 * One dialog for the three edits: a new group (name and colour), a rename (name) and a colour change (colour). The
 * confirm button is disabled until the name is valid ([tabGroupName]); the field stops at [MAX_TAB_GROUP_NAME_CHARS]
 * characters, so a name is never refused after the button was enabled.
 */
@Composable
internal fun TabGroupEditDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialColor: TabGroupColor,
    askName: Boolean,
    askColor: Boolean,
    onConfirm: (name: String, color: TabGroupColor) -> Unit,
    onDismiss: () -> Unit,
) {
    // The cursor starts at the end of an existing name (Rename).
    var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }
    val nameFocus = remember { FocusRequester() }
    // Once, after the first composition: the keyboard comes up with the dialog. Not for the colour-only dialog.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (askName) {
            nameFocus.requestFocus()
            keyboard?.show()
        }
    }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value ->
                            name = if (value.text.length > MAX_TAB_GROUP_NAME_CHARS) {
                                TextFieldValue(value.text.take(MAX_TAB_GROUP_NAME_CHARS), TextRange(MAX_TAB_GROUP_NAME_CHARS))
                            } else {
                                value
                            }
                        },
                        placeholder = { Text(stringResource(R.string.tab_group_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag(GROUP_NAME_FIELD_TAG),
                    )
                }
                if (askColor) {
                    Text(
                        stringResource(R.string.tab_group_colour_label),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = if (askName) 16.dp else 0.dp, bottom = 4.dp),
                    )
                    // Rows of four: a FlowRow in a dialog's text slot never settled its layout under test.
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        for (row in TabGroupColor.entries.chunked(4)) {
                            Row(Modifier.fillMaxWidth()) {
                                for (option in row) {
                                    ColorChoice(option, selected = option == color, Modifier.weight(1f)) { color = option }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.text, color) },
                enabled = !askName || tabGroupName(name.text) != null,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tab_group_cancel)) } },
    )
}

@Composable
private fun ColorChoice(color: TabGroupColor, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val name = tabGroupColorName(color)
    Column(
        modifier
            .testTag(groupColorTag(color))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(28.dp).background(color.themedColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
            }
        }
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

/** "Close N tabs?" before a whole group is closed. */
@Composable
internal fun CloseGroupDialog(tabCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.tab_group_close_title, tabCount, tabCount)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.tab_group_close_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tab_group_cancel)) } },
    )
}
