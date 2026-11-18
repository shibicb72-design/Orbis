package com.shibi.orbis.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import com.shibi.orbis.R

/**
 * What the unlock prompt accepts: a strong (class 3) biometric or the screen lock (PIN, pattern, password); a weak
 * biometric alone is not enough. With a fingerprint enrolled the prompt asks for it and offers the screen lock as its own
 * fallback; with none enrolled it asks for the screen lock.
 */
internal fun allowedAuthenticators(): Int =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Android's own biometric/screen-lock prompt (no extra library); [onSuccess] runs on the main thread. */
internal fun authenticate(context: Context, title: String, subtitle: String, onSuccess: () -> Unit) {
    val activity = context.findActivity() ?: return
    val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
    builder.setAllowedAuthenticators(allowedAuthenticators())
    val failed = runCatching {
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }.isFailure
    if (failed) Toast.makeText(context, R.string.passwords_auth_unavailable, Toast.LENGTH_LONG).show()
}

/** Whether the phone has a PIN, pattern, password or fingerprint-backed lock. Without one the prompt has nothing to ask. */
fun isScreenLockSet(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }