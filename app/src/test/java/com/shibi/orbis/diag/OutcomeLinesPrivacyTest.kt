package com.shibi.orbis.diag

import com.shibi.orbis.debug.DebugLog
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The privacy rule of the outcome lines is made by the types: no text goes in, so no text comes out. */
class OutcomeLinesPrivacyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class WithText {
        @Suppress("unused")
        fun bad(address: String): Int = address.length
    }

    private fun isText(type: Class<*>): Boolean =
        CharSequence::class.java.isAssignableFrom(type) ||
            type.name == "android.net.Uri" ||
            type == java.net.URI::class.java ||
            type == java.net.URL::class.java ||
            (type.isArray && isText(type.componentType))

    private fun textParameters(type: Class<*>): List<String> {
        val methods: List<Method> = type.declaredMethods.toList()
        val constructors: List<Constructor<*>> = type.declaredConstructors.toList()
        return methods.flatMap { m -> m.parameterTypes.filter(::isText).map { "${type.simpleName}.${m.name}" } } +
            constructors.flatMap { c -> c.parameterTypes.filter(::isText).map { "${type.simpleName}.<init>" } }
    }

    @Test
    fun theCheckSeesAStringParameter() {
        assertEquals(listOf("WithText.bad"), textParameters(WithText::class.java))
    }

    @Test
    fun noOutcomeLineFunctionTakesAString() {
        val classes =
            listOf(
                OutcomeLines::class.java,
                LoadOutcomeTracker::class.java,
                LoadEnd::class.java,
            )
        for (c in classes) assertEquals(c.name, emptyList<String>(), textParameters(c))
    }

    @Test
    fun aSessionThatProducesEveryLine_writesOnlyKindsNumbersAndFlags() {
        val log = DebugLog(tmp.root, Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC))
        val tracker = LoadOutcomeTracker { end ->
            val error = end.error
            log.log(
                "GeckoSession",
                if (error != null) {
                    OutcomeLines.loadError(error, isPrivate = false, tab = 4, ms = end.ms)
                } else {
                    OutcomeLines.load(end.kind, isPrivate = false, tab = 4, ms = end.ms, empty = end.empty, blocked = if (end.empty) 9 else -1)
                },
            )
        }
        // One load of every kind.
        tracker.onLoadRequest(false, 0); tracker.onPageStart(1); tracker.onCommit(); tracker.onPageStop(true, 2); tracker.onEmptyReport(true)
        tracker.onLoadRequest(false, 3); tracker.onLoadRequest(true, 4); tracker.onPageStart(5); tracker.onCommit(); tracker.onPageStop(true, 6)
        tracker.onLoadRequest(false, 7); tracker.onDownload(8)
        tracker.onLoadRequest(false, 9); tracker.onAppHandOff(10)
        tracker.onLoadRequest(false, 11); tracker.onRefused(12)
        tracker.onLoadRequest(false, 13); tracker.onPageStart(14); tracker.onUserStop(15)
        tracker.onLoadRequest(false, 16); tracker.onPageStart(17); tracker.onClosed(18)
        for (category in ErrorCategory.entries) {
            val one = LoadOutcomeTracker { end -> log.log("GeckoSession", OutcomeLines.loadError(end.error!!, false, 4, end.ms)) }
            one.onLoadRequest(false, 0)
            one.onError(category, 5)
        }
        for (via in LinkVia.entries) log.log("Tabs", OutcomeLines.linkIn(via, false, 4, true))
        for (result in PopupResult.entries) log.log("GeckoSession", OutcomeLines.popup(result, false, 4, 5, 1))
        for (kind in AppLinkKind.entries) for (decision in AppLinkDecision.entries) {
            log.log("GeckoSession", OutcomeLines.appLink(kind, decision, asked = true, isPrivate = false, tab = 4))
        }
        log.log("GeckoSession", OutcomeLines.processKilled(false, 12, 3, true))
        for (why in DesktopWhy.entries) log.log("GeckoSession", OutcomeLines.desktopMode(true, why, false, 4))
        // The same lines from a private tab.
        log.log("GeckoSession", OutcomeLines.load(LoadKind.COMMITTED, true, 4, 5))
        log.log("GeckoSession", OutcomeLines.popup(PopupResult.OPENED, true, 4, 5, 1))
        log.log("GeckoSession", OutcomeLines.processKilled(true, 12, 3, true))

        val lines = log.lines()
        assertTrue(lines.size > 40)
        // After the timestamp and the tag: lower-case words, digits, spaces, '=', ':' and '-', nothing else.
        val allowed = Regex("^[a-z0-9 =:\\-]+$")
        for (line in lines) {
            val message = line.substringAfter("GeckoSession ").substringAfter("Tabs ")
            assertTrue(line, allowed.matches(message))
            assertFalse(line, message.contains("http") || message.contains("://") || message.contains('.') || message.contains('/'))
        }
        // The private lines carry no tab number.
        assertTrue(lines.takeLast(3).none { it.contains("tab=4") || it.contains("opener=4") || it.contains("tabs=12") })
    }

    @Test
    fun theOldPopupLineIsGone() {
        val source = engineSource()
        assertFalse(source.contains("popup session created"))
    }

    private fun engineSource(): String {
        val relative = "src/main/java/com/shibi/orbis/engine/GeckoEngineImpl.kt"
        val file = listOf(File(relative), File("app/$relative")).first { it.exists() }
        return file.readText()
    }
}
