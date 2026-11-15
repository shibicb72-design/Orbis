package com.shibi.orbis.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab layer keeps one pending "desktop site kept" mark per tab, from every session, in memory only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoDesktopNoticeMarksTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val created = mutableListOf<NoticeSession>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): TabManager {
        val m = TabManager(
            factory = { init -> NoticeSession(init).also { created += it } },
            store = NoticeStore(),
            scope = scope,
            log = {},
            io = dispatcher,
        )
        m.restore()
        return m
    }

    @Test
    fun noEventMeansNoMark() {
        val m = manager()
        m.newTab("https://a.example/")
        assertTrue(m.autoDesktopNotices.value.isEmpty())
    }

    @Test
    fun anEventMarksOnlyItsOwnTab_evenInTheBackground() {
        val m = manager()
        val a = m.newTab("https://a.example/")!!
        val b = m.newTab("https://b.example/")!!
        // b is selected; a is in the background.
        created[0].kept.tryEmit(Unit)
        assertEquals(setOf(a), m.autoDesktopNotices.value.keys)
        created[1].kept.tryEmit(Unit)
        assertEquals(setOf(a, b), m.autoDesktopNotices.value.keys)
        m.clearAutoDesktopNotice(a)
        assertEquals(setOf(b), m.autoDesktopNotices.value.keys)
    }

    @Test
    fun closingATabDropsItsMark() {
        val m = manager()
        val a = m.newTab("https://a.example/")!!
        val b = m.newTab("https://b.example/")!!
        created[0].kept.tryEmit(Unit)
        created[1].kept.tryEmit(Unit)
        m.close(a)
        assertEquals(setOf(b), m.autoDesktopNotices.value.keys)
        // A closed session's late event is not collected any more.
        created[0].kept.tryEmit(Unit)
        assertEquals(setOf(b), m.autoDesktopNotices.value.keys)
    }

    @Test
    fun aPopupChildAndAnAdoptedSessionAreCollectedToo() {
        val m = manager()
        m.newTab("https://a.example/")
        val opener = created[0]
        val child = NoticeSession(com.shibi.orbis.engine.SessionInit())
        m.onPopupSession(child, opener)
        child.kept.tryEmit(Unit)
        assertEquals(1, m.autoDesktopNotices.value.size)
        val adopted = NoticeSession(com.shibi.orbis.engine.SessionInit())
        assertTrue(m.adoptSession(adopted))
        adopted.kept.tryEmit(Unit)
        assertEquals(2, m.autoDesktopNotices.value.size)
    }

    @Test
    fun aRestoredTabIsCollectedOnceItsSessionExists() {
        val m = manager()
        val first = m.selected.value.id
        assertTrue(m.autoDesktopNotices.value.isEmpty())
        m.openSelected()
        created.single().kept.tryEmit(Unit)
        assertEquals(setOf(first), m.autoDesktopNotices.value.keys)
    }

    private fun markedOn(url: String): Pair<TabManager, NoticeSession> {
        val m = manager()
        m.newTab(url)
        val s = created[0]
        s.stateFlow.value = s.stateFlow.value.copy(url = url)
        s.kept.tryEmit(Unit)
        return m to s
    }

    private fun NoticeSession.goTo(url: String) {
        stateFlow.value = stateFlow.value.copy(url = url)
    }

    @Test
    fun theMarkIsTheUrlOfThePageWhenTheEventArrived() {
        val (m, _) = markedOn("https://a.test/x")
        assertEquals(mapOf(m.selected.value.id to "https://a.test/x"), m.autoDesktopNotices.value)
    }

    @Test
    fun committingAnotherHostDropsTheMark() {
        val (m, s) = markedOn("https://a.test/x")
        s.goTo("https://b.test/")
        assertTrue(m.autoDesktopNotices.value.isEmpty())
    }

    @Test
    fun anotherPathOnTheSameHostKeepsTheOriginalUrl() {
        val (m, s) = markedOn("https://a.test/x")
        s.goTo("https://a.test/other")
        assertEquals(mapOf(m.selected.value.id to "https://a.test/x"), m.autoDesktopNotices.value)
    }

    @Test
    fun aBlankUrlKeepsTheMarkAndAboutBlankDropsIt() {
        val (m, s) = markedOn("https://a.test/x")
        s.goTo("")
        assertEquals(1, m.autoDesktopNotices.value.size)
        s.goTo("about:blank")
        assertTrue(m.autoDesktopNotices.value.isEmpty())
    }

    @Test
    fun anEventOnANonWebPageSetsNoMark() {
        val m = manager()
        m.newTab("https://a.test/x")
        created[0].goTo("about:blank")
        created[0].kept.tryEmit(Unit)
        assertTrue(m.autoDesktopNotices.value.isEmpty())
    }
}
