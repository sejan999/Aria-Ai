package com.aria.ai.core.ai

/**
 * Thrown when a provider rejects the stored credential (HTTP 401 / invalid key).
 *
 * The message is user-facing and NEVER contains key material.
 */
class InvalidApiKeyException(providerId: String) :
    Exception("Invalid API key for $providerId. Please check Settings → Providers.")
