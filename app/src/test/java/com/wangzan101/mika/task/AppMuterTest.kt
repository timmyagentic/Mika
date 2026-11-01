package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静音规则:启动前静、中途冒出的新 App 静一次、另一份单独静、收尾全还原、没静成照实记下。 */
class AppMuterTest {
    /** 记下每一次写 PLAY_AUDIO 的假外壳;键是 [AppAudioKey]。 */
    private class FakeAudio : AudioPort {
        val modes = mutableMapOf<String, String>()
        val mutes = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        override fun mode(packageName: String) = if (packageName in failing) null else modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean {
            if (packageName in failing) return false
            mutes += packageName
            modes[packageName] = "ignore"
            return true
        }
        override fun setMode(packageName: String, mode: String): Boolean {
            if (packageName in failing) return false
            modes[packageName] = mode
            return true
        }
    }

    private val audio = FakeAudio()
    private val store = MemoryMarker()
    private val record = AudioMute(audio, store)
    private var enabled = true
    private val onMain = mutableSetOf<String>()
    private val failed = mutableListOf<String>()
    private val muter = AppMuter(
        record,
        enabled = { enabled },
        mutable = { it != "mika.self" && it != "system.ui" },
        onMainScreen = { pkg, user -> AppAudioKey.of(pkg, user) in onMain },
        failed = { failed += it },
    )

    @Test fun mutesBeforeLaunchAndDoesNotMuteAgainOnceOpened() {
        muter.beforeOpen("video.app", null)
        // 启动还没发出,已经静上了
        assertEquals(listOf("video.app"), audio.mutes)
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), audio.mutes)
        assertTrue(failed.isEmpty())
    }

    @Test fun refusedLaunchRestoresTheEarlyMute() {
        audio.modes["video.app"] = "allow"
        muter.beforeOpen("video.app", null)
        muter.notOpened("video.app", null)
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
        // 已经开出来过的,启动被拒不还原(它还在屏上)
        muter.beforeOpen("music.app", null)
        muter.opened("music.app", null)
        muter.notOpened("music.app", null)
        assertEquals("ignore", audio.modes["music.app"])
    }

    @Test fun earlyMuteFailureIsReportedOnlyWhenTheAppOpens() {
        audio.failing += "video.app"
        muter.beforeOpen("video.app", null)
        assertTrue(failed.isEmpty())
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), failed)
    }

    @Test fun newPackageMidTaskIsMutedOnce() {
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        // 从目标 App 里点开的另一个 App:第一次看到就静,之后再看到不重复
        muter.opened("player.app", null)
        muter.opened("player.app", null)
        muter.opened("feed.app", null)
        assertEquals(listOf("feed.app", "player.app"), audio.mutes)
    }

    @Test fun copyInAnotherUserIsMutedSeparately() {
        muter.beforeOpen("chat.app", 10)
        muter.opened("chat.app", 10)
        assertEquals(listOf("chat.app@10"), audio.mutes)
        assertFalse("chat.app" in audio.modes)
        // 本来那份冒出来时另静一次
        muter.opened("chat.app", null)
        assertEquals(listOf("chat.app@10", "chat.app"), audio.mutes)
    }

    @Test fun restoreAllAtEndCoversEarlyAndMidTaskMutes() {
        audio.modes["feed.app"] = "allow"
        audio.modes["chat.app@10"] = "deny"
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        muter.opened("player.app", null)
        muter.opened("chat.app", 10)
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["feed.app"])
        assertEquals("default", audio.modes["player.app"])
        assertEquals("deny", audio.modes["chat.app@10"])
        assertNull(store.saved)
    }

    @Test fun neverTouchesOwnOrSystemOrMainScreenApps() {
        muter.opened("mika.self", null)
        muter.opened("system.ui", null)
        onMain += "maps.app"
        muter.beforeOpen("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        // 用户在主屏上看得见的:不静,照实记下可能出声
        muter.opened("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertEquals(listOf("maps.app"), failed)
    }

    @Test fun switchedOffMeansNothingIsMuted() {
        enabled = false
        muter.beforeOpen("video.app", null)
        muter.opened("video.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertTrue(failed.isEmpty())
    }

    @Test fun midTaskFailureIsNoted() {
        audio.failing += "player.app"
        muter.opened("player.app", null)
        assertEquals(listOf("player.app"), failed)
    }

    @Test fun releaseForTakeoverThenMuteAgain() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.release("video.app", null))
        assertEquals("allow", audio.modes["video.app"])
        muter.opened("video.app", null)
        assertEquals("ignore", audio.modes["video.app"])
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
    }

    @Test fun audioKeyRoundTrips() {
        assertEquals("a.app", AppAudioKey.of("a.app", null))
        assertEquals("a.app@999", AppAudioKey.of("a.app", 999))
        assertEquals("a.app" to null, AppAudioKey.parse("a.app"))
        assertEquals("a.app" to 999, AppAudioKey.parse("a.app@999"))
        assertNull(AppAudioKey.parse("a app; rm"))
    }
}
