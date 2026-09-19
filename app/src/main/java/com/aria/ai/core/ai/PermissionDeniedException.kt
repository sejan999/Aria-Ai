package com.aria.ai.core.ai

/**
 * Thrown when the key is valid but the account may not use the endpoint
 * (HTTP 403), e.g. a restricted key or an unentitled model catalogue.
 */
class PermissionDeniedException(providerId: String, reason: String) :
    Exception("Permission denied for $providerId: $reason")
