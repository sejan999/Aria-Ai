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
 * Model discovery for OpenAI (`GET /v1/models`, bearer auth).
 *
 * The response is a flat `data[]` of `{id, created, owned_by}` with no
 * capability metadata, so capabilities are inferred from the id: `realtime` for
 * audio, the vision families for images, and `mini`/`nano` for cheap work.
 */
@Singleton
class OpenAIModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.OPENAI

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
            IllegalStateException("OpenAI response carried no 'data' array")
        )

        val out = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id", "").orEmpty()
            if (id.isBlank() || !isChatCapable(id)) continue
            out += describe(id)
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("OpenAI returned no chat-capable models for this key")
            )
        }
        return out
    }

    /** Keeps the conversational families and drops embeddings/audio/tts/moderation. */
    private fun isChatCapable(id: String): Boolean {
        val lower = id.lowercase()
        if (lower.contains("embedding") || lower.contains("whisper") ||
            lower.contains("tts") || lower.contains("dall-e") ||
            lower.contains("moderation") || lower.contains("davinci") ||
            lower.contains("babbage") || lower.contains("audio-preview")
        ) {
            return false
        }
        return lower.startsWith("gpt-") || lower.startsWith("o1") ||
            lower.startsWith("o3") || lower.startsWith("o4") ||
            lower.startsWith("chatgpt-")
    }

    internal fun describe(id: String): ModelInfo {
        val lower = id.lowercase()
        val vision = lower.contains("gpt-4o") || lower.contains("gpt-4-turbo") ||
            lower.contains("gpt-4.1") || lower.startsWith("o1") || lower.startsWith("o3")
        val realtime = lower.contains("realtime")
        return Ranking.model(
            id = id,
            providerId = providerId,
            displayName = id,
            // 128k is the documented window for the 4o family; o-series differs.
            inputTokenLimit = when {
                lower.contains("gpt-4o") || lower.contains("gpt-4.1") -> 128_000
                lower.contains("gpt-4-turbo") -> 128_000
                lower.contains("gpt-3.5") -> 16_385
                lower.startsWith("o1") || lower.startsWith("o3") -> 200_000
                else -> null
            },
            supportsChat = !realtime,
            supportsVision = vision && !realtime,
            supportsAudioLive = realtime
        )
    }

    override fun selectBestModel(available: List<ModelInfo>, task: TaskType): String {
        val chosen = when (task) {
            TaskType.CHAT -> chat(available)
            TaskType.VISION -> vision(available)
            TaskType.AUDIO_LIVE -> live(available)
            TaskType.LIGHTWEIGHT -> lightweight(available)
            TaskType.LONG_CONTEXT -> longContext(available)
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("OpenAI offers no model for $task")
        )
        return chosen.id
    }

    private fun chat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"))
            ?: Ranking.firstWhere(available) { it.supportsChat && it.id.lowercase().startsWith("gpt-4") }
            ?: Ranking.firstWhere(available) { it.supportsChat && it.id.lowercase().startsWith("gpt-3.5") }
            ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun vision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1"))
            ?.takeIf { it.supportsVision }
            ?: Ranking.firstWhere(available) { it.supportsVision }
            ?: chat(available)

    private fun live(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf("gpt-4o-realtime-preview", "gpt-4o-mini-realtime-preview")
        )?.takeIf { it.supportsAudioLive }
            ?: Ranking.firstWhere(available) { it.supportsAudioLive }

    private fun lightweight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("gpt-4o-mini", "gpt-3.5-turbo"))
            ?: Ranking.firstWhere(available) { it.supportsChat && it.looksLightweight }
            ?: chat(available)

    private fun longContext(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("gpt-4o", "gpt-4-turbo", "o1"))
            ?: Ranking.highestContext(available)

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/models"
    }
}