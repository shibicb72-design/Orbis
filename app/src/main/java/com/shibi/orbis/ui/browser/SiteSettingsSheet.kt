package com.shibi.orbis.ui.browser

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.engine.SitePermissionKind

/** A remembered permission answer for a site, or [ASK] when there is none (the site asks again). */
internal enum class PermissionChoice { ALLOW, BLOCK, ASK }

internal fun permissionChoiceOf(stored: Boolean?): PermissionChoice =
    when (stored) {
        true -> PermissionChoice.ALLOW
        false -> PermissionChoice.BLOCK
        null -> PermissionChoice.ASK
    }

@StringRes
private fun kindLabel(kind: SitePermissionKind): Int =
    when (kind) {
        SitePermissionKind.CAMERA -> R.string.site_perm_camera
        SitePermissionKind.MICROPHONE -> R.string.site_perm_microphone
        SitePermissionKind.LOCATION -> R.string.site_perm_location
    }

/**
 * Everything Orbis knows about one site in one place: whether the connection is secure, tracking protection, JavaScript,
 * desktop site, the camera/microphone/location answers, and clearing the site's cookies and data. A private tab shows
 * the switches only (its answers and data are not kept).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteSettingsSheet(
    host: String,
    secure: Boolean,
    isPrivate: Boolean,
    trackingOn: Boolean,
    javaScriptOn: Boolean,
    desktop: Boolean,
    desktopAutoNote: Boolean = false,
    permissions: Map<SitePermissionKind, PermissionChoice>,
    onTracking: (Boolean) -> Unit,
    onJavaScript: (Boolean) -> Unit,
    onDesktop: (Boolean) -> Unit,
    onPermission: (SitePermissionKind, PermissionChoice) -> Unit,
    onClearData: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(host, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(if (secure) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(
                    stringResource(if (secure) R.string.site_secure else R.string.site_not_secure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (secure) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
            SwitchRow(R.string.menu_tracking_protection, R.string.site_tracking_summary, trackingOn, onTracking)
            SwitchRow(R.string.javascript_title, R.string.site_js_summary, javaScriptOn, onJavaScript)
            SwitchRow(R.string.menu_desktop_site, R.string.site_desktop_summary, desktop, onDesktop)
            if (desktopAutoNote) Text(stringResource(R.string.auto_desktop_note), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.site_permissions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (isPrivate) {
                Text(stringResource(R.string.site_private_note), style = MaterialTheme.typography.bodyMedium)
            } else {
                SitePermissionKind.entries.forEach { kind ->
                    val current = permissions[kind] ?: PermissionChoice.ASK
                    Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PermissionChoice.entries.forEach { choice ->
                            FilterChip(
                                selected = current == choice,
                                onClick = { onPermission(kind, choice) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (choice) {
                                                PermissionChoice.ALLOW -> R.string.site_allow
                                                PermissionChoice.BLOCK -> R.string.site_block
                                                PermissionChoice.ASK -> R.string.site_ask
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onClearData, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.site_clear_data))
                }
                Text(stringResource(R.string.site_clear_data_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
