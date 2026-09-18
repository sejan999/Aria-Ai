package com.aria.ai.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating quantum HUD.
 *
 * A lightweight, dependency-free overlay built from plain platform views (no
 * Compose, no XML layout) that the foreground service adds through
 * [WindowManager] as a `TYPE_APPLICATION_OVERLAY` window — the same window type
 * used by chat heads, which requires SYSTEM_ALERT_WINDOW.
 *
 * The controller is defensive by design: without the overlay permission,
 * [show] returns false and the foreground notification remains the only UI, so
 * Aria keeps working even when the user denied the "display over other apps"
 * grant.
 */
class OverlayViewController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** True when the user granted "display over other apps". */
    fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** Adds the HUD, or refreshes it when it is already on screen. */
    fun show(status: String): Boolean {
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay permission missing; HUD not shown")
            return false
        }
        val manager = windowManager ?: return false
        if (root != null) {
            update(status)
            return true
        }

        val view = buildView(status)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }

        return runCatching {
            manager.addView(view, layoutParams)
            root = view
            params = layoutParams
            true
        }.getOrElse { failure ->
            Log.e(TAG, "HUD could not be attached (${failure.javaClass.simpleName})")
            false
        }
    }

    /** Replaces the status line ("Listening…", "Thinking…", …). */
    fun update(status: String) {
        val view = statusView ?: return
        view.post { view.text = status.take(MAX_STATUS_CHARS) }
    }

    /** Removes the HUD from the screen. */
    fun hide() {
        val manager = windowManager
        val view = root
        root = null
        statusView = null
        params = null
        if (manager == null || view == null) return
        runCatching { manager.removeView(view) }
    }

    fun isShowing(): Boolean = root != null

    private fun buildView(status: String): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                colors = intArrayOf(SURFACE_START, SURFACE_END)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(dp(1), STROKE_COLOR)
            }
        }

        val dot = TextView(context).apply {
            text = "◉"
            setTextColor(NEON_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(8), 0)
        }

        val label = TextView(context).apply {
            text = status.take(MAX_STATUS_CHARS)
            setTextColor(MIST_WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }

        container.addView(dot)
        container.addView(label)
        statusView = label
        return container
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private companion object {
        const val TAG = "AriaOverlayHud"
        const val MAX_STATUS_CHARS = 90
        const val NEON_CYAN = 0xFF35E1F0.toInt()
        const val MIST_WHITE = 0xFFEAF6F8.toInt()
        const val STROKE_COLOR = 0x5535E1F0
        val SURFACE_START: Int = Color.argb(232, 10, 16, 28)
        val SURFACE_END: Int = Color.argb(232, 18, 10, 34)
    }
}