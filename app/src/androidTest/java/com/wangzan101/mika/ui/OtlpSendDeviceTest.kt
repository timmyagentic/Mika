package com.wangzan101.mika.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OtlpSendDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedSendSurvivesCollapseAndClearsTheAuthField() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "test-otlp").toFile()
        val runs = RunStore(dir, { null }, keep = 1, clock = System::currentTimeMillis) { it }
        val runId = runs.open("OTLP test").use { it.runId }
        val store = (context.applicationContext as MikaApp).detailedTraces.store
        try {
            val trace = store.start(runId)!!
            trace.event(RunEvent.TaskEnded(0, "succeeded", ""))
            store.finish(trace)
            val archive = store.archive(runId)!!
            val bytes = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
            MockWebServer().use { receiver ->
                val vm = RunTimelineViewModel(runs, runId)
                compose.setContent { RunTimelineScreen(vm, onBack = {}) }
                fun toggle() = compose.awaitOne("详细 Trace").performScrollTo().performClick()
                toggle()
                fun confirm() = compose.onNode(hasText("通过 OTLP/HTTP 发送") and hasClickAction() and hasAnyAncestor(isDialog()))
                compose.onNodeWithText("通过 OTLP/HTTP 发送").performScrollTo().performClick()
                compose.onNodeWithTag("trace-endpoint").performTextReplacement("http://8.8.8.8/v1/traces")
                confirm().assertIsNotEnabled()
                compose.onNodeWithTag("trace-endpoint").performTextReplacement(receiver.url("/v1/traces").toString())
                compose.onNodeWithTag("trace-auth-value").performTextReplacement("Bearer test-only")
                assertEquals(0, receiver.requestCount)
                confirm().performClick()
                val request = receiver.takeRequest(12, TimeUnit.SECONDS)!!
                assertEquals("Bearer test-only", request.getHeader("Authorization"))
                assertArrayEquals(bytes, request.body.readByteArray())
                // Hold the acknowledgement until the controls have left and re-entered composition.
                toggle()
                toggle()
                compose.onNodeWithText("通过 OTLP/HTTP 发送").assertExists()
                compose.onNode(hasText("通过 OTLP/HTTP 发送") and hasClickAction()).assertDoesNotExist()
                receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf"))
                compose.awaitOne("接收端已接受").assertExists()
                assertEquals(1, receiver.requestCount)
                compose.onNodeWithText("通过 OTLP/HTTP 发送").performScrollTo().performClick()
                compose.onNodeWithTag("trace-auth-value").assert(hasText(""))
            }
        } finally {
            File(context.noBackupFilesDir, "harness-traces/$runId").deleteRecursively()
            dir.deleteRecursively()
        }
    }
}
