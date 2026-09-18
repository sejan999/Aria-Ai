package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Removes anything that looks like a credential from a string before it is
 * logged, displayed or thrown. This is the single redaction primitive of
 * Aria Ai — every adapter funnels provider errors through it.
 */
internal object KeyRedactor {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("([?&](?:key|api_key|apikey|access_token)=)([^&\\s\"']+)", RegexOption.IGNORE_CASE) to "$1REDACTED",
        Regex("(Bearer\\s+)([A-Za-z0-9._\\-]{6,})", RegexOption.IGNORE_CASE) to "$1REDACTED",
        Regex("([\"']?x-(?:goog-)?api-key[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,}]+)", RegexOption.IGNORE_CASE) to "$1REDACTED",
        Regex("([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,}]+)", RegexOption.IGNORE_CASE) to "$1REDACTED"
    )

    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        for ((pattern, replacement) in rules) {
            out = out.replace(pattern, replacement)
        }
        return out
    }
}

/**
 * Shared HTTP plumbing for every REST adapter: request building plus a single
 * cancellable Server-Sent-Events reader, so redaction and cancellation rules
 * are enforced for all providers at once.
 */
internal object AiHttp {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun buildRequest(url: String, headers: Map<String, String>, jsonBody: String): Request {
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) {
            builder.header(name, value)
        }
        builder.header("Accept", "text/event-stream, application/json")
        return builder.post(jsonBody.toRequestBody(JSON_MEDIA)).build()
    }

    /**
     * Executes a streaming POST and maps each SSE `data:` payload through
     * [parseData]. The flow is cold: nothing is sent until it is collected.
     */
    fun streamSse(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        parseData: (String) -> ChatChunk
    ): Flow<ChatChunk> = flow {
        val call = client.newCall(buildRequest(url, headers, jsonBody))

        val response = withContext(Dispatchers.IO) {
            try {
                call.execute()
            } catch (io: IOException) {
                throw IOException(KeyRedactor.scrub(io.message ?: "network request failed"), io)
            }
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = withContext(Dispatchers.IO) {
                    runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                }
                throw AiHttpException(resp.code, KeyRedactor.scrub(body.take(500)))
            }

            val source = resp.body?.source() ?: throw IOException("Provider returned an empty body")
            while (true) {
                currentCoroutineContext().ensureActive()
                val rawLine = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith(":")) continue
                if (line.startsWith("event:")) continue
                if (!line.startsWith("data:")) continue

                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue

                val chunk = parseData(payload)
                emit(chunk)
                if (chunk.finished) break
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Aggregates a provider stream into one [ChatResponse] so every adapter can
 * implement `chat()` with a single line and identical semantics.
 */
internal suspend fun aggregateViaStream(
    provider: AIProvider,
    messages: List<Message>,
    options: ChatOptions
): ChatResponse {
    val builder = StringBuilder()
    var finishReason: String? = null
    var failure: String? = null
    var model: String? = null

    provider.streamChat(messages, options).collect { chunk ->
        if (chunk.error != null) {
            failure = chunk.error
            return@collect
        }
        if (chunk.hasText) builder.append(chunk.delta)
        if (chunk.finishReason != null) finishReason = chunk.finishReason
        if (chunk.model != null) model = chunk.model
    }

    failure?.let { throw AiHttpException(-1, it) }

    return ChatResponse(
        text = builder.toString().trim(),
        model = model ?: options.model ?: provider.defaultModel,
        finishReason = finishReason ?: "stop",
        providerId = provider.id,
        fromOnDevice = provider.isLocal
    )
}