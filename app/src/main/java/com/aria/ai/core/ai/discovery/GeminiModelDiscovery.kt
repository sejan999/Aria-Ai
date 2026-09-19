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
 * Model discovery for the Gemini Generative Language API.
 *
 * `GET /v1beta/models` returns `models[]` carrying `name`, `displayName`,
 * `supportedGenerationMethods`, `inputTokenLimit` and `outputTokenLimit`, which
 * is enough to classify chat, vision and realtime audio without any local
 * model list. The key travels as the `key` query parameter per the Gemini
 * contract; `NetworkModule`'s interceptor never logs query strings, so it
 * cannot leak into logcat.
 */
@Singleton
class GeminiModelDiscovery @Inject constructor(
    private val client: OkHttpClient
) : AutoModelSelector {

    override val providerId: String = ProviderIds.GEMINI

    override suspend fun listAvailableModels(apiKey: String): List<ModelInfo> {
        val body = DiscoveryHttp.getJson(
            client = client,
            providerId = providerId,
            url = "$ENDPOINT?key=$apiKey"
        )
        return parse(body)
    }

    /** Pure parser, separated so a parse failure maps to a typed exception. */
    internal fun parse(body: String): List<ModelInfo> {
        val models = try {
            JSONObject(body).optJSONArray("models")
        } catch (t: Throwable) {
            throw ModelDiscoveryFailedException(providerId, t)
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Gemini response carried no 'models' array")
        )

        val out = ArrayList<ModelInfo>(models.length())
        for (index in 0 until models.length()) {
            val entry = models.optJSONObject(index) ?: continue
            // The API returns "models/gemini-3.8-flash"; callers want the bare id.
            val rawName = entry.optString("name", "").removePrefix("models/")
            if (rawName.isBlank()) continue

            val methods = entry.optJSONArray("supportedGenerationMethods")
            val supported = HashSet<String>()
            if (methods != null) {
                for (m in 0 until methods.length()) {
                    supported += methods.optString(m, "").lowercase()
                }
            }

            val canGenerate = supported.isEmpty() || supported.contains("generatecontent")
            val canLive = supported.contains("bidigeneratecontent")
            val lower = rawName.lowercase()

            out += Ranking.model(
                id = rawName,
                providerId = providerId,
                displayName = entry.optString("displayName", rawName),
                inputTokenLimit = entry.optInt("inputTokenLimit", 0).takeIf { it > 0 },
                outputTokenLimit = entry.optInt("outputTokenLimit", 0).takeIf { it > 0 },
                supportsChat = canGenerate,
                // Flash/Pro families are multimodal on the Gemini API.
                supportsVision = canGenerate && (lower.contains("flash") || lower.contains("pro")),
                supportsAudioLive = canLive,
                isFree = false
            )
        }
        if (out.isEmpty()) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("Gemini returned an empty model catalogue")
            )
        }
        return out
    }

    override fun selectBestModel(available: List<ModelInfo>, task: TaskType): String {
        val candidate = when (task) {
            TaskType.CHAT -> preferredChat(available)
            TaskType.VISION -> preferredVision(available)
            TaskType.AUDIO_LIVE -> preferredLive(available)
            TaskType.LIGHTWEIGHT -> preferredLight(available)
            TaskType.LONG_CONTEXT -> Ranking.highestContext(available)
        } ?: throw ModelDiscoveryFailedException(
            providerId,
            IllegalStateException("Gemini offers no model for $task")
        )
        // Guard: never hand back an id the provider did not advertise.
        if (available.none { it.id == candidate.id }) {
            throw ModelDiscoveryFailedException(
                providerId,
                IllegalStateException("Selected model '${candidate.id}' is not in the catalogue")
            )
        }
        return candidate.id
    }

    private fun isLite(model: ModelInfo): Boolean {
        val lower = model.id.lowercase()
        return lower.contains("-lite")
    }

    private fun preferredChat(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhereExcluding(available, exclude = ::isLite) {
            it.supportsChat && it.id.lowercase().contains("flash")
        }
            ?: Ranking.firstWhereExcluding(available, exclude = ::isLite) { it.supportsChat }
            ?: Ranking.firstWhere(available) { it.supportsChat }

    private fun preferredVision(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstWhereExcluding(available, exclude = ::isLite) { it.supportsVision }
            ?: preferredChat(available)

    private fun preferredLive(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf(
                "gemini-3.8-live",
                "gemini-3.5-live",
                "gemini-3.1-flash-live-preview",
                "gemini-2.5-flash-native-audio-preview",
                "gemini-2.5-flash-preview-native-audio",
                "gemini-live"
            )
        )?.takeIf { it.supportsAudioLive }
            ?: Ranking.firstWhere(available) { it.supportsAudioLive }

    private fun preferredLight(available: List<ModelInfo>): ModelInfo? =
        Ranking.firstOf(
            available,
            listOf(
                "gemini-3.5-flash-lite",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash-lite"
            )
        ) ?: Ranking.firstWhere(available) { it.looksLightweight && it.supportsChat }
            ?: preferredChat(available)

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}