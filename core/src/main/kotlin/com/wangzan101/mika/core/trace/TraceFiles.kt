package com.wangzan101.mika.core.trace

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.common.v1.ArrayValue
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import io.opentelemetry.sdk.trace.data.SpanData
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** UI reads only span metadata until the user asks for a particular request. */
data class TraceRow(
    val id: String, val name: String, val step: Long?, val attempt: Long?, val status: String,
    val input: Long?, val cached: Long?, val output: Long?, val durationMs: Long,
    val difference: String?, val contextChange: String?, val captured: Boolean, val toolCallId: String?,
) {
    val uncached: Long? get() = if (input != null && cached != null) input - cached else null
}

@Serializable
internal data class BodyPart(val path: String, val blob: String?, val truncated: Boolean, val reason: String? = null)

class TraceArchive internal constructor(internal val dir: File, private val disk: TraceDisk) {
    val runId: String get() = dir.name
    internal val root = File(dir, "root.pb")
    private val spanDir = File(dir, "spans")
    private val bodyDir = File(dir, "requests")
    private val blobs = File(dir, "blocks")

    internal fun initialize() {
        if (!spanDir.mkdir() || !bodyDir.mkdir() || !blobs.mkdir()) throw IOException("trace directory")
    }

    internal fun store(data: SpanData) {
        val message = Span.newBuilder()
            .setTraceId(bytes(data.traceId)).setSpanId(bytes(data.spanId))
            .setName(data.name).setKind(Span.SpanKind.valueOf("SPAN_KIND_" + data.kind.name))
            .setFlags(data.spanContext.traceFlags.asByte().toInt() and 0xff)
            .setStartTimeUnixNano(data.startEpochNanos)
            .setEndTimeUnixNano(if (data.hasEnded()) data.endEpochNanos else data.startEpochNanos)
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.valueOf("STATUS_CODE_" + data.status.statusCode.name)))
        if (data.parentSpanContext.isValid) message.parentSpanId = bytes(data.parentSpanContext.spanId)
        // Store recorder attributes here. Export adds block locations from the capture manifest.
        data.attributes.forEach { key, value ->
            val any = AnyValue.newBuilder()
            when (value) {
                is String -> any.stringValue = value
                is Long -> any.intValue = value
                is Boolean -> any.boolValue = value
                else -> error("Unsupported trace attribute")
            }
            message.addAttributes(KeyValue.newBuilder().setKey(key.key).setValue(any))
        }
        disk.write(dir, if (!data.parentSpanContext.isValid) root else File(spanDir, data.spanId + ".pb"), message.build().toByteArray(), "span")
    }

    internal fun hasBlock(key: String) = File(blobs, key).isFile

    internal fun putBlock(key: String, bytes: ByteArray, path: String): Int {
        val dest = File(blobs, key)
        if (dest.exists()) return 0
        disk.write(dir, dest, bytes, path)
        return bytes.size
    }

    internal fun writeBody(id: String, parts: List<BodyPart>): Int {
        val content = Json.encodeToString(parts).toByteArray()
        disk.write(dir, File(bodyDir, id + ".json"), content, "request_manifest")
        return content.size
    }

    private fun content(id: String): Sequence<String>? {
        if (!SPAN_ID.matches(id)) return null
        val manifest = File(bodyDir, id + ".json")
        if (!manifest.isFile) return null
        return sequence {
            for (part in Json.decodeFromString<List<BodyPart>>(manifest.readText())) {
                yield(part.path + "\n")
                part.blob?.let { key ->
                    require(BLOCK_ID.matches(key))
                    yield(File(blobs, key).readText())
                }
                if (part.truncated) yield("\n[truncated]")
                yield("\n\n")
            }
            val reply = File(bodyDir, id + ".reply")
            if (reply.isFile) {
                yield("response\n")
                yield(reply.readText())
            }
        }
    }

    internal fun writeReply(id: String, data: ByteArray) = disk.write(dir, File(bodyDir, id + ".reply"), data, "response")

    data class Page(val text: String, val hasMore: Boolean)

    /** Materialize only one page, even when a long run repeatedly references the same block. */
    fun bodyPage(id: String, offset: Int = 0, limit: Int = 3000): Page? {
        require(offset >= 0 && limit in 1..65536)
        val sections = content(id) ?: return null
        val result = StringBuilder()
        var skip = offset
        for (section in sections) {
            if (skip >= section.length) { skip -= section.length; continue }
            val end = minOf(section.length, skip + limit - result.length)
            result.append(section, skip, end)
            if (end < section.length) return Page(result.toString(), true)
            skip = 0
        }
        return Page(result.toString(), false)
    }

    private fun spans(): List<Span> {
        val children = spanDir.listFiles().orEmpty().filter { it.extension == "pb" }.map { Span.parseFrom(it.readBytes()) }
        return listOf(Span.parseFrom(root.readBytes())) + children.sortedBy { span -> span.attributesList.firstOrNull { it.key == "mika.sequence" }?.value?.intValue }
    }

    fun rows(): List<TraceRow> = spans().map { span ->
        val a = span.attributesList.associate { it.key to it.value }
        TraceRow(span.id(), span.name, a["mika.step.index"]?.intValue, a["mika.attempt"]?.intValue,
            a["mika.status"]?.stringValue ?: "partial",
            a["gen_ai.usage.input_tokens"]?.intValue, a["gen_ai.usage.cache_read.input_tokens"]?.intValue,
            a["gen_ai.usage.output_tokens"]?.intValue,
            TimeUnit.NANOSECONDS.toMillis(span.endTimeUnixNano - span.startTimeUnixNano),
            a["mika.context.first_changed_block"]?.stringValue, a["mika.context.change"]?.stringValue,
            a["mika.request.captured"]?.boolValue == true, a["gen_ai.tool.call.id"]?.stringValue)
    }

    /** Location and reason come from manifests even for runs recorded before diagnostics were exported. */
    private fun exportSpans(): List<Span> {
        val recorded = spans()
        val root = recorded.first()
        val legacy = root.attributesList.none { it.key == "mika.trace.format_version" }
        if (!legacy && root.attribute("mika.status") != "partial") return recorded
        val reasons = linkedSetOf<String>()
        root.attribute("mika.trace.stop_reason")?.let(reasons::add)
        val children = recorded.drop(1).map { span ->
            if (span.attributesList.none { it.key == "mika.attempt" }) return@map span
            val builder = span.toBuilder()
            fun truncated(path: String, reason: String, time: Long) {
                reasons += reason
                builder.addEvents(Span.Event.newBuilder().setName("mika.trace.truncated").setTimeUnixNano(time)
                    .addAttributes(textAttribute("mika.block.path", path))
                    .addAttributes(textAttribute("mika.truncation.reason", reason)))
            }
            val manifest = File(bodyDir, span.id() + ".json")
            if (manifest.isFile) {
                val parts = try {
                    Json.decodeFromString<List<BodyPart>>(manifest.readText())
                } catch (_: SerializationException) {
                    truncated("request_manifest", "manifest_unreadable", span.startTimeUnixNano)
                    emptyList()
                } catch (_: IOException) {
                    truncated("request_manifest", "manifest_unreadable", span.startTimeUnixNano)
                    emptyList()
                }
                for (part in parts) {
                    if (part.truncated) {
                        val reason = part.reason ?: if (part.blob == null) "run_body_limit" else "block_limit"
                        truncated(part.path, reason, span.startTimeUnixNano)
                    }
                }
            }
            val reply = span.attribute("mika.trace.reply_truncation")
                ?: if (legacy) legacyReplyTruncation(span.id()) else null
            reply?.let { truncated("response", it, span.endTimeUnixNano) }
            builder.build()
        }
        if (root.attribute("mika.status") == "partial") {
            if (root.startTimeUnixNano == root.endTimeUnixNano) reasons += "not_finalized"
            if (legacy && root.attribute("mika.run.outcome") == "cancelled") reasons += "run_cancelled"
            if (reasons.isEmpty()) reasons += "unknown"
        }
        if (reasons.isEmpty()) return recorded
        val summary = ArrayValue.newBuilder().addAllValues(reasons.map { AnyValue.newBuilder().setStringValue(it).build() })
        val updated = root.toBuilder().addAttributes(KeyValue.newBuilder().setKey("mika.trace.partial_reasons")
            .setValue(AnyValue.newBuilder().setArrayValue(summary)))
        // Older writers did not mark the root when only a reply was clipped.
        if (root.attribute("mika.status") != "partial") {
            updated.setAttributes(root.attributesList.indexOfFirst { it.key == "mika.status" }, textAttribute("mika.status", "partial"))
        }
        return listOf(updated.build()) + children
    }

    private fun legacyReplyTruncation(id: String): String? {
        val file = File(bodyDir, "$id.reply")
        if (!file.isFile) return null
        // The old writer put this marker outside the JSON-encoded visible reply.
        val suffix = "\n[truncated]".toByteArray()
        return RandomAccessFile(file, "r").use { input ->
            val n = minOf(input.length(), suffix.size.toLong()).toInt()
            input.seek(input.length() - n)
            val tail = ByteArray(n).also(input::readFully)
            when {
                tail.contentEquals(suffix) -> "block_limit"
                input.length() == n.toLong() && tail.decodeToString() == "[truncated]" -> "run_body_limit"
                else -> null
            }
        }
    }

    private fun payload(spans: List<Span>): ByteArray {
        val scope = ScopeSpans.newBuilder().setScope(InstrumentationScope.newBuilder().setName("mika.harness")).addAllSpans(spans)
        val resource = Resource.newBuilder().addAttributes(KeyValue.newBuilder().setKey("service.name")
            .setValue(AnyValue.newBuilder().setStringValue("Mika")))
        return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
            .addScopeSpans(scope)).build().toByteArray()
    }

    /** Plain export and manual POST share the identical OTLP message. Only a local ZIP can include text. */
    fun save(output: OutputStream, includeBody: Boolean): Unit = disk.read(dir) {
        val recorded = exportSpans()
        val data = payload(recorded)
        if (!includeBody) {
            output.write(data)
            return@read
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("trace.pb"))
            zip.write(data)
            zip.closeEntry()
            for (span in recorded) {
                val id = span.id()
                val sections = content(id) ?: continue
                zip.putNextEntry(ZipEntry("requests/$id.txt"))
                for (section in sections) zip.write(section.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** The address is explicitly supplied per export. No model credentials, redirects or background delivery. */
    suspend fun send(endpoint: String): Boolean = withContext(Dispatchers.IO) {
        val url = endpoint.trim().toHttpUrlOrNull() ?: throw IOException("Invalid endpoint")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        val data = disk.read(dir) { payload(exportSpans()) }
        val request = Request.Builder().url(url).post(data.toRequestBody(PROTOBUF)).build()
        suspendCancellableCoroutine { cont ->
            val call = sender.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val accepted = accepted(it)
                            if (cont.isActive) cont.resume(accepted)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private fun accepted(response: Response): Boolean {
        if (response.code in 400..499) return false
        if (response.code != 200) throw IOException("HTTP " + response.code)
        val source = response.body?.source() ?: throw IOException("Missing acknowledgement")
        if (source.request(65537)) throw IOException("Oversized acknowledgement")
        val ack = ExportTraceServiceResponse.parseFrom(source.readByteArray())
        return !ack.hasPartialSuccess() || ack.partialSuccess.rejectedSpans == 0L
    }

    companion object {
        private val SPAN_ID = Regex("[a-f0-9]{16}")
        private val BLOCK_ID = Regex("[a-f0-9]{64}")
        private val PROTOBUF = "application/x-protobuf".toMediaType()
        private val sender by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build() }
        private fun bytes(hex: String) = ByteString.copyFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        private fun textAttribute(key: String, value: String) = KeyValue.newBuilder().setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value)).build()
        private fun Span.attribute(key: String) = attributesList.firstOrNull { it.key == key }?.value?.stringValue
        private fun Span.id() = spanId.toByteArray().toByteString().hex()
    }
}
