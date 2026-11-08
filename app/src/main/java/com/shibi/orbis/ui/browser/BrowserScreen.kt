package com.shibi.orbis.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.AppContainer
import com.shibi.orbis.R
import com.shibi.orbis.bookmarks.AddResult
import com.shibi.orbis.engine.AndroidPermissionRequest
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.FilePickLaunch
import com.shibi.orbis.engine.FindResult
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.engine.pickerMimeTypes
import com.shibi.orbis.tabs.TabEvent
import com.shibi.orbis.ui.OrbisEmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stand-ins used while the selected tab has no session yet. */
private val IDLE_STATE: StateFlow<SessionState> = MutableStateFlow(SessionState())
private val NO_PROMPT: StateFlow<SitePrompt?> = MutableStateFlow(null)

/** Remembers which session the single content view currently shows, so the view update is idempotent. */
private class AttachedSession {
    var session: BrowserSession? = null
}

/** The file prompt whose system picker this composition launched; plain memory, so a recreation starts empty. */
private class LaunchedPick {
    var prompt: SitePrompt.FilePick? = null
}

/** The system permission request this composition launched; plain memory, so a recreation starts empty. */
private class LaunchedAndroidRequest {
    var request: AndroidPermissionRequest? = null
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
fun BrowserScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
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
    BrowserContent(container, onOpenSettings, onOpenDownloads, onOpenBookmarks, onOpenHistory, modifier)
}

@Composable
private fun BrowserContent(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
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
    // The menu entry follows the committed address, never a pending one.
    val bookmarkList by container.bookmarks.entries.collectAsStateWithLifecycle()
    val bookmarked = remember(bookmarkList, state.url) { container.bookmarks.isBookmarked(state.url) }
    val prompt by (session?.prompts ?: NO_PROMPT).collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    // Where focus goes after Go. focusManager.clearFocus() is not enough: after a key event (hardware Enter) the app is
    // out of touch mode and the view system hands focus straight back to the first focusable, the address field.
    val focusSink = remember { FocusRequester() }
    val requestNotificationPermission = rememberNotificationPermissionRequest()

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

    // File pick: the system document picker or camera is the UI; the prompt is answered from its result. The result goes
    // to the prompt that launched it. After a recreation the holder is empty, so it falls back to the current one.
    // Answering a prompt that already ended does nothing, and a newer prompt is never touched by an older result.
    val currentFilePick by rememberUpdatedState(prompt as? SitePrompt.FilePick)
    val launchedPick = remember { LaunchedPick() }
    val pickOne =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = launchedPick.prompt ?: currentFilePick
            if (uri == null) target?.dismiss() else target?.submit(listOf(uri))
        }
    val pickMany =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            (launchedPick.prompt ?: currentFilePick)?.submit(uris)
        }
    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            (launchedPick.prompt ?: currentFilePick)?.cameraResult(ok)
        }
    LaunchedEffect(prompt) {
        val p = prompt as? SitePrompt.FilePick ?: return@LaunchedEffect
        if (p.isFinished) return@LaunchedEffect
        p.begin()
        // The prompt says which system screen to open and when (the camera waits for its permission). Each launch is
        // claimed once per prompt, so a replayed value after a recreation or coming back to this tab launches nothing.
        p.launchRequest.collect { request ->
            when (request) {
                null -> Unit

                FilePickLaunch.Documents -> {
                    if (!p.claimPickerLaunch()) return@collect
                    launchedPick.prompt = p
                    val types = pickerMimeTypes(p.mimeTypes) { ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
                    runCatching { if (p.multiple) pickMany.launch(types.toTypedArray()) else pickOne.launch(types.toTypedArray()) }
                        .onFailure { p.dismiss() }
                }

                is FilePickLaunch.Camera -> {
                    if (!p.claimPickerLaunch()) return@collect
                    launchedPick.prompt = p
                    // No camera app (or the permission vanished): the prompt falls back to the document picker.
                    runCatching { takePicture.launch(request.output) }.onFailure { p.cameraLaunchFailed() }
                }
            }
        }
    }

    // System permission dialogs for camera, microphone and location. App level, not per tab: Gecko can ask on behalf of
    // a background tab. One launch per request; a failed launch completes it as denied. The page was already answered
    // by the time the settings hint shows, so that dialog never holds anything up.
    val androidContext = LocalContext.current
    val broker = container.engine.androidPermissions
    val androidRequest by broker.head.collectAsStateWithLifecycle()
    val settingsHint by broker.settingsHint.collectAsStateWithLifecycle()
    val currentAndroidRequest by rememberUpdatedState(androidRequest)
    val launchedAndroid = remember { LaunchedAndroidRequest() }
    val androidLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // After a recreation the holder is empty and the original request already ended as denied (see onDispose):
            // this late result may only answer the current request if it is the same question.
            val target =
                launchedAndroid.request
                    ?: currentAndroidRequest?.takeIf { results.keys.containsAll(it.permissions) }
                    ?: return@rememberLauncherForActivityResult
            launchedAndroid.request = null
            val activity = androidContext.findActivity()
            // Without an activity to ask, "rationale" is assumed so the result is never judged blocked.
            broker.evaluate(target, results) { p ->
                activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, p) } ?: true
            }
        }
    LaunchedEffect(androidRequest) {
        val r = androidRequest ?: return@LaunchedEffect
        if (!r.claimLaunch()) return@LaunchedEffect
        launchedAndroid.request = r
        runCatching { androidLauncher.launch(r.permissions.toTypedArray()) }
            .onFailure {
                launchedAndroid.request = null
                broker.failLaunch(r)
            }
    }
    // Leaving this screen drops the launcher's result callback: end what it launched so the queue cannot stall.
    DisposableEffect(broker) {
        onDispose { launchedAndroid.request?.let { broker.failLaunch(it) } }
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
                .imePadding()
                .focusRequester(focusSink)
                .focusable(),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { session?.goBack() }, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                // The production address bar (BrowserAddressBar.kt): keyed by tab inside, so typed text, focus, edit mode and
                // the pending submit never leak from one tab into another. It reads the selected tab's OWN url and session
                // flow, never the `state` collected above, which lags a frame behind a tab switch.
                BrowserAddressBar(
                    tabId = selectedTab.id,
                    isPrivate = selectedTab.isPrivate,
                    tabUrl = selectedTab.url,
                    sessionState = selectedTab.session?.state,
                    downloadOffer = pendingDownload,
                    isLoading = state.isLoading,
                    canReload = session != null,
                    searchEngine = { container.searchEngine.selected.value },
                    focusOnStart = focusAddressFor == selectedTab.id,
                    onFocusOnStartConsumed = { focusAddressFor = null },
                    loadUrl = { session?.loadUrl(it) },
                    stop = { session?.stop() },
                    reload = { session?.reload() },
                    hideKeyboard = { keyboardController?.hide() },
                    moveFocusToSink = { focusSink.requestFocus() },
                    modifier = Modifier.weight(1f),
                )
                val tabsDescription =
                    stringResource(if (selectedTab.isPrivate) R.string.tabs_button_private else R.string.tabs_button, tabList.size)
                val tabsColor = if (selectedTab.isPrivate) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
                IconButton(onClick = { showTabs = true }) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .border(2.dp, tabsColor, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = tabsDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tabList.size.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = tabsColor,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val forwardDescription = stringResource(R.string.forward)
                        DropdownMenuItem(
                            text = { Text(forwardDescription) },
                            modifier = Modifier.semantics { contentDescription = forwardDescription },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                            enabled = state.canGoForward,
                            onClick = {
                                menuOpen = false
                                session?.goForward()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_new_tab)) },
                            onClick = {
                                menuOpen = false
                                focusAddressFor = tabs.newTab()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_new_private_tab)) },
                            onClick = {
                                menuOpen = false
                                focusAddressFor = tabs.newPrivateTab()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark))
                            },
                            enabled = isWebUrl(state.url),
                            onClick = {
                                menuOpen = false
                                val url = state.url
                                if (bookmarked) {
                                    container.bookmarks.removeByUrl(url)
                                    Toast.makeText(androidContext, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                                } else {
                                    val title = state.title?.takeIf { it.isNotBlank() } ?: url
                                    val message =
                                        when (container.bookmarks.add(url, title)) {
                                            AddResult.ADDED -> R.string.bookmark_added
                                            AddResult.EXISTS -> R.string.bookmark_exists
                                            AddResult.FULL -> R.string.bookmark_full
                                            AddResult.INVALID -> R.string.bookmark_invalid
                                        }
                                    Toast.makeText(androidContext, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_bookmarks)) },
                            onClick = {
                                menuOpen = false
                                onOpenBookmarks()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_history)) },
                            onClick = {
                                menuOpen = false
                                onOpenHistory()
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
            if (selectedTab.isPrivate) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.private_banner_title),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        )
                        TextButton(
                            onClick = { tabs.closeAllPrivate() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        ) {
                            Text(stringResource(R.string.private_close_all))
                        }
                    }
                }
            }
            if (findOpen) {
                PrivateImeScope(selectedTab.isPrivate) {
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
                    OrbisEmptyState(
                        stringResource(
                            if (selectedTab.isPrivate) R.string.browser_private_empty_state else R.string.browser_empty_state,
                        ),
                        if (selectedTab.isPrivate) Icons.Filled.VisibilityOff else Icons.Filled.Search,
                    )
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
            onNewPrivateTab = {
                focusAddressFor = tabs.newPrivateTab()
                showTabs = false
            },
            onClosePrivateTabs = {
                tabs.closeAllPrivate()
                showTabs = false
            },
            onDismiss = { showTabs = false },
        )
    }

    // Only the selected tab's prompts are shown; a background tab's wait in its own queue until it is selected.
    // Its text fields (script prompt, sign-in, seconds, colour) belong to the selected tab, so a private tab's get the scope.
    prompt?.let { PrivateImeScope(selectedTab.isPrivate) { SitePromptDialog(it) } }

    if (settingsHint) {
        PermissionSettingsDialog(
            onOpenSettings = {
                broker.dismissSettingsHint()
                runCatching {
                    androidContext.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", androidContext.packageName, null)),
                    )
                }
            },
            onDismiss = broker::dismissSettingsHint,
        )
    }

    pendingDownload?.let { pending ->
        DownloadConfirmDialog(
            pending = pending,
            onConfirm = {
                // A private tab's download keeps no source host in the index; the file itself stays.
                container.downloads.start(pending.download, recordSource = !pending.isPrivate)
                tabs.resolveDownload(pending)
                // After start: the notification permission never delays or blocks the download.
                requestNotificationPermission()
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
    Column(
        Modifier.padding(horizontal = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
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
}
