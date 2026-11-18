package com.shibi.orbis.ui.browser

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shibi.orbis.R
import com.shibi.orbis.engine.AddonPopup

/** Test tag of the add-on panel sheet. */
const val ADDON_POPUP_TAG = "addon_popup"

/** The panel takes at most this share of the window's height. */
internal const val ADDON_POPUP_MAX_HEIGHT = 0.7f

/**
 * An add-on's panel (its toolbar button's popup) in a bottom sheet: the add-on's name, a Close button, and the panel's
 * page in a view of its own ([createView], an engine content view), so the page's view stays as it is. Closing the
 * sheet in any way (Close, Back, a tap outside) closes the panel's page ([AddonPopup.close]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonPopupSheet(
    popup: AddonPopup,
    createView: (Context) -> View,
) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = { popup.close() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // A drag inside the panel belongs to the panel's page, not to the sheet.
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(ADDON_POPUP_TAG),
    ) {
        Column(Modifier.fillMaxWidth().height((windowHeight * ADDON_POPUP_MAX_HEIGHT - bottomInset).coerceAtLeast(120.dp))) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    popup.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    maxLines = 1,
                )
                TextButton(onClick = { popup.close() }) { Text(stringResource(R.string.addon_popup_close)) }
            }
            AndroidView(
                factory = { context ->
                    createView(context).also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { view -> popup.attachTo(view) },
                onRelease = { view -> popup.detachFrom(view) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
