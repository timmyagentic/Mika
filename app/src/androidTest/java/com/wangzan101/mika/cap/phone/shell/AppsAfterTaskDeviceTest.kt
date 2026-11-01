package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 任务结束时 Mika 屏上的 App 怎么办(IMikaShell.finishDisplay):没出声的留下,挪到主屏最后面、不到用户眼前;
 * 下次经启动入口打开时同一个任务挪回 Mika 的屏;正在出声的、标了 close 的随屏关掉;标了 keep 的出声也留下。
 * 需要外壳已连上。用户的主屏不该有任何变化(焦点、焦点窗口都不变,留下的任务看不见)。
 */
@RunWith(AndroidJUnit4::class)
class AppsAfterTaskDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = DeviceSupport.shell()
    private val screens = mutableListOf<ParityScreen>()
    private lateinit var probe: ShellProbe

    @Before fun setUp() { probe = ShellProbe(context) }

    @After fun tearDown() {
        screens.forEach { runCatching { it.close() } }
        // 留在主屏后面的探针任务:挪回一块新屏再随屏关掉(就是下次打开的那条路)
        runCatching { ParityScreen(shell, 720, 1600, 320).use { s -> shell.startApp(s.id, TEST_PACKAGE); Thread.sleep(500) } }
        runCatching { probe.close() }
    }

    private fun newScreen() = ParityScreen(shell, 720, 1600, 320).also { screens += it }

    /** 主屏此刻的焦点(最上层焦点屏、焦点窗口、焦点 Activity),不含「最上面的任务」:有的系统按最近使用排任务。 */
    private fun mainFocus(): String = DeviceSupport.mainScreenState(shell).substringBefore(" topTask=")

    private fun Bundle.kept() = getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty()
    private fun Bundle.closed() = getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty()

    private fun probeOn(displayId: Int, component: String): Int {
        sh("am start --display $displayId -f 0x18000000 -n $component")
        return (probe.await { it.kind == ShellProbe.RESUMED && it.display == displayId }
            ?: error("probe did not come up on display $displayId: ${probe.all()}")).task
    }

    @Test fun quietAppStaysBehindOnMainAndReopenBringsTheSameTaskBack() {
        val s = newScreen()
        assertEquals(ShellService.START_OK, shell.startApp(s.id, TEST_PACKAGE))
        val task = probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id }?.task ?: error("probe did not come up: ${probe.all()}")
        val before = mainFocus()

        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())
        assertNotNull(result)
        val note = result!!.getString(AppsAfterTask.KEY_NOTE)
        if (note?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) {
            // 这台系统挪不了:照旧随屏关掉
            assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.MoveUnsupported.wire}"), result.closed())
            assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
            return
        }
        assertEquals("note=$note closed=${result.closed()}", listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        // 不到用户眼前:看不见、焦点没变
        assertFalse("kept task is visible on the main screen", shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
        assertEquals("main screen focus changed", before, mainFocus())
        // 在最近任务里:系统的任务列表里还有它
        // 最近任务的记录里每个任务写成 Task{… #<编号> …}
        assertTrue("kept task is gone from recents", Regex("#$task\\b").containsMatchIn(sh("dumpsys activity recents")))

        // 下次打开:同一个任务挪回 Mika 的新屏
        val s2 = newScreen()
        probe.clear()
        assertEquals(ShellService.START_OK, shell.startApp(s2.id, TEST_PACKAGE))
        assertEquals(s2.id, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == s2.id } })
        assertNotNull("probe did not come back on ${s2.id}: ${probe.all()}",
            probe.await { it.kind == ShellProbe.RESUMED && it.display == s2.id && it.task == task })
        assertEquals("main screen focus changed", before, mainFocus())
    }

    @Test fun playingAppIsClosedByDefault() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        // 播放器报到音频服务要一会儿
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())!!
        assertEquals("kept=${result.kept()}", listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Playing.wire}"), result.closed())
        assertNotNull("playing task outlived the display", pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun keepMarkKeepsAPlayingApp() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, arrayOf(TEST_PACKAGE), emptyArray())!!
        if (result.getString(AppsAfterTask.KEY_NOTE)?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) return
        assertEquals(listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        assertFalse(shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
    }

    @Test fun closeMarkClosesAQuietApp() {
        val s = newScreen()
        val task = probeOn(s.id, ShellProbe.COMPONENT)
        val result = shell.finishDisplay(s.id, emptyArray(), arrayOf(TEST_PACKAGE))!!
        assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Marked.wire}"), result.closed())
        assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun refusesDisplaysItDidNotCreate() {
        assertEquals(null, shell.finishDisplay(Display.DEFAULT_DISPLAY, emptyArray(), emptyArray()))
    }

    private companion object {
        const val AUDIO_PROBE = "com.wangzan101.mika.test/com.wangzan101.mika.shell.AudioProbeActivity"
        const val PLAYER_SETTLE_MS = 1_000L
    }
}
