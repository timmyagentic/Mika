package com.wangzan101.mika.settings

/** 界面配色:跟随系统(默认)、一直浅色、一直深色。 */
enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun parse(s: String?): ThemeMode = entries.firstOrNull { it.name == s } ?: System
    }
}
