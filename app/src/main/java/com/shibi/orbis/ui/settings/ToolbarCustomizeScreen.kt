package com.shibi.orbis.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.settings.CUSTOMIZABLE_MENU_ACTIONS
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.settings.ToolbarSettings
import com.shibi.orbis.ui.OrbisTopAppBar
import com.shibi.orbis.ui.browser.MenuAction
import com.shibi.orbis.ui.browser.MenuPageState
import com.shibi.orbis.ui.browser.MenuWindow
import com.shibi.orbis.ui.browser.menuItemState

/** A page with nothing special about it: the label [menuItemState] gives an action then is its plain name. */
private val plainPage =
    MenuPageState(
        hasSession = true,
        sessionIsPrivate = false,
        url = "",
        isLoading = false,
        canGoForward = false,
        readerable = false,
        translation = null,
        desktopMode = false,
        bookmarked = false,
        casting = false,
    )

@StringRes
internal fun menuActionLabel(action: MenuAction): Int = menuItemState(action, plainPage, MenuWindow(widthDp = 400, inMultiWindow = false)).labelRes

/** The name of a toolbar button choice in the choice list and under the "Toolbar button" row. */
@StringRes
internal fun toolbarButtonLabel(button: ToolbarButton?): Int =
    when (button) {
        null -> R.string.toolbar_button_none
        ToolbarButton.RELOAD -> R.string.reload
        ToolbarButton.BOOKMARK_PAGE -> R.string.toolbar_button_bookmark
        else -> menuActionLabel(checkNotNull(button.menuAction))
    }

internal fun menuRowTag(action: MenuAction) = "menu_row_${action.name}"

internal fun menuShowTag(action: MenuAction) = "menu_show_${action.name}"

/** Settings > Menu and toolbar. Every change is stored at once; the browser screen follows [ToolbarSettings]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarCustomizeScreen(settings: ToolbarSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val order by settings.menuOrder.collectAsStateWithLifecycle()
    val hidden by settings.menuHidden.collectAsStateWithLifecycle()
    val button by settings.toolbarButton.collectAsStateWithLifecycle()
    var choosingButton by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    if (choosingButton) {
        ChoiceDialog(
            title = R.string.toolbar_button_title,
            options = (listOf<ToolbarButton?>(null) + ToolbarButton.entries).map { it to toolbarButtonLabel(it) },
            selected = button,
            onSelect = {
                settings.setToolbarButton(it)
                choosingButton = false
            },
            onDismiss = { choosingButton = false },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.toolbar_reset_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.reset()
                        confirmReset = false
                    },
                ) { Text(stringResource(R.string.toolbar_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.prompt_cancel)) } },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.toolbar_menu_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                containerColor = colors.background,
                contentColor = colors.onBackground,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.toolbar_button_title)) },
                supportingContent = { Text(stringResource(toolbarButtonLabel(button))) },
                modifier = Modifier.fillMaxWidth().clickable { choosingButton = true },
            )
            HorizontalDivider(color = colors.outlineVariant)
            Text(
                text = stringResource(R.string.toolbar_menu_section),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics { heading() },
            )
            Text(
                text = stringResource(R.string.toolbar_menu_note),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val rows = order.filter { it in CUSTOMIZABLE_MENU_ACTIONS }
            for (action in rows) {
                val label = stringResource(menuActionLabel(action))
                MenuRow(
                    label = label,
                    shown = action !in hidden,
                    onShownChange = { settings.setShown(action, it) },
                    canMoveUp = settings.canMoveUp(action),
                    canMoveDown = settings.canMoveDown(action),
                    onMoveUp = { settings.moveUp(action) },
                    onMoveDown = { settings.moveDown(action) },
                    modifier = Modifier.testTag(menuRowTag(action)),
                    showTag = menuShowTag(action),
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
            // Settings is the last entry and cannot be hidden or moved: its row is there so the list reads as the menu does.
            MenuRow(
                label = stringResource(R.string.settings),
                shown = true,
                onShownChange = {},
                canMoveUp = false,
                canMoveDown = false,
                onMoveUp = {},
                onMoveDown = {},
                modifier = Modifier.testTag(menuRowTag(MenuAction.SETTINGS)),
                showTag = menuShowTag(MenuAction.SETTINGS),
                pinned = true,
            )
            HorizontalDivider(color = colors.outlineVariant)
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.toolbar_reset)) },
                trailingContent = {},
                modifier = Modifier.fillMaxWidth().clickable { confirmReset = true },
            )
        }
    }
}

/** One menu entry: its name, a "Show" checkbox, and the two move buttons. The name is in the button descriptions. */
@Composable
private fun MenuRow(
    label: String,
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier,
    showTag: String,
    pinned: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (pinned) {
            Text(
                stringResource(R.string.toolbar_settings_pinned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .testTag(showTag)
                    .toggleable(value = shown, enabled = !pinned, role = Role.Checkbox, onValueChange = onShownChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = shown, onCheckedChange = null, enabled = !pinned)
                Text(stringResource(R.string.toolbar_show), modifier = Modifier.padding(start = 8.dp))
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.toolbar_move_up, label))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.toolbar_move_down, label))
            }
        }
    }
}
