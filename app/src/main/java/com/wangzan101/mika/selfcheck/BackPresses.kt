package com.wangzan101.mika.selfcheck

/**
 * 自检里按返回、等页面离开:按之前弹着输入法的那一下,安卓先拿去收起输入法(或先清掉没上屏的字),不算一次;
 * 输入法没弹着时按了 [tries] 下页面还在才算失败。一共最多按 [maxPresses] 下,输入法收不起来也不会一直按下去。
 */
internal object BackPresses {
    suspend fun until(
        tries: Int,
        maxPresses: Int,
        keyboardUp: suspend () -> Boolean,
        press: suspend () -> Unit,
        left: suspend () -> Boolean,
    ): Boolean {
        var counted = 0
        var presses = 0
        while (counted < tries && presses < maxPresses) {
            val keyboard = keyboardUp()
            press()
            presses++
            if (left()) return true
            if (!keyboard) counted++
        }
        return false
    }
}
