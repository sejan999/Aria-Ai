package com.aria.ai.core.ai.discovery

import com.aria.ai.core.ai.AiHttpException
import com.aria.ai.core.ai.InvalidApiKeyException
import com.aria.ai.core.ai.KeyRedactor
import com.aria.ai.core.ai.ModelDiscoveryFailedException
import com.aria.ai.core.ai.PermissionDeniedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Shared GET plumbing for every `ListModels` call.
 *
 * Centralising it means the 401/403/5xx → exception mapping, the key-redaction
 * rule and the IO dispatcher are defined exactly once for all seven providers.
 */
internal object DiscoveryHttp {

    /**
     * Performs an authenticated GET and returns the raw JSON body.
     *
     * @param providerId used to build typed, user-safe exceptions.
     * @param optionalAuth when true a 401/403 is tolerated (OpenRouter's public
     *   catalogue works without a key).
     */
    suspend fun getJson(
        client: OkHttpClient,
        providerId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        optionalAuth: Boolean = false
    ): String {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Accept", "application/json")
            .get()
            .build()

        val response = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute()
            } catch (timeout: SocketTimeoutException) {
                throw ModelDiscoveryFailedException(providerId, timeout)
            } catch (io: IOException) {
                throw ModelDiscoveryFailedException(
                    providerId,
                    IOException(KeyRedactor.scrub(io.message ?: "network failure"), io)
                )
            }
        }

        response.use { resp ->
            val body = withContext(Dispatchers.IO) {
                runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            }
            if (resp.isSuccessful) return body

            when (resp.code) {
                401 -> if (optionalAuth) return body else throw InvalidApiKeyException(providerId)
                403 -> if (optionalAuth) return body else throw PermissionDeniedException(
                    providerId,
                    KeyRedactor.scrub(body.take(200)).ifBlank { "the key is not permitted to list models" }
                )
                else -> throw ModelDiscoveryFailedException(
                    providerId,
                    AiHttpException(resp.code, KeyRedactor.scrub(body.take(300)))
                )
            }
        }
    }
}
