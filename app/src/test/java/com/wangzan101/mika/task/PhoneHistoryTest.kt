package com.wangzan101.mika.task

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.trace.TraceStore
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun runs() = RunStore(tmp.root, { null }, 30, { 1_780_000_000_000L }, { it })
    private fun conversations() = ConversationStore(tmp.root.resolve("conversations"))
    private fun traces() = TraceStore(tmp.root.resolve("traces"))
    private fun history() = PhoneHistory(conversations(), runs(), traces(), { true })

    @Test fun recordedModelAttemptAndPagedBodyReachHistoryWithoutInventingUsage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Recorded answer"}]}],"usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":0},"output_tokens":2}}"""))
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("private-test-header"),
                "test-model", Capabilities(true, false, 128000, false), OkHttpClient())
            val record = runs().open("Read the saved source")
            val store = traces()
            val trace = store.start(record.runId)!!
            try {
                val screen = LazyPhoneScreen(100, 100, { emptyList() }) { error("This read-only fixture must not open a phone app") }
                val result = AgentLoop(model, toolDispatcher(screen), "Base", maxSteps = 3,
                    reviewFinalAnswer = false, trace = trace, sink = record).run("Read the saved source")
                assertTrue(result is TaskState.Succeeded)
            } finally { store.finish(trace); record.close() }
            conversations().append("phone", Turn("Read the saved source", "Recorded answer", "unverified", runId = record.runId))
            val reader = PhoneHistory(conversations(), runs(), store, { true })
            val row = reader.run("phone", record.runId, 0, 20)!!.getValue("rows").jsonArray.first().jsonObject
            assertEquals(1000, row.getValue("input").jsonPrimitive.int)
            assertEquals(0, row.getValue("cached").jsonPrimitive.int)
            val span = row.getValue("id").jsonPrimitive.content
            val text = StringBuilder()
            var offset = 0
            do {
                val page = reader.body("phone", record.runId, span, offset, 3000)!!
                text.append(page.getValue("text").jsonPrimitive.content)
                offset = page.getValue("next_offset").jsonPrimitive.intOrNull ?: -1
            } while (offset >= 0)
            assertTrue(text.contains("Read the saved source"))
            assertTrue(text.contains("Recorded answer"))
            assertFalse(text.contains("private-test-header"))
        }
    }

    @Test fun readsPhoneOnlyConversationsAfterReopeningWithOlderTurnsPaged() {
        val store = conversations()
        repeat(24) { store.append("phone-conversation", Turn("question $it", "answer $it", "unverified")) }
        store.setTitle("phone-conversation", "A phone conversation")
        val reader = history()
        assertEquals("phone-conversation", reader.conversations(0, 20).getValue("conversations").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val recent = reader.turns("phone-conversation", 0, 20)!!
        assertEquals("question 4", recent.getValue("turns").jsonArray.first().jsonObject.getValue("goal").jsonPrimitive.content)
        val older = reader.turns("phone-conversation", 20, 20)!!
        assertEquals(4, older.getValue("turns").jsonArray.size)
        assertEquals(JsonNull, older["next_offset"])
        val first = older.getValue("turns").jsonArray.first().jsonObject
        assertTrue(first.getValue("read_only").jsonPrimitive.boolean)
        assertNull(first["verified"])
        assertNull(reader.turns("missing", 0, 20))
    }

    @Test fun runMustBelongToTheRequestedConversationAndSurvivesRemoteIndexLoss() {
        val run = runs().open("a task")
        run.event(RunEvent.TaskStarted(1, "a task", "model"))
        run.event(RunEvent.ModelReply(2, 1, "done", null, null, 100, 2, null))
        run.close()
        conversations().append("phone", Turn("a task", "done", "unverified", runId = run.runId))
        conversations().append("other", Turn("different", "done", "unverified"))
        val reader = history()
        val data = reader.run("phone", run.runId, 0, 1)!!
        assertTrue(data.getValue("record_available").jsonPrimitive.boolean)
        assertFalse(data.getValue("trace_available").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("next_offset").jsonPrimitive.int)
        assertEquals("task_started", data.getValue("events").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertNull(reader.run("other", run.runId, 0, 20))
        assertNull(reader.body("other", run.runId, "0123456789abcdef", 0, 3000))
        conversations().delete("phone")
        assertNull(reader.run("phone", run.runId, 0, 20))
    }

    @Test fun livePhoneRunIsVisibleBeforeItsFirstTurnIsSavedAndTraceFinishes() {
        val traceStore = traces()
        val trace = traceStore.start("live-run")!!
        val task = RemoteTask("live", "phone", "first turn", 0, TaskState.Running(1, "reading"), runId = "live-run")
        val reader = PhoneHistory(conversations(), runs(), traceStore, { true }, { listOf(task) })
        assertEquals(1, reader.conversations(0, 20).getValue("conversations").jsonArray.size)
        val turns = reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray
        assertEquals("live-run", turns.single().jsonObject.getValue("run_id").jsonPrimitive.content)
        assertTrue(reader.run("phone", "live-run", 0, 20)!!.getValue("recording").jsonPrimitive.boolean)
        traceStore.finish(trace)
        assertFalse(reader.run("phone", "live-run", 0, 20)!!.getValue("recording").jsonPrimitive.boolean)
        conversations().append("phone", Turn("first turn", "done", "unverified", runId = "live-run"))
        assertEquals(1, reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray.size)
    }

    @Test fun missingArchivesAndLongTextAreReportedExplicitly() {
        conversations().append("phone", Turn("long".repeat(3000), "done", "unverified", runId = "old-run"))
        val reader = history()
        val turn = reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray.single().jsonObject
        assertTrue(turn.getValue("text_truncated").jsonPrimitive.boolean)
        assertEquals(8000, turn.getValue("goal").jsonPrimitive.content.length)
        val record = reader.run("phone", "old-run", 0, 20)!!
        assertFalse(record.getValue("record_available").jsonPrimitive.boolean)
        assertFalse(record.getValue("trace_available").jsonPrimitive.boolean)
    }
}
