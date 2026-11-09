package com.shibi.orbis.engine

import android.app.Activity
import android.content.Intent
import android.view.MenuItem
import com.shibi.orbis.R
import org.mozilla.geckoview.BasicSelectionActionDelegate

internal const val ACTION_ORBIS_SEARCH = "com.shibi.orbis.SELECTION_SEARCH"
internal const val ACTION_ORBIS_SHARE = "com.shibi.orbis.SELECTION_SHARE"

/** Longest selection passed to search or share; a whole page selected is cut, not sent. */
internal const val SELECTION_MAX_CHARS = 2000

/**
 * GeckoView's text selection menu (Copy, Select all, Paste and the apps that process text, such as a translator) plus
 * Search, with the user's search engine in a new tab, and Share. Nothing about the selection is logged.
 */
internal class OrbisSelectionDelegate(
    activity: Activity,
    private val onSearch: (String) -> Unit,
) : BasicSelectionActionDelegate(activity) {
    override fun getAllActions(): Array<String> = super.getAllActions() + arrayOf(ACTION_ORBIS_SEARCH, ACTION_ORBIS_SHARE)

    private fun selectedText(): String? = mSelection?.text?.trim()?.take(SELECTION_MAX_CHARS)?.takeIf { it.isNotEmpty() }

    override fun isActionAvailable(id: String): Boolean =
        when (id) {
            // Not inside a password field: its text must never leave it.
            ACTION_ORBIS_SEARCH, ACTION_ORBIS_SHARE ->
                selectedText() != null && (mSelection?.flags ?: 0) and org.mozilla.geckoview.GeckoSession.SelectionActionDelegate.FLAG_IS_PASSWORD == 0
            else -> super.isActionAvailable(id)
        }

    override fun prepareAction(id: String, item: MenuItem) {
        when (id) {
            ACTION_ORBIS_SEARCH -> item.setTitle(R.string.selection_search)
            ACTION_ORBIS_SHARE -> item.setTitle(R.string.menu_share)
            else -> super.prepareAction(id, item)
        }
    }

    override fun performAction(id: String, item: MenuItem): Boolean {
        val text = selectedText()
        return when (id) {
            ACTION_ORBIS_SEARCH -> {
                if (text != null) onSearch(text)
                clearSelection()
                true
            }
            ACTION_ORBIS_SHARE -> {
                if (text != null) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { mActivity.startActivity(Intent.createChooser(send, null)) }
                }
                clearSelection()
                true
            }
            else -> super.performAction(id, item)
        }
    }
}
