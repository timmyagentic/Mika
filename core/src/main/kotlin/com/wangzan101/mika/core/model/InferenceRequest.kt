package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.trace.TraceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class InferenceProtocol(val provider: String, val instruction: String?, val messages: String) {
    Chat("openai_compatible", null, "messages"),
    Responses("openai_compatible", "instructions", "input"),
    Anthropic("anthropic", "system", "messages"),
    Gemini("gcp.gemini", "systemInstruction", "contents");

    fun usage(el: JsonElement?): ReportedTokens? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        fun detail(key: String, field: String) = ((u[key] as? JsonObject)?.get(field) as? JsonPrimitive)?.intOrNull
        return when (this) {
            Chat -> {
                val hit = n("prompt_cache_hit_tokens")
                val miss = n("prompt_cache_miss_tokens")
                val input = n("prompt_tokens") ?: if (hit != null && miss != null) hit + miss else null
                val cache = listOfNotNull(hit, detail("prompt_tokens_details", "cached_tokens")).maxOrNull()
                ReportedTokens(input, n("completion_tokens"), cache, detail("completion_tokens_details", "reasoning_tokens"))
            }
            Responses -> ReportedTokens(n("input_tokens"), n("output_tokens"), detail("input_tokens_details", "cached_tokens"),
                detail("output_tokens_details", "reasoning_tokens"))
            Anthropic -> {
                val cache = n("cache_read_input_tokens")
                ReportedTokens(n("input_tokens")?.plus((cache ?: 0) + (n("cache_creation_input_tokens") ?: 0)), n("output_tokens"), cache)
            }
            Gemini -> {
                val text = n("candidatesTokenCount")
                val thoughts = n("thoughtsTokenCount")
                ReportedTokens(n("promptTokenCount"), if (text == null && thoughts == null) null else (text ?: 0) + (thoughts ?: 0),
                    n("cachedContentTokenCount"), thoughts)
            }
        }
    }

    fun usageIn(packet: JsonObject) = usage(when (this) {
        Responses -> (packet["response"] as? JsonObject)?.get("usage") ?: packet["usage"]
        Anthropic -> (packet["message"] as? JsonObject)?.get("usage") ?: packet["usage"]
        Gemini -> packet["usageMetadata"]
        Chat -> packet["usage"]
    })
}

internal data class ReportedTokens(val input: Int?, val output: Int?, val cached: Int?, val reasoning: Int? = null) {
    fun totals(): Usage? = input?.let { Usage(it, output ?: 0, (cached ?: 0).coerceIn(0, it.coerceAtLeast(0)),
        (reasoning ?: 0).coerceIn(0, (output ?: 0).coerceAtLeast(0))) }
}

/** Only inference requests carry this tag. Token refresh and catalog HTTP calls can run in the same coroutine. */
internal data class InferenceRequest(val protocol: InferenceProtocol, val payload: JsonObject, val provider: String)

internal suspend fun Request.Builder.postInference(protocol: InferenceProtocol, payload: JsonObject, provider: String = protocol.provider): Request.Builder {
    if (currentCoroutineContext()[TraceCall] != null) tag(InferenceRequest::class.java, InferenceRequest(protocol, payload, provider))
    return post(payload.toString().toRequestBody("application/json".toMediaType()))
}
