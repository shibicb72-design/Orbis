package com.shibi.orbis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupsTest {
    private data class Item(val name: String, val group: Long? = null)

    private fun arrange(items: List<Item>, groups: List<TabGroup>) =
        arrangeGroups(items, groups, { it.group }, { it.copy(group = null) })

    private fun group(id: Long, name: String = "G$id") = TabGroup(id, name, TabGroupColor.BLUE)

    @Test
    fun membersAreGatheredAtTheFirstMemberKeepingEveryOtherOrder() {
        val items = listOf(Item("a", 1), Item("b"), Item("c", 2), Item("d", 1), Item("e"), Item("f", 2), Item("g", 1))
        val arranged = arrange(items, listOf(group(1), group(2)))
        assertEquals(listOf("a", "d", "g", "b", "c", "f", "e"), arranged.items.map { it.name })
        assertTrue(groupsAreContiguous(arranged.items) { it.group })
    }

    @Test
    fun aGroupWithNoMemberIsDroppedAndGroupsFollowTheirFirstMember() {
        val arranged = arrange(listOf(Item("a"), Item("b", 3), Item("c", 1)), listOf(group(1), group(2), group(3)))
        assertEquals(listOf(3L, 1L), arranged.groups.map { it.id })
    }

    @Test
    fun aReferenceToAMissingGroupIsCleared() {
        val arranged = arrange(listOf(Item("a", 9), Item("b", 1)), listOf(group(1)))
        assertEquals(listOf(Item("a"), Item("b", 1)), arranged.items)
        assertEquals(listOf(group(1)), arranged.groups)
    }

    @Test
    fun arrangingTwiceChangesNothing() {
        val items = listOf(Item("a", 2), Item("b", 1), Item("c", 2), Item("d"), Item("e", 1))
        val once = arrange(items, listOf(group(1), group(2)))
        assertEquals(once, arrange(once.items, once.groups))
    }

    @Test
    fun noGroupsLeavesTheListAlone() {
        val items = listOf(Item("a"), Item("b"), Item("c"))
        assertEquals(ArrangedGroups(items, emptyList()), arrange(items, emptyList()))
    }

    @Test
    fun contiguityCheck() {
        assertTrue(groupsAreContiguous(listOf(Item("a", 1), Item("b", 1), Item("c"), Item("d", 2))) { it.group })
        assertFalse(groupsAreContiguous(listOf(Item("a", 1), Item("b"), Item("c", 1))) { it.group })
        assertFalse(groupsAreContiguous(listOf(Item("a", 1), Item("b", 2), Item("c", 1))) { it.group })
    }

    @Test
    fun namesAreTrimmedAndOneToFortyCharacters() {
        assertEquals("Work", tabGroupName("  Work \n"))
        assertNull(tabGroupName(""))
        assertNull(tabGroupName("   "))
        val forty = "x".repeat(MAX_TAB_GROUP_NAME_CHARS)
        assertEquals(forty, tabGroupName(" $forty "))
        assertNull(tabGroupName(forty + "y"))
        assertEquals("a", tabGroupName("a"))
    }

    @Test
    fun colourNamesAreFixedAndUnknownOnesReadAsGrey() {
        // Stored on disk: these names must never change.
        assertEquals(
            listOf("GREY", "BLUE", "RED", "YELLOW", "GREEN", "PINK", "PURPLE", "CYAN"),
            TabGroupColor.entries.map { it.name },
        )
        for (c in TabGroupColor.entries) assertEquals(c, TabGroupColor.fromStored(c.name))
        assertEquals(TabGroupColor.GREY, TabGroupColor.fromStored("ORANGE"))
        assertEquals(TabGroupColor.GREY, TabGroupColor.fromStored("blue"))
        assertEquals(TabGroupColor.GREY, TabGroupColor.fromStored(null))
    }
}
