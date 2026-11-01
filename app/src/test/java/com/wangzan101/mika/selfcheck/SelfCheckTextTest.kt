package com.wangzan101.mika.selfcheck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自检认页面文字:读屏给的大小写、空白和页面上写的不一样时照样认得出,别的文字不认错。 */
class SelfCheckTextTest {
    @Test
    fun buttonTextReadBackInCapitalsStillMatches() {
        assertTrue(SelfCheckText.same("TAP ME", "Tap me"))
        assertTrue(SelfCheckText.same("tap me", "Tap me"))
    }

    @Test
    fun extraOrOddWhitespaceStillMatches() {
        assertTrue(SelfCheckText.same("  Tap  me\n", "Tap me"))
        assertTrue(SelfCheckText.same("Mika  self-check\tpage", "Mika self-check page"))
    }

    @Test
    fun differentTextDoesNotMatch() {
        assertFalse(SelfCheckText.same("Tap me now", "Tap me"))
        assertFalse(SelfCheckText.same("Tapme", "Tap me"))
    }

    @Test
    fun nonLatinTextMatchesAsIs() {
        assertTrue(SelfCheckText.same("点我", "点我"))
        assertTrue(SelfCheckText.same("НАЖМИ МЕНЯ", "Нажми меня"))
    }

    @Test
    fun containsIgnoresCaseAndWhitespace() {
        assertTrue(SelfCheckText.contains("MIKA 123", "Mika 123"))
        assertTrue(SelfCheckText.contains("text  CLICKED 1 here", "Clicked 1"))
        assertFalse(SelfCheckText.contains("Clicked 0", "Clicked 1"))
    }

    @Test
    fun numberedTextIgnoresCaseAndWhitespace() {
        val taps = SelfCheckText.Numbered("Taps: 987654321", "987654321")
        assertTrue(taps.matches("Taps: 0"))
        assertTrue(taps.matches("TAPS:  12"))
        assertFalse(taps.matches("Taps: "))
        assertFalse(taps.matches("Taps: x"))
        assertFalse(taps.matches("Ticks: 3"))
    }

    @Test
    fun numberedTextWithWordsAfterTheNumber() {
        val taps = SelfCheckText.Numbered("点了 987654321 次", "987654321")
        assertTrue(taps.matches("点了 3 次"))
        assertFalse(taps.matches("点了 3"))
    }
}
