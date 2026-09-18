package com.aria.ai.core.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility surface for the automation agent.
 *
 * Enables only global navigation gestures the user explicitly requests (home,
 * back, recents, lock screen, notification shade, swipes). It inspects no screen
 * content: `canRetrieveWindowContent` is declared purely so the system delivers
 * window-change events that keep [connected] accurate.
 */
class AriaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected = true
        Log.i(TAG, "Automation surface connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected = false
        super.onDestroy()
    }

    /** Home / Back / Recents / Notifications / Lock screen. */
    fun perform(action: Action): Boolean {
        val globalAction = when (action) {
            Action.HOME -> GLOBAL_ACTION_HOME
            Action.BACK -> GLOBAL_ACTION_BACK
            Action.RECENTS -> GLOBAL_ACTION_RECENTS
            Action.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Action.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            Action.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                GLOBAL_ACTION_LOCK_SCREEN
            } else {
                GLOBAL_ACTION_HOME
            }
        }
        return runCatching { performGlobalAction(globalAction) }.getOrDefault(false)
    }

    /** Straight-line swipe, expressed in screen fractions (0f..1f). */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(startX * metrics.widthPixels, startY * metrics.heightPixels)
            lineTo(endX * metrics.widthPixels, endY * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 2_000L)))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    enum class Action { HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN }

    companion object {
        private const val TAG = "AriaAccessibility"

        @Volatile
        private var instance: AriaAccessibilityService? = null

        @Volatile
        private var _connected = false

        /** True when the user has enabled Aria in Accessibility settings. */
        val connected: Boolean get() = _connected && instance != null

        fun perform(action: Action): Boolean = instance?.perform(action) ?: false

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean =
            instance?.swipe(startX, startY, endX, endY, durationMs) ?: false
    }
}