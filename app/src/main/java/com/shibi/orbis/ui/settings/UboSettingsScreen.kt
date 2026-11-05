package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.ubo.UboOnboardingController
import com.shibi.orbis.ubo.UboState

/** Status and controls for the optional uBlock Origin install. Claims nothing beyond the actual state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UboSettingsScreen(
    controller: UboOnboardingController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ubo_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(uboStatusLine(state), style = MaterialTheme.typography.titleMedium)
            when (val current = state) {
                UboState.Checking -> Unit
                UboState.Installing ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.ubo_installing), style = MaterialTheme.typography.bodyMedium)
                    }
                is UboState.Installed -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ubo_settings_enable),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 16.dp),
                        )
                        Switch(checked = current.enabled, onCheckedChange = controller::setEnabled)
                    }
                    Text(
                        stringResource(
                            when {
                                current.enabled -> R.string.ubo_settings_on
                                current.disabledByUser -> R.string.ubo_settings_off_by_user
                                else -> R.string.ubo_settings_off_by_browser
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = controller::remove, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ubo_remove))
                    }
                }
                UboState.NotInstalled, is UboState.Failed -> {
                    if (current is UboState.Failed) {
                        Text(
                            uboFailureMessage(current.reason),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(stringResource(R.string.ubo_disclosure), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = controller::retry, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (current is UboState.Failed) R.string.ubo_try_again else R.string.ubo_install,
                            ),
                        )
                    }
                }
            }
        }
    }
}
