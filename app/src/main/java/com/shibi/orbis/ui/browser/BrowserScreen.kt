package com.shibi.orbis.ui.browser

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.AppContainer
import com.shibi.orbis.R
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.FindResult
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.engine.normalizeAddressInput
import com.shibi.orbis.engine.pickerMimeTypes
import com.shibi.orbis.tabs.TabEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stand-ins used while the selected tab has no session yet. */
private val IDLE_STATE: StateFlow<SessionState> = MutableStateFlow(SessionState())
private val NO_PROMPT: StateFlow<SitePrompt?> = MutableStateFlow(null)

private fun isBlankAddress(url: String) = url.isEmpty() || url == "about:blank"

/** Remembers which session the single content view currently shows, so the view update is idempotent. */
private class AttachedSession {
    var session: BrowserSession? = null
}

@Composable
fun BrowserScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = container.tabs
    val context = LocalContext.current
    val ready by tabs.ready.collectAsStateWithLifecycle()
    val pendingUrl by container.pendingExternalUrl.collectAsStateWithLifecycle()

    // Sessions are created here and nowhere earlier: BrowserScreen is only composed after the onboarding decision.
    LaunchedEffect(ready) {
        if (ready) tabs.openSelected()
    }
    // A link from another app waits in the container until this screen can take it (never hijacks the current tab).
    LaunchedEffect(ready, pendingUrl) {
        val url = pendingUrl
        if (ready && url != null && container.pendingExternalUrl.compareAndSet(url, null)) tabs.openExternal(url)
    }
    LaunchedEffect(tabs) {
        tabs.events.collect { event ->
            when (event) {
                TabEvent.TAB_LIMIT -> Toast.makeText(context, R.string.tabs_limit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (!ready) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }
    BrowserContent(container, onOpenSettings, onOpenDownloads, modifier)
}

@Composable
private fun BrowserContent(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier,
) {
    val tabs = container.tabs
    // The sessions are owned by the tab manager and outlive this composition (e.g. while Settings is open): nothing
    // below ever closes one. Leaving composition only releases the session from the view.
    val selectedTab by tabs.selected.collectAsStateWithLifecycle()
    val tabList by tabs.tabs.collectAsStateWithLifecycle()
    val pendingDownload by tabs.pendingDownload.collectAsStateWithLifecycle()
    val linkMenu by tabs.linkMenu.collectAsStateWithLifecycle()
    val session = selectedTab.session
    val state by (session?.state ?: IDLE_STATE).collectAsStateWithLifecycle()
    val prompt by (session?.prompts ?: NO_PROMPT).collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var showTabs by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The one tab whose address bar should take focus: set only when the user makes a tab with New tab. Cold start,
    // popups, external links and "closed the last tab" leave it null, so they never steal focus or open edit mode.
    var focusAddressFor by remember { mutableStateOf<Long?>(null) }
    // Find state belongs to one session: it resets when the selected tab changes.
    var findOpen by remember(session) { mutableStateOf(false) }
    var findQuery by remember(session) { mutableStateOf("") }
    DisposableEffect(session) {
        onDispose { session?.clearFind() }
    }

    val blankTab = isBlankAddress(selectedTab.url) && isBlankAddress(state.url)
    val hasLiveOpener = selectedTab.openerTabId?.let { id -> tabList.any { it.id == id } } == true

    // Back order: close find bar, then history, then a tab opened from another tab, then the system default.
    BackHandler(enabled = findOpen || state.canGoBack || hasLiveOpener) {
        when {
            findOpen -> {
                findOpen = false
                session?.clearFind()
            }
            state.canGoBack -> session?.goBack()
            hasLiveOpener -> tabs.close(selectedTab.id)
        }
    }

    // File pick: the system document picker is the UI; the prompt is answered from its result.
    var filePrompt by remember { mutableStateOf<SitePrompt.FilePick?>(null) }
    val pickOne =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            filePrompt?.let { if (uri == null) it.dismiss() else it.submit(listOf(uri)) }
        }
    val pickMany =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            filePrompt?.submit(uris)
        }
    LaunchedEffect(prompt) {
        val p = prompt
        if (p is SitePrompt.FilePick) {
            filePrompt = p
            val types = pickerMimeTypes(p.mimeTypes).toTypedArray()
            runCatching { if (p.multiple) pickMany.launch(types) else pickOne.launch(types) }
                .onFailure { p.dismiss() }
        }
    }

    val attached = remember { AttachedSession() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding(),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Keyed by tab: typed text, focus and the edit mode never leak from one tab into another.
                key(selectedTab.id) {
                    var typed by rememberSaveable { mutableStateOf("") }
                    var addressFocused by remember { mutableStateOf(false) }
                    val focusRequester = remember { FocusRequester() }
                    val tabId = selectedTab.id
                    LaunchedEffect(Unit) {
                        // A tab the user just created starts with the address bar focused (once).
                        if (focusAddressFor == tabId) {
                            focusAddressFor = null
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                    OutlinedTextField(
                        // Unfocused, the bar shows only the committed location (blank tabs show nothing).
                        value = if (addressFocused) typed else if (isBlankAddress(state.url)) "" else state.url,
                        onValueChange = { typed = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                if (focus.hasFocus && !addressFocused) {
                                    typed = if (isBlankAddress(state.url)) "" else state.url
                                }
                                addressFocused = focus.hasFocus
                            },
                        singleLine = true,
                        label = { Text(stringResource(R.string.address_bar_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = normalizeAddressInput(typed)
                                if (target != null) {
                                    session?.loadUrl(target)
                                    focusManager.clearFocus()
                                }
                            },
                        ),
                    )
                }
                val tabsDescription = stringResource(R.string.tabs_button, tabList.size)
                IconButton(onClick = { showTabs = true }) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .border(2.dp, LocalContentColor.current, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = tabsDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tabList.size.coerceAtMost(99).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_new_tab)) },
                            onClick = {
                                menuOpen = false
                                focusAddressFor = tabs.newTab()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_downloads)) },
                            onClick = {
                                menuOpen = false
                                onOpenDownloads()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_find)) },
                            enabled = session != null,
                            onClick = {
                                menuOpen = false
                                findOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_desktop_site)) },
                            enabled = session != null,
                            trailingIcon = { Checkbox(checked = state.desktopMode, onCheckedChange = null) },
                            onClick = {
                                menuOpen = false
                                session?.setDesktopMode(!state.desktopMode)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = {
                                menuOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { session?.goBack() }, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                IconButton(onClick = { session?.goForward() }, enabled = state.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.forward))
                }
                if (state.isLoading) {
                    IconButton(onClick = { session?.stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop))
                    }
                } else {
                    IconButton(onClick = { session?.reload() }, enabled = session != null) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
                    }
                }
            }
            if (findOpen) {
                FindBar(
                    query = findQuery,
                    result = state.findResult,
                    onQueryChange = {
                        findQuery = it
                        session?.find(it, false)
                    },
                    onNext = { session?.find(findQuery, false) },
                    onPrevious = { session?.find(findQuery, true) },
                    onClose = {
                        findOpen = false
                        session?.clearFind()
                    },
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.isLoading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.crashed || state.loadFailed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (state.crashed) R.string.browser_crashed else R.string.browser_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        TextButton(
                            onClick = { session?.reload() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text(stringResource(if (state.crashed) R.string.reload else R.string.browser_retry))
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // ONE content view for all tabs; the tab shown is swapped in update(), which may run any number of
                // times and so only acts when the selected session differs from the attached one.
                AndroidView(
                    factory = { context ->
                        container.engine.createContentView(context).also { view ->
                            view.layoutParams =
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                        }
                    },
                    update = { view ->
                        val target = session
                        if (target != null && attached.session !== target) {
                            attached.session?.detachFrom(view)
                            target.attachTo(view)
                            attached.session = target
                        }
                    },
                    // Release only: the session lives on in the tab manager.
                    onRelease = { view ->
                        attached.session?.detachFrom(view)
                        attached.session = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (blankTab && !state.isLoading) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.browser_empty_state),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTabs) {
        TabSwitcher(
            tabs = tabList,
            onSelect = {
                tabs.select(it)
                showTabs = false
            },
            onClose = { tabs.close(it) },
            onNewTab = {
                focusAddressFor = tabs.newTab()
                showTabs = false
            },
            onDismiss = { showTabs = false },
        )
    }

    // Only the selected tab's prompts are shown; a background tab's wait in its own queue until it is selected.
    prompt?.let { SitePromptDialog(it) }

    pendingDownload?.let { pending ->
        DownloadConfirmDialog(
            pending = pending,
            onConfirm = {
                container.downloads.start(pending.download)
                tabs.resolveDownload(pending)
            },
            onCancel = {
                pending.download.discard()
                tabs.resolveDownload(pending)
            },
        )
    }

    linkMenu?.let { request ->
        LinkMenuDialog(
            request = request,
            onOpenInNewTab = { tabs.openLinkInNewTab(request) },
            onDismiss = { tabs.dismissLinkMenu() },
        )
    }
}

@Composable
private fun FindBar(
    query: String,
    result: FindResult?,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(
        Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            label = { Text(stringResource(R.string.find_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        val summary =
            when {
                query.isEmpty() || result == null -> ""
                !result.found || result.total == 0 -> stringResource(R.string.find_no_matches)
                else -> stringResource(R.string.find_count, result.current, result.total)
            }
        Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous))
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.find_close))
        }
    }
}
