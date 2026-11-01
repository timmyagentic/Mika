package com.wangzan101.mika.settings

import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.cap.phone.OperatingPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** settings_list / settings_set 的白名单:只给能随手改回去的偏好;模型、Key、删数据一律不在其中。 */
class SelfConfigImplTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun offersOnlyPreferencesAvailableToday() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }.toSet()
        assertEquals(setOf("operating_pace", "mute_apps", "appearance", "capsule", "keep_screen_on"), keys)
    }

    @Test fun modelsKeysAndAnythingThatDeletesDataAreNotOffered() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }
        assertTrue(keys.none { k -> listOf("model", "key", "delete", "reset", "clear").any { it in k } })
        // 容器的联网与代理会让数据离开手机,也要用户自己在设置里决定
        assertTrue("container_network" !in keys)
    }

    @Test fun changingAPreferenceTakesEffect() {
        val f = flags()
        val s = SelfConfigImpl(f)
        assertNull(s.set("operating_pace", "fast"))
        assertEquals(OperatingPace.FAST, f.operatingPace())
        assertNull(s.set("mute_apps", "off"))
        assertEquals(false, f.muteTarget())
        assertNull(s.set("appearance", "dark"))
        assertEquals(ThemeMode.Dark, f.themeMode())
        assertNull(s.set("capsule", "off"))
        assertEquals(CapsuleMode.Off, f.capsuleMode())
        assertNull(s.set("keep_screen_on", "off"))
        assertEquals(false, f.keepScreenOn())
    }

    @Test fun badValuesAndUnknownKeysAreReported() {
        val s = SelfConfigImpl(flags())
        assertNotNull(s.set("operating_pace", "turbo"))
        assertNotNull(s.set("appearance", "sepia"))
        assertNotNull(s.set("mute_apps", "maybe"))
        assertEquals("there is no such setting", s.set("api_key", "sk-123"))
        assertEquals("there is no such setting", s.set("container_network", "on"))
    }
}
