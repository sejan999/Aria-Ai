package com.aria.ai.core.ai.discovery

import com.aria.ai.core.ai.model.ModelInfo

/**
 * Small, shared ranking primitives so all seven discoveries express their
 * preference order the same way: an ordered list of ids, then a pattern filter,
 * then a metric (context window / price).
 */
internal object Ranking {

    /**
     * Finds the best match for [name]:
     *  1. exact id (case-insensitive);
     *  2. id that begins with [name] at a `-` boundary (`gpt-4o` → `gpt-4o-2024-08-06`);
     *  3. id that merely contains [name].
     */
    fun byName(available: List<ModelInfo>, name: String): ModelInfo? {
        val lower = name.lowercase()
        available.firstOrNull { it.id.equals(name, ignoreCase = true) }?.let { return it }
        available.firstOrNull { model ->
            val id = model.id.lowercase()
            id.startsWith(lower) && (id.length == lower.length || id[lower.length] == '-')
        }?.let { return it }
        return available.firstOrNull { it.id.contains(lower, ignoreCase = true) }
    }

    /** First hit walking the ordered preference list. */
    fun firstOf(available: List<ModelInfo>, names: List<String>): ModelInfo? =
        names.firstNotNullOfOrNull { byName(available, it) }

    /** First model satisfying [predicate]. */
    fun firstWhere(
        available: List<ModelInfo>,
        predicate: (ModelInfo) -> Boolean
    ): ModelInfo? = available.firstOrNull(predicate)

    /**
     * First model matching [predicate] while excluding ids matching [exclude].
     * Used to keep "-lite"/"-mini" variants out of CHAT selection.
     */
    fun firstWhereExcluding(
        available: List<ModelInfo>,
        exclude: (ModelInfo) -> Boolean,
        predicate: (ModelInfo) -> Boolean
    ): ModelInfo? = available.firstOrNull { !exclude(it) && predicate(it) }

    /** Model with the largest reported context window. */
    fun highestContext(available: List<ModelInfo>): ModelInfo? =
        available.filter { it.supportsChat }
            .maxByOrNull { it.contextWindow }
            ?: available.maxByOrNull { it.contextWindow }

    /** Model with the smallest reported context window (cheap work). */
    fun smallestContext(available: List<ModelInfo>): ModelInfo? =
        available.filter { it.supportsChat && it.contextWindow > 0 }
            .minByOrNull { it.contextWindow }

    /** Free models first, then the cheapest by context size as a proxy. */
    fun cheapest(available: List<ModelInfo>): ModelInfo? =
        available.filter { it.supportsChat && it.isFree }.minByOrNull { it.contextWindow }
            ?: available.filter { it.supportsChat }.minByOrNull { it.contextWindow }

    /** Builds a [ModelInfo] with sane defaults, deriving displayName from id. */
    fun model(
        id: String,
        providerId: String,
        displayName: String = id,
        inputTokenLimit: Int? = null,
        outputTokenLimit: Int? = null,
        supportsChat: Boolean = true,
        supportsVision: Boolean = false,
        supportsAudioLive: Boolean = false,
        isFree: Boolean = false
    ): ModelInfo = ModelInfo(
        id = id,
        displayName = displayName.ifBlank { id },
        providerId = providerId,
        inputTokenLimit = inputTokenLimit,
        outputTokenLimit = outputTokenLimit,
        supportsChat = supportsChat,
        supportsVision = supportsVision,
        supportsAudioLive = supportsAudioLive,
        isFree = isFree
    )
}