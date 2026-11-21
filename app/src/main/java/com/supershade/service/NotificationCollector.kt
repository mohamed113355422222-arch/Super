package com.supershade.service

import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.supershade.domain.notification.NotificationRepository
import com.supershade.settings.ShadeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()
    }

    private val repository: NotificationRepository by inject()
    private val settings: ShadeSettings by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        _isConnectedFlow.value = true
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
        ensureShadeServiceActive()
    }

    private fun ensureShadeServiceActive() {
        if (ShadeService.isServiceRunning) return
        if (!Settings.canDrawOverlays(this)) return
        scope.launch {
            try {
                if (settings.isActive.first() &&
                    !ShadeService.isServiceRunning &&
                    Settings.canDrawOverlays(this@NotificationCollector)
                ) {
                    val intent = Intent(this@NotificationCollector, ShadeService::class.java)
                    try {
                        startForegroundService(intent)
                    } catch (_: Exception) {
                        try { startService(intent) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        _isConnectedFlow.value = false
        repository.canceller = null
        repository.batchCanceller = null
        repository.snoozer = null
        repository.clearAller = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    fun refreshNotifications() {
        try {
            val current = activeNotifications ?: return
            val rankingMap = try { currentRanking } catch (_: Exception) { null }
            repository.syncActiveNotifications(current.toList(), rankingMap)
        } catch (_: Exception) {}
    }
}
