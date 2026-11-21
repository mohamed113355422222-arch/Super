package com.supershade.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.supershade.domain.notification.NotificationRepository
import org.koin.android.ext.android.inject

/**
 * Listens for posted/removed notifications from the system and forwards them
 * to [NotificationRepository], which owns the authoritative in-memory list.
 *
 * The service must be enabled by the user via Settings → Notification Access.
 * Once connected, [onListenerConnected] replays all currently active notifications
 * so the shade starts fully populated.
 */
class NotificationCollector : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotificationCollector? = null
            private set
    }

    private val repository: NotificationRepository by inject()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val rankingMap = try { currentRanking } catch (_: Exception) { null }
        repository.onNotificationPosted(sbn, rankingMap)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        repository.onNotificationPosted(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        repository.onNotificationRemoved(sbn.key)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        repository.canceller = { key ->
            try {
                cancelNotification(key)
            } catch (_: Exception) {}
        }
        repository.batchCanceller = { keys ->
            try {
                if (keys.isNotEmpty()) {
                    cancelNotifications(keys.toTypedArray())
                }
            } catch (_: Exception) {}
        }
        repository.snoozer = { key, durationMs ->
            try {
                snoozeNotification(key, durationMs)
            } catch (_: Exception) {}
        }
        repository.clearAller = {
            try {
                cancelAllNotifications()
            } catch (_: Exception) {}
        }
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        repository.canceller = null
        repository.batchCanceller = null
        repository.snoozer = null
        repository.clearAller = null
    }

    fun refreshNotifications() {
        try {
            val current = activeNotifications ?: return
            val rankingMap = try { currentRanking } catch (_: Exception) { null }
            current.forEach { repository.onNotificationPosted(it, rankingMap) }
        } catch (_: Exception) {}
    }
}
