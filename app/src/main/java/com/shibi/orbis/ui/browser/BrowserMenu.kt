package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.engine.TranslationInfo
import com.shibi.orbis.engine.isWebUrl

/**
 * Every action the page menu can offer. The names are stable ids (they will be stored by the customizable menu): never
 * rename one. Each action belongs to one [MenuGroup]; the menu draws a divider where the group changes.
 */
enum class MenuAction(val group: MenuGroup) {
    // The row of icon buttons at the top.
    HOME(MenuGroup.QUICK),
    FORWARD(MenuGroup.QUICK),
    BOOKMARK_PAGE(MenuGroup.QUICK),
    SHARE(MenuGroup.QUICK),

    // Page
    PRINT(MenuGroup.PAGE),
    CAST(MenuGroup.PAGE),
    ADD_TO_HOME(MenuGroup.PAGE),
    SPLIT_SCREEN(MenuGroup.PAGE),
    READER(MenuGroup.PAGE),
    TRANSLATE(MenuGroup.PAGE),
    FIND(MenuGroup.PAGE),
    SITE_SETTINGS(MenuGroup.PAGE),
    GENERATE_PASSWORD(MenuGroup.PAGE),
    DESKTOP_SITE(MenuGroup.PAGE),

    // Tabs
    NEW_TAB(MenuGroup.TABS),
    NEW_PRIVATE_TAB(MenuGroup.TABS),
    SCAN_QR(MenuGroup.TABS),

    // Library
    BOOKMARKS(MenuGroup.LIBRARY),
    HISTORY(MenuGroup.LIBRARY),
    DOWNLOADS(MenuGroup.LIBRARY),

    // Settings
    SETTINGS(MenuGroup.SETTINGS),
}

/** Where a [MenuAction] sits: the icon row, or Page / Tabs / Library / Settings. A divider separates two groups. */
enum class MenuGroup { QUICK, PAGE, TABS, LIBRARY, SETTINGS }

/** The menu as it ships: the icon row, then Page, Tabs, Library and Settings. */
val DEFAULT_MENU_ORDER: List<MenuAction> = MenuAction.entries

/** What [menuItemState] needs to know about the page. [sessionIsPrivate] is null when the tab has no session. */
data class MenuPageState(
    val hasSession: Boolean,
    val sessionIsPrivate: Boolean?,
    val url: String,
    val isLoading: Boolean,
    val canGoForward: Boolean,
    val readerable: Boolean,
    val translation: TranslationInfo?,
    val desktopMode: Boolean,
    val bookmarked: Boolean,
    val casting: Boolean,
)

/** The window the menu is drawn in: split-screen entries depend on it. */
data class MenuWindow(val widthDp: Int, val inMultiWindow: Boolean)

/**
 * How one action looks right now. [checked] is null for a plain entry; for BOOKMARK_PAGE it means "bookmarked", for
 * DESKTOP_SITE "desktop mode is on" (a checkbox) and for CAST "casting now" (the connected icon).
 */
data class MenuItemState(
    val visible: Boolean,
    val enabled: Boolean,
    val labelRes: Int,
    val checked: Boolean? = null,
)

/** Today's visibility, enabled and label rules for [action]. Pure: no composition, no session calls. */
fun menuItemState(action: MenuAction, page: MenuPageState, window: MenuWindow): MenuItemState {
    val web = isWebUrl(page.url)
    val notPrivate = page.sessionIsPrivate == false
    return when (action) {
        MenuAction.HOME -> MenuItemState(true, page.hasSession, R.string.menu_home)
        MenuAction.FORWARD -> MenuItemState(true, page.canGoForward, R.string.forward)
        MenuAction.BOOKMARK_PAGE ->
            MenuItemState(
                true,
                web,
                if (page.bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark,
                checked = page.bookmarked,
            )
        MenuAction.SHARE -> MenuItemState(true, shareTargetFor(page.url, page.hasSession) != ShareTarget.NONE, R.string.menu_share)
        MenuAction.PRINT -> MenuItemState(true, page.hasSession && web && !page.isLoading, R.string.menu_print)
        MenuAction.CAST -> MenuItemState(true, page.hasSession, R.string.menu_cast, checked = page.casting)
        // Not from a private tab: the shortcut would keep the address on the home screen.
        MenuAction.ADD_TO_HOME -> MenuItemState(true, web && notPrivate, R.string.menu_add_home)
        // Not a private page: the second window is a normal (non-private) one.
        MenuAction.SPLIT_SCREEN ->
            MenuItemState(showsTabStrip(window.widthDp) || window.inMultiWindow, web && notPrivate, R.string.menu_split_screen)
        MenuAction.READER -> MenuItemState(true, page.readerable && page.hasSession, R.string.menu_reader)
        MenuAction.TRANSLATE -> {
            val translated = page.translation?.translatedTo?.isNotEmpty() == true
            // Shown only when the page can be translated (or already is): most pages are in the phone's language.
            MenuItemState(
                page.hasSession && (translated || page.translation?.canTranslate == true),
                true,
                if (translated) R.string.menu_show_original else R.string.menu_translate,
            )
        }
        MenuAction.FIND -> MenuItemState(true, page.hasSession, R.string.menu_find)
        MenuAction.SITE_SETTINGS -> MenuItemState(true, page.hasSession && web, R.string.menu_site_settings)
        MenuAction.GENERATE_PASSWORD -> MenuItemState(true, true, R.string.menu_generate_password)
        MenuAction.DESKTOP_SITE -> MenuItemState(true, page.hasSession, R.string.menu_desktop_site, checked = page.desktopMode)
        MenuAction.NEW_TAB -> MenuItemState(true, true, R.string.menu_new_tab)
        MenuAction.NEW_PRIVATE_TAB -> MenuItemState(true, true, R.string.menu_new_private_tab)
        MenuAction.SCAN_QR -> MenuItemState(true, true, R.string.menu_scan_qr)
        MenuAction.BOOKMARKS -> MenuItemState(true, true, R.string.menu_bookmarks)
        MenuAction.HISTORY -> MenuItemState(true, true, R.string.menu_history)
        MenuAction.DOWNLOADS -> MenuItemState(true, true, R.string.menu_downloads)
        MenuAction.SETTINGS -> MenuItemState(true, true, R.string.settings)
    }
}

/**
 * The page menu: the QUICK actions as one row of icon buttons, the others as text entries, a divider between groups.
 * Choosing an action closes the menu ([onDismiss]) and then calls [onAction]; the caller runs what the action does.
 */
@Composable
fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    order: List<MenuAction>,
    stateOf: (MenuAction, MenuWindow) -> MenuItemState,
    onAction: (MenuAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Read inside the popup, as the menu always did.
        val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
        val androidContext = LocalContext.current
        val inMultiWindow = (androidContext as? android.app.Activity)?.isInMultiWindowMode == true
        val window = MenuWindow(widthDp, inMultiWindow)
        val shown = order.filter { stateOf(it, window).visible }
        var previous: MenuGroup? = null
        var index = 0
        while (index < shown.size) {
            val group = shown[index].group
            if (previous != null) HorizontalDivider()
            previous = group
            val run = shown.drop(index).takeWhile { it.group == group }
            index += run.size
            if (group == MenuGroup.QUICK) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (action in run) {
                        val item = stateOf(action, window)
                        IconButton(
                            enabled = item.enabled,
                            onClick = {
                                onDismiss()
                                onAction(action)
                            },
                        ) { Icon(quickIcon(action, item), contentDescription = stringResource(item.labelRes)) }
                    }
                }
            } else {
                for (action in run) {
                    val item = stateOf(action, window)
                    DropdownMenuItem(
                        text = { Text(stringResource(item.labelRes)) },
                        leadingIcon = entryIcon(action, item)?.let { icon -> { Icon(icon, contentDescription = null) } },
                        trailingIcon = if (action == MenuAction.DESKTOP_SITE) {
                            { Checkbox(checked = item.checked == true, onCheckedChange = null) }
                        } else {
                            null
                        },
                        enabled = item.enabled,
                        onClick = {
                            onDismiss()
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}

private fun quickIcon(action: MenuAction, item: MenuItemState): ImageVector =
    when (action) {
        MenuAction.HOME -> Icons.Filled.Home
        MenuAction.FORWARD -> Icons.AutoMirrored.Filled.ArrowForward
        MenuAction.BOOKMARK_PAGE -> if (item.checked == true) Icons.Filled.Star else Icons.Outlined.StarBorder
        else -> Icons.Filled.Share
    }

/** The leading icon of a text entry; null for the entries that have none (as before). */
private fun entryIcon(action: MenuAction, item: MenuItemState): ImageVector? =
    when (action) {
        MenuAction.PRINT -> Icons.Filled.Print
        MenuAction.CAST -> if (item.checked == true) Icons.Filled.CastConnected else Icons.Filled.Cast
        MenuAction.ADD_TO_HOME -> Icons.AutoMirrored.Filled.AddToHomeScreen
        MenuAction.SPLIT_SCREEN -> Icons.Filled.VerticalSplit
        MenuAction.READER -> Icons.AutoMirrored.Filled.Article
        MenuAction.TRANSLATE -> Icons.Filled.Translate
        MenuAction.SITE_SETTINGS -> Icons.Filled.Tune
        MenuAction.GENERATE_PASSWORD -> Icons.Filled.Password
        MenuAction.SCAN_QR -> Icons.Filled.QrCodeScanner
        else -> null
    }
