package com.aria.ai.core.ai.discovery

import com.aria.ai.core.ai.AutoModelSelector
import com.aria.ai.core.ai.ModelDiscoveryFailedException
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Model discovery for Groq (`GET /openai/v1/models`, bearer auth).
 *
 * Groq's catalogue is OpenAI-compatible JSON. It surfaces `context_window` on
 * most entries, which is used directly for LONG_CONTEXT; when absent the known
 * window of the model family is used instead.
 *
 * Groq has no realtime audio API. `selectBestModel(AUDIO_LIVE)` therefore
 * returns the fastest chat model so the caller can degrade to
 * speech-to-text → chat → text-to-speech instead of failing.
 */
@Singleton
class GroqModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.GROQ

    override suspend fun listAvailableModels(apiKey: String): List<ModelInfo> {
        val body = DiscoveryHttp.getJson(
            client = client,
            providerId = providerId,
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey")
        )
        return parse(body)
    }

    internal fun parse(body: String): List<ModelInfo> {
        val data = try {
            JSONObject(body).optJSONArray("data")
        } catch (t: Throwable) {
            throw ModelDiscoveryFailedException(providerId, t)
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Groq response carried no 'data' array")
        )

        val out = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val id = entry.optString("id", "")
            if (id.isBlank()) continue
            val lower = id.lowercase()
            // Groq also lists guard/whisper models that cannot hold a conversation.
            val conversational = !lower.contains("guard") && !lower.contains("whisper")
            out += Ranking.model(
                id = id,
                providerId = providerId,
                displayName = id,
                inputTokenLimit = entry.optInt("context_window", 0)
                    .takeIf { it > 0 } ?: familyContext(lower),
                supportsChat = conversational,
                supportsVision = lower.contains("vision"),
                supportsAudioLive = false
            )
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("Groq returned an empty model catalogue")
            )
        }
        return out
    }

    private fun familyContext(lower: String): Int? = when {
        lower.contains("mixtral") -> 32_768
        lower.contains("llama-3.3") || lower.contains("llama-3.1") -> 131_072
        lower.contains("llama-3") -> 8_192
        lower.contains("gemma") -> 8_192
        else -> null
    }

    override fun selectBestModel(available: List<ModelInfo>, task: TaskType): String {
        val chosen = when (task) {
            TaskType.CHAT -> chat(available)
            TaskType.VISION -> vision(available)
            TaskType.AUDIO_LIVE -> chat(available)
            TaskType.LIGHTWEIGHT -> lightweight(available)
            TaskType.LONG_CONTEXT -> Ranking.highestContext(available)
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Groq offers no model for $task")
        )
        return chosen.id
    }

    private fun chat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf("llama-3.3-70b-versatile", "llama-3.1-70b-versatile", "mixtral-8x7b-32768")
        ) ?: Ranking.firstWhere(available) { it.supportsChat && it.id.lowercase().contains("llama-3") }
            ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun vision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf("llama-3.2-90b-vision-preview", "llama-3.2-11b-vision-preview")
        ) ?: Ranking.firstWhere(available) { it.supportsVision }

    private fun lightweight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("llama-3.1-8b-instant", "gemma2-9b-it"))
            ?: Ranking.firstWhere(available) { it.supportsChat && it.looksLightweight }
            ?: chat(available)

    private companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/models"
    }
}