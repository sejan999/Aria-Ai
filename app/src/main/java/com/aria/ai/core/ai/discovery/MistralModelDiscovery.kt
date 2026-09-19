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
 * Model discovery for Mistral AI (`GET /v1/models`, bearer auth).
 *
 * The catalogue is OpenAI-shaped (`data[]` of `{id, ...}`). Mistral exposes no
 * realtime audio API, so [TaskType.AUDIO_LIVE] raises
 * [ModelDiscoveryFailedException] and the caller falls through.
 */
@Singleton
class MistralModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.MISTRAL

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
            IllegalStateException("Mistral response carried no 'data' array")
        )

        val out = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val id = entry.optString("id", "")
            if (id.isBlank()) continue
            val lower = id.lowercase()
            // Drop embedding / moderation / OCR only models — they cannot chat.
            if (lower.contains("embed") || lower.contains("moderation")) continue

            out += Ranking.model(
                id = id,
                providerId = providerId,
                displayName = id,
                inputTokenLimit = when {
                    lower.contains("large") -> 128_000
                    lower.contains("pixtral") -> 128_000
                    lower.contains("small") -> 128_000
                    else -> null
                },
                supportsChat = true,
                supportsVision = lower.contains("pixtral"),
                supportsAudioLive = false
            )
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("Mistral returned an empty model catalogue")
            )
        }
        return out
    }

    override fun selectBestModel(available: List<ModelInfo>, task: TaskType): String {
        val chosen = when (task) {
            TaskType.CHAT -> chat(available)
            TaskType.VISION -> vision(available)
            TaskType.LIGHTWEIGHT -> lightweight(available)
            TaskType.LONG_CONTEXT -> longContext(available)
            TaskType.AUDIO_LIVE -> throw ModelDiscoveryFailedException(
                providerId,
                UnsupportedOperationException(
                    "Mistral has no realtime audio model; use Gemini or OpenAI for live voice."
                )
            )
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Mistral offers no model for $task")
        )
        return chosen.id
    }

    private fun chat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest")
        ) ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun vision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("pixtral-large-latest", "pixtral-12b-2409"))
            ?.takeIf { it.supportsVision }
            ?: Ranking.firstWhere(available) { it.supportsVision }

    private fun lightweight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("mistral-small-latest", "open-mistral-7b"))
            ?: Ranking.firstWhere(available) { it.supportsChat && it.looksLightweight }
            ?: chat(available)

    private fun longContext(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(available, listOf("mistral-large-latest"))
            ?: Ranking.highestContext(available)

    private companion object {
        const val ENDPOINT = "https://api.mistral.ai/v1/models"
    }
}