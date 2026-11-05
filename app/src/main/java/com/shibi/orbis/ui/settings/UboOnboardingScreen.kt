package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.ubo.UboOnboardingController
import com.shibi.orbis.ubo.UboState

/** First-run choice: install uBlock Origin from Mozilla Add-ons (switch defaults on). Shown instead of the browser. */
@Composable
fun UboOnboardingScreen(controller: UboOnboardingController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    var install by rememberSaveable { mutableStateOf(true) }
    val installing = state is UboState.Installing
    val failed = state as? UboState.Failed

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ubo_onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ubo_onboarding_switch),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
                Switch(checked = install, onCheckedChange = { install = it }, enabled = !installing && failed == null)
            }
            Text(stringResource(R.string.ubo_disclosure), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ubo_etp_stays_on), style = MaterialTheme.typography.bodyMedium)
            if (installing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.ubo_installing), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (failed != null) {
                Text(
                    uboFailureMessage(failed.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = controller::retry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ubo_try_again))
                }
                TextButton(onClick = controller::decline, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ubo_continue_without))
                }
            } else {
                Button(
                    onClick = { if (install) controller.accept() else controller.decline() },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ubo_continue))
                }
            }
        }
    }
}
