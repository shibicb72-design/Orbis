package com.shibi.orbis.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shibi.orbis.R
import com.shibi.orbis.ubo.UboFailure
import com.shibi.orbis.ubo.UboState

@Composable
internal fun uboFailureMessage(reason: UboFailure): String =
    stringResource(
        when (reason) {
            UboFailure.NETWORK -> R.string.ubo_failed_network
            UboFailure.TIMEOUT -> R.string.ubo_failed_timeout
            UboFailure.SIGNATURE,
            UboFailure.BLOCKED,
            UboFailure.WRONG_EXTENSION,
            UboFailure.CORRUPT,
            -> R.string.ubo_failed_trust
            UboFailure.INCOMPATIBLE,
            UboFailure.CANCELLED,
            UboFailure.UNKNOWN,
            -> R.string.ubo_failed_other
        },
    )

/** One-line status for the Settings row and the uBlock Origin screen; never implies protection that is not active. */
@Composable
internal fun uboStatusLine(state: UboState): String =
    when (state) {
        UboState.Checking -> stringResource(R.string.ubo_status_checking)
        UboState.NotInstalled -> stringResource(R.string.ubo_status_not_installed)
        UboState.Installing -> stringResource(R.string.ubo_status_installing)
        is UboState.Installed ->
            if (state.enabled) {
                stringResource(R.string.ubo_status_installed, state.version)
            } else {
                stringResource(R.string.ubo_status_disabled)
            }
        is UboState.Failed -> stringResource(R.string.ubo_status_failed)
    }
