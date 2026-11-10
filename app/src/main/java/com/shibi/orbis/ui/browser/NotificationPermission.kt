package com.shibi.orbis.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

// Asked at most once per process, so a user who declines is not nagged on every download.
private var requestedThisProcess = false
private var askedNearbyThisProcess = false

/**
 * Returns a function that asks for POST_NOTIFICATIONS (API 33+) the first time it is called in this process and does
 * nothing afterwards. It never blocks anything: call it after `downloads.start`. The answer is ignored on purpose: a
 * denied permission only hides the notification, the download still runs and can be cancelled in the Downloads screen.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !requestedThisProcess) {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    requestedThisProcess = true
                    runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
        }
    }
}

/**
 * Asks once per process for NEARBY_WIFI_DEVICES so mDNS can see Chromecast devices. The permission is marked
 * never-for-location. A denial only hides Chromecasts; DLNA discovery does not use it. [onGranted] runs when the user
 * allows it, so discovery can start again.
 */
@Composable
internal fun rememberCastNearbyPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted()
        }
    return remember(context, launcher, onGranted) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNearbyThisProcess) {
                val granted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    askedNearbyThisProcess = true
                    runCatching { launcher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) }
                }
            }
        }
    }
}
