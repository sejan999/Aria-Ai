package com.aria.ai.core.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.aria.ai.MainActivity
import com.aria.ai.R

/**
 * Foreground service that owns the floating quantum HUD and keeps the voice
 * session alive while Aria is backgrounded.
 *
 * The foreground service type is chosen at runtime: microphone is only claimed
 * when RECORD_AUDIO is actually granted, otherwise Aria starts as a
 * media-playback service (which never throws on Android 14).
 */
class AriaOverlayFGS : Service() {

    private var controller: OverlayViewController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        controller = OverlayViewController(this).also { hud ->
            if (!hud.show(getString(R.string.overlay_notification_text))) {
                hud.hide()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_STATUS)?.let { status -> controller?.update(status) }
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.hide()
        controller = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundTypes())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundTypes(): Int {
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (micGranted) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_stat_aria)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "aria_overlay_channel"
        const val NOTIFICATION_ID = 4242
        const val EXTRA_STATUS = "extra_status"

        /** Starts (or re-targets) the overlay HUD service. */
        fun start(context: Context, status: String = "Aria • online") {
            val intent = Intent(context, AriaOverlayFGS::class.java).putExtra(EXTRA_STATUS, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Pushes a new status line into the already-running HUD. */
        fun update(context: Context, status: String) {
            val intent = Intent(context, AriaOverlayFGS::class.java).putExtra(EXTRA_STATUS, status)
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AriaOverlayFGS::class.java)) }
        }
    }
}