package com.shibi.orbis.customtabs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.MainActivity
import com.shibi.orbis.OrbisApp
import com.shibi.orbis.R
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.tabs.PendingDownload
import com.shibi.orbis.ui.browser.DownloadConfirmDialog
import com.shibi.orbis.ui.browser.SitePromptDialog
import com.shibi.orbis.ui.browser.PageShareDialogs
import com.shibi.orbis.ui.browser.rememberPageShare
import com.shibi.orbis.ui.theme.OrbisTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A Custom Tab: one page opened by another app (Gmail, chat apps...) on top of that app, in Orbis's normal profile
 * (cookies, logins, tracking protection, uBlock Origin). Close returns to the app; "Open in Orbis" moves the page, with
 * its history, into a regular tab. Visits go to history as in a tab. Started only by [com.shibi.orbis.LinkRouterActivity].
 */
class CustomTabActivity : ComponentActivity() {
    private val app get() = application as OrbisApp
    private var session: BrowserSession? = null

    /** Set once the page moved into a regular tab: the session must then not be closed here. */
    private var handedOff = false
    private val pendingDownload = MutableStateFlow<PendingDownload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent?.dataString?.takeIf { isWebUrl(it) }
        if (url == null) {
            finish()
            return
        }
        val toolbar = if (intent.hasExtra(EXTRA_ORBIS_TOOLBAR_COLOR)) intent.getIntExtra(EXTRA_ORBIS_TOOLBAR_COLOR, 0) else null
        val showTitle = intent.getBooleanExtra(EXTRA_ORBIS_SHOW_TITLE, false)
        // The page loads once the view is attached (see CustomTabScreen): loading before leaves Gecko's idea of the
        // page's screen position at 0,0, which misplaces screen-reader focus.
        val s = app.container.engine.createSession(SessionInit())
        s.host = host
        session = s
        setContent {
            val theme by app.container.browserSettings.appTheme.collectAsStateWithLifecycle()
            OrbisTheme(theme) {
                CustomTabScreen(
                    session = s,
                    startUrl = url,
                    toolbarArgb = toolbar,
                    showTitle = showTitle,
                    pendingDownload = pendingDownload,
                    onClose = ::finish,
                    onOpenInOrbis = ::openInOrbis,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session?.setActive(true)
    }

    override fun onStop() {
        if (!handedOff) session?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        pendingDownload.value?.download?.discard()
        pendingDownload.value = null
        if (!handedOff) session?.close()
        session = null
        super.onDestroy()
    }

    private fun openInOrbis() {
        val s = session ?: return
        val tabs = app.container.tabs
        if (tabs.adoptSession(s)) {
            handedOff = true
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // The tab list is not ready (or full): open the address in the browser instead.
            val url = s.state.value.url.takeIf { isWebUrl(it) } ?: intent.dataString ?: return
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private val host =
        object : SessionHost {
            override fun canOpenTab(): Boolean = app.container.tabs.ready.value

            override fun onOpenInNewTab(url: String, opener: BrowserSession) {
                if (isWebUrl(url)) app.container.tabs.newTab(url)
            }

            override fun onPopupSession(child: BrowserSession, opener: BrowserSession) {
                // A new window opens as a regular Orbis tab, like Chrome does from a Custom Tab.
                if (app.container.tabs.ready.value) {
                    app.container.tabs.onPopupSession(child, opener)
                    startActivity(Intent(this@CustomTabActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    child.close()
                }
            }

            override fun onCloseRequest(session: BrowserSession) = finish()

            override fun onDownload(download: IncomingDownload, session: BrowserSession) {
                if (pendingDownload.value != null) download.discard() else pendingDownload.value = PendingDownload(download, -1L, false)
            }

            override fun onLinkContextMenu(url: String, session: BrowserSession) {}

            override fun onExternalAppMissing(url: String) {
                if (url.isBlank()) return
                runCatching { Toast.makeText(this@CustomTabActivity, R.string.open_app_none, Toast.LENGTH_LONG).show() }
            }

            override fun onSessionStateChanged(session: BrowserSession) {}

            override fun onVisit(session: BrowserSession, url: String, title: String) = app.container.history.recordVisit(url, title)

            override fun onVisitTitle(session: BrowserSession, url: String, title: String) = app.container.history.updateTitle(url, title)
        }
}

@Composable
private fun CustomTabScreen(
    session: BrowserSession,
    startUrl: String,
    toolbarArgb: Int?,
    showTitle: Boolean,
    pendingDownload: MutableStateFlow<PendingDownload?>,
    onClose: () -> Unit,
    onOpenInOrbis: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as OrbisApp
    val state by session.state.collectAsStateWithLifecycle()
    val prompt by session.prompts.collectAsStateWithLifecycle()
    val download by pendingDownload.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val pageShare = rememberPageShare()
    // The app's color when it gave one; its content color is chosen for contrast (never dark on dark).
    val bar = toolbarArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainer
    val onBar = toolbarArgb?.let { Color(contentColorOn(it)) } ?: MaterialTheme.colorScheme.onSurface
    BackHandler { if (!session.goBack()) onClose() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Surface(color = bar, contentColor = onBar) {
            Column {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ct_close)) }
                    Column(Modifier.weight(1f)) {
                        val host = hostOfUrl(state.url).orEmpty().removePrefix("www.")
                        if (showTitle && !state.title.isNullOrBlank()) {
                            Text(state.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.url.startsWith("https://")) {
                                Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.ct_secure), modifier = Modifier.padding(end = 4.dp).height(14.dp))
                            }
                            Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ct_open_in_orbis)) }, onClick = {
                            menuOpen = false
                            onOpenInOrbis()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reload)) }, onClick = {
                            menuOpen = false
                            session.reload()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_share)) }, enabled = isWebUrl(state.url), onClick = {
                            menuOpen = false
                            pageShare.onShare(session, state.url, state.title)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ct_copy_link)) }, enabled = isWebUrl(state.url), onClick = {
                            menuOpen = false
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("URL", state.url))
                            Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_desktop_site)) }, onClick = {
                            menuOpen = false
                            session.setDesktopMode(!state.desktopMode)
                        })
                    }
                }
                if (state.isLoading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = onBar)
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        AndroidView(
            factory = { ctx ->
                app.container.engine.createContentView(ctx).also { view ->
                    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            // Attached once the view has been laid out in the window, then the first page loads (once; a recreated view
            // only reattaches). Earlier, Gecko takes the page's screen position as 0,0 and screen-reader focus is misplaced.
            update = { view ->
                if (view.tag == null) {
                    view.tag = session
                    view.doOnLayout {
                        session.attachTo(view)
                        if (session.state.value.url.isEmpty()) session.loadUrl(startUrl)
                    }
                }
            },
            onRelease = { view -> session.detachFrom(view) },
            modifier = Modifier.fillMaxSize(),
        )
    }
    prompt?.let { SitePromptDialog(it) }
    PageShareDialogs(pageShare)
    download?.let { pending ->
        DownloadConfirmDialog(
            pending = pending,
            onConfirm = {
                app.container.downloads.start(pending.download, isPrivate = false)
                pendingDownload.value = null
            },
            onCancel = {
                pending.download.discard()
                pendingDownload.value = null
            },
        )
    }
}
