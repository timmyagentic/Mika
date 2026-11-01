package com.wangzan101.mika.core.trace

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.Verb
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Kept outside ordinary run directories, which may be mirrored for evaluation. */
class TraceStore(private val directory: File, private val byteLimit: Int = 8 * 1024 * 1024) {
    private val active = mutableMapOf<String, RunTrace>()

    @Synchronized fun start(id: String): RunTrace? {
        if (!valid(id) || id in active) return null
        return runCatching {
            directory.mkdirs()
            val entries = directory.listFiles().orEmpty().filter { it.isDirectory && valid(it.name) }
            val removable = entries.filter { it.name !in active }.sortedBy { it.lastModified() }
            val excess = (entries.size - 11).coerceAtLeast(0)
            if (excess > removable.size) return null
            for (old in removable.take(excess)) if (!old.deleteRecursively()) return null
            val files = TraceArchive(File(directory, id))
            files.initialize()
            RunTrace(files, byteLimit).also { active[id] = it }
        }.getOrNull()
    }

    @Synchronized fun finish(trace: RunTrace?) {
        if (trace != null && active.remove(trace.archive.runId, trace)) trace.finish()
    }

    @Synchronized fun stopAll() {
        active.values.forEach { it.finish(stopped = true) }
        active.clear()
    }

    fun archive(id: String): TraceArchive? = if (valid(id))
        TraceArchive(File(directory, id)).takeIf { it.root.isFile } else null

    private fun valid(id: String) = RUN_ID.matches(id)

    private companion object { val RUN_ID = Regex("[a-zA-Z0-9_-]{1,80}") }
}

/** Exists only in the main loop model invocation, including the adapter's own retry loop. */
internal class TraceCall(
    val run: RunTrace, val step: Int, val model: ChatModel, val verb: (String?) -> Verb?, change: String?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceCall>
    var wireSeen = false
        private set

    private var pendingChange = change

    fun takeChange(): String? = pendingChange.also { pendingChange = null }
    fun usesWireCapture() { wireSeen = true }

    fun begin(body: JsonObject, messages: List<Message>, provider: String = "openai_compatible"): Span? {
        return run.begin(this, body, messages, provider = provider)
    }
}

class RunTrace internal constructor(internal val archive: TraceArchive, private val byteLimit: Int) : RunSink {
    @Volatile private var writeFailed = false
    // A private SDK processor writes this run's ended spans locally. No global provider or automatic exporter.
    private val provider = SdkTracerProvider.builder().addSpanProcessor(object : SpanProcessor {
        override fun isStartRequired() = false
        override fun onStart(parent: Context, span: ReadWriteSpan) = Unit
        override fun isEndRequired() = true
        override fun onEnd(span: ReadableSpan) {
            try { archive.store(span.toSpanData()) } catch (_: Exception) { writeFailed = true }
        }
    }).build()
    private val tracer = provider.get("mika.harness")
    private val root = tracer.spanBuilder("invoke_agent Mika").setNoParent().startSpan()
        .setAttribute("gen_ai.operation.name", "invoke_agent").setAttribute("mika.status", "partial")
    private val parent = Context.root().with(root)
    private val pending = mutableSetOf<Span>()
    private val previous = IdentityHashMap<ChatModel, List<Pair<String, String>>>()
    private val attempts = mutableMapOf<Int, Long>()
    private var toolsOpened = false
    private var outcome: String? = null
    private var count = 0
    private var bytes = 0
    private var truncated = false
    @Volatile private var recording = true

    init { checkpoint(root) }

    private fun checkpoint(span: Span) = archive.store((span as ReadableSpan).toSpanData())

    internal suspend fun modelCall(
        step: Int, model: ChatModel, verb: (String?) -> Verb?, change: String?, block: suspend () -> ModelReply,
    ): ModelReply {
        if (!recording) return block()
        val scope = TraceCall(this, step, model, verb, change)
        val started = System.currentTimeMillis()
        var reply: ModelReply? = null
        var status = "error"
        try {
            return withContext(scope) { block() }.also { reply = it; status = "ok" }
        } catch (e: CancellationException) {
            status = "cancelled"
            throw e
        } finally {
            if (!scope.wireSeen) {
                val span = begin(scope, null, emptyList(), started)
                val usage = reply?.usage
                end(span, status, usage?.inputTokens, usage?.cachedInputTokens?.takeIf { it > 0 }, usage?.outputTokens)
            }
        }
    }

    internal fun begin(
        call: TraceCall, body: JsonObject?, messages: List<Message>, start: Long = System.currentTimeMillis(), provider: String? = null,
    ): Span? {
        if (!recording) return null
        // Do not hold the stop lock while fingerprinting last turn's images or redacting a long prompt.
        val blocks = try { body?.let { TracePayload.blocks(it, messages, call.verb) } }
            catch (_: Exception) { finish(stopped = true); return null }
            catch (_: StackOverflowError) { finish(stopped = true); return null }
        return beginPrepared(call, blocks, start, provider)
    }

    @Synchronized private fun beginPrepared(call: TraceCall, blocks: List<RequestBlock>?, start: Long, provider: String?): Span? = record {
        val span = child("chat " + TracePayload.label(call.model.name), call.step, SpanKind.CLIENT, start)
        val index = (attempts[call.step] ?: 0) + 1
        attempts[call.step] = index
        span.setAttribute("gen_ai.operation.name", "chat").setAttribute("gen_ai.request.model", TracePayload.label(call.model.name))
            .setAttribute("mika.attempt", index).setAttribute("mika.request.captured", blocks != null)
        provider?.let { span.setAttribute("gen_ai.provider.name", it) }
        val change = listOfNotNull("tools_opened".takeIf { toolsOpened }, call.takeChange()).joinToString(",")
        if (change.isNotEmpty()) span.setAttribute("mika.context.change", change)
        toolsOpened = false
        if (blocks != null) {
            val signatures = blocks.map { it.path to it.digest }
            val last = previous.put(call.model, signatures)
            val prefix = if (last == null) 0 else (0 until minOf(last.size, blocks.size))
                .firstOrNull { last[it] != signatures[it] } ?: minOf(last.size, blocks.size)
            val diff = when {
                last == null -> "first"
                prefix == blocks.size && prefix == last.size -> "same"
                prefix == last.size -> "append " + blocks[prefix].path
                prefix == blocks.size -> "removed " + last[prefix].first
                last[prefix].first != blocks[prefix].path && signatures.none { it.first == last[prefix].first } ->
                    "removed " + last[prefix].first
                else -> blocks[prefix].path
            }
            span.setAttribute("mika.context.first_changed_block", diff)
            val parts = ArrayList<BodyPart>()
            for (b in blocks) {
                val content = b.text.toByteArray()
                val key = content.toByteString().sha256().hex()
                val extra = if (archive.hasBlock(key)) 0 else content.size
                if (bytes + extra + (parts.size + 1) * 192 > byteLimit) {
                    span.setAttribute("mika.status", "truncated")
                    parts.add(BodyPart(b.path, null, true))
                    bytes += archive.writeBody(span.spanContext.spanId, parts)
                    checkpoint(span)
                    finish(stopped = true)
                    return@record null
                }
                if (b.truncated) truncated = true
                bytes += archive.putBlock(key, content)
                parts.add(BodyPart(b.path, key, b.truncated))
            }
            bytes += archive.writeBody(span.spanContext.spanId, parts)
        }
        checkpoint(span)
        span
    }

    @Synchronized internal fun end(span: Span?, status: String, input: Int?, cached: Int?, output: Int?,
        replyText: (() -> String)? = null,
    ) {
        if (span == null || !recording || span !in pending) return
        record {
            input?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
            cached?.let { span.setAttribute("gen_ai.usage.cache_read.input_tokens", it.toLong()) }
            output?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }
            if (replyText != null) {
                val data = replyText().toByteArray()
                if (bytes + data.size <= byteLimit) {
                    archive.writeReply(span.spanContext.spanId, data)
                    bytes += data.size
                } else {
                    truncated = true
                    archive.writeReply(span.spanContext.spanId, "[truncated]".toByteArray())
                }
            }
            span.setAttribute("mika.status", status)
            if (status == "error") span.setStatus(StatusCode.ERROR)
            pending.remove(span)
            span.end()
        }
    }

    @Synchronized override fun event(e: RunEvent) {
        record {
            when (e) {
                is RunEvent.ToolResult -> if (e.source == "model") {
                    val name = TracePayload.label(e.tool)
                    val span = child("execute_tool " + name, e.step, SpanKind.INTERNAL, e.atMs - e.durationMs)
                    span.setAttribute("gen_ai.operation.name", "execute_tool").setAttribute("gen_ai.tool.name", name)
                    e.callId?.let { span.setAttribute("gen_ai.tool.call.id", TracePayload.label(it)) }
                    val failed = runCatching { Json.parseToJsonElement(e.resultJson).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == false }
                        .getOrDefault(false) || Regex("""^\{\s*"ok"\s*:\s*false\b""").containsMatchIn(e.resultJson)
                    span.setAttribute("mika.status", if (failed) "error" else "ok")
                    if (failed) span.setStatus(StatusCode.ERROR)
                    span.end(e.atMs, TimeUnit.MILLISECONDS)
                    pending.remove(span)
                }
                is RunEvent.State -> if (e.name == "tools_opened") toolsOpened = true
                is RunEvent.TaskEnded -> outcome = e.outcome
                else -> Unit
            }
        }
    }

    private fun child(name: String, step: Int, kind: SpanKind, start: Long): Span {
        check(count < 1024)
        count++
        return tracer.spanBuilder(name).setParent(parent).setSpanKind(kind).setStartTimestamp(start, TimeUnit.MILLISECONDS)
            .startSpan().setAttribute("mika.step.index", step.toLong()).setAttribute("mika.sequence", count.toLong())
            .setAttribute("mika.status", "partial")
            .also { pending.add(it) }
    }

    /** Cancellation, quota exhaustion and storage failure seal the trace. Late model callbacks cannot reopen it. */
    @Synchronized internal fun finish(stopped: Boolean = false) {
        if (!recording) return
        recording = false
        val interrupted = pending.isNotEmpty() || outcome == "cancelled"
        pending.forEach { it.end() }
        pending.clear()
        previous.clear()
        val state = if (stopped || interrupted || truncated || writeFailed || outcome == null) "partial" else outcome!!
        outcome?.let { root.setAttribute("mika.run.outcome", it) }
        root.setAttribute("mika.status", state)
        if (state == "failed" || state == "error") root.setStatus(StatusCode.ERROR)
        root.end()
        provider.shutdown()
    }

    private inline fun <T> record(block: () -> T): T? {
        if (!recording) return null
        if (writeFailed) { finish(stopped = true); return null }
        return try { block() }
        catch (_: Exception) { finish(stopped = true); null }
        catch (_: StackOverflowError) { finish(stopped = true); null }
    }

}
