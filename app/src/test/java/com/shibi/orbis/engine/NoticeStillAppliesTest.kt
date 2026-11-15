package com.shibi.orbis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule the tab layer and the Custom Tab share for dropping a "desktop site kept" mark. */
class NoticeStillAppliesTest {
    private val mark = "https://a.test/x"

    @Test
    fun anotherPathOnTheSameHostKeepsIt() = assertTrue(noticeStillApplies(mark, "https://A.test/other"))

    @Test
    fun anotherHostDropsIt() = assertFalse(noticeStillApplies(mark, "https://b.test/"))

    @Test
    fun aBlankUrlKeepsIt() = assertTrue(noticeStillApplies(mark, ""))

    @Test
    fun aNonWebPageDropsIt() = assertFalse(noticeStillApplies(mark, "about:blank"))
}
