package com.aria.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aria.ai.ui.navigation.AriaNavGraph
import com.aria.ai.ui.theme.AriaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose host for Aria Ai.
 *
 * Everything (home HUD, provider vault, settings) is a Compose destination in
 * [AriaNavGraph]; the activity itself stays a thin shell so the voice pipeline
 * always runs against Hilt-injected singletons.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            AriaTheme {
                AriaNavGraph()
            }
        }
    }

    /**
     * Android 13+ requires the POST_NOTIFICATIONS runtime grant before the
     * always-listening foreground service can post its persistent notification.
     * The request is fire-and-forget: denying it never blocks the app, it only
     * hides the listener notification.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}