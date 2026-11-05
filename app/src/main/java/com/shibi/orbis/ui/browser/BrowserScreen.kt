package com.shibi.orbis.ui.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.AppContainer
import com.shibi.orbis.R
import com.shibi.orbis.engine.normalizeAddressInput

private const val START_URL = "https://example.com"

@Composable
fun BrowserScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The session is owned by the container and outlives this composition (e.g. while Settings is open).
    val session = container.session
    val state by session.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var typed by rememberSaveable { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }

    // Only the very first composition loads a page; coming back from Settings keeps the current one.
    LaunchedEffect(session) {
        if (session.state.value.url.isEmpty()) session.loadUrl(START_URL)
    }

    BackHandler(enabled = state.canGoBack) { session.goBack() }

    fun submitAddress() {
        val target = normalizeAddressInput(typed) ?: return
        session.loadUrl(target)
        focusManager.clearFocus()
    }

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
                OutlinedTextField(
                    value = if (addressFocused) typed else state.url,
                    onValueChange = { typed = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focus ->
                            if (focus.hasFocus && !addressFocused) typed = state.url
                            addressFocused = focus.hasFocus
                        },
                    singleLine = true,
                    label = { Text(stringResource(R.string.address_bar_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { session.goBack() }, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                IconButton(onClick = { session.goForward() }, enabled = state.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.forward))
                }
                if (state.isLoading) {
                    IconButton(onClick = { session.stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop))
                    }
                } else {
                    IconButton(onClick = { session.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
                    }
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
                            onClick = { session.reload() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text(stringResource(if (state.crashed) R.string.reload else R.string.browser_retry))
                        }
                    }
                }
            }
            AndroidView(
                factory = { context ->
                    container.engine.createContentView(context).also { view ->
                        view.layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        session.attachTo(view)
                    }
                },
                onRelease = { view -> session.detachFrom(view) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
