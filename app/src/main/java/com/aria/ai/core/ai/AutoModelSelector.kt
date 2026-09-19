package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType

/**
 * Automatic model discovery + selection for one provider.
 *
 * Implementations call the provider's ListModels endpoint and then rank the
 * result for a given [TaskType]. They must never invent model ids: everything
 * returned by [selectBestModel] has to be present in the list produced by
 * [listAvailableModels].
 */
interface AutoModelSelector {

    /** Provider id this selector serves (matches `ProviderIds`). */
    val providerId: String

    /**
     * Fetches every model the supplied key can use.
     *
     * @throws InvalidApiKeyException on HTTP 401.
     * @throws PermissionDeniedException on HTTP 403.
     * @throws ModelDiscoveryFailedException on 5xx, timeout or parse failure.
     */
    suspend fun listAvailableModels(apiKey: String): List<ModelInfo>

    /**
     * Picks the best model for [task] from an already-discovered list.
     * Throws [ModelDiscoveryFailedException] when the provider cannot serve the
     * task at all (e.g. Anthropic has no realtime audio API).
     */
    fun selectBestModel(available: List<ModelInfo>, task: TaskType): String
}