package com.aria.ai.core.ai

/**
 * Thrown by adapters when a provider is invoked without a configured key.
 *
 * The message is deliberately user-facing so the Home screen can surface an
 * actionable hint instead of a stack trace. It NEVER contains key material.
 */
class MissingApiKeyException(
    val providerId: String,
    message: String? = null
) : RuntimeException(
    message ?: "No API key stored for '$providerId'. Open Settings → AI Providers, paste a key and tap Save."
) {
    companion object {
        fun forProvider(providerId: String, displayName: String): MissingApiKeyException =
            MissingApiKeyException(
                providerId,
                "Aria needs a $displayName API key first. Open Settings → AI Providers and save one."
            )
    }
}

/**
 * Thrown when an on-device model (e.g. Gemma via MediaPipe) is requested but the
 * model file has not been placed on the device yet.
 */
class ModelNotAvailableException(message: String) : RuntimeException(message)

/**
 * Thrown when a provider answers with a non-2xx status. [payload] is already
 * redacted by KeyRedactor before this exception is constructed.
 */
class AiHttpException(
    val statusCode: Int,
    payload: String
) : RuntimeException("HTTP $statusCode: $payload")