package com.aria.ai.core.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screen capture through the official MediaProjection API.
 *
 * The user approves the capture dialog once (launched from the Home screen); Aria
 * then mirrors the display into a virtual display backed by an [ImageReader] and
 * grabs single frames on demand for the VisionAgent. Nothing is recorded, nothing
 * is stored: a frame lives only long enough to become one JPEG payload.
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
        }
    }

    /** Intent that triggers the system screen-capture consent dialog. */
    fun requestIntent(): Intent? =
        context.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()

    /** Call from the Activity result callback once the user approves capture. */
    fun onProjectionResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "Screen capture consent denied")
            return false
        }
        release()

        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return false
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .getOrNull() ?: return false

        val metrics = realDisplayMetrics()
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        val display = runCatching {
            mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        }.getOrNull()

        if (display == null) {
            runCatching { mediaProjection.stop() }
            runCatching { reader.close() }
            return false
        }

        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mediaProjection
        virtualDisplay = display
        imageReader = reader
        _active.value = true
        Log.i(TAG, "Screen capture armed (${metrics.widthPixels}x${metrics.heightPixels})")
        return true
    }

    /** Grabs the newest frame as an ARGB bitmap, or null when nothing is ready yet. */
    suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            ?: return@withContext null
        image.use { frame -> frame.toBitmap() }
    }

    fun release() {
        _active.value = false
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val wide = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(buffer)

        if (width == wide.width) return wide
        val cropped = Bitmap.createBitmap(wide, 0, 0, width, height)
        wide.recycle()
        return cropped
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private companion object {
        const val TAG = "AriaScreenCapture"
        const val VIRTUAL_DISPLAY_NAME = "aria-vision"
        const val MAX_IMAGES = 4
    }
}