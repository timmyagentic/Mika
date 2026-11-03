package com.wangzan101.mika.cap.phone.shell

/** 后台屏不抢主屏焦点时,系统可能把横屏 Activity 缩在竖屏顶部。按系统报告的应用窗口比例纠正屏方向。 */
internal object DisplayOrientation {
    fun rotation(bufferWidth: Int, bufferHeight: Int, current: Int, appWidth: Int, appHeight: Int): Int? {
        if (appWidth <= 0 || appHeight <= 0 || appWidth == appHeight || bufferWidth == bufferHeight) return null
        val naturalLandscape = bufferWidth > bufferHeight
        val currentLandscape = if (current % 2 == 0) naturalLandscape else !naturalLandscape
        val appLandscape = appWidth > appHeight
        if (currentLandscape == appLandscape) return null
        return if (naturalLandscape == appLandscape) 0 else 1
    }
}
