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
 * Model discovery for Anthropic (`GET /v1/models`).
 *
 * Auth is `x-api-key` plus the mandatory `anthropic-version` header. The payload
 * is `data[]` of `{id, display_name, created_at}`.
 *
 * Anthropic exposes no realtime audio API, so [TaskType.AUDIO_LIVE] is
 * explicitly unsupported and raises [ModelDiscoveryFailedException] so the
 * caller falls through to the next provider in the chain.
 */
@Singleton
class AnthropicModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.ANTHROPIC

    override suspend fun listAvailableModels(apiKey: String): List<ModelInfo> {
        val body = DiscoveryHttp.getJson(
            client = client,
            providerId = providerId,
            url = ENDPOINT,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION
            )
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
            IllegalStateException("Anthropic response carried no 'data' array")
        )

        val out = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val id = entry.optString("id", "")
            if (id.isBlank()) continue
            val lower = id.lowercase()
            out += Ranking.model(
                id = id,
                providerId = providerId,
                displayName = entry.optString("display_name", id),
                // Every Claude 3+ model carries a 200k window.
                inputTokenLimit = if (lower.contains("claude-2")) 100_000 else 200_000,
                outputTokenLimit = 8_192,
                supportsChat = true,
                // Multimodal from Claude 3 onward, including every Claude 4 model.
                supportsVision = lower.contains("claude-3") || lower.contains("claude-sonnet-4") ||
                    lower.contains("claude-opus-4") || lower.contains("claude-haiku-4"),
                supportsAudioLive = false,
                isFree = false
            )
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("Anthropic returned an empty model catalogue")
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
                    "Anthropic has no realtime audio model; use Gemini or OpenAI for live voice."
                )
            )
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Anthropic offers no model for $task")
        )
        return chosen.id
    }

    private fun chat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.id.lowercase().contains("claude-sonnet-4") }
            ?: Ranking.firstWhere(available) { it.id.lowercase().contains("claude-3-5-sonnet") }
            ?: Ranking.firstWhere(available) { it.id.lowercase().contains("claude-3-opus") }
            ?: Ranking.firstWhere(available) { it.id.lowercase().contains("claude-3-sonnet") }
            ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun vision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.supportsVision && it.id.lowercase().contains("claude-sonnet-4") }
            ?: Ranking.firstWhere(available) { it.supportsVision && it.id.lowercase().contains("claude-3-5-sonnet") }
            ?: Ranking.firstWhere(available) { it.supportsVision && it.id.lowercase().contains("claude-3-opus") }
            ?: Ranking.firstWhere(available) { it.supportsVision }
            ?: chat(available)

    private fun lightweight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.id.lowercase().contains("claude-3-5-haiku") }
            ?: Ranking.firstWhere(available) { it.id.lowercase().contains("claude-3-haiku") }
            ?: Ranking.firstWhere(available) { it.id.lowercase().contains("haiku") }
            ?: chat(available)

    private fun longContext(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhere(available) { it.id.lowercase().contains("claude-sonnet-4") }
            ?: Ranking.highestContext(available)

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/models"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}