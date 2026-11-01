package com.wangzan101.mika.core.trace

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

internal data class RequestBlock(val path: String, val digest: String, val text: String, val truncated: Boolean)

internal object TracePayload {
    const val MAX_BLOCK = 32_768
    private const val MASK = "[redacted]"
    private val headers = Regex("""(?im)\b(?:authorization|proxy-authorization|set-cookie|cookie)\s*:\s*[^\r\n]*""")
    private val url = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val userInfo = Regex("""(://)[^/?#\s]*@""")
    private val dataUri = Regex("""(?i)data:[^,\s"']{0,100};base64,[a-z0-9+/=_-]+""")
    private val assignment = Regex("""(?i)((?:\b(?:password|passwd|token|api[_-]?key|secret|otp|pin|authorization|cookie|session[_-]?id)|密码|口令)\s*[=:：]\s*)[^\s,;&]+""")
    private val identifier = Regex("[a-zA-Z0-9_.:/-]{1,96}")

    private fun scrub(value: String): String {
        val urls = url.replace(dataUri.replace(value, "[attachment]")) { m ->
            val address = userInfo.replace(m.value, "$1")
            val cut = address.indexOfAny(charArrayOf('?', '#'))
            if (cut < 0) address else address.take(cut) + "?[redacted]"
        }
        return Redactor.evidence(Redactor.pageData(assignment.replace(headers.replace(urls, MASK), "$1[redacted]")))
    }

    fun label(value: String): String =
        if (value.length <= 96) scrub(value).takeIf { it.matches(identifier) } ?: "other" else "other"

    /** Fingerprint the actual serialized blocks before redaction. Keep these digests in memory only. */
    fun blocks(body: JsonObject, messages: List<Message>, verb: (String?) -> Verb?): List<RequestBlock> {
        val images = messages.asSequence().flatMap { it.parts.asSequence() }.filterIsInstance<Part.Image>().iterator()
        val view = View(images, verb)
        return buildList {
            fun block(path: String, value: JsonElement) {
                val digest = value.toString().encodeUtf8().sha256().hex()
                view.truncated = false
                val cleaned = view.clean(value).toString()
                add(RequestBlock(path, digest, cleaned.take(MAX_BLOCK), view.truncated || cleaned.length > MAX_BLOCK))
            }
            for ((k, v) in body) if (k != "instructions" && k != "tools" && k != "input") block(k, v)
            body["instructions"]?.let { block("instructions", it) }
            (body["tools"] as? JsonArray)?.forEachIndexed { i, v -> block("tools[$i]", v) }
            (body["input"] as? JsonArray)?.forEachIndexed { i, v -> block("input[$i]", v) }
        }
    }

    fun reply(reply: ModelReply, verb: (String?) -> Verb?): String {
        val view = View(emptyList<Part.Image>().iterator(), verb)
        val visible = buildString {
            for (part in reply.parts) {
                when (part) {
                    is Part.Text -> appendLine(view.clean(JsonPrimitive(part.text)))
                    is Part.ToolCall -> appendLine(view.clean(buildJsonObject {
                        put("type", "function_call"); put("name", part.name); put("call_id", part.id); put("arguments", part.argsJson)
                    }))
                    else -> Unit
                }
                if (length > MAX_BLOCK) break
            }
        }
        return if (visible.length > MAX_BLOCK || view.truncated) visible.take(MAX_BLOCK) + "\n[truncated]" else visible
    }

    private class View(private val images: Iterator<Part.Image>, private val verb: (String?) -> Verb?) {
        private val calls = mutableMapOf<String, Verb?>()
        var truncated = false

        fun clean(v: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 32) {
                truncated = true
                return JsonPrimitive("[truncated]")
            }
            if (v is JsonPrimitive && v.isString && v.content.length > MAX_BLOCK) {
                truncated = true
                // Cut at a boundary so a partial credential cannot evade the redactor.
                val prefix = v.content.take(MAX_BLOCK)
                val boundary = prefix.lastIndexOfAny(charArrayOf(' ', '\n', '\r', '\t'))
                return JsonPrimitive(scrub(if (boundary < 0) "" else prefix.take(boundary)) + "[truncated]")
            }
            if (v is JsonArray) return JsonArray(v.map { clean(it, depth + 1) })
            if (v !is JsonObject) return if (v is JsonPrimitive && v.isString) JsonPrimitive(scrub(v.content)) else v

            fun str(key: String) = (v[key] as? JsonPrimitive)?.contentOrNull
            val type = str("type")
            if (type == "input_image") {
                val img = if (images.hasNext()) images.next() else null
                return buildJsonObject {
                    put("type", "input_image")
                    put("width", img?.width ?: 0)
                    put("height", img?.height ?: 0)
                    put("sha256", str("image_url").orEmpty().encodeUtf8().sha256().hex())
                }
            }
            if (type == "reasoning" || str("password") == "true" || str("isPassword") == "true" ||
                type.equals("password", true)) return JsonPrimitive(MASK)
            val callId = str("call_id")
            if (type == "function_call" && callId != null) calls[callId] = verb(str("name"))
            return JsonObject(v.mapValues { (key, item) ->
                when {
                    Redactor.secretName(key) || key == "encrypted_content" || key.contains("base64", ignoreCase = true) -> JsonPrimitive(MASK)
                    key == "arguments" && type == "function_call" -> {
                        val args = Redactor.args(calls[callId], str(key).orEmpty(), secretField = true)
                        encoded(str(key).orEmpty(), clean(parsed(args), depth + 1))
                    }
                    key == "output" && type == "function_call_output" -> {
                        val result = parsed(str(key).orEmpty())
                        val operation = calls[callId]
                        val safe = when {
                            operation == Verb.AskUser -> JsonPrimitive(MASK)
                            result is JsonObject -> clean(Redactor.result(operation, result), depth + 1)
                            else -> clean(result, depth + 1)
                        }
                        encoded(str(key).orEmpty(), safe)
                    }
                    else -> clean(item, depth + 1)
                }
            })
        }

        // Responses encodes arguments and tool output as strings, even when they contain JSON.
        private fun encoded(original: String, safe: JsonElement): JsonPrimitive {
            if (parsed(original) == safe) return JsonPrimitive(original)
            return if (safe is JsonPrimitive && safe.isString) safe else JsonPrimitive(safe.toString())
        }

        private fun parsed(s: String): JsonElement =
            runCatching { Json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }
    }
}
