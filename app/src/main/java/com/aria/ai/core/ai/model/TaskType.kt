package com.aria.ai.core.ai.model

/**
 * What Aria is about to do with a model. Selection is per task type so a single
 * provider can serve a fast chat turn, a vision request and a realtime voice
 * session with three different models — all chosen automatically.
 */
enum class TaskType {
    /** Ordinary text conversation. */
    CHAT,

    /** Image / screen understanding. */
    VISION,

    /** Full-duplex realtime audio (Gemini Live, OpenAI Realtime). */
    AUDIO_LIVE,

    /** Cheap, low-latency calls: digests, classification, extraction. */
    LIGHTWEIGHT,

    /** Large documents, long transcripts, big context windows. */
    LONG_CONTEXT;

    /** Stable key used for the per-(provider, task) selection cache. */
    fun cacheKey(providerId: String): String = "$providerId:$name"
}