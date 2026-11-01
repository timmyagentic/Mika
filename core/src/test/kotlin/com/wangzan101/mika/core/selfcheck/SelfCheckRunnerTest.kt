package com.wangzan101.mika.core.selfcheck

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckRunnerTest {
    private val facts = DeviceFacts(
        "Acme", "Phone 7", "14", 34, listOf("arm64-v8a"), 8L * 1024 * 1024 * 1024, 1080, 2400,
        wirelessDebugging = true, appVersion = "0.1.21", appBuild = "abc1234",
    )

    /** 假端口:按表给结论,记下调用顺序;表里没写的算通过。 */
    private class FakePort(
        val facts: DeviceFacts,
        val outcomes: Map<SelfCheck, suspend () -> CheckOutcome> = emptyMap(),
    ) : SelfCheckPort {
        val calls = mutableListOf<SelfCheck>()
        var released = 0
        override suspend fun facts(): DeviceFacts = facts.also { calls += SelfCheck.DEVICE }
        override suspend fun check(check: SelfCheck): CheckOutcome {
            calls += check
            return outcomes[check]?.invoke() ?: CheckOutcome.pass("ok")
        }
        override suspend fun release() { released++ }
    }

    @Test
    fun runsEveryCheckInOrderAndReleasesOnce() = runTest {
        val port = FakePort(facts)
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        assertEquals(SelfCheck.entries, port.calls)
        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertTrue(s.results.all { it.status == CheckStatus.Pass })
        assertEquals(1, port.released)
        assertEquals(facts, s.facts)
    }

    @Test
    fun aFailedCheckSkipsTheOnesThatNeedIt() = runTest {
        val port = FakePort(facts, mapOf(SelfCheck.HELPER to { CheckOutcome.fail("helper not running", Advice.SetUpPhoneControl) }))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        val needHelper = setOf(SelfCheck.VIRTUAL_DISPLAY, SelfCheck.APP_LAUNCH, SelfCheck.SCREEN_READING, SelfCheck.ACTIONS,
            SelfCheck.STAYS_ALIVE, SelfCheck.MAIN_SCREEN, SelfCheck.APP_COPIES)
        for (c in needHelper) {
            assertEquals(c.name, CheckStatus.Skipped, s.of(c).status)
            assertFalse(c.name, c in port.calls)
        }
        // 直接缺的那一项写在跳过的原因里
        assertEquals(SelfCheck.HELPER, s.of(SelfCheck.VIRTUAL_DISPLAY).blockedBy)
        assertEquals("needs helper (fail)", s.of(SelfCheck.VIRTUAL_DISPLAY).detail)
        assertEquals(SelfCheck.VIRTUAL_DISPLAY, s.of(SelfCheck.APP_LAUNCH).blockedBy)
        // 不靠助手的照跑
        for (c in listOf(SelfCheck.PERMISSIONS, SelfCheck.BROWSER, SelfCheck.SANDBOX)) assertEquals(c.name, CheckStatus.Pass, s.of(c).status)
    }

    @Test
    fun partialDoesNotSkipDependents() = runTest {
        val port = FakePort(facts, mapOf(SelfCheck.PERMISSIONS to { CheckOutcome.partial("notifications blocked", Advice.AllowNotifications) }))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        assertEquals(CheckStatus.Partial, s.of(SelfCheck.PERMISSIONS).status)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.APP_LAUNCH).status)
    }

    @Test
    fun oldAndroidFailsDeviceAndSkipsPhoneChecksButNotBrowserOrSandbox() = runTest {
        val port = FakePort(facts.copy(androidRelease = "11", sdk = 30))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        assertEquals(CheckStatus.Fail, s.of(SelfCheck.DEVICE).status)
        assertEquals(Advice.PhoneTooOld, s.of(SelfCheck.DEVICE).advice)
        assertEquals(CheckStatus.Skipped, s.of(SelfCheck.HELPER).status)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.BROWSER).status)
    }

    @Test
    fun missingWirelessDebuggingIsPartial() {
        val o = SelfCheckRunner.judgeDevice(facts.copy(wirelessDebugging = false))
        assertEquals(CheckStatus.Partial, o.status)
        assertEquals(Advice.NoWirelessDebugging, o.advice)
    }

    @Test
    fun aSlowCheckTimesOutAndTheRestStillRun() = runTest {
        val port = FakePort(facts, mapOf(SelfCheck.BROWSER to { awaitCancellation() }))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        val b = s.of(SelfCheck.BROWSER)
        assertEquals(CheckStatus.Fail, b.status)
        assertEquals(Advice.TimedOut, b.advice)
        assertEquals("timed out after 15 s", b.detail)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.SANDBOX).status)
    }

    @Test
    fun aTimeoutInsideThePortIsAFailureNotACancel() = runTest {
        val port = FakePort(facts, mapOf(SelfCheck.SANDBOX to { withTimeout(10) { awaitCancellation() } }))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertEquals(CheckStatus.Fail, s.of(SelfCheck.SANDBOX).status)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.APP_COPIES).status)
    }

    @Test
    fun anExceptionIsAFailureWithTheClassNameOnly() = runTest {
        val port = FakePort(facts, mapOf(SelfCheck.MAIN_SCREEN to { throw IllegalStateException("secret 10.0.0.1") }))
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }).run()
        assertEquals("error: IllegalStateException", s.of(SelfCheck.MAIN_SCREEN).detail)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.BROWSER).status)
    }

    @Test
    fun theTotalBudgetSkipsWhatIsLeft() = runTest {
        // 每项用掉 4 秒,总时限 18 秒:前四项跑完,第五项只剩 2 秒、超时,之后的都因时间用完跳过
        val port = object : SelfCheckPort {
            override suspend fun facts(): DeviceFacts { delay(4_000); return facts }
            override suspend fun check(check: SelfCheck): CheckOutcome { delay(4_000); return CheckOutcome.pass("ok") }
            override suspend fun release() {}
        }
        val s = SelfCheckRunner(port, now = { testScheduler.currentTime }, budgetMs = 18_000).run()
        for (c in listOf(SelfCheck.DEVICE, SelfCheck.PERMISSIONS, SelfCheck.HELPER, SelfCheck.VIRTUAL_DISPLAY)) assertEquals(c.name, CheckStatus.Pass, s.of(c).status)
        assertEquals("timed out after 2 s", s.of(SelfCheck.APP_LAUNCH).detail)
        assertEquals(SelfCheck.APP_LAUNCH, s.of(SelfCheck.SCREEN_READING).blockedBy)
        for (c in listOf(SelfCheck.MAIN_SCREEN, SelfCheck.BROWSER, SelfCheck.SANDBOX, SelfCheck.APP_COPIES)) {
            assertEquals(c.name, CheckStatus.Skipped, s.of(c).status)
            assertEquals(c.name, Advice.OutOfTime, s.of(c).advice)
        }
    }

    @Test
    fun cancelStopsAtOnceMarksTheRestAndReleases() = runTest {
        val entered = CompletableDeferred<Unit>()
        val port = FakePort(facts, mapOf(SelfCheck.STAYS_ALIVE to { entered.complete(Unit); awaitCancellation() }))
        val runner = SelfCheckRunner(port, now = { testScheduler.currentTime })
        val job = launch { runner.run() }
        entered.await()
        assertEquals(CheckStatus.Running, runner.state.value.of(SelfCheck.STAYS_ALIVE).status)
        job.cancel()
        job.join()
        val s = runner.state.value
        assertEquals(SelfCheckState.Phase.Cancelled, s.phase)
        assertEquals(1, port.released)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.ACTIONS).status)
        for (c in listOf(SelfCheck.STAYS_ALIVE, SelfCheck.MAIN_SCREEN, SelfCheck.APP_COPIES)) {
            assertEquals(c.name, CheckStatus.Skipped, s.of(c).status)
            assertEquals(c.name, Advice.Cancelled, s.of(c).advice)
        }
        assertFalse(SelfCheck.MAIN_SCREEN in port.calls)
    }

    @Test
    fun stateShowsPendingBeforeRunning() {
        val s = SelfCheckState()
        assertEquals(SelfCheckState.Phase.Idle, s.phase)
        assertTrue(s.results.all { it.status == CheckStatus.Pending })
        assertNull(s.facts)
    }

    @Test
    fun environmentWithoutAWayToStartTheHelperFailsDeviceAndSkipsPhoneChecks() = runTest {
        val f = facts.copy(wirelessDebugging = false, developerOptionsPage = false, phoneControlUnavailable = true)
        val o = SelfCheckRunner.judgeDevice(f)
        assertEquals(CheckStatus.Fail, o.status)
        assertEquals(Advice.PhoneControlUnavailable, o.advice)
        assertTrue(o.detail, o.detail.contains("developer options page missing"))
        val s = SelfCheckRunner(FakePort(f), now = { testScheduler.currentTime }).run()
        assertEquals(CheckStatus.Skipped, s.of(SelfCheck.HELPER).status)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.BROWSER).status)
        assertEquals(CheckStatus.Pass, s.of(SelfCheck.SANDBOX).status)
    }
}
