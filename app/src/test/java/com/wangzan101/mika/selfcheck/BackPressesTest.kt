package com.wangzan101.mika.selfcheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自检按返回:输入法弹着时按的那几下是收起输入法,不算在次数里;总下数有上限。 */
class BackPressesTest {
    /** 一块屏:输入法要按 [imePresses] 下才收起(先清没上屏的字、再收起),之后再按 [pageLeavesAfter] 下页面离开。 */
    private class Screen(var imePresses: Int, val pageLeavesAfter: Int = 1) {
        var presses = 0
        private var pageBacks = 0
        var gone = false
        fun press() {
            presses++
            if (imePresses > 0) imePresses-- else if (++pageBacks >= pageLeavesAfter) gone = true
        }
    }

    private fun run(screen: Screen, tries: Int = 2, max: Int = 5) = runBlocking {
        BackPresses.until(tries, max, { screen.imePresses > 0 }, { screen.press() }, { screen.gone })
    }

    @Test fun keyboardThatTakesTwoPressesNoLongerFailsTheCheck() {
        // 原来两下都被输入法吃掉就判失败
        val screen = Screen(imePresses = 2)
        assertTrue(run(screen))
        assertEquals(3, screen.presses)
    }

    @Test fun withoutKeyboardOnlyTheTriesCount() {
        val screen = Screen(imePresses = 0, pageLeavesAfter = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(2, screen.presses)
    }

    @Test fun keyboardThatNeverHidesStopsAtTheCap() {
        val screen = Screen(imePresses = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(5, screen.presses)
    }

    @Test fun firstPressLeavingIsEnough() {
        val screen = Screen(imePresses = 0)
        assertTrue(run(screen))
        assertEquals(1, screen.presses)
    }
}
