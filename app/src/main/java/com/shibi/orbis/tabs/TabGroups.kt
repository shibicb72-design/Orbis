package com.shibi.orbis.tabs

/**
 * The colour of a tab group. The entry names are written to disk (`tabs.json`): never rename or remove one. An
 * unknown stored name reads as [GREY].
 */
enum class TabGroupColor { GREY, BLUE, RED, YELLOW, GREEN, PINK, PURPLE, CYAN;

    companion object {
        /** The colour stored as [name], or [GREY] when it is missing or unknown. */
        fun fromStored(name: String?): TabGroupColor = entries.firstOrNull { it.name == name } ?: GREY
    }
}

/** A named, coloured group of normal tabs. Private tabs are never in a group. */
data class TabGroup(val id: Long, val name: String, val color: TabGroupColor, val collapsed: Boolean = false)

/** The longest group name, in characters, after trimming. */
const val MAX_TAB_GROUP_NAME_CHARS = 40

/** [raw] trimmed, or null when that is empty or longer than [MAX_TAB_GROUP_NAME_CHARS]. */
fun tabGroupName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_TAB_GROUP_NAME_CHARS }

/** A list of tabs and its groups after [arrangeGroups]. */
internal data class ArrangedGroups<T>(val items: List<T>, val groups: List<TabGroup>)

/**
 * Applies the group rules to [items] (any tab type; [groupOf] reads its group id, [ungroup] clears it):
 * - a reference to a group not in [groups] is cleared;
 * - the members of each group are moved next to each other, at the place of the group's first member, keeping
 *   their order; every other item keeps its order too;
 * - a group with no member is dropped, and the rest are listed in the order of their first member.
 * Idempotent.
 */
internal fun <T> arrangeGroups(
    items: List<T>,
    groups: List<TabGroup>,
    groupOf: (T) -> Long?,
    ungroup: (T) -> T,
): ArrangedGroups<T> {
    val known = groups.associateBy { it.id }
    val cleaned = items.map { item ->
        val group = groupOf(item)
        if (group != null && group !in known) ungroup(item) else item
    }
    val members = LinkedHashMap<Long, MutableList<T>>()
    for (item in cleaned) groupOf(item)?.let { members.getOrPut(it) { ArrayList() } += item }
    val out = ArrayList<T>(cleaned.size)
    val placed = HashSet<Long>()
    for (item in cleaned) {
        val group = groupOf(item)
        if (group == null) {
            out += item
        } else if (placed.add(group)) {
            out += members.getValue(group)
        }
    }
    return ArrangedGroups(out, members.keys.map { known.getValue(it) })
}

/** True when the members of every group in [items] are next to each other. */
internal fun <T> groupsAreContiguous(items: List<T>, groupOf: (T) -> Long?): Boolean {
    val finished = HashSet<Long>()
    var current: Long? = null
    for (item in items) {
        val group = groupOf(item)
        if (group != current) {
            current?.let { finished += it }
            if (group != null && group in finished) return false
            current = group
        }
    }
    return true
}
