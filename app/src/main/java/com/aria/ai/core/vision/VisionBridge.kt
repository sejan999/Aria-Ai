package com.aria.ai.core.vision

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the current screen into a vision payload.
 *
 * Frames are downscaled and JPEG-encoded on a background dispatcher so a single
 * capture costs a few tens of kilobytes instead of a full-resolution bitmap. The
 * base64 result is handed straight to whichever vision-capable adapter is active.
 */
@Singleton
class VisionBridge @Inject constructor(
    private val capture: ScreenCaptureManager
) {

    val isArmed: Boolean get() = capture.active.value

    /** Captures the screen as a base64 JPEG, or null when capture is not armed. */
    suspend fun captureBase64(
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        quality: Int = DEFAULT_QUALITY
    ): String? = withContext(Dispatchers.Default) {
        val frame = capture.captureFrame() ?: return@withContext null
        val prepared = frame.scaled(maxDimension)

        val stream = ByteArrayOutputStream()
        val encoded = runCatching {
            if (prepared === null) {
                frame.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            } else {
                prepared.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
        }.getOrDefault(false)

        if (prepared != null) prepared.recycle()
        frame.recycle()

        if (!encoded) null else Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** Human-readable state for the HUD / settings screens. */
    fun describe(): String =
        if (isArmed) "Screen vision armed" else "Screen vision off — tap the eye to grant capture"

    fun release() = capture.release()

    private fun Bitmap.scaled(maxDimension: Int): Bitmap? {
        val largest = maxOf(width, height)
        if (largest <= maxDimension) return null
        val ratio = maxDimension.toFloat() / largest
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private companion object {
        const val DEFAULT_MAX_DIMENSION = 1024
        const val DEFAULT_QUALITY = 60
    }
}