package com.aria.ai.core.ai

/**
 * Thrown when a provider's model catalogue cannot be obtained or parsed, or when
 * the provider cannot serve the requested task at all (for example Anthropic and
 * Mistral expose no realtime audio model).
 *
 * Callers treat this as "fall back to the next provider in the chain".
 */
class ModelDiscoveryFailedException(providerId: String, cause: Throwable? = null) :
    Exception("Failed to discover models for $providerId.", cause)
