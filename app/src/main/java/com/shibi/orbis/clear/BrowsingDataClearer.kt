package com.shibi.orbis.clear

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What the user ticked in Clear browsing data. The defaults are the ones the panel opens with. */
data class ClearSelection(
    val history: Boolean = true,
    val cookiesAndSiteData: Boolean = true,
    val cache: Boolean = true,
    val downloadsList: Boolean = false,
    val openTabs: Boolean = false,
    /** Never on unless the user ticks it: bookmarks are the one thing here that cannot be rebuilt. */
    val bookmarks: Boolean = false,
    /** Saved logins. Never on unless the user ticks it, like bookmarks. */
    val passwords: Boolean = false,
) {
    val isEmpty: Boolean get() = !(history || cookiesAndSiteData || cache || downloadsList || openTabs || bookmarks || passwords)

    /** The ticked categories, in the order the panel lists them. */
    val categories: List<ClearCategory>
        get() =
            listOfNotNull(
                ClearCategory.HISTORY.takeIf { history },
                ClearCategory.COOKIES_AND_SITE_DATA.takeIf { cookiesAndSiteData },
                ClearCategory.CACHE.takeIf { cache },
                ClearCategory.DOWNLOADS_LIST.takeIf { downloadsList },
                ClearCategory.OPEN_TABS.takeIf { openTabs },
                ClearCategory.BOOKMARKS.takeIf { bookmarks },
                ClearCategory.PASSWORDS.takeIf { passwords },
            )
}

/** The things that can be cleared; what an outcome names when one of them did not go through. */
enum class ClearCategory { HISTORY, COOKIES_AND_SITE_DATA, CACHE, DOWNLOADS_LIST, OPEN_TABS, BOOKMARKS, PASSWORDS }

/** Cookies and cache are the only categories that go through the engine. */
fun needsEngine(selection: ClearSelection): Boolean = selection.cookiesAndSiteData || selection.cache

/**
 * True when the selection would reach the engine while a private tab is open. The engine cannot clear the normal
 * profile on its own: it would take the private tabs' cookies and cache with it.
 */
fun blockedByPrivateTabs(selection: ClearSelection, hasPrivateTabs: Boolean): Boolean =
    needsEngine(selection) && hasPrivateTabs

/** Whether the Clear button is enabled. */
fun canSubmit(selection: ClearSelection, hasPrivateTabs: Boolean, running: Boolean): Boolean =
    !running && !selection.isEmpty && !blockedByPrivateTabs(selection, hasPrivateTabs)

/**
 * What [BrowsingDataClearer] reaches into. Everything but [clearEngine] is synchronous and runs on the main thread.
 */
interface ClearTargets {
    fun clearHistory()

    /** Closes every normal tab, leaving one blank one. Private tabs are not touched. */
    fun closeNormalTabs()

    /** Forgets the finished downloads in the list; no file is deleted, running downloads stay. */
    fun clearDownloadsList()
    fun clearBookmarks()

    /** Deletes every saved login. Throws when the encrypted store is not loaded yet, so the outcome names it. */
    fun clearPasswords() {}
    fun hasPrivateTabs(): Boolean

    /** True only when the engine confirmed the clear. Must not be called while a private tab is open. */
    suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean): Boolean
}

sealed interface ClearOutcome {
    /** Everything selected was cleared (and the engine confirmed it). */
    object Done : ClearOutcome

    /** A private tab was open and cookies or cache were selected: nothing was cleared. */
    object Blocked : ClearOutcome

    /**
     * Some categories did not go through. [failed] threw; [unconfirmed] are the engine categories that the engine did
     * not report as cleared in time (or at all). Every other selected category was cleared.
     */
    data class Incomplete(val failed: Set<ClearCategory>, val unconfirmed: Set<ClearCategory>) : ClearOutcome
}

private const val ENGINE_TIMEOUT_MILLIS = 30_000L

/**
 * Runs one Clear browsing data request against [targets]. Main thread.
 *
 * All or nothing at the start: if cookies or cache are selected and a private tab is open, nothing is cleared at all
 * (see [blockedByPrivateTabs]). Otherwise the categories run in a fixed order: the open tabs first, so a closing
 * session cannot record a visit or write a cookie after the wipe, then history, the downloads list and bookmarks, and
 * the engine last. One category failing does not stop the others. Nothing between the private-tab check and the
 * engine call can suspend, so no private tab can appear in between.
 *
 * Logs name categories and outcomes only, never an address.
 */
class BrowsingDataClearer(
    private val targets: ClearTargets,
    private val log: (String) -> Unit = {},
    private val engineTimeoutMillis: Long = ENGINE_TIMEOUT_MILLIS,
) {
    suspend fun run(selection: ClearSelection): ClearOutcome {
        if (selection.isEmpty) return ClearOutcome.Done
        if (needsEngine(selection)) {
            // A check that cannot be made counts as a private tab being open.
            val privateOpen =
                try {
                    targets.hasPrivateTabs()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    true
                }
            if (blockedByPrivateTabs(selection, privateOpen)) {
                log("clear blocked: private tabs open")
                return ClearOutcome.Blocked
            }
        }

        val failed = LinkedHashSet<ClearCategory>()
        val unconfirmed = LinkedHashSet<ClearCategory>()
        if (selection.openTabs) attempt(ClearCategory.OPEN_TABS, failed) { targets.closeNormalTabs() }
        if (selection.history) attempt(ClearCategory.HISTORY, failed) { targets.clearHistory() }
        if (selection.downloadsList) attempt(ClearCategory.DOWNLOADS_LIST, failed) { targets.clearDownloadsList() }
        if (selection.bookmarks) attempt(ClearCategory.BOOKMARKS, failed) { targets.clearBookmarks() }
        if (selection.passwords) attempt(ClearCategory.PASSWORDS, failed) { targets.clearPasswords() }
        if (needsEngine(selection)) {
            val engineCategories = selection.categories.filter { it == ClearCategory.COOKIES_AND_SITE_DATA || it == ClearCategory.CACHE }
            val confirmed =
                try {
                    withTimeoutOrNull(engineTimeoutMillis) {
                        targets.clearEngine(selection.cookiesAndSiteData, selection.cache)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("clear failed: ${engineCategories.joinToString { it.name }}: ${e.javaClass.simpleName}")
                    failed += engineCategories
                    return finish(failed, unconfirmed)
                }
            if (confirmed != true) {
                log("clear not confirmed: ${engineCategories.joinToString { it.name }}")
                unconfirmed += engineCategories
            }
        }
        return finish(failed, unconfirmed)
    }

    private fun finish(failed: Set<ClearCategory>, unconfirmed: Set<ClearCategory>): ClearOutcome {
        val outcome =
            if (failed.isEmpty() && unconfirmed.isEmpty()) ClearOutcome.Done else ClearOutcome.Incomplete(failed, unconfirmed)
        log(if (outcome is ClearOutcome.Done) "clear done" else "clear incomplete")
        return outcome
    }

    private inline fun attempt(category: ClearCategory, failed: MutableSet<ClearCategory>, action: () -> Unit) {
        try {
            action()
            log("cleared ${category.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("clear failed: ${category.name}: ${e.javaClass.simpleName}")
            failed += category
        }
    }
}
