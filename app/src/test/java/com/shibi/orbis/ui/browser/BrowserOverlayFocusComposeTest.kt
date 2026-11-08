package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 2: one outcome per tab sheet and where focus returns. The gate and the return rule are pure; their use in the
 * production sheet is covered by TabSwitcherComposeTest, and BrowserScreen's keyboard-mode return on a device.
 */
class BrowserOverlayFocusComposeTest {
    @Test
    fun theFirstTerminalActionWinsAndEveryLaterOneIsRefused() {
        val gate = TabSheetGate()
        assertNull(gate.outcome)
        assertTrue(gate.claim(TabSheetOutcome.SELECTED))
        for (o in TabSheetOutcome.values()) assertFalse(gate.claim(o))
        assertEquals(TabSheetOutcome.SELECTED, gate.outcome)
    }

    @Test
    fun aSelectionFollowedByTheSheetsOwnDismissStaysASelection() {
        val gate = TabSheetGate()
        gate.claim(TabSheetOutcome.SELECTED)
        assertFalse(gate.claim(TabSheetOutcome.DISMISSED))
        assertEquals(ReturnFocus.PAGE, tabSheetReturnFocus(gate.outcome!!))
    }

    @Test
    fun focusReturnsToTheTabsButtonOnlyWhenTheSheetWasDismissed() {
        assertEquals(ReturnFocus.TABS_BUTTON, tabSheetReturnFocus(TabSheetOutcome.DISMISSED))
        assertEquals(ReturnFocus.PAGE, tabSheetReturnFocus(TabSheetOutcome.SELECTED))
        assertEquals(ReturnFocus.PAGE, tabSheetReturnFocus(TabSheetOutcome.CLOSED_PRIVATE))
        assertEquals(ReturnFocus.NEW_TAB_ADDRESS, tabSheetReturnFocus(TabSheetOutcome.NEW_TAB))
    }

    @Test
    fun noOutcomeEverReturnsToAnOldAddressEditor() {
        // There is no such target: the only address focus is the new tab's own, after it was really created.
        assertEquals(setOf(ReturnFocus.TABS_BUTTON, ReturnFocus.PAGE, ReturnFocus.NEW_TAB_ADDRESS), ReturnFocus.values().toSet())
    }
}
