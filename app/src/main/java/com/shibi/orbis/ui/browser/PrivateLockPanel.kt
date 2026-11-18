package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.privacy.PrivateTabsLock
import com.shibi.orbis.ui.authenticate

/**
 * Drawn over the page area instead of a private tab's page while the private tabs are locked. It shows nothing of the
 * page. "Close private tabs" never asks for the fingerprint: closing hides more, not less.
 */
@Composable
internal fun PrivateLockPanel(onUnlock: () -> Unit, onClosePrivate: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize().pointerInput(Unit) {},
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.private_lock_panel_title), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onUnlock) { Text(stringResource(R.string.private_lock_unlock)) }
            OutlinedButton(onClick = onClosePrivate) { Text(stringResource(R.string.private_close_all)) }
        }
    }
}

/** What stands in for the address bar while the page is hidden: no address, no input. */
@Composable
internal fun PrivateLockAddressStub(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            stringResource(R.string.private_lock_card_title),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/**
 * Asks for the fingerprint (or the screen lock) and, when it passes, unlocks [lock] and runs the callback. The prompt is
 * Android's own, the one the Passwords screen uses.
 */
@Composable
internal fun rememberPrivateUnlock(lock: PrivateTabsLock): (afterUnlock: () -> Unit) -> Unit {
    val context = LocalContext.current
    val title = stringResource(R.string.private_lock_auth_title)
    val subtitle = stringResource(R.string.private_lock_auth_subtitle)
    return remember(context, lock, title, subtitle) {
        { afterUnlock ->
            authenticate(context, title, subtitle) {
                lock.onUnlocked()
                afterUnlock()
            }
        }
    }
}

/**
 * What the menu and the extra toolbar button may know about the page while it is [hidden] behind the lock panel: no
 * session, no address and nothing to go forward to. Every page entry (and the button) then turns itself off, so none can
 * act on the hidden page; New tab, Bookmarks, History, Downloads and Settings do not depend on the page.
 */
internal fun MenuPageState.whileHidden(hidden: Boolean): MenuPageState =
    if (!hidden) {
        this
    } else {
        copy(
            hasSession = false,
            url = "",
            isLoading = false,
            canGoForward = false,
            readerable = false,
            translation = null,
            desktopMode = false,
            bookmarked = false,
        )
    }
