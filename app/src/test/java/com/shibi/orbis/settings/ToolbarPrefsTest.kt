package com.shibi.orbis.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.ui.browser.DEFAULT_MENU_ORDER
import com.shibi.orbis.ui.browser.MenuAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ToolbarPrefsTest {
    private class FakePrefs : ToolbarPrefs {
        override var menuOrder: List<String> = emptyList()
        override var menuHidden: Set<String> = emptySet()
        override var toolbarButton: String? = null

        override fun clear() {
            menuOrder = emptyList()
            menuHidden = emptySet()
            toolbarButton = null
        }
    }

    private fun names(vararg actions: MenuAction) = actions.map { it.name }

    @Test
    fun freshInstallIsTheShippedMenuAndNoToolbarButton() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences(SharedPreferencesToolbarPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val settings = ToolbarSettings(SharedPreferencesToolbarPrefs(sp))
        assertEquals(DEFAULT_MENU_ORDER, settings.menuOrder.value)
        assertTrue(settings.menuHidden.value.isEmpty())
        assertNull(settings.toolbarButton.value)
    }

    @Test
    fun anActionMissingFromAStoredOrderIsInsertedInItsDefaultPlace() {
        val withoutDuplicate = DEFAULT_MENU_ORDER.filter { it != MenuAction.DUPLICATE_TAB }.map { it.name }
        assertEquals(DEFAULT_MENU_ORDER, mergeMenuOrder(withoutDuplicate))

        // A user's own order: Library first, reversed. The new action still follows its default predecessor.
        val reversed = withoutDuplicate.drop(4).dropLast(1).reversed()
        val merged = mergeMenuOrder(reversed).drop(4).dropLast(1)
        // The stored order is kept; only the missing action is added.
        assertEquals(reversed, merged.filter { it != MenuAction.DUPLICATE_TAB }.map { it.name })
        assertEquals(merged.indexOf(MenuAction.NEW_PRIVATE_TAB) + 1, merged.indexOf(MenuAction.DUPLICATE_TAB))

        val settings = ToolbarSettings(FakePrefs().apply { menuOrder = withoutDuplicate })
        assertEquals(DEFAULT_MENU_ORDER, settings.menuOrder.value)
    }

    @Test
    fun anActionAddedAtTheStartOfTheDefaultOrderGoesFirstAndUnknownNamesAreDropped() {
        val stored = listOf("NO_SUCH_ACTION") + DEFAULT_MENU_ORDER.filter { it != MenuAction.PRINT }.map { it.name } + "PRINT_OLD"
        val merged = mergeMenuOrder(stored)
        assertEquals(DEFAULT_MENU_ORDER, merged)
        assertEquals(DEFAULT_MENU_ORDER.size, merged.size)
    }

    @Test
    fun theIconRowIsFirstAndSettingsLastWhateverIsStored() {
        val stored = names(MenuAction.SETTINGS, MenuAction.SHARE, MenuAction.HISTORY, MenuAction.HOME, MenuAction.PRINT)
        val merged = mergeMenuOrder(stored)
        assertEquals(DEFAULT_MENU_ORDER.take(4), merged.take(4))
        assertEquals(MenuAction.SETTINGS, merged.last())
        assertEquals(DEFAULT_MENU_ORDER.toSet(), merged.toSet())
        assertTrue(merged.indexOf(MenuAction.HISTORY) < merged.indexOf(MenuAction.PRINT))
    }

    @Test
    fun hidingSettingsIsRefusedAndAStoredSettingsHideIsIgnored() {
        val prefs = FakePrefs().apply { menuHidden = (names(MenuAction.SETTINGS, MenuAction.HOME, MenuAction.HISTORY) + "NO_SUCH_ACTION").toSet() }
        val settings = ToolbarSettings(prefs)
        assertEquals(setOf(MenuAction.HISTORY), settings.menuHidden.value)
        assertFalse(settings.setShown(MenuAction.SETTINGS, false))
        assertFalse(settings.setShown(MenuAction.HOME, false))
        assertFalse(MenuAction.SETTINGS in settings.menuHidden.value)
        assertEquals(MenuAction.SETTINGS, settings.menuOrder.value.last())
        assertTrue(settings.setShown(MenuAction.PRINT, false))
        assertEquals(setOf(MenuAction.HISTORY, MenuAction.PRINT), settings.menuHidden.value)
        assertTrue(settings.setShown(MenuAction.PRINT, true))
        assertEquals(setOf(MenuAction.HISTORY), settings.menuHidden.value)
    }

    @Test
    fun movesSwapNeighboursAreStoredAndStopAtTheEnds() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences(SharedPreferencesToolbarPrefs.FILE_NAME, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val settings = ToolbarSettings(SharedPreferencesToolbarPrefs(sp))
        assertFalse(settings.canMoveUp(MenuAction.PRINT))
        assertFalse(settings.moveUp(MenuAction.PRINT))
        assertFalse(settings.canMoveDown(MenuAction.DOWNLOADS))
        assertFalse(settings.moveDown(MenuAction.DOWNLOADS))
        assertFalse(settings.moveDown(MenuAction.SETTINGS))
        assertTrue(settings.moveDown(MenuAction.PRINT))
        val expected = DEFAULT_MENU_ORDER.toMutableList().also {
            val a = it.indexOf(MenuAction.PRINT)
            it[a] = MenuAction.CAST
            it[a + 1] = MenuAction.PRINT
        }
        assertEquals(expected, settings.menuOrder.value)
        // A new object over the same file reads the stored order back.
        assertEquals(expected, ToolbarSettings(SharedPreferencesToolbarPrefs(sp)).menuOrder.value)
    }

    @Test
    fun theToolbarButtonIsStoredByNameAndAnUnknownNameMeansNone() {
        val prefs = FakePrefs()
        val settings = ToolbarSettings(prefs)
        settings.setToolbarButton(ToolbarButton.RELOAD)
        assertEquals("RELOAD", prefs.toolbarButton)
        assertEquals(ToolbarButton.RELOAD, settings.toolbarButton.value)
        settings.setToolbarButton(null)
        assertNull(settings.toolbarButton.value)
        assertNull(ToolbarSettings(FakePrefs().apply { toolbarButton = "FROM_THE_FUTURE" }).toolbarButton.value)
    }

    @Test
    fun resetRestoresTheShippedMenuAndRemovesTheButton() {
        val settings = ToolbarSettings(FakePrefs())
        settings.moveDown(MenuAction.PRINT)
        settings.setShown(MenuAction.CAST, false)
        settings.setToolbarButton(ToolbarButton.FIND)
        settings.reset()
        assertEquals(DEFAULT_MENU_ORDER, settings.menuOrder.value)
        assertTrue(settings.menuHidden.value.isEmpty())
        assertNull(settings.toolbarButton.value)
    }

    @Test
    fun everyToolbarChoiceExceptReloadRunsAMenuEntry() {
        for (button in ToolbarButton.entries) {
            if (button == ToolbarButton.RELOAD) assertNull(button.menuAction) else assertTrue(button.name, button.menuAction != null)
        }
        assertEquals(10, ToolbarButton.entries.size)
    }
}
