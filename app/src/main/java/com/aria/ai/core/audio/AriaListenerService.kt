package com.aria.ai.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aria.ai.MainActivity
import com.aria.ai.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground microphone service for the always-listening feature.
 *
 * The service runs [WakeWordDetector] (TFLite phrase spotter) and, on a wake
 * event, opens a full-duplex Gemini Live session via [AudioBridge.startStreaming].
 * On Android 14+ the service must be started with
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] while the manifest carries
 * `android:foregroundServiceType="microphone"`.
 */
@AndroidEntryPoint
class AriaListenerService : Service() {

    companion object {
        private const val CHANNEL_ID = "aria_listener_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.aria.ai.ACTION_STOP_LISTENING"
        const val ACTION_START = "com.aria.ai.ACTION_START_LISTENING"
    }

    @Inject
    lateinit var wakeWordDetector: ListenerWakeWordDetector

    @Inject
    lateinit var audioBridge: AudioBridge

    private var streaming = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeWordDetector.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithType()
        startListening()
        return START_STICKY
    }

    private fun startListening() {
        wakeWordDetector.start {
            if (!streaming) {
                streaming = audioBridge.startStreaming()
                if (streaming) {
                    updateNotification(
                        getString(R.string.listener_active_title),
                        getString(R.string.listener_active_text)
                    )
                }
            }
        }
        updateNotification(
            getString(R.string.listener_title),
            getString(R.string.listener_text)
        )
    }

    private fun startForegroundWithType() {
        val notification = buildNotification(
            getString(R.string.listener_title),
            getString(R.string.listener_text)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_aria)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .addAction(0, getString(R.string.listener_stop), stopPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun stopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, AriaListenerService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.listener_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.listener_channel_description) }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        wakeWordDetector.stop()
        audioBridge.stopStreaming()
        streaming = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
