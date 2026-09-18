package com.aria.ai.core.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One captured notification, trimmed to what the NotificationAgent needs. */
data class AriaNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isOngoing: Boolean
) {
    fun summary(): String = buildString {
        append(appGuess()).append(": ")
        if (title.isNotBlank()) append(title).append(" — ")
        append(text.ifBlank { "(no text)" })
    }

    private fun appGuess(): String =
        packageName.substringAfterLast('.')
            .replaceFirstChar { it.uppercaseChar() }
}

/**
 * Notification listener that feeds the notification agent.
 *
 * Access is strictly opt-in through the system "Notification access" screen, and
 * Aria keeps only a rolling window of the most recent items in memory —
 * notifications are never persisted and never leave the device unless the user
 * explicitly asks Aria about them.
 */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        snapshot()
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val item = sbn?.let { capture(it) } ?: return
        _items.value = (listOf(item) + _items.value.filterNot { it.key == item.key })
            .take(MAX_RETAINED)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val key = sbn?.key ?: return
        _items.value = _items.value.filterNot { it.key == key }
    }

    private fun snapshot() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        _items.value = active.mapNotNull { capture(it) }
            .sortedByDescending { it.postedAt }
            .take(MAX_RETAINED)
    }

    private fun capture(sbn: StatusBarNotification): AriaNotification? {
        if (sbn.packageName == packageName) return null
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        // Skip group summaries: they carry no readable body of their own.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: ""
        if (title.isBlank() && text.isBlank()) return null

        return AriaNotification(
            key = sbn.key.orEmpty(),
            packageName = sbn.packageName.orEmpty(),
            title = title.take(120),
            text = text.take(240),
            postedAt = sbn.postTime,
            isOngoing = sbn.isOngoing
        )
    }

    companion object {
        const val MAX_RETAINED = 40

        private val _connected = MutableStateFlow(false)

        /** True when the user has granted notification access and the service is bound. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _items = MutableStateFlow<List<AriaNotification>>(emptyList())

        /** Most recent notifications, newest first. */
        val items: StateFlow<List<AriaNotification>> = _items.asStateFlow()

        fun recent(limit: Int = 10): List<AriaNotification> = _items.value.take(limit)

        fun unreadCount(): Int = _items.value.count { !it.isOngoing }

        fun clear() {
            _items.value = emptyList()
            Log.i("AriaNotifications", "Notification buffer cleared")
        }
    }
}