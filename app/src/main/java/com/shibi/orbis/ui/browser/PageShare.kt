package com.shibi.orbis.ui.browser

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shibi.orbis.OrbisApp
import com.shibi.orbis.R
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.isImportLoadUrl
import com.shibi.orbis.engine.isWebUrl
import com.shibi.orbis.share.ShareIntents
import com.shibi.orbis.share.ShareOutResult
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the menu's Share does for a page. */
internal enum class ShareTarget {
    /** Start page, blank tab, no session: Share is disabled. */
    NONE,

    /** A web page: its link, or the PDF file when the built-in viewer shows one. */
    LINK_OR_PDF,

    /** A document imported from another app: the file itself. Its `file:` URL is never shared. */
    IMPORTED_FILE,
}

internal fun shareTargetFor(url: String, hasSession: Boolean): ShareTarget =
    when {
        !hasSession -> ShareTarget.NONE
        isWebUrl(url) -> ShareTarget.LINK_OR_PDF
        isImportLoadUrl(url) -> ShareTarget.IMPORTED_FILE
        else -> ShareTarget.NONE
    }

/** One share at a time across the whole app, so a rotation or a second screen cannot start a second copy. */
private val shareRunning = AtomicBoolean(false)

/**
 * Share for the browser and Custom Tab menus. The copy runs in [OrbisApp.appScope], so a rotation does not cancel it;
 * the chooser then opens from the screen that asked if it is still there.
 */
internal class PageShareState(context: Context, private val uiScope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val screen = WeakReference(context)

    /** The web PDF the "Share PDF file / Share link" dialog is about; null when the dialog is closed. */
    var pdfChoice by mutableStateOf<PdfChoice?>(null)
        private set

    class PdfChoice(val session: BrowserSession, val url: String, val title: String?)

    fun onShare(session: BrowserSession?, url: String, title: String?) {
        when (shareTargetFor(url, session != null)) {
            ShareTarget.NONE -> Unit
            ShareTarget.IMPORTED_FILE -> shareImported(url)
            ShareTarget.LINK_OR_PDF -> {
                val s = session ?: return
                uiScope.launch {
                    if (s.isPdfDocument()) pdfChoice = PdfChoice(s, url, title) else shareLink(chooserContext(), url, title)
                }
            }
        }
    }

    fun dismissChoice() {
        pdfChoice = null
    }

    fun shareChoiceLink() {
        val choice = pdfChoice ?: return
        pdfChoice = null
        shareLink(chooserContext(), choice.url, choice.title)
    }

    fun shareChoiceFile() {
        val choice = pdfChoice ?: return
        pdfChoice = null
        runShare {
            val download = choice.session.currentPdf() ?: return@runShare ShareOutResult.Failed
            app().container.shareOut.copyPdf(download, choice.session.isPrivate)
        }
    }

    private fun shareImported(url: String) {
        runShare { app().container.let { it.shareOut.copyImported(it.imports.root, url) } }
    }

    private fun runShare(copy: suspend () -> ShareOutResult) {
        if (!shareRunning.compareAndSet(false, true)) return
        app().appScope.launch {
            try {
                deliver(copy())
            } finally {
                shareRunning.set(false)
            }
        }
    }

    private fun deliver(result: ShareOutResult) {
        val context = chooserContext()
        val message =
            when (result) {
                is ShareOutResult.Ready -> {
                    val chooser = ShareIntents.fileChooser(context, result.file, result.mime, context.getString(R.string.share_file_chooser))
                    if (chooser != null && ShareIntents.start(context, chooser)) return
                    R.string.share_file_failed
                }
                ShareOutResult.TooLarge -> R.string.share_file_too_large
                ShareOutResult.Failed -> R.string.share_file_failed
            }
        toast(message)
    }

    private fun toast(@StringRes message: Int) = Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()

    private fun app(): OrbisApp = appContext as OrbisApp

    /** The screen while it is alive (the chooser stays in its task), else the application. */
    private fun chooserContext(): Context {
        val c = screen.get()
        if (c == null || (c is Activity && (c.isFinishing || c.isDestroyed))) return appContext
        return c
    }
}

@Composable
internal fun rememberPageShare(): PageShareState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) { PageShareState(context, scope) }
}

/** Shows the "Share PDF file / Share link" choice while [state] has one open. */
@Composable
internal fun PageShareDialogs(state: PageShareState) {
    if (state.pdfChoice != null) {
        SharePdfChoiceDialog(
            onShareFile = state::shareChoiceFile,
            onShareLink = state::shareChoiceLink,
            onDismiss = state::dismissChoice,
        )
    }
}

@Composable
internal fun SharePdfChoiceDialog(onShareFile: () -> Unit, onShareLink: () -> Unit, onDismiss: () -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_pdf_title)) },
        text = {
            Column {
                TextButton(
                    onClick = onShareFile,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
                ) { Text(stringResource(R.string.share_pdf_file), modifier = Modifier.fillMaxWidth()) }
                TextButton(
                    onClick = onShareLink,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
                ) { Text(stringResource(R.string.link_menu_share), modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = onContainer)) {
                Text(stringResource(R.string.prompt_cancel))
            }
        },
        containerColor = container,
        titleContentColor = onContainer,
        textContentColor = onContainer,
    )
}
