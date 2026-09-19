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
 * Model discovery for OpenRouter (`GET /api/v1/models`).
 *
 * This catalogue is the richest of the seven: each entry carries
 * `context_length`, `pricing{prompt,completion}` and
 * `architecture{input_modalities,output_modalities}`, so capability detection
 * and free-tier ranking are exact rather than inferred from the id.
 *
 * The endpoint is readable without a key; when a key is present it is sent so
 * account-scoped availability is reflected. OpenRouter exposes no realtime
 * audio API, so [TaskType.AUDIO_LIVE] raises [ModelDiscoveryFailedException].
 */
@Singleton
class OpenRouterModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.OPENROUTER

    override suspend fun listAvailableModels(apiKey: String): List<ModelInfo> {
        val headers = if (apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf(
                "Authorization" to "Bearer $apiKey",
                "HTTP-Referer" to SITE_URL,
                "X-Title" to SITE_TITLE
            )
        }
        val body = DiscoveryHttp.getJson(
            client = client,
            providerId = providerId,
            url = ENDPOINT,
            headers = headers,
            optionalAuth = true
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
            IllegalStateException("OpenRouter response carried no 'data' array")
        )

        val out = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val id = entry.optString("id", "")
            if (id.isBlank()) continue

            val pricing = entry.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "0").orEmpty()
            val completionPrice = pricing?.optString("completion", "0").orEmpty()
            val isFree = promptPrice.trim().let { it == "0" || it == "0.0" || it == "0.00" } &&
                completionPrice.trim().let { it == "0" || it == "0.0" || it == "0.00" }

            val modalities = entry.optJSONObject("architecture")
                ?.optJSONArray("input_modalities")
            val inputs = HashSet<String>()
            if (modalities != null) {
                for (m in 0 until modalities.length()) {
                    inputs += modalities.optString(m, "").lowercase()
                }
            }
            val hasImage = inputs.contains("image") || id.lowercase().contains("vision")

            out += Ranking.model(
                id = id,
                providerId = providerId,
                displayName = entry.optString("name", id),
                inputTokenLimit = entry.optInt("context_length", 0).takeIf { it > 0 },
                supportsChat = true,
                supportsVision = hasImage,
                supportsAudioLive = false,
                isFree = isFree
            )
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("OpenRouter returned an empty model catalogue")
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
                    "OpenRouter has no realtime audio model; use Gemini or OpenAI for live voice."
                )
            )
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("OpenRouter offers no model for $task")
        )
        return chosen.id
    }

    private fun chat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf(
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemini-2.5-flash:free",
                "deepseek/deepseek-chat:free"
            )
        ) ?: Ranking.firstWhere(available) { it.isFree }
            ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun vision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.supportsVision && it.isFree }
            ?: Ranking.firstWhere(available) { it.supportsVision }
            ?: chat(available)

    private fun lightweight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.isFree && it.contextWindow in 1..40_000 }
            ?: Ranking.firstWhere(available) { it.isFree }
            ?: Ranking.smallestContext(available)
            ?: chat(available)

    private fun longContext(available: List<ModelInfo>): ModelInfo? =
        Ranking.highestContext(available)

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/models"
        const val SITE_URL = "https://github.com/sejan999/Aria-Ai"
        const val SITE_TITLE = "Aria Ai"
    }
}