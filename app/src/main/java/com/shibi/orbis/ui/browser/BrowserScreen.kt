package com.shibi.orbis.ui.browser

import android.app.Activity
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.SitePermissionKey
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.engine.siteOriginOf
import com.shibi.orbis.ui.passwords.copySensitive
import com.shibi.orbis.passwords.generatePassword
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Tune
import com.shibi.orbis.engine.normalizeAddressInput
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.shibi.orbis.engine.PageCapture
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import android.content.Context
import android.content.pm.ActivityInfo
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
import com.shibi.orbis.clear.ClearState
import com.shibi.orbis.home.topSites
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.Article
import com.shibi.orbis.engine.ReaderArticle
import com.shibi.orbis.engine.pullTriggersRefresh
import com.shibi.orbis.engine.PullToRefreshSource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import com.shibi.orbis.suggest.mergeSuggestions
import com.shibi.orbis.suggest.localSuggestions
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import com.shibi.orbis.ui.home.StartPage
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager
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

/** Hides the system bars (swipe shows them briefly) and keeps the screen on while [fullScreen]; restores both after. */
@Composable
internal fun FullScreenWindowEffect(fullScreen: Boolean, landscape: Boolean = false) {
    val view = LocalView.current
    // A wide fullscreen video turns the screen to landscape (either way round) and gives it back afterwards.
    DisposableEffect(landscape, view) {
        val activity = view.context.findActivity()
        val before = activity?.requestedOrientation
        if (landscape && activity != null) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (landscape && activity != null && before != null) activity.requestedOrientation = before
        }
    }
    DisposableEffect(fullScreen, view) {
        val window = view.context.findActivity()?.window
        if (fullScreen && window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            view.keepScreenOn = true
        }
        onDispose {
            if (fullScreen && window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                view.keepScreenOn = false
            }
        }
    }
}

/** How far the finger travels before a pull reloads the page. */
private val PULL_TO_REFRESH_THRESHOLD = 96.dp

/** A circle that follows the pull down and fills as it nears the reload threshold. Decorative: Reload has a button. */
@Composable
private fun PullToRefreshIndicator(fraction: Float, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .padding(top = 8.dp + 48.dp * fraction)
            .size(40.dp)
            .clearAndSetSemantics {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
    }
}

/** Wait this long after the last keystroke before asking the search engine. */
private const val SUGGEST_DEBOUNCE_MS = 150L

/** Whether the toolbar wraps to two rows: large text (font scale 1.3+) on a window narrower than 600dp. */
internal fun toolbarWraps(fontScale: Float, screenWidthDp: Int): Boolean = fontScale >= 1.3f && screenWidthDp < 600

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
    onOpenQrScanner: () -> Unit = {},
) {
    val tabs = container.tabs
    val context = LocalContext.current
    val ready by tabs.ready.collectAsStateWithLifecycle()
    val pendingUrl by container.pendingExternalUrl.collectAsStateWithLifecycle()
    val pendingImport by container.pendingImports.head.collectAsStateWithLifecycle()
    val clearState by container.clear.state.collectAsStateWithLifecycle()

    // Sessions are created here and nowhere earlier: BrowserScreen is only composed after the onboarding decision.
    LaunchedEffect(ready) {
        if (ready) tabs.openSelected()
    }
    // A link from another app waits in the container until this screen can take it (never hijacks the current tab).
    LaunchedEffect(ready, pendingUrl) {
        val url = pendingUrl
        if (ready && url != null && container.pendingExternalUrl.compareAndSet(url, null)) tabs.openExternal(url)
    }
    // A PDF or HTML file copied from another app. Same wait as a link, and not while Clear browsing data is running.
    // consume publishes the next queued file, so two shares that finish together both open.
    LaunchedEffect(ready, pendingImport, clearState) {
        val url = pendingImport
        if (ready && clearState !is ClearState.Running && url != null && container.pendingImports.consume(url)) {
            if (!tabs.openImported(url)) withContext(Dispatchers.IO) { container.imports.deleteImport(url) }
        }
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
    BrowserContent(container, onOpenSettings, onOpenDownloads, onOpenBookmarks, onOpenHistory, onOpenQrScanner, modifier)
}

@Composable
private fun BrowserContent(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenQrScanner: () -> Unit,
    modifier: Modifier,
) {
    val tabs = container.tabs
    // The sessions are owned by the tab manager and outlive this composition (e.g. while Settings is open): nothing
    // below ever closes one. Leaving composition only releases the session from the view.
    val selectedTab by tabs.selected.collectAsStateWithLifecycle()
    val tabList by tabs.tabs.collectAsStateWithLifecycle()
    val recentlyClosed by tabs.recentlyClosed.collectAsStateWithLifecycle()
    var showRecentlyClosed by remember { mutableStateOf(false) }
    var showSiteSettings by remember { mutableStateOf(false) }
    var generatedPassword by remember { mutableStateOf<String?>(null) }
    val toolbarBottom by container.browserSettings.toolbarBottom.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val closedMessage = stringResource(R.string.tabs_closed_snackbar)
    val undoLabel = stringResource(R.string.undo)
    // Tab previews: taken of the page shown when it finishes loading and when the tab list opens. Memory only.
    var contentView by remember { mutableStateOf<android.view.View?>(null) }
    val thumbnailMap by container.thumbnails.all.collectAsStateWithLifecycle()
    val thumbnailImages = remember(thumbnailMap) { thumbnailMap.mapValues { it.value.asImageBitmap() } }
    LaunchedEffect(tabList) { container.thumbnails.retain(tabList.map { it.id }.toSet()) }
    val pendingDownload by tabs.pendingDownload.collectAsStateWithLifecycle()
    val linkMenu by tabs.linkMenu.collectAsStateWithLifecycle()
    val session = selectedTab.session
    val state by (session?.state ?: IDLE_STATE).collectAsStateWithLifecycle()
    // The menu entry follows the committed address, never a pending one.
    val bookmarkList by container.bookmarks.entries.collectAsStateWithLifecycle()
    val bookmarked = remember(bookmarkList, state.url) { container.bookmarks.isBookmarked(state.url) }
    val historyEntries by container.history.entries.collectAsStateWithLifecycle()
    val prompt by (session?.prompts ?: NO_PROMPT).collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    // Where focus goes after Go. focusManager.clearFocus() is not enough: after a key event (hardware Enter) the app is
    // out of touch mode and the view system hands focus straight back to the first focusable, the address field.
    val focusSink = remember { FocusRequester() }
    val requestNotificationPermission = rememberNotificationPermissionRequest()

    var showTabs by remember { mutableStateOf(false) }
    fun capturePreview() {
        // Only a web page: the start page is drawn over the page area, so a capture of a blank tab is empty.
        if (!isWebUrl(state.url)) return
        val tabId = selectedTab.id
        (contentView as? PageCapture)?.capturePage { bmp -> if (bmp != null) container.thumbnails.put(tabId, bmp) }
    }
    LaunchedEffect(showTabs) { if (showTabs) capturePreview() }
    LaunchedEffect(selectedTab.id, state.isLoading) {
        if (!state.isLoading && isWebUrl(state.url)) {
            kotlinx.coroutines.delay(600)
            capturePreview()
        }
    }
    // Bumped by the start page's search box to put the address bar into edit mode.
    var addressFocusRequests by remember { mutableIntStateOf(0) }
    // The typed text and a submit hook from the address bar, for the suggestion dropdown.
    val addressBridge = remember { AddressBarBridge() }
    val addressDrop = rememberLinkDropTarget { text -> addressBridge.submit(text) }
    // Where focus goes once the tab sheet has ended (keyboard navigation only; see tabSheetReturnFocus). Opening the
    // sheet again clears it, which cancels a return that has not happened yet.
    var sheetReturnFocus by remember { mutableStateOf<ReturnFocus?>(null) }
    val tabsButtonFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    var menuOpen by remember { mutableStateOf(false) }
    // The one tab whose address bar should take focus: set only when the user makes a tab with New tab. Cold start,
    // popups, external links and "closed the last tab" leave it null, so they never steal focus or open edit mode.
    var focusAddressFor by remember { mutableStateOf<Long?>(null) }
    val voiceContext = LocalContext.current
    val hasVoice = remember { voiceSearchAvailable(voiceContext) }
    // The words heard go through the address bar's own Go path (search, or open when they are an address).
    val voiceSearch = rememberVoiceSearch(isPrivate = selectedTab.isPrivate) { heard ->
        if (!addressBridge.submit(heard)) {
            normalizeAddressInput(heard, container.searchEngine.selected.value)?.let { session?.loadUrl(it) }
        }
    }
    val updateResult by (container.updates?.result ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val updateMessageTemplate = stringResource(R.string.update_available)
    val updateAction = stringResource(R.string.update_get)
    LaunchedEffect(updateResult) {
        val available = updateResult as? com.shibi.orbis.update.UpdateResult.Available ?: return@LaunchedEffect
        val updates = container.updates ?: return@LaunchedEffect
        if (updates.announcedVersion == available.release.version) return@LaunchedEffect
        updates.announcedVersion = available.release.version
        val r = snackbars.showSnackbar(updateMessageTemplate.format(available.release.version), actionLabel = updateAction, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) tabs.newTab(available.release.pageUrl)
    }
    val widgetSearch by container.pendingWidgetSearch.collectAsStateWithLifecycle()
    LaunchedEffect(widgetSearch) {
        if (widgetSearch) {
            container.pendingWidgetSearch.value = false
            focusAddressFor = tabs.newTab()
        }
    }
    // Reader view of the selected tab's page; closed when the tab or its page changes.
    var readerArticle by remember(session) { mutableStateOf<ReaderArticle?>(null) }
    LaunchedEffect(state.url) { if (readerArticle != null && readerArticle?.url != state.url) readerArticle = null }
    // Find state belongs to one session: it resets when the selected tab changes.
    var findOpen by remember(session) { mutableStateOf(false) }
    var findQuery by remember(session) { mutableStateOf("") }
    DisposableEffect(session) {
        onDispose {
            session?.clearFind()
            // Leaving a tab (switch, close, another screen) ends its page fullscreen.
            session?.exitFullScreen()
        }
    }
    // The selected tab's own page fullscreen; system bars hide and the screen stays on while it lasts.
    val fullScreen = state.fullScreen && session != null
    FullScreenWindowEffect(fullScreen, landscape = fullScreen && state.fullScreenVideoLandscape)

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
    // Registered after the history Back, so it wins: Back closes reader view first.
    BackHandler(enabled = readerArticle != null) { readerArticle = null }
    // Registered last, so it wins: Back first leaves page fullscreen.
    BackHandler(enabled = fullScreen) { session?.exitFullScreen() }

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
    val noAppMessage = stringResource(R.string.open_app_none)
    val copyLinkAction = stringResource(R.string.open_app_copy)
    LaunchedEffect(tabs, noAppMessage, copyLinkAction) {
        tabs.externalLinkFailures.collect { url ->
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(noAppMessage, actionLabel = copyLinkAction, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) copyLink(androidContext, url)
        }
    }
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
    // Pull-to-refresh: the content view reports the drag; past the threshold the page reloads. The view's callbacks read
    // the CURRENT session and fullscreen state when they run, never values captured when the view was created.
    var pullPx by remember { mutableFloatStateOf(0f) }
    val pullThresholdPx = with(LocalDensity.current) { PULL_TO_REFRESH_THRESHOLD.toPx() }
    val currentSession by rememberUpdatedState(session)
    val currentFullScreen by rememberUpdatedState(fullScreen)

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
            // The toolbar block (address bar, buttons, banners, progress): above the page, or below it when the user
            // chose a bottom toolbar. Page fullscreen (a video) hides it; it comes back when that ends.
            val chrome: @Composable () -> Unit = {
                // Very large text on a narrow window: the address gets a row of its own and Back, Tabs and the menu move
                // to a second row (toolbarWraps). The address bar keeps its place in composition either way, so a layout
                // change never drops what the user is typing.
                val density = LocalDensity.current
                // The window's own width (split screen and the Fold's halves included), not the whole display.
                val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
                val twoRow = toolbarWraps(density.fontScale, windowWidthDp)
                val backButton: @Composable () -> Unit = {
                    IconButton(onClick = { session?.goBack() }, enabled = state.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
                val tabsAndMenu: @Composable () -> Unit = {
                    val tabsDescription =
                        stringResource(if (selectedTab.isPrivate) R.string.tabs_button_private else R.string.tabs_button, tabList.size)
                    val tabsColor = if (selectedTab.isPrivate) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
                    IconButton(
                        onClick = {
                            sheetReturnFocus = null
                            showTabs = true
                        },
                        modifier = Modifier.focusRequester(tabsButtonFocus),
                    ) {
                        // At least 24dp, growing with large fonts instead of clipping the count against its border.
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                                .border(2.dp, tabsColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 3.dp)
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
                            // Grouped: Page (this page) / Tabs / Library / Settings. The most used page actions are one
                            // row of icons, so the whole menu fits on a phone screen.
                            val forwardDescription = stringResource(R.string.forward)
                            val homeDescription = stringResource(R.string.menu_home)
                            val bookmarkDescription =
                                stringResource(if (bookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark)
                            val shareDescription = stringResource(R.string.menu_share)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                IconButton(
                                    enabled = session != null,
                                    onClick = {
                                        menuOpen = false
                                        // Custom homepage, or the start page (a blank tab).
                                        val homepage = container.browserSettings.homepage.value
                                        if (homepage == null) session?.showStartPage() else session?.loadUrl(homepage)
                                    },
                                ) { Icon(Icons.Filled.Home, contentDescription = homeDescription) }
                                IconButton(
                                    enabled = state.canGoForward,
                                    onClick = {
                                        menuOpen = false
                                        session?.goForward()
                                    },
                                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = forwardDescription) }
                                IconButton(
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
                                ) {
                                    Icon(
                                        if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = bookmarkDescription,
                                    )
                                }
                                IconButton(
                                    enabled = isWebUrl(state.url),
                                    onClick = {
                                        menuOpen = false
                                        shareLink(androidContext, state.url, state.title)
                                    },
                                ) { Icon(Icons.Filled.Share, contentDescription = shareDescription) }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_print)) },
                                leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null) },
                                enabled = session != null && isWebUrl(state.url) && !state.isLoading,
                                onClick = {
                                    menuOpen = false
                                    session?.printPage()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_add_home)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, contentDescription = null) },
                                // Not from a private tab: the shortcut would keep the address on the home screen.
                                enabled = isWebUrl(state.url) && session?.isPrivate == false,
                                onClick = {
                                    menuOpen = false
                                    if (!requestHomeShortcut(androidContext, state.url, state.title.orEmpty())) {
                                        Toast.makeText(androidContext, R.string.add_home_unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            val menuWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
                            val inMultiWindow = (androidContext as? android.app.Activity)?.isInMultiWindowMode == true
                            if (showsTabStrip(menuWidthDp) || inMultiWindow) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_split_screen)) },
                                    leadingIcon = { Icon(Icons.Filled.VerticalSplit, contentDescription = null) },
                                    // Not a private page: the second window is a normal (non-private) one.
                                    enabled = isWebUrl(state.url) && session?.isPrivate == false,
                                    onClick = {
                                        menuOpen = false
                                        openInSplitScreen(androidContext, state.url)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_reader)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                                enabled = state.readerable && session != null,
                                onClick = {
                                    menuOpen = false
                                    session?.requestReaderArticle { article ->
                                        if (article != null) readerArticle = article else Toast.makeText(androidContext, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            val translation = state.translation
                            val translated = translation?.translatedTo?.isNotEmpty() == true
                            // Shown only when the page can be translated (or already is): most pages are in the phone's language.
                            if (session != null && (translated || translation?.canTranslate == true)) DropdownMenuItem(
                                text = { Text(stringResource(if (translated) R.string.menu_show_original else R.string.menu_translate)) },
                                leadingIcon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (translated) {
                                        session.showOriginal()
                                    } else {
                                        Toast.makeText(androidContext, R.string.translate_started, Toast.LENGTH_SHORT).show()
                                        session.translatePage { ok -> if (!ok) Toast.makeText(androidContext, R.string.translate_failed, Toast.LENGTH_LONG).show() }
                                    }
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
                                text = { Text(stringResource(R.string.menu_site_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                enabled = session != null && isWebUrl(state.url),
                                onClick = {
                                    menuOpen = false
                                    showSiteSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_generate_password)) },
                                leadingIcon = { Icon(Icons.Filled.Password, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    generatedPassword = generatePassword()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_desktop_site)) },
                                enabled = session != null,
                                trailingIcon = { Checkbox(checked = state.desktopMode, onCheckedChange = null) },
                                onClick = {
                                    menuOpen = false
                                    val s = session ?: return@DropdownMenuItem
                                    // Remembered for the site (see Settings > Always request desktop site).
                                    if (isWebUrl(state.url)) container.browserSettings.rememberDesktop(state.url, !state.desktopMode, s.isPrivate)
                                    s.setDesktopMode(!state.desktopMode)
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
                                text = { Text(stringResource(R.string.menu_scan_qr)) },
                                leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenQrScanner()
                                },
                            )
                            HorizontalDivider()
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
                            HorizontalDivider()
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
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!twoRow) backButton()
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
                        // A link or text dropped on the bar opens (or is searched) in this tab, as if typed and Go pressed.
                        modifier = Modifier.weight(1f).dragAndDropTarget(::carriesLinkOrText, addressDrop),
                        focusRequests = addressFocusRequests,
                        bridge = addressBridge,
                        onSwipe = { step -> swipeTargetTab(tabList, selectedTab.id, step)?.let(tabs::select) },
                        onVoice = if (hasVoice) voiceSearch else null,
                    )
                    if (!twoRow) tabsAndMenu()
                }
                if (twoRow) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        backButton()
                        Spacer(Modifier.weight(1f))
                        tabsAndMenu()
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
                                stringResource(
                                    when {
                                        state.crashed -> R.string.browser_crashed
                                        state.httpsOnlyBlocked -> R.string.https_only_blocked
                                        else -> R.string.browser_load_failed
                                    },
                                ),
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
            }
            val stripWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
            if (!fullScreen && showsTabStrip(stripWidthDp)) {
                TabStrip(
                    tabs = tabList,
                    onSelect = tabs::select,
                    onClose = { tabs.close(it) },
                    onNewTab = { focusAddressFor = tabs.newTab() },
                    onDropOpen = { text ->
                        normalizeAddressInput(text, container.searchEngine.selected.value)?.let { tabs.newTab(it) }
                    },
                )
            }
            if (!fullScreen && !toolbarBottom) chrome()
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
                            (view as? PullToRefreshSource)?.apply {
                                onPull = { px -> if (!currentFullScreen) pullPx = px }
                                onPullEnd = { px ->
                                    if (!currentFullScreen && pullTriggersRefresh(px, pullThresholdPx)) currentSession?.reload()
                                    pullPx = 0f
                                }
                            }
                        }
                    },
                    update = { view ->
                        contentView = view
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
                readerArticle?.let { article ->
                    val readerSize by container.browserSettings.readerTextSize.collectAsStateWithLifecycle()
                    val readerSerif by container.browserSettings.readerSerif.collectAsStateWithLifecycle()
                    val readerTheme by container.browserSettings.readerTheme.collectAsStateWithLifecycle()
                    ReaderView(
                        article = article,
                        textSizeSp = readerSize,
                        serif = readerSerif,
                        theme = readerTheme,
                        onTextSize = container.browserSettings::setReaderTextSize,
                        onSerif = container.browserSettings::setReaderSerif,
                        onTheme = container.browserSettings::setReaderTheme,
                        onOpenLink = { url ->
                            readerArticle = null
                            session?.loadUrl(url)
                        },
                        onClose = { readerArticle = null },
                    )
                }
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
                if (pullPx > 0f) {
                    PullToRefreshIndicator(
                        fraction = (pullPx / pullThresholdPx).coerceIn(0f, 1f),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                if (blankTab && !state.isLoading) {
                    if (selectedTab.isPrivate) {
                        // A private tab keeps its notice; the start page (history-based Top sites) is never shown here.
                        OrbisEmptyState(stringResource(R.string.browser_private_empty_state), Icons.Filled.VisibilityOff)
                    } else {
                        val pinned by container.browserSettings.pinnedSites.collectAsStateWithLifecycle()
                        val hidden by container.browserSettings.hiddenSites.collectAsStateWithLifecycle()
                        val sites = remember(historyEntries, pinned, hidden) {
                            topSites(historyEntries, pinned = pinned, hiddenHosts = hidden)
                        }
                        StartPage(
                            topSites = sites,
                            bookmarks = bookmarkList,
                            onSearch = { addressFocusRequests++ },
                            onVoice = if (hasVoice) voiceSearch else null,
                            dashboard = container.privacyStats?.let { stats ->
                                {
                                    val version by stats.version.collectAsStateWithLifecycle()
                                    val savings = container.youtubeAdSavings
                                    val ytVersion by remember(savings) {
                                        savings?.version ?: kotlinx.coroutines.flow.MutableStateFlow(0)
                                    }.collectAsStateWithLifecycle()
                                    val uboState by container.ubo.state.collectAsStateWithLifecycle()
                                    val week = remember(version) { stats.summary(7) }
                                    val all = remember(version) { stats.summary(null) }
                                    val youtube = remember(ytVersion, savings) {
                                        savings?.summary() ?: com.shibi.orbis.youtube.YoutubeAdSummary.EMPTY
                                    }
                                    var confirmReset by remember { mutableStateOf(false) }
                                    com.shibi.orbis.ui.home.PrivacyDashboard(
                                        week = week,
                                        allTime = all,
                                        youtube = youtube,
                                        uboActive = (uboState as? com.shibi.orbis.ubo.UboState.Installed)?.enabled == true,
                                        onReset = { confirmReset = true },
                                    )
                                    if (confirmReset) {
                                        androidx.compose.material3.AlertDialog(
                                            onDismissRequest = { confirmReset = false },
                                            title = { Text(stringResource(R.string.stats_reset_title)) },
                                            text = { Text(stringResource(R.string.stats_reset_body)) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    stats.reset()
                                                    savings?.clear()
                                                    confirmReset = false
                                                }) { Text(stringResource(R.string.stats_reset)) }
                                            },
                                            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.prompt_cancel)) } },
                                        )
                                    }
                                }
                            },
                            onOpen = { tabs.loadInSelected(it) },
                            onAllBookmarks = onOpenBookmarks,
                            onPin = { container.browserSettings.pinSite(it.url, it.title) },
                            onUnpin = { container.browserSettings.unpinSite(it.url) },
                            onRemove = { container.browserSettings.removeSite(it.url) },
                        )
                    }
                }
                // Suggestions while typing, over the page (the engine view is never resized or moved for them).
                val query = addressBridge.query
                if (query.isNotBlank() && !fullScreen) {
                    val suggestOn by container.browserSettings.searchSuggestions.collectAsStateWithLifecycle()
                    val engine by container.searchEngine.selected.collectAsStateWithLifecycle()
                    // Live suggestions send what is typed to the search engine: never from a private tab.
                    val remoteAllowed = suggestOn && !selectedTab.isPrivate
                    val local = remember(query, historyEntries, bookmarkList) { localSuggestions(query, historyEntries, bookmarkList) }
                    val remote by produceState(emptyList<String>(), query, engine, remoteAllowed) {
                        if (!remoteAllowed) {
                            value = emptyList()
                            return@produceState
                        }
                        delay(SUGGEST_DEBOUNCE_MS)
                        value = container.searchSuggest.suggest(engine, query.trim())
                    }
                    SuggestionList(
                        suggestions = mergeSuggestions(query, local, if (remoteAllowed) remote else emptyList()),
                        onPick = { addressBridge.submit(it.submitText) },
                        // Next to the address bar: at the bottom of the page area when the toolbar is at the bottom.
                        modifier = Modifier.align(if (toolbarBottom) Alignment.BottomStart else Alignment.TopStart),
                    )
                }
            }
            if (!fullScreen && toolbarBottom) chrome()
        }
    }

    if (showSiteSettings && session != null && isWebUrl(state.url)) {
        val s0 = session
        val origin = siteOriginOf(state.url)
        val entries by container.sitePermissions.entries.collectAsStateWithLifecycle()
        val perms =
            SitePermissionKind.entries.associateWith { kind ->
                permissionChoiceOf(entries.firstOrNull { it.key.kind == kind && it.key.origin == origin && it.key.embedder == null }?.allow)
            }
        SiteSettingsSheet(
            host = hostOfUrl(state.url).orEmpty(),
            secure = state.url.startsWith("https://"),
            isPrivate = s0.isPrivate,
            trackingOn = state.trackingProtectionOff != true,
            javaScriptOn = state.javaScriptOn,
            desktop = state.desktopMode,
            permissions = perms,
            onTracking = { on -> s0.setTrackingProtectionOff(!on) },
            onJavaScript = { on ->
                container.browserSettings.rememberJavaScript(state.url, on, s0.isPrivate)
                s0.setJavaScriptEnabled(on)
            },
            onDesktop = { on ->
                container.browserSettings.rememberDesktop(state.url, on, s0.isPrivate)
                s0.setDesktopMode(on)
            },
            onPermission = { kind, choice ->
                if (origin != null) {
                    val key = SitePermissionKey(kind, origin)
                    when (choice) {
                        PermissionChoice.ALLOW -> container.sitePermissions.record(key, allow = true, remember = true)
                        PermissionChoice.BLOCK -> container.sitePermissions.record(key, allow = false, remember = true)
                        PermissionChoice.ASK -> {
                            container.sitePermissions.remove(key)
                            container.engine.reconcileSitePermissions()
                        }
                    }
                }
            },
            onClearData = {
                val url = state.url
                scope.launch {
                    val ok = container.engine.clearSiteData(url)
                    Toast.makeText(androidContext, if (ok) R.string.site_cleared else R.string.site_clear_failed, Toast.LENGTH_SHORT).show()
                    if (ok) s0.reload()
                }
                showSiteSettings = false
            },
            onDismiss = { showSiteSettings = false },
        )
    }

    generatedPassword?.let { pw ->
        GeneratedPasswordDialog(
            password = pw,
            onCopy = {
                copySensitive(androidContext, pw, sensitive = true)
                generatedPassword = null
            },
            onAnother = { generatedPassword = generatePassword() },
            onDismiss = { generatedPassword = null },
        )
    }

    if (showRecentlyClosed) {
        RecentlyClosedDialog(
            entries = recentlyClosed,
            onOpen = {
                showRecentlyClosed = false
                tabs.reopen(it)
            },
            onClear = {
                tabs.forgetClosed()
                showRecentlyClosed = false
            },
            onDismiss = { showRecentlyClosed = false },
        )
    }

    if (showTabs) {
        TabSwitcher(
            tabs = tabList,
            onEnded = { sheetReturnFocus = tabSheetReturnFocus(it) },
            onSelect = {
                tabs.select(it)
                showTabs = false
            },
            onClose = { id ->
                val before = tabs.recentlyClosed.value.firstOrNull()
                tabs.close(id)
                val closed = tabs.recentlyClosed.value.firstOrNull()
                if (closed != null && closed !== before) {
                    scope.launch {
                        snackbars.currentSnackbarData?.dismiss()
                        val result = snackbars.showSnackbar(closedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed && closed in tabs.recentlyClosed.value) tabs.reopen(closed)
                    }
                }
            },
            thumbnails = thumbnailImages,
            snackbars = snackbars,
            onRecentlyClosed = if (recentlyClosed.isEmpty()) null else ({
                showTabs = false
                showRecentlyClosed = true
            }),
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

    LaunchedEffect(sheetReturnFocus, showTabs) {
        val target = sheetReturnFocus ?: return@LaunchedEffect
        if (showTabs) return@LaunchedEffect
        sheetReturnFocus = null
        // Touch mode: nothing is focused on purpose (no stray focus ring). A new tab focuses its own bar itself.
        if (inputModeManager.inputMode != InputMode.Keyboard) return@LaunchedEffect
        when (target) {
            ReturnFocus.TABS_BUTTON -> runCatching { tabsButtonFocus.requestFocus() }
            ReturnFocus.PAGE -> runCatching { focusSink.requestFocus() }
            ReturnFocus.NEW_TAB_ADDRESS -> Unit
        }
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
                container.downloads.start(pending.download, isPrivate = pending.isPrivate)
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
        LinkMenuSheet(
            request = request,
            onOpenInNewTab = { tabs.openLinkInNewTab(request) },
            onOpenInPrivateTab = { tabs.openLinkInPrivateTab(request) },
            onDownloadLink = { tabs.downloadLink(request) },
            onOpenExternal = { tabs.openExternalLink(request) },
            onDismiss = { tabs.dismissLinkMenu() },
            onOpenImageInNewTab = { tabs.openImageInNewTab(request) },
            onSaveImage = { tabs.saveImage(request) },
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
