package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 外观改了(设置页或任务里)界面当场跟着换,不靠重建界面。 */
class FeatureFlagsThemeTest {
    @Test fun defaultsToSystemAndUnknownFallsBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
        prefs.edit().putString("theme_mode", "Sepia").commit()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val seen = mutableListOf<ThemeMode>()
        val job = launch { flags.themeModeChanges().take(3).toList(seen) }
        runCurrent()
        flags.setThemeMode(ThemeMode.Dark)
        runCurrent()
        SelfConfigImpl(flags).set("appearance", "light")
        job.join()
        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light), seen)
    }
}
