package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ToolSpec
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
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed app's settings, TaskRun, recording and timeline with a local Responses endpoint. */
@RunWith(AndroidJUnit4::class)
class RunTraceDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun releaseIgnoresAPreviouslyEnabledRecordingPreference() {
        org.junit.Assume.assumeFalse(com.wangzan101.mika.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MikaApp
        val prefs = app.getSharedPreferences("trace_settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("enabled", false)
        try {
            prefs.edit().putBoolean("enabled", true).apply()
            assertTrue(!app.detailedTraces.enabled && app.detailedTraces.ticket() == null)
            assertTrue(app.detailedTraces.start(null, "release-check") == null)
        } finally {
            prefs.edit().putBoolean("enabled", previous).apply()
        }
    }

    @Test fun recordsAndViewsARequestFromTheSettingsSwitch() {
        org.junit.Assume.assumeTrue(com.wangzan101.mika.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MikaApp
        val previous = app.detailedTraces.enabled
        val previousLimit = app.detailedTraces.limitMiB
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}],"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":512},"output_tokens":2}}""")
                }
            }
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed(null), "trace-device",
                Capabilities(true, false, 128_000, false), OkHttpClient())
            try {
                app.detailedTraces.setEnabled(false)
                // One local request exercises clipping without a long task or a large chat reply.
                val fixture = object : ChatModel by model {
                    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                        model.complete(system + " fixture".repeat(5_000), messages, tools)
                }
                testTasks.modelFactory = { _, _ -> fixture }
                testTasks.lightModelFactory = { _, _ -> null }
                testTasks.visionModelFactory = { _, _ -> null }
                // A fresh install starts on the optional setup page above the chat.
                compose.waitForIdle()
                if (compose.onAllNodesWithContentDescription("菜单").fetchSemanticsNodes().isEmpty()) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                }
                compose.onNodeWithContentDescription("菜单").performClick()
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("记录详细 Trace").assertDoesNotExist()
                compose.onNodeWithText("开发者选项").performScrollTo().performClick()
                compose.onNodeWithText("Trace 存储上限").performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("0")
                compose.onNode(hasText("保存") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
                compose.onNode(hasSetTextAction()).performTextReplacement("37")
                compose.clickInDialog("保存")
                compose.awaitOne("37 MiB").assertExists()
                assertTrue(com.wangzan101.mika.record.DetailedTraces(app).limitMiB == 37)
                compose.onNodeWithText("记录详细 Trace").performScrollTo().performClick()
                assertTrue(app.detailedTraces.enabled)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.awaitOne("设置").assertExists()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.runOnUiThread {
                    app.chat.newConversation()
                    assertTrue(app.chat.send("请回复完成，TRACE_FIXTURE", Prerequisites.Check(emptyList())))
                }
                compose.waitUntil(30_000) { compose.onAllNodesWithText("查看过程").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("查看过程").performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(15_000) { compose.onAllNodesWithText("运行过程").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("导出用量记录").assertExists()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("详细 Trace"))
                compose.onNodeWithText("部分记录").assertExists()
                compose.onNodeWithText("详细 Trace").performScrollTo().performClick()
                compose.onNodeWithText("输入 1200 · 缓存读取 512 · 未命中 688", substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("保存本次 Trace").assertExists()
                val run = com.wangzan101.mika.record.RunStore(app).list().first { "TRACE_FIXTURE" in it.goal }
                val bytes = ByteArrayOutputStream().also { app.detailedTraces.store.archive(run.id)!!.save(it, false) }.toByteArray()
                assertTrue(bytes.isNotEmpty() && "TRACE_FIXTURE" !in bytes.decodeToString())
            } finally {
                app.detailedTraces.setEnabled(previous)
                app.detailedTraces.setLimitMiB(previousLimit)
                testTasks.modelFactory = TaskEnvironment.defaultModelFactory
                testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
                testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            }
        }
    }
}
