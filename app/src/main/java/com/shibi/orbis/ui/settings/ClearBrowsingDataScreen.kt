package com.shibi.orbis.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.bookmarks.BookmarkManager
import com.shibi.orbis.clear.ClearCategory
import com.shibi.orbis.clear.ClearController
import com.shibi.orbis.clear.ClearOutcome
import com.shibi.orbis.clear.ClearSelection
import com.shibi.orbis.clear.ClearState
import com.shibi.orbis.clear.blockedByPrivateTabs
import com.shibi.orbis.clear.canSubmit
import com.shibi.orbis.tabs.TabManager

private val SelectionSaver =
    listSaver<ClearSelection, Boolean>(
        save = { listOf(it.history, it.cookiesAndSiteData, it.cache, it.downloadsList, it.openTabs, it.bookmarks, it.passwords) },
        restore = { ClearSelection(it[0], it[1], it[2], it[3], it[4], it[5], it.getOrElse(6) { false }) },
    )

private const val STEP_NONE = 0
private const val STEP_CONFIRM = 1
private const val STEP_CONFIRM_BOOKMARKS = 2

/**
 * Clear browsing data for the normal profile. The selection and the dialog step live only in this screen's saved state:
 * a fresh visit starts from the defaults, and a restored dialog step only shows the dialog again, it never clears
 * anything by itself.
 *
 * The engine cannot clear cookies or cache without also reaching private tabs' data, so those two are refused while any
 * private tab is open (the clearer checks again when it runs). While a clear is running the screen cannot be left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearBrowsingDataScreen(
    tabs: TabManager,
    bookmarks: BookmarkManager,
    controller: ClearController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabList by tabs.tabs.collectAsStateWithLifecycle()
    val bookmarkList by bookmarks.entries.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(ClearSelection()) }
    var step by rememberSaveable { mutableIntStateOf(STEP_NONE) }
    var entered by rememberSaveable { mutableStateOf(false) }

    // A result from an earlier visit is not shown on a new one (and not at all before this has run).
    LaunchedEffect(Unit) {
        if (!entered) {
            controller.dismissResult()
            entered = true
        }
    }

    val running = state is ClearState.Running
    val hasPrivate = tabList.any { it.isPrivate }
    val blocked = blockedByPrivateTabs(selection, hasPrivate)
    val submittable = canSubmit(selection, hasPrivate, running)

    // A dialog step is only valid while its Clear button would be; otherwise it is dropped, not carried over.
    val activeStep =
        when {
            !submittable -> STEP_NONE
            step == STEP_CONFIRM_BOOKMARKS && !selection.bookmarks -> STEP_NONE
            else -> step
        }
    LaunchedEffect(activeStep, step) { if (activeStep != step) step = activeStep }

    fun leave() {
        controller.dismissResult()
        onBack()
    }
    // Blocked while running, so no private tab can be opened while the engine clears.
    BackHandler { if (!running) leave() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clear_data_title)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }, enabled = !running) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.clear_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (entered) (state as? ClearState.Finished)?.let { ResultNotice(it.outcome) }

            val enabled = !running
            CheckRow(R.string.clear_history, null, selection.history, enabled) { selection = selection.copy(history = it) }
            CheckRow(R.string.clear_cookies, R.string.clear_cookies_summary, selection.cookiesAndSiteData, enabled) {
                selection = selection.copy(cookiesAndSiteData = it)
            }
            CheckRow(R.string.clear_cache, null, selection.cache, enabled) { selection = selection.copy(cache = it) }
            CheckRow(R.string.clear_downloads, R.string.clear_downloads_summary, selection.downloadsList, enabled) {
                selection = selection.copy(downloadsList = it)
            }
            CheckRow(R.string.clear_tabs, R.string.clear_tabs_summary, selection.openTabs, enabled) {
                selection = selection.copy(openTabs = it)
            }

            if (blocked) {
                PrivateTabsNotice(onClosePrivate = { tabs.closeAllPrivate() }, enabled = enabled)
            }

            // Bookmarks are the one thing that cannot be rebuilt, so they sit apart from the rest.
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.clear_bookmarks_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            CheckRow(R.string.clear_bookmarks, R.string.clear_bookmarks_summary, selection.bookmarks, enabled) {
                selection = selection.copy(bookmarks = it)
            }
            CheckRow(R.string.clear_passwords, R.string.clear_passwords_summary, selection.passwords, enabled) {
                selection = selection.copy(passwords = it)
            }

            Button(
                onClick = { step = STEP_CONFIRM },
                enabled = submittable,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.clear_button)) }
        }
    }

    if (activeStep == STEP_CONFIRM) {
        val names = selection.categories.map { "- " + stringResource(categoryLabel(it)) }.joinToString("\n")
        AlertDialog(
            onDismissRequest = { step = STEP_NONE },
            title = { Text(stringResource(R.string.clear_confirm_title)) },
            text = { Text(stringResource(R.string.clear_confirm_body, names)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selection.bookmarks) {
                            step = STEP_CONFIRM_BOOKMARKS
                        } else {
                            step = STEP_NONE
                            controller.start(selection)
                        }
                    },
                ) { Text(stringResource(R.string.clear_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { step = STEP_NONE }) { Text(stringResource(R.string.clear_cancel)) }
            },
        )
    }

    if (activeStep == STEP_CONFIRM_BOOKMARKS) {
        val count = bookmarkList.size
        AlertDialog(
            onDismissRequest = { step = STEP_NONE },
            title = { Text(stringResource(R.string.clear_bookmarks_confirm_title)) },
            text = {
                Text(
                    if (count == 0) {
                        stringResource(R.string.clear_bookmarks_confirm_none)
                    } else {
                        pluralStringResource(R.plurals.clear_bookmarks_confirm_body, count, count)
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        step = STEP_NONE
                        controller.start(selection)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) { Text(stringResource(R.string.clear_bookmarks_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { step = STEP_NONE }) { Text(stringResource(R.string.clear_cancel)) }
            },
        )
    }
}

private fun categoryLabel(category: ClearCategory): Int =
    when (category) {
        ClearCategory.HISTORY -> R.string.clear_history
        ClearCategory.COOKIES_AND_SITE_DATA -> R.string.clear_cookies
        ClearCategory.CACHE -> R.string.clear_cache
        ClearCategory.DOWNLOADS_LIST -> R.string.clear_downloads
        ClearCategory.OPEN_TABS -> R.string.clear_tabs
        ClearCategory.BOOKMARKS -> R.string.clear_bookmarks
        ClearCategory.PASSWORDS -> R.string.clear_passwords
    }

/** The whole row toggles, not just the box. */
@Composable
private fun CheckRow(
    title: Int,
    summary: Int?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.6f)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    )
}

@Composable
private fun PrivateTabsNotice(onClosePrivate: () -> Unit, enabled: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clear_private_notice), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClosePrivate, enabled = enabled) { Text(stringResource(R.string.clear_close_private)) }
        }
    }
}

/** What the last clear did, said honestly: failures and unconfirmed engine clears are named. */
@Composable
private fun ResultNotice(outcome: ClearOutcome) {
    val problem = outcome !is ClearOutcome.Done
    val text =
        when (outcome) {
            ClearOutcome.Done -> stringResource(R.string.clear_result_done)
            ClearOutcome.Blocked -> stringResource(R.string.clear_result_blocked)
            is ClearOutcome.Incomplete ->
                listOfNotNull(
                    outcome.failed.takeIf { it.isNotEmpty() }?.let { set ->
                        stringResource(R.string.clear_result_failed, set.map { stringResource(categoryLabel(it)) }.joinToString(", "))
                    },
                    outcome.unconfirmed.takeIf { it.isNotEmpty() }?.let { set ->
                        stringResource(R.string.clear_result_unconfirmed, set.map { stringResource(categoryLabel(it)) }.joinToString(", "))
                    },
                ).joinToString("\n")
        }
    Surface(
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}
