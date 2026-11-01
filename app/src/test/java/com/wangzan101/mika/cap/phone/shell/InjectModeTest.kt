package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入方式:按键等系统定下投给谁,没投出去就报失败(调用方才会退回);触摸照旧不等。
 * 一对按下/抬起:按下没成才退回,按下成了只补抬起。
 */
class InjectModeTest {
    // InputManager.INJECT_INPUT_EVENT_MODE_*
    private val async = 0
    private val waitForResult = 1
    private val waitForFinish = 2

    @Test fun keysWaitForTheDispatchResult() {
        assertEquals(waitForResult, ShellService.injectMode(key = true, waitForFinish = false))
    }

    @Test fun touchesStayAsync() {
        assertEquals(async, ShellService.injectMode(key = false, waitForFinish = false))
    }

    @Test fun explicitWaitForFinishWins() {
        assertEquals(waitForFinish, ShellService.injectMode(key = true, waitForFinish = true))
        assertEquals(waitForFinish, ShellService.injectMode(key = false, waitForFinish = true))
    }

    @Test fun undeliveredDownFallsBack() {
        var ups = 0
        var fallbacks = 0
        assertTrue(ShellService.sendPair({ false }, { ups++; true }, { fallbacks++; true }))
        assertEquals(1, fallbacks)
        assertEquals(0, ups)
        assertFalse(ShellService.sendPair({ false }, { true }, { false }))
    }

    @Test fun deliveredDownNeverFallsBackAndRetriesUpOnce() {
        var ups = 0
        var fallbacks = 0
        assertFalse(ShellService.sendPair({ true }, { ups++; false }, { fallbacks++; true }))
        assertEquals(2, ups)
        assertEquals(0, fallbacks)
    }
}
