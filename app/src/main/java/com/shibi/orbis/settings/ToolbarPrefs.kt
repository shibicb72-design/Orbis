package com.shibi.orbis.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.shibi.orbis.ui.browser.MenuAction
import com.shibi.orbis.ui.browser.MenuGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The entries the user can hide and reorder: everything except the icon row (QUICK, always first) and Settings (always
 * last and always shown). Listed in the order the menu ships.
 */
val CUSTOMIZABLE_MENU_ACTIONS: List<MenuAction> =
    MenuAction.entries.filter { it.group != MenuGroup.QUICK && it.group != MenuGroup.SETTINGS }

/** The choices for the one extra toolbar button. [menuAction] is the menu entry it runs; Reload has none (the address bar's own). */
enum class ToolbarButton(val menuAction: MenuAction?) {
    NEW_TAB(MenuAction.NEW_TAB),
    RELOAD(null),
    SHARE(MenuAction.SHARE),
    BOOKMARK_PAGE(MenuAction.BOOKMARK_PAGE),
    FIND(MenuAction.FIND),
    HOME(MenuAction.HOME),
    FORWARD(MenuAction.FORWARD),
    READER(MenuAction.READER),
    DESKTOP_SITE(MenuAction.DESKTOP_SITE),
    DUPLICATE_TAB(MenuAction.DUPLICATE_TAB),
}

/**
 * The menu order for a stored list of action names. Unknown and repeated names are dropped; an action the stored list
 * lacks (added by a later version) goes right after the action that precedes it in the default order. The icon row
 * comes first and Settings last, whatever was stored.
 */
fun mergeMenuOrder(stored: List<String>): List<MenuAction> {
    val middle = stored.mapNotNull { name -> CUSTOMIZABLE_MENU_ACTIONS.firstOrNull { it.name == name } }.distinct().toMutableList()
    for ((index, action) in CUSTOMIZABLE_MENU_ACTIONS.withIndex()) {
        if (action in middle) continue
        // Every earlier default action is already in [middle]: present, or inserted by an earlier turn of this loop.
        val before = CUSTOMIZABLE_MENU_ACTIONS.getOrNull(index - 1)
        middle.add(if (before == null) 0 else middle.indexOf(before) + 1, action)
    }
    return MenuAction.entries.filter { it.group == MenuGroup.QUICK } + middle + MenuAction.SETTINGS
}

/** The hidden entries for a stored set of names. Unknown names, the icon row and Settings are dropped: they cannot be hidden. */
fun mergeMenuHidden(stored: Set<String>): Set<MenuAction> = CUSTOMIZABLE_MENU_ACTIONS.filter { it.name in stored }.toSet()

interface ToolbarPrefs {
    /** [MenuAction] names, top to bottom. Empty: the default order. */
    var menuOrder: List<String>
    var menuHidden: Set<String>

    /** A [ToolbarButton] name, or null for none. */
    var toolbarButton: String?

    fun clear()
}

class SharedPreferencesToolbarPrefs(private val prefs: SharedPreferences) : ToolbarPrefs {
    override var menuOrder: List<String>
        get() = prefs.getString(KEY_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit { putString(KEY_ORDER, value.joinToString(",")) }

    override var menuHidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()
        // A fresh set: the one SharedPreferences hands out must never be edited in place.
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN, value.toSet()) }

    override var toolbarButton: String?
        get() = prefs.getString(KEY_BUTTON, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_BUTTON) else putString(KEY_BUTTON, value) }

    override fun clear() = prefs.edit { clear() }

    companion object {
        const val FILE_NAME = "toolbar_prefs"
        private const val KEY_ORDER = "menu_order"
        private const val KEY_HIDDEN = "menu_hidden"
        private const val KEY_BUTTON = "toolbar_button"
    }
}

/** Settings > Menu and toolbar. The browser screen follows the three flows. */
class ToolbarSettings(private val prefs: ToolbarPrefs) {
    private val _menuOrder = MutableStateFlow(mergeMenuOrder(prefs.menuOrder))
    private val _menuHidden = MutableStateFlow(mergeMenuHidden(prefs.menuHidden))
    private val _toolbarButton = MutableStateFlow(readButton())

    /** Every menu action in the order shown: the icon row, the customizable entries, then Settings. */
    val menuOrder: StateFlow<List<MenuAction>> = _menuOrder.asStateFlow()

    /** The hidden actions. Never holds Settings or an icon-row action. */
    val menuHidden: StateFlow<Set<MenuAction>> = _menuHidden.asStateFlow()
    val toolbarButton: StateFlow<ToolbarButton?> = _toolbarButton.asStateFlow()

    private fun readButton() = ToolbarButton.entries.firstOrNull { it.name == prefs.toolbarButton }

    /** Shows or hides [action]. Returns false, and changes nothing, for Settings and the icon row. */
    fun setShown(action: MenuAction, shown: Boolean): Boolean {
        if (action !in CUSTOMIZABLE_MENU_ACTIONS) return false
        val hidden = if (shown) _menuHidden.value - action else _menuHidden.value + action
        prefs.menuHidden = hidden.mapTo(mutableSetOf()) { it.name }
        _menuHidden.value = mergeMenuHidden(prefs.menuHidden)
        return true
    }

    fun canMoveUp(action: MenuAction): Boolean = customizable().indexOf(action) > 0

    fun canMoveDown(action: MenuAction): Boolean = customizable().indexOf(action).let { it >= 0 && it < customizable().lastIndex }

    fun moveUp(action: MenuAction): Boolean = move(action, -1)

    fun moveDown(action: MenuAction): Boolean = move(action, 1)

    private fun customizable() = _menuOrder.value.filter { it in CUSTOMIZABLE_MENU_ACTIONS }

    private fun move(action: MenuAction, step: Int): Boolean {
        val list = customizable().toMutableList()
        val from = list.indexOf(action)
        val to = from + step
        if (from < 0 || to !in list.indices) return false
        list[from] = list[to].also { list[to] = list[from] }
        prefs.menuOrder = list.map { it.name }
        _menuOrder.value = mergeMenuOrder(prefs.menuOrder)
        return true
    }

    fun setToolbarButton(button: ToolbarButton?) {
        prefs.toolbarButton = button?.name
        _toolbarButton.value = readButton()
    }

    /** The shipped menu, nothing hidden, no toolbar button. */
    fun reset() {
        prefs.clear()
        _menuOrder.value = mergeMenuOrder(prefs.menuOrder)
        _menuHidden.value = mergeMenuHidden(prefs.menuHidden)
        _toolbarButton.value = readButton()
    }
}
