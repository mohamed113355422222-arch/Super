package com.supershade.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.supershade.R
import com.supershade.overlay.GestureOverlay
import com.supershade.overlay.ShadeWindowManager
import com.supershade.settings.ShadeSettings
import com.supershade.shizuku.ShizukuPlusConnector
import com.supershade.shizuku.StatusBarGovernor
import com.supershade.viewmodel.ShadeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that is the backbone of SuperShade.
 *
 * Lifecycle:
 *   onCreate  → attaches the gesture overlay (1 px strip below status bar),
 *               calls [StatusBarGovernor.disableExpansion] so the system shade
 *               cannot be pulled down, then calls [ShadeWindowManager.show] in
 *               response to swipe-down gestures detected by [GestureOverlay].
 *   onDestroy → detaches the gesture overlay, hides any open shade window,
 *               re-enables system expansion.
 */
class ShadeService : Service() {

    private val governor: StatusBarGovernor by inject()
    private val connector: ShizukuPlusConnector by inject()
    private val settings: ShadeSettings by inject()
    private val notificationRepo: com.supershade.domain.notification.NotificationRepository by inject()
    private val brightnessRepo: com.supershade.domain.brightness.BrightnessRepository by inject()

    // ShadeViewModel is a Koin singleton — resolved here so the service and the
    // ComposeView overlay share the exact same instance.
    private val shadeViewModel: ShadeViewModel by inject()

    /** Supervisor scope kept alive for the lifetime of the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val windowManager: ShadeWindowManager by inject()
    private val customStatusBarOverlay: com.supershade.overlay.CustomStatusBarOverlay by inject()
    private val customStatusBarRepository: com.supershade.domain.system.CustomStatusBarRepository by inject()
    private val headsUpOverlay: HeadsUpOverlay by inject()
    private var gestureOverlay: GestureOverlay? = null
    private var screenStateReceiver: android.content.BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "supershade_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_OPEN_SHADE = "com.supershade.action.OPEN_SHADE"
        const val EXTRA_EXPAND_QS = "expand_qs"
        @Volatile var isServiceRunning: Boolean = false
            private set
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        var currentSplitMode = com.supershade.settings.SplitGestureMode.SEPARATE_70_30
        settings.splitGestureMode
            .onEach { currentSplitMode = it }
            .launchIn(scope)

        // Pre-warm the Compose shade window so pulling down responds immediately.
        windowManager.prewarm()

        gestureOverlay = GestureOverlay(
            context = this,
            isShadeOpen = {
                shadeViewModel.state.value.isOpen &&
                    !shadeViewModel.isClosing.value &&
                    windowManager.isShowing()
            },
            splitGestureMode = { currentSplitMode },
            onSwipeDown = { expandQs -> shadeViewModel.open(expandQs) },
            onBeginSwipeOpen = { expandQs ->
                customStatusBarOverlay.setSuppressedForShade(true)
                windowManager.beginInteractiveShow(expandQs)
                shadeViewModel.beginInteractiveOpen(expandQs)
            },
            onUpdateSwipeProgress = { progress ->
                shadeViewModel.updateInteractiveOpen(progress)
            },
            onEndSwipeOpen = { shouldOpen, progress, progressVelocity ->
                shadeViewModel.endInteractiveOpen(shouldOpen, progress, progressVelocity)
                windowManager.finishInteractiveShow(shouldOpen)
            },
            onBrightnessSwipe = { brightness ->
                scope.launch(Dispatchers.IO) {
                    brightnessRepo.set(brightness)
                }
            },
        )
        attachGestureAndSyncPolicy()
        registerScreenRecoveryReceiver()

        // Show peek cards for new notifications when the shade panel is closed.
        notificationRepo.newNotifications
            .onEach { notification ->
                if (!shadeViewModel.state.value.isOpen) {
                    headsUpOverlay.show(notification)
                }
            }
            .launchIn(scope)

        // Show ambient media card when track changes and shade is closed
        var lastMediaKey: Pair<String, String>? = null
        combine(shadeViewModel.state.map { it.media }, settings.ambientMediaWidgetEnabled) { media, ambientEnabled ->
            media to ambientEnabled
        }
        .onEach { (media, ambientEnabled) ->
            if (ambientEnabled && media != null && media.isPlaying && !shadeViewModel.state.value.isOpen) {
                val key = media.title to media.artist
                if (key != lastMediaKey && media.title.isNotBlank()) {
                    lastMediaKey = key
                    headsUpOverlay.showMedia(
                        media = media,
                        onPlayPause = { shadeViewModel.mediaPlayPause() },
                        onSkipNext = { shadeViewModel.mediaSkipNext() },
                        onSkipPrevious = { shadeViewModel.mediaSkipPrevious() },
                    )
                }
            } else if (media == null || !media.isPlaying) {
                lastMediaKey = null
            }
        }
        .launchIn(scope)

        // Observe isOpen state from ViewModel so opening shade from any component
        // presents the overlay window and hides the custom status bar to avoid overlap.
        shadeViewModel.state
            .map { it.isOpen }
            .distinctUntilChanged()
            .onEach { isOpen ->
                customStatusBarOverlay.setSuppressedForShade(isOpen)
                if (isOpen) {
                    if (!shadeViewModel.isGestureDragging.value) {
                        windowManager.show()
                    }
                } else {
                    windowManager.hideImmediate()
                }
            }
            .launchIn(scope)

        // Keep custom status bar preferences synced without recreating the window.
        settings.statusBarConfig
            .distinctUntilChanged()
            .onEach { cfg ->
                customStatusBarOverlay.updateConfig(cfg)
                customStatusBarRepository.setClockOptions(
                    showSeconds = cfg.showClockSeconds,
                    clockFormat = cfg.clockFormat,
                )
                customStatusBarRepository.setNotificationIconOptions(
                    showNotificationIcons = cfg.showNotificationIcons,
                    maxNotificationIcons = cfg.maxNotificationIcons,
                    includeSilentNotifications = cfg.includeSilentNotifications,
                )
            }
            .launchIn(scope)

        // Attach the custom iOS-style status bar only when enabled AND Shizuku has hidden
        // the native status bar icons/clock, preventing duplicate icons on screen.
        combine(
            settings.isActive,
            settings.customStatusBarEnabled,
            governor.isSystemStatusBarHidden,
        ) { active, customEnabled, nativeHidden ->
            active && customEnabled && nativeHidden
        }
            .distinctUntilChanged()
            .onEach { shouldShowCustomBar ->
                if (shouldShowCustomBar) {
                    customStatusBarOverlay.attach()
                } else {
                    customStatusBarOverlay.detach()
                }
            }
            .launchIn(scope)

        // Start the Shizuku UserService so tile-click and status-bar commands work.
        governor.bindService()

        // Stop foreground service whenever SuperShade is disabled.
        settings.isActive
            .distinctUntilChanged()
            .onEach { active ->
                if (!active) {
                    stopSelf()
                } else {
                    attachGestureAndSyncPolicy()
                }
            }
            .launchIn(scope)

        // Apply unified system status bar policy (expansion block + native icon/clock hiding).
        combine(
            settings.isActive,
            settings.blockSystemShade,
            settings.customStatusBarEnabled,
        ) { active, block, hideNativeBar ->
            (active && block) to (active && hideNativeBar)
        }
            .distinctUntilChanged()
            .onEach { (shouldBlock, shouldHideNative) ->
                updateTouchCaptureAvailability()
                governor.applyStatusBarPolicy(
                    disableExpansion = shouldBlock,
                    hideSystemStatusBar = shouldHideNative,
                )
            }
            .launchIn(scope)
    }

    private fun attachGestureAndSyncPolicy() {
        windowManager.prewarm()
        gestureOverlay?.attach()
        updateTouchCaptureAvailability()
    }

    private fun updateTouchCaptureAvailability() {
        val available = (gestureOverlay?.isAttached == true) ||
            SuperShadeAccessibilityService.isTouchCaptureAttached
        governor.updateTouchCaptureAvailability(available)
    }

    private fun registerScreenRecoveryReceiver() {
        if (screenStateReceiver != null) return
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        gestureOverlay?.cancelActiveTracking()
                        if (shadeViewModel.state.value.isOpen ||
                            shadeViewModel.isClosing.value ||
                            windowManager.isShowing()
                        ) {
                            shadeViewModel.completeClose()
                            windowManager.hideImmediate()
                        }
                    }
                    else -> {
                        // Heal any stale close/open state or detached overlay after screen sleep/low-RAM trim.
                        if ((shadeViewModel.state.value.isOpen || shadeViewModel.isClosing.value) &&
                            !windowManager.isShowing()
                        ) {
                            shadeViewModel.completeClose()
                        }
                        governor.bindService()
                        attachGestureAndSyncPolicy()
                    }
                }
            }
        }
        screenStateReceiver = receiver
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachGestureAndSyncPolicy()
        if (intent?.action == ACTION_OPEN_SHADE) {
            val expandQs = intent.getBooleanExtra(EXTRA_EXPAND_QS, false)
            shadeViewModel.open(expandQs)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scope.launch {
            val active = try { settings.isActive.first() } catch (_: Exception) { false }
            if (active && android.provider.Settings.canDrawOverlays(this@ShadeService)) {
                attachGestureAndSyncPolicy()
                try {
                    val restartIntent = Intent(applicationContext, ShadeService::class.java)
                    val pending = android.app.PendingIntent.getForegroundService(
                        applicationContext,
                        1002,
                        restartIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                    val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                    alarmManager?.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 800L,
                        pending,
                    )
                } catch (_: Exception) {
                    try {
                        startForegroundService(Intent(applicationContext, ShadeService::class.java))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        customStatusBarOverlay.onConfigurationChanged()
        gestureOverlay?.detach()
        attachGestureAndSyncPolicy()
    }

    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenStateReceiver = null
        customStatusBarOverlay.detach()
        gestureOverlay?.detach()
        gestureOverlay = null
        governor.updateTouchCaptureAvailability(SuperShadeAccessibilityService.isTouchCaptureAttached)
        headsUpOverlay.destroy()
        windowManager.destroy()

        // Re-enable the native system status bar synchronously when accessibility capture isn't active.
        if (!SuperShadeAccessibilityService.isTouchCaptureAttached) {
            governor.restoreSystemStatusBarBlocking()
            governor.unbindService()
            connector.cleanup()
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------
    // Notification channel + foreground notification
    // ---------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SuperShade",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "SuperShade notification shade service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SuperShade active")
            .setContentText("Swipe down to open the shade")
            .setSmallIcon(R.drawable.ic_notification_shade)
            .setOngoing(true)
            .build()
}
