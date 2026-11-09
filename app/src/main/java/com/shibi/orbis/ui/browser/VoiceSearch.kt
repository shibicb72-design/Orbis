package com.shibi.orbis.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shibi.orbis.R

/** True when the phone has a speech recognizer (Google, Samsung or another) that Orbis can ask. */
internal fun voiceSearchAvailable(context: Context): Boolean =
    runCatching {
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }.getOrDefault(false)

/**
 * Starts the phone's speech recognizer and hands the words heard to [onResult] (then searched or opened like typed
 * text). A private tab asks for on-device recognition. Returns the function that starts it.
 */
@Composable
internal fun rememberVoiceSearch(isPrivate: Boolean, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
            if (!heard.isNullOrEmpty()) callback.value(heard)
        }
    val private = rememberUpdatedState(isPrivate)
    val prompt = stringResource(R.string.voice_prompt)
    return {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .apply { if (private.value) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
        runCatching { launcher.launch(intent) }.onFailure {
            Toast.makeText(context, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
