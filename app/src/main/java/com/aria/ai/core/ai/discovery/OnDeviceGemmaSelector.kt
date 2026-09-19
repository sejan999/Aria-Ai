package com.aria.ai.core.ai.discovery

import com.aria.ai.core.ai.AutoModelSelector
import com.aria.ai.core.ai.KeylessSelector
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selector for the bundled on-device Gemma brain.
 *
 * There is nothing to discover: the model ships as a single quantised artefact
 * the user drops on the device, so the catalogue is a constant. This is the
 * final fallback in every selection chain — it needs no key and no network, and
 * is therefore always "available". [KeylessSelector] tells `ModelResolver` that
 * an empty API key is expected rather than an error.
 */
@Singleton
class OnDeviceGemmaSelector @Inject constructor() : AutoModelSelector, KeylessSelector {

    override val providerId: String = ProviderIds.ON_DEVICE_GEMMA

    override suspend fun listAvailableModels(apiKey: String): List<ModelInfo> = CATALOGUE

    override fun selectBestModel(available: List<ModelInfo>, task: TaskType): String = when (task) {
        // The single local model serves every text task.
        TaskType.CHAT, TaskType.LIGHTWEIGHT, TaskType.LONG_CONTEXT, TaskType.VISION -> BUNDLED_ID
        // No realtime audio on-device: callers must use a cloud live provider.
        TaskType.AUDIO_LIVE -> BUNDLED_ID
    }

    companion object {
        const val BUNDLED_ID = "gemma-2b-it-int4"

        /**
         * The one artefact Aria expects on-device. `inputTokenLimit` reflects the
         * model card's context window so LONG_CONTEXT ranking is meaningful.
         */
        val CATALOGUE: List<ModelInfo> = listOf(
            Ranking.model(
                id = BUNDLED_ID,
                providerId = ProviderIds.ON_DEVICE_GEMMA,
                displayName = "Gemma 2B (on-device, int4)",
                inputTokenLimit = 8_192,
                outputTokenLimit = 2_048,
                supportsChat = true,
                supportsVision = false,
                supportsAudioLive = false,
                isFree = true
            )
        )
    }
}