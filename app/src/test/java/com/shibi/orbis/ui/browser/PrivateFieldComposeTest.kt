package com.shibi.orbis.ui.browser

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Compose on the JVM (Robolectric): a focused text field makes Compose open its genuine platform text-input session,
 * and the EditorInfo the platform (the keyboard) would receive is read from the AndroidComposeView. No fakes for the
 * request, the interceptor or the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PrivateFieldComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Captured(val editorInfo: EditorInfo, val connection: InputConnection?)

    private val flag = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    /** The AndroidComposeView that currently is a text editor, i.e. whose text input session is open. */
    private fun textEditorView(): View {
        fun find(v: View): View? {
            if (v.onCheckIsTextEditor()) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(rule.activity.window.decorView)) { "no view with an open text input session" }
    }

    private fun captureEditorInfo(): Captured {
        rule.waitForIdle()
        val view = textEditorView()
        var captured: Captured? = null
        rule.runOnUiThread {
            val info = EditorInfo()
            val connection = view.onCreateInputConnection(info)
            captured = Captured(info, connection)
        }
        return captured!!
    }

    /** The shape of the address bar field (singleLine, Uri, Go), focused by [requester] once composed. */
    @androidx.compose.runtime.Composable
    private fun AddressShapedField(requester: FocusRequester, focusOnStart: Boolean) {
        var text by remember { mutableStateOf("") }
        if (focusOnStart) LaunchedEffect(Unit) { requester.requestFocus() }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.focusRequester(requester),
            singleLine = true,
            label = { Text("address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        )
    }

    /** The find-bar / page-prompt shape: a plain single-line field with ImeAction.Search. */
    @androidx.compose.runtime.Composable
    private fun FindShapedField(requester: FocusRequester) {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.focusRequester(requester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }

    @Test
    fun privateScopeAddsNoPersonalizedLearningToTheRealEditorInfo() {
        val requester = FocusRequester()
        rule.setContent { PrivateImeScope(isPrivate = true) { AddressShapedField(requester, focusOnStart = true) } }
        val captured = captureEditorInfo()
        assertNotNull("private field must still get a real input connection", captured.connection)
        assertTrue(
            "imeOptions=${Integer.toHexString(captured.editorInfo.imeOptions)}",
            (captured.editorInfo.imeOptions and flag) != 0,
        )
        assertEquals(
            "Compose's own action bits survive",
            EditorInfo.IME_ACTION_GO,
            captured.editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION,
        )
    }

    @Test
    fun normalScopeLeavesTheFlagOffAndStillCreatesAConnection() {
        val requester = FocusRequester()
        rule.setContent { PrivateImeScope(isPrivate = false) { AddressShapedField(requester, focusOnStart = true) } }
        val captured = captureEditorInfo()
        // Positive control: the capture path works (non-null connection, Go action present), the flag is just absent.
        assertNotNull(captured.connection)
        assertEquals(EditorInfo.IME_ACTION_GO, captured.editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertEquals(0, captured.editorInfo.imeOptions and flag)
    }

    @Test
    fun privateImeOptionsEqualTheNormalOnesWithOnlyTheFlagAdded() {
        var isPrivate by mutableStateOf(false)
        val requester = FocusRequester()
        // key() recomposes a brand-new field per mode, so each mode opens its own fresh text input session.
        rule.setContent {
            key(isPrivate) { PrivateImeScope(isPrivate) { AddressShapedField(requester, focusOnStart = true) } }
        }
        val normal = captureEditorInfo()
        rule.runOnUiThread { isPrivate = true }
        val private = captureEditorInfo()

        assertNotNull(normal.connection)
        assertNotNull(private.connection)
        assertNotSame(normal.editorInfo, private.editorInfo)
        assertEquals(0, normal.editorInfo.imeOptions and flag)
        assertEquals(normal.editorInfo.imeOptions or flag, private.editorInfo.imeOptions)
        assertEquals(addressBarImeOptions(normal.editorInfo.imeOptions, isPrivate = true), private.editorInfo.imeOptions)
    }

    @Test
    fun everyFieldInsideOnePrivateScopeGetsTheFlag() {
        val address = FocusRequester()
        val find = FocusRequester()
        rule.setContent {
            PrivateImeScope(isPrivate = true) {
                Column {
                    AddressShapedField(address, focusOnStart = true)
                    FindShapedField(find)
                }
            }
        }
        val first = captureEditorInfo()
        assertTrue((first.editorInfo.imeOptions and flag) != 0)
        assertEquals(EditorInfo.IME_ACTION_GO, first.editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)

        rule.runOnUiThread { find.requestFocus() }
        val second = captureEditorInfo()
        assertNotNull(second.connection)
        assertTrue((second.editorInfo.imeOptions and flag) != 0)
        // It really is the other field's session: the action is the find field's, not the address bar's.
        assertEquals(EditorInfo.IME_ACTION_SEARCH, second.editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
    }
}
