package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.AppsAfterTask.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppsAfterTaskTest {
    private val me = 0

    private fun task(id: Int, key: String, playing: Boolean? = false, standard: Boolean = true, own: Boolean = false) =
        AppsAfterTask.Task(id, listOf(key), standard, own, playing)

    private fun fates(vararg t: AppsAfterTask.Task, keep: Set<String> = emptySet(), close: Set<String> = emptySet()) =
        AppsAfterTask.decide(t.toList(), keep, close).associate { it.task.id to (it.keep to it.why) }

    @Test fun keepsQuietAppsAndClosesPlayingOnesByDefault() {
        val f = fates(task(1, "a.quiet"), task(2, "a.music", playing = true))
        assertEquals(true to Why.Default, f[1])
        assertEquals(false to Why.Playing, f[2])
    }

    @Test fun unknownPlaybackKeepsUnlessMikaIsMutingIt() {
        assertEquals(true to Why.Default, fates(task(1, "a.app", playing = null))[1])
        val muted = AppsAfterTask.Task(2, listOf("a.music"), standard = true, own = false, playing = null, muted = true)
        assertEquals(false to Why.PlaybackUnknown, fates(muted)[2])
        assertEquals(true to Why.Marked, fates(muted, keep = setOf("a.music"))[2])
        // 读得到、没在出声的,静着也留下
        assertEquals(true to Why.Default, fates(muted.copy(playing = false))[2])
    }

    @Test fun marksOverrideTheDefault() {
        val f = fates(task(1, "a.music", playing = true), task(2, "a.login"), keep = setOf("a.music"), close = setOf("a.login"))
        assertEquals(true to Why.Marked, f[1])
        assertEquals(false to Why.Marked, f[2])
    }

    @Test fun ownPagesAndNonAppPagesAlwaysClose() {
        val f = fates(task(1, "mika", own = true), task(2, "a.home", standard = false), keep = setOf("mika", "a.home"))
        assertEquals(false to Why.Own, f[1])
        assertEquals(false to Why.NotApp, f[2])
    }

    @Test fun otherCopiesAreMarkedPerUser() {
        val f = fates(task(1, "a.chat"), task(2, "a.chat@10"), close = setOf("a.chat@10"))
        assertEquals(true to Why.Default, f[1])
        assertEquals(false to Why.Marked, f[2])
    }

    @Test fun keysUseTheMuteLedgerSpelling() {
        assertEquals("a.chat", AppsAfterTask.keyOf("a.chat", me, me))
        assertEquals("a.chat@10", AppsAfterTask.keyOf("a.chat", 10, me))
        assertEquals(setOf("a.chat", "a.chat@10"), AppsAfterTask.normalize(arrayOf("a.chat@0", "a.chat@10", "bad key!"), me))
        assertEquals(emptySet<String>(), AppsAfterTask.normalize(null, me))
    }

    @Test fun recheckClosesAppsThatStartedPlayingUnlessMarkedKeep() {
        val fate = AppsAfterTask.decide(listOf(task(1, "a.app")), emptySet(), emptySet()).single()
        assertEquals(false to Why.Playing, AppsAfterTask.recheck(fate, true).let { it.keep to it.why })
        assertEquals(fate, AppsAfterTask.recheck(fate, false))
        assertEquals(fate, AppsAfterTask.recheck(fate, null))
        val marked = AppsAfterTask.decide(listOf(task(1, "a.app")), setOf("a.app"), emptySet()).single()
        assertEquals(marked, AppsAfterTask.recheck(marked, true))
    }

    @Test fun readsStartedPlayersFromAudioDump() {
        val dump = """
            PlaybackActivityMonitor dump time: 10:00:00.000
              ID:15 -- type:android.media.AudioTrack -- u/pid:10123/4567 -- state:started
              players:
              AudioPlaybackConfiguration piid:15 deviceId:2 type:android.media.AudioTrack u/pid:10123/4567 state:started attr:AudioAttributes: usage=USAGE_MEDIA sessionId:33 mutedState:none
              AudioPlaybackConfiguration piid:23 deviceIds:[2] type:android.media.MediaPlayer u/pid:1010200/890 state:paused attr:AudioAttributes: usage=USAGE_MEDIA sessionId:41
              AudioPlaybackConfiguration piid:31 deviceId:0 type:android.media.SoundPool u/pid:1010201/891 state:started attr:AudioAttributes: usage=USAGE_GAME
        """.trimIndent()
        assertEquals(setOf(10123, 1010201), AppsAfterTask.playingUids(dump))
        assertEquals(emptySet<Int>(), AppsAfterTask.playingUids("PlaybackActivityMonitor dump time: x\n  players:\n"))
        assertNull(AppsAfterTask.playingUids("Can't find service: audio"))
    }

    @Test fun diagnosticsLineCountsWithoutNamingApps() {
        assertEquals("none", AppsAfterTask.diagnosticsLine(0, emptyList(), null))
        assertEquals("kept 1, closed 2 (marked 1, playing 1)", AppsAfterTask.diagnosticsLine(1, listOf("a.music:playing", "a.login:marked"), null))
        assertEquals("closed 2 (move not supported 2); move not supported",
            AppsAfterTask.diagnosticsLine(0, listOf("a:move_not_supported", "b@10:move_not_supported"), AppsAfterTask.NOTE_MOVE_UNSUPPORTED))
    }
}
