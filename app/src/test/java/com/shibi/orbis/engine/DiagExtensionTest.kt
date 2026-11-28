package com.shibi.orbis.engine

import com.shibi.orbis.stats.BlockKind
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebExtension

class DiagExtensionTest {
    private val dir = File("src/main/assets/extensions/diag")
    private val script = File(dir, "content.js").readText()

    // ---- enable and disable ----

    @Test
    fun recordingOffNeverInstallsAndAlwaysDisables() {
        for (enabled in listOf(true, false)) {
            for (allowed in listOf(true, false)) {
                for (wantPrivate in listOf(true, false)) {
                    val step = diagNextStep(installed = true, enabled = enabled, allowedInPrivate = allowed, wantRecording = false, wantPrivate = wantPrivate)
                    when {
                        enabled -> assertEquals(DiagStep.DISABLE, step)
                        allowed -> assertEquals(DiagStep.SET_PRIVATE, step)
                        else -> assertEquals(DiagStep.NONE, step)
                    }
                }
            }
            assertEquals(DiagStep.NONE, diagNextStep(false, false, false, wantRecording = false, wantPrivate = true))
        }
    }

    @Test
    fun recordingOnInstallsOnceThenSetsPrivateBeforeEnabling() {
        assertEquals(DiagStep.INSTALL, diagNextStep(false, false, false, wantRecording = true, wantPrivate = false))
        assertEquals(DiagStep.ENABLE, diagNextStep(true, false, false, wantRecording = true, wantPrivate = false))
        assertEquals(DiagStep.SET_PRIVATE, diagNextStep(true, false, false, wantRecording = true, wantPrivate = true))
        assertEquals(DiagStep.ENABLE, diagNextStep(true, false, true, wantRecording = true, wantPrivate = true))
        assertEquals(DiagStep.SET_PRIVATE, diagNextStep(true, true, true, wantRecording = true, wantPrivate = false))
        assertEquals(DiagStep.NONE, diagNextStep(true, true, false, wantRecording = true, wantPrivate = false))
        assertEquals(DiagStep.NONE, diagNextStep(true, true, true, wantRecording = true, wantPrivate = true))
    }

    // ---- no step is asked twice without a change ----

    @Test
    fun aPrivateValueThatWasAskedAndNotTakenIsNotAskedAgain() {
        // allowed stays true, false wanted, false already asked: nothing more to ask (old code: SET_PRIVATE).
        assertEquals(DiagStep.NONE, diagNextStep(true, true, true, wantRecording = true, wantPrivate = false, privateAskedNotTaken = false))
        // Not enabled yet: what follows is ENABLE.
        assertEquals(DiagStep.ENABLE, diagNextStep(true, false, true, wantRecording = true, wantPrivate = false, privateAskedNotTaken = false))
        // Another value asked before: this one is asked.
        assertEquals(DiagStep.SET_PRIVATE, diagNextStep(true, true, true, wantRecording = true, wantPrivate = false, privateAskedNotTaken = true))
        // Recording off: the same rule.
        assertEquals(DiagStep.NONE, diagNextStep(true, false, true, wantRecording = false, wantPrivate = true, privateAskedNotTaken = false))
    }

    @Test
    fun aStuckEnableOrDisableIsNotAskedAgain() {
        assertEquals(DiagStep.NONE, diagNextStep(true, false, false, wantRecording = true, wantPrivate = false, stuck = setOf(DiagStep.ENABLE)))
        assertEquals(DiagStep.NONE, diagNextStep(true, true, false, wantRecording = false, wantPrivate = false, stuck = setOf(DiagStep.DISABLE)))
        assertEquals(DiagStep.DISABLE, diagNextStep(true, true, false, wantRecording = false, wantPrivate = false, stuck = setOf(DiagStep.ENABLE)))
    }

    private data class FakeExt(val enabled: Boolean, val allowedInPrivate: Boolean)

    /** An engine that takes install, enable and disable, and takes the private-tab permission only when [takesPrivate]. */
    private class FakeControl(var takesPrivate: Boolean) : DiagControl<FakeExt> {
        val calls = mutableListOf<String>()
        var ext = FakeExt(enabled = true, allowedInPrivate = true)

        override fun enabled(ext: FakeExt) = ext.enabled

        override fun allowedInPrivate(ext: FakeExt) = ext.allowedInPrivate

        override fun run(step: DiagStep, current: FakeExt?, wantPrivate: Boolean, ok: (FakeExt?) -> Unit, fail: (Throwable?) -> Unit) {
            calls += if (step == DiagStep.SET_PRIVATE) "SET_PRIVATE=$wantPrivate" else step.name
            check(calls.size < 50) { "step asked 50 times: $calls" }
            ext =
                when (step) {
                    DiagStep.INSTALL -> FakeExt(enabled = false, allowedInPrivate = false)
                    DiagStep.ENABLE -> ext.copy(enabled = true)
                    DiagStep.DISABLE -> ext.copy(enabled = false)
                    DiagStep.SET_PRIVATE -> if (takesPrivate) ext.copy(allowedInPrivate = wantPrivate) else ext
                    DiagStep.NONE -> ext
                }
            ok(ext)
        }
    }

    @Test
    fun anEngineThatKeepsThePrivatePermissionGetsOneSetPrivateCall() {
        val control = FakeControl(takesPrivate = false)
        val log = mutableListOf<String>()
        val stepper = DiagStepper(control, { log += it }) { }
        stepper.set(recording = true, privateTabs = false)
        stepper.start(control.ext)
        stepper.set(recording = true, privateTabs = false)
        assertEquals(listOf("SET_PRIVATE=false"), control.calls)
        assertEquals(listOf("diag extension step: SET_PRIVATE", "diag extension: private-tab permission stays true"), log)
    }

    @Test
    fun aChangedWantAsksOnceMoreAndNotAgain() {
        val control = FakeControl(takesPrivate = false)
        val stepper = DiagStepper(control, { }) { }
        stepper.set(recording = true, privateTabs = false)
        stepper.start(control.ext)
        stepper.set(recording = true, privateTabs = true)
        stepper.set(recording = true, privateTabs = false)
        stepper.set(recording = true, privateTabs = false)
        assertEquals(listOf("SET_PRIVATE=false", "SET_PRIVATE=false"), control.calls)
    }

    @Test
    fun anEngineThatTakesThePermissionIsAskedOnceAndEnabledAfterwards() {
        val control = FakeControl(takesPrivate = true)
        control.ext = FakeExt(enabled = false, allowedInPrivate = true)
        val stepper = DiagStepper(control, { }) { }
        stepper.set(recording = true, privateTabs = false)
        stepper.start(control.ext)
        assertEquals(listOf("SET_PRIVATE=false", "ENABLE"), control.calls)
    }

    // ---- the channel ----

    @Test
    fun onlyTheDiagContentScriptOfTheSameTabIsHeard() {
        val session = Any()
        val content = WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
        assertTrue(diagSenderOk(DiagExtensionHolder.ID, content, session, session))
        assertFalse(diagSenderOk("uBlock0@raymondhill.net", content, session, session))
        assertFalse(diagSenderOk(CastExtensionHolder.ID, content, session, session))
        assertFalse(diagSenderOk(null, content, session, session))
        assertFalse(diagSenderOk(DiagExtensionHolder.ID, WebExtension.MessageSender.ENV_TYPE_EXTENSION, session, session))
        assertFalse(diagSenderOk(DiagExtensionHolder.ID, null, session, session))
        assertFalse(diagSenderOk(DiagExtensionHolder.ID, content, Any(), session))
        assertFalse(diagSenderOk(DiagExtensionHolder.ID, content, null, session))
    }

    @Test
    fun helloIsExactlyOneField() {
        assertTrue(isDiagHello(JSONObject().put("type", "hello")))
        assertFalse(isDiagHello(JSONObject().put("type", "hello").put("url", "https://example.com/")))
        assertFalse(isDiagHello(JSONObject().put("type", "record")))
        assertFalse(isDiagHello(JSONObject()))
    }

    @Test
    fun blockCountsArePerPage() {
        val c = PageBlockCounter()
        c.count(BlockKind.TRACKER, "https://a.example/")
        c.count(BlockKind.TRACKER, "https://a.example/")
        c.count(BlockKind.AD, "https://a.example/")
        assertEquals(mapOf(BlockKind.TRACKER to 2, BlockKind.AD to 1), c.snapshot("https://a.example/"))
        assertEquals(emptyMap<BlockKind, Int>(), c.snapshot("https://b.example/"))
        c.count(BlockKind.AD, "https://b.example/")
        assertEquals(mapOf(BlockKind.AD to 1), c.snapshot("https://b.example/"))
    }

    // ---- the extension's files ----

    @Test
    fun theManifestHasOneContentScriptOnlyAndExactlyTheMessagingPermissions() {
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        // A new version would make the engine reinstall it enabled (ruling K1): it stays "1.0".
        assertEquals("1.0", manifest.getString("version"))
        assertEquals(DiagExtensionHolder.ID, manifest.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        assertFalse(manifest.has("background"))
        assertFalse(manifest.has("browser_action"))
        assertFalse(manifest.has("page_action"))
        assertFalse(manifest.has("web_accessible_resources"))
        val perms = manifest.getJSONArray("permissions").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(setOf("nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons"), perms)
        val scripts: JSONArray = manifest.getJSONArray("content_scripts")
        assertEquals(1, scripts.length())
        val cs = scripts.getJSONObject(0)
        assertEquals(listOf("content.js"), cs.getJSONArray("js").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertTrue(cs.getBoolean("all_frames"))
        assertEquals(setOf("http://*/*", "https://*/*"), cs.getJSONArray("matches").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
        assertEquals(setOf("content.js", "manifest.json"), dir.list()!!.toSet())
    }

    @Test
    fun theScriptNeverActsOnAMediaElementOrThePage() {
        for (bad in listOf(".load(", ".play(", ".pause(", "currentTime =", "src =", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket",
            "appendChild", "insertBefore", ".append(", ".prepend(", ".remove(", "setAttribute", "innerHTML", "textContent", "document.title",
            "location.href =", "localStorage", "indexedDB", "document.cookie", "postMessage(location", "eval(")) {
            assertFalse("content.js contains $bad", script.contains(bad))
        }
        // Calls on a media element: only these read-only ones (ruling K5).
        val onElement = Regex("""\bel\.(\w+)\s*\(""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("getVideoPlaybackQuality"), onElement)
        val onProbe = Regex("""\bprobe\.(\w+)\s*\(""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("canPlayType"), onProbe)
        val onRanges = Regex("""\branges\.(\w+)\s*\(""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("start", "end"), onRanges)
        // Nothing is assigned to an element.
        assertFalse(Regex("""\bel\.\w+\s*=[^=]""").containsMatchIn(script))
        // The probe is never added to the page.
        assertFalse(Regex("""[(,]\s*probe\s*[,)]""").containsMatchIn(script))
    }

    @Test
    fun theScriptWaitsForTheAppsRecordAnswerBeforeListening() {
        val start = script.indexOf("function start()")
        val firstListener = script.indexOf("addEventListener(")
        assertTrue(start in 0 until firstListener)
        // The only call of start() is in the port's "record" listener.
        assertEquals(1, Regex("""\bstart\(\);""").findAll(script).count())
        val recordListener = script.substringAfter("port.onMessage.addListener(")
        assertTrue(recordListener.substringBefore("});").contains("message.type !== \"record\""))
        assertTrue(recordListener.substringBefore("});").contains("start();"))
    }
}
