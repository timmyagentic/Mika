package com.wangzan101.mika.core.trace

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.ScreenPort
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class RunTraceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun realLoopPreservesWireRequestsAndRecordsRetriesToolsAndPortableUsage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("dummy-auth-for-test"),
                "trace-test", Capabilities(true, false, 4096, false), OkHttpClient())
            val store = TraceStore(temp.newFolder())
            val goal = "列出应用 PRIVATE_GOAL"
            suspend fun replay(trace: RunTrace?): Pair<List<String>, List<String>> {
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                server.enqueue(json("""{"status":"completed","output":[{"type":"function_call","call_id":"call_apps","name":"phone_list_apps","arguments":"{}"}],"usage":{"input_tokens":3800,"output_tokens":4}}"""))
                server.enqueue(json("""{"error":{"param":"include","message":"unsupported"}}""").setResponseCode(400))
                server.enqueue(json("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"好了"}]}],"usage":{"input_tokens":1100,"input_tokens_details":{"cached_tokens":0},"output_tokens":2}}"""))
                val tools = mutableListOf<String>()
                val screen = object : ScreenPort by FakeScreen() {
                    override suspend fun listApps() = listOf("com.example" to ("very long application label ".repeat(5000)))
                }
                val monitor = object : RunSink {
                    override fun event(e: RunEvent) { if (e is RunEvent.ToolResult) tools += e.tool }
                }
                val end = AgentLoop(model, toolDispatcher(screen), "BASE", maxSteps = 2, sink = monitor, trace = trace,
                    clock = { 1_780_000_000_000L }).run(goal)
                assertTrue(end is TaskState.Succeeded)
                return (1..3).map { server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8() } to tools
            }
            val off = replay(null)
            val trace = store.start("test")!!
            val on = replay(trace)
            store.finish(trace)
            assertEquals(off, on)
            assertEquals(listOf("phone_list_apps"), on.second)
            val archive = store.archive("test")!!
            val rows = archive.rows()
            val attempts = rows.filter { it.attempt != null }.sortedWith(compareBy({ it.step }, { it.attempt }))
            assertEquals(listOf(1L, 2L, 2L), attempts.map { it.step })
            assertEquals(listOf("ok", "error", "ok"), attempts.map { it.status })
            assertEquals(listOf(null, null, 0L), attempts.map { it.cached })
            assertEquals(1100L, attempts.last().uncached)
            assertTrue(attempts[1].contextChange.orEmpty().contains("history_rewritten"))
            assertNull(attempts.last().contextChange)
            assertTrue(rows.any { it.name == "execute_tool phone_list_apps" })
            assertTrue(archive.bodyPage(attempts.first().id, limit = 65536)!!.text.contains("PRIVATE_GOAL"))
            assertTrue(archive.bodyPage(attempts.last().id, limit = 65536)!!.text.contains("call_apps"))
            assertFalse(archive.bodyPage(attempts.first().id, limit = 65536)!!.text.contains("dummy-auth-for-test"))
            assertEquals("removed include", attempts.last().difference)
            assertEquals("call_apps", rows.single { it.name == "execute_tool phone_list_apps" }.toolCallId)
            val binary = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
            val packet = ExportTraceServiceRequest.parseFrom(binary)
            val spans = packet.getResourceSpans(0).getScopeSpans(0).spansList
            assertEquals(5, spans.size)
            assertEquals(16, spans.first().traceId.size())
            assertTrue(spans.drop(1).all { it.traceId == spans.first().traceId && it.parentSpanId == spans.first().spanId })
            assertFalse(packet.toString().contains("PRIVATE_GOAL"))
            assertTrue(packet.toString().contains("gen_ai.usage.cache_read.input_tokens"))

            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(archive.send(server.url("/v1/traces").toString()))
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/v1/traces", post.path)
            assertEquals("application/x-protobuf", post.getHeader("Content-Type"))
            assertNull(post.getHeader("Authorization"))
            assertArrayEquals(binary, post.body.readByteArray())
            val rejected = ExportTraceServiceResponse.newBuilder().setPartialSuccess(
                ExportTracePartialSuccess.newBuilder().setRejectedSpans(1)).build()
            server.enqueue(MockResponse().setBody(Buffer().write(rejected.toByteArray())))
            assertFalse(archive.send(server.url("/v1/traces").toString()))
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(403))
            assertFalse(archive.send(server.url("/v1/traces").toString()))
            server.takeRequest()
            val zip = ByteArrayOutputStream().also { archive.save(it, true) }.toByteArray()
            ZipInputStream(zip.inputStream()).use { stream ->
                assertEquals("trace.pb", stream.nextEntry.name)
                assertArrayEquals(binary, stream.readBytes())
                assertTrue(stream.nextEntry.name.startsWith("requests/"))
                assertTrue(stream.readBytes().decodeToString().contains("PRIVATE_GOAL"))
            }
        }
    }

    @Test fun privatePayloadMasksSecretsAndOpaqueStateAndFingerprintsTheActualImage() {
        val img = Part.Image("aW1hZ2VkYXRh", width = 20, height = 30)
        val messages = listOf(Message(Role.User, listOf(img), Author.User))
        val body = Json.parseToJsonElement("""{"instructions":"go","input":[
            {"type":"reasoning","encrypted_content":"private_opaque"},
            {"type":"function_call","name":"phone_type_text","call_id":"c","arguments":"{\"text\":\"private_typed\"}"},
            {"type":"function_call_output","call_id":"c","output":"{\"password\":\"private_secret\",\"url\":\"https://bob:private_user@local/path?token=private_token\"}"},
            {"type":"message","content":[{"type":"input_image","image_url":"data:image/png;base64,aW1hZ2VkYXRh"}]}]}""").jsonObject
        val blocks = TracePayload.blocks(body, messages) { Verb.TypeText }
        val display = blocks.joinToString { it.text }
        for (secret in listOf("private_opaque","private_typed","private_secret","private_token","private_user","aW1hZ2VkYXRh"))
            assertFalse(secret, display.contains(secret))
        assertTrue(display.contains("sha256") && display.contains("\"width\":20") && display.contains("\"height\":30"))
    }

    @Test fun appendRewriteAndStopWhileARequestIsInFlight() {
        val store = TraceStore(temp.newFolder())
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "model", Capabilities(true,false,1000,false), OkHttpClient())
        val trace = store.start("running")!!
        val completed = mutableListOf<String>()
        fun request(step: Int, input: String) = TraceCall(trace, step, model, { null }, null)
            .begin(Json.parseToJsonElement(input).jsonObject, emptyList())!!
        for ((n, json) in listOf(
            """{"input":["first"]}""", """{"input":["first","new"]}""", """{"input":["rewritten","new"]}"""
        ).withIndex()) {
            val span = request(n + 1, json)
            completed += span.spanContext.spanId
            trace.end(span, "ok", 100, 20, 5)
        }
        val pending = request(4, """{"input":["rewritten","new"]}""")
        val files = store.archive("running")!!
        assertEquals(listOf("first", "append input[1]", "input[0]", "same"), files.rows().drop(1).map { it.difference })
        val body = generateSequence(0) { it + 1 }.map { files.bodyPage(completed[2], it * 8, 8)!! }
        val full = buildString { for (chunk in body) { append(chunk.text); if (!chunk.hasMore) break } }
        assertTrue(full.contains("rewritten") && full.contains("new"))
        trace.toolStarted(4, "phone_observe", "call_wait", System.currentTimeMillis())
        store.stopAll()
        trace.end(pending, "ok", 300, 250, 4)
        assertEquals("partial", files.rows().first().status)
        assertEquals("partial", files.rows().last().status)
        assertEquals("call_wait", files.rows().last().toolCallId)
        assertNull(files.rows().single { it.id == pending.spanContext.spanId }.cached)
    }

    @Test fun stopAndQuotaLeaveReadablePartialRunsAndLateCallbacksCannotResumeCapture() {
        val root = temp.newFolder()
        val store = TraceStore(root, byteLimit = 100)
        val trace = store.start("limited")!!
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "model", Capabilities(true,false,1000,false), OkHttpClient())
        val call = TraceCall(trace, 1, model, { null }, null)
        val attempt = call.begin(buildJsonObject { put("instructions", "long".repeat(100)) }, emptyList())
        trace.end(attempt, "ok", 80, 20, 2)
        store.finish(trace)
        val limited = store.archive("limited")!!.rows()
        assertEquals("partial", limited.first().status)
        assertEquals("truncated", limited.last().status)
        assertTrue(store.archive("limited")!!.bodyPage(limited.last().id)!!.text.contains("instructions\n\n[truncated]"))
        val other = store.start("stopped")!!
        store.stopAll()
        assertNull(TraceCall(other, 1, model, { null }, null).begin(JsonObject(emptyMap()), emptyList()))
        assertEquals("partial", store.archive("stopped")!!.rows().single().status)
        assertNull(store.archive("../limited"))
        assertNull(store.archive("older-run"))
    }
}
