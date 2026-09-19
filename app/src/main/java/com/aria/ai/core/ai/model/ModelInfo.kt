package com.aria.ai.core.ai.model

/**
 * One model as advertised by a provider's ListModels endpoint.
 *
 * This is the neutral shape every provider discovery parses into, so the
 * selector logic and the Settings UI never need to know a provider's wire
 * format. Instances are cheap value objects and safe to cache.
 */
data class ModelInfo(
    /** Provider-specific model id, e.g. `gemini-3.8-flash`, `gpt-4o`. */
    val id: String,
    /** Human-readable name for display; falls back to [id] when absent. */
    val displayName: String,
    /** Owning provider id (matches `ProviderIds`). */
    val providerId: String,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportsChat: Boolean = true,
    val supportsVision: Boolean = false,
    /** True when the model can drive a full-duplex Live/realtime audio session. */
    val supportsAudioLive: Boolean = false,
    val supportsStreaming: Boolean = true,
    /** OpenRouter-style zero-cost models. */
    val isFree: Boolean = false
) {
    /** Total context window when the provider reports an input limit. */
    val contextWindow: Int get() = inputTokenLimit ?: 0

    /** True when the id looks like a small/cheap model, used as a tie-breaker. */
    val looksLightweight: Boolean
        get() {
            val lower = id.lowercase()
            return lower.contains("lite") ||
                lower.contains("mini") ||
                lower.contains("instant") ||
                lower.contains("8b") ||
                lower.contains("haiku") ||
                lower.contains("small")
        }

    /** True when the id advertises vision capability by naming convention. */
    val looksVisionCapable: Boolean
        get() {
            val lower = id.lowercase()
            return lower.contains("vision") ||
                lower.contains("pixtral") ||
                lower.contains("gpt-4o") ||
                lower.contains("gpt-4-turbo") ||
                lower.contains("claude-3") ||
                lower.contains("claude-sonnet-4") ||
                lower.contains("gemini")
        }
}