package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.testTasks
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed app's settings, TaskRun, recording and timeline with a local Responses endpoint. */
@RunWith(AndroidJUnit4::class)
class RunTraceDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordsAndViewsARequestFromTheSettingsSwitch() {
        val app = compose.activity.application as MikaApp
        val previous = app.detailedTraces.enabled
        val received = LinkedBlockingQueue<ByteArray>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/traces") {
                        received.add(request.body.readByteArray())
                        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-protobuf")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}],"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":512},"output_tokens":2}}""")
                }
            }
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed(null), "trace-device",
                Capabilities(true, false, 128_000, false), OkHttpClient())
            try {
                app.detailedTraces.setEnabled(false)
                testTasks.modelFactory = { _, _ -> model }
                testTasks.lightModelFactory = { _, _ -> null }
                testTasks.visionModelFactory = { _, _ -> null }
                // A fresh install starts on the optional setup page above the chat.
                compose.waitForIdle()
                if (compose.onAllNodesWithContentDescription("菜单").fetchSemanticsNodes().isEmpty()) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                }
                compose.onNodeWithContentDescription("菜单").performClick()
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("记录详细 Trace").performScrollTo().performClick()
                assertTrue(app.detailedTraces.enabled)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.runOnUiThread {
                    app.chat.newConversation()
                    assertTrue(app.chat.send("请回复完成，TRACE_FIXTURE", Prerequisites.Check(emptyList())))
                }
                compose.waitUntil(30_000) { compose.onAllNodesWithText("查看过程").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("查看过程").performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(15_000) { compose.onAllNodesWithText("运行过程").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("详细 Trace"))
                compose.onNodeWithText("详细 Trace").performScrollTo().performClick()
                compose.onNodeWithText("输入 1200 · 缓存读取 512 · 未命中 688", substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("保存本次 Trace").assertExists()
                compose.onNodeWithText("通过 OTLP/HTTP 发送").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.onNode(hasSetTextAction()).performTextReplacement(server.url("/v1/traces").toString())
                compose.onNode(hasText("通过 OTLP/HTTP 发送") and hasClickAction() and hasAnyAncestor(isDialog()))
                    .performSemanticsAction(SemanticsActions.OnClick)
                val run = com.wangzan101.mika.record.RunStore(app).list().first { "TRACE_FIXTURE" in it.goal }
                val bytes = ByteArrayOutputStream().also { app.detailedTraces.store.archive(run.id)!!.save(it, false) }.toByteArray()
                assertArrayEquals(bytes, received.poll(10, TimeUnit.SECONDS))
                compose.waitUntil(10_000) { compose.onAllNodesWithText("接收端已接受").fetchSemanticsNodes().isNotEmpty() }
            } finally {
                app.detailedTraces.setEnabled(previous)
                testTasks.modelFactory = TaskEnvironment.defaultModelFactory
                testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
                testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            }
        }
    }
}
