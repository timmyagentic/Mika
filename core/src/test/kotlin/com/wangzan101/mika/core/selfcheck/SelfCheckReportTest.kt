package com.wangzan101.mika.core.selfcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckReportTest {
    private val facts = DeviceFacts(
        "Acme", "Acme Phone 7", "14", 34, listOf("arm64-v8a", "armeabi-v7a"), 12L * 1024 * 1024 * 1024, 1224, 2700,
        wirelessDebugging = true, appVersion = "0.1.21", appBuild = "abc1234",
    )

    private fun done(vararg rs: CheckResult) = SelfCheckState(
        SelfCheckState.Phase.Done,
        SelfCheck.entries.map { c -> rs.firstOrNull { it.check == c } ?: CheckResult(c, CheckStatus.Pass, "ok") },
        facts, elapsedMs = 62_400,
    )

    @Test
    fun headerAndOneLinePerCheck() {
        val text = SelfCheckReport.render(done(
            CheckResult(SelfCheck.PERMISSIONS, CheckStatus.Partial, "accessibility on (bound), notifications blocked", Advice.AllowNotifications),
            CheckResult(SelfCheck.STAYS_ALIVE, CheckStatus.Fail, "frames stopped after 6 s", Advice.FramesStopped),
        ))
        val lines = text.lines()
        assertEquals("Mika self-check 0.1.21 (build abc1234)", lines[0])
        assertEquals("Device: Acme Phone 7, Android 14 (SDK 34), arm64-v8a, RAM 12.0 GB, 1224x2700", lines[1])
        assertEquals("Wireless debugging: present", lines[2])
        assertEquals("Result: 10 pass, 1 partial, 1 fail, 0 skipped in 62 s", lines[3])
        assertEquals("", lines[4])
        assertTrue(text.contains("2 permissions: PARTIAL — accessibility on (bound), notifications blocked\n"))
        assertTrue(text.contains("8 stays alive: FAIL — frames stopped after 6 s\n"))
        assertTrue(text.endsWith("12 app copies: PASS — ok\n"))
        assertEquals(5 + SelfCheck.entries.size, lines.size - 1)
    }

    @Test
    fun manufacturerIsAddedWhenTheModelLacksIt() {
        val text = SelfCheckReport.render(done().copy(facts = facts.copy(model = "Phone 7", wirelessDebugging = false)))
        assertTrue(text.contains("Device: Acme Phone 7,"))
        assertTrue(text.contains("Wireless debugging: missing"))
    }

    @Test
    fun environmentWithoutPhoneControlIsWrittenInTheHeader() {
        val f = facts.copy(wirelessDebugging = false, developerOptionsPage = false, phoneControlUnavailable = true)
        val text = SelfCheckReport.render(done().copy(facts = f))
        assertTrue(text.contains("Developer options page: missing"))
        assertTrue(text.contains("Phone control: unavailable in this environment"))
        assertFalse(SelfCheckReport.render(done()).contains("Phone control:"))
    }

    @Test
    fun cancelledRunSaysSo() {
        val s = done().copy(phase = SelfCheckState.Phase.Cancelled, elapsedMs = 9_000)
        assertTrue(SelfCheckReport.render(s).contains("Result: cancelled after 9 s;"))
    }

    @Test
    fun detailsAreFlattenedAndCut() {
        val long = "line one\nline two " + "x".repeat(400)
        val line = SelfCheckReport.line(CheckResult(SelfCheck.BROWSER, CheckStatus.Fail, long, Advice.WebFailed))
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("10 browser: FAIL — line one line two x"))
        assertTrue(line.length < 260)
    }

    /** 报告只有固定的几行抬头和每项一行:没有地方放账号、App 列表、用户内容或序列号。 */
    @Test
    fun onlyKnownLinesAppear() {
        val text = SelfCheckReport.render(done())
        val allowed = listOf("Mika self-check ", "Device: ", "Wireless debugging: ", "Developer options page: ", "Phone control: ", "Result: ") +
            SelfCheck.entries.map { "${it.number} ${it.key}: " }
        for (l in text.lines().filter { it.isNotEmpty() }) assertTrue(l, allowed.any { l.startsWith(it) })
    }

    @Test
    fun theWholeTextGoesThroughSanitize() {
        val s = done(CheckResult(SelfCheck.HELPER, CheckStatus.Pass, "connected to 192.168.1.20:37000"))
        val text = SelfCheckReport.render(s) { it.replace(Regex("""\d+\.\d+\.\d+\.\d+(:\d+)?"""), "<ip>") }
        assertTrue(text.contains("3 helper: PASS — connected to <ip>"))
        assertFalse(text.contains("192.168"))
    }

    @Test
    fun issueLinkPrefillsOnlyTheTitle() {
        assertEquals("[兼容性] Android 14 Acme Phone 7", SelfCheckReport.issueTitle(facts))
        val url = SelfCheckReport.issueUrl("https://github.com/wangzan101/Mika/issues/new", facts)
        assertEquals(
            "https://github.com/wangzan101/Mika/issues/new?template=device-report.yml&title=%5B%E5%85%BC%E5%AE%B9%E6%80%A7%5D%20Android%2014%20Acme%20Phone%207",
            url,
        )
        assertFalse(url.contains("body="))
    }
}
