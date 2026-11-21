package com.supershade.viewmodel

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.supershade.domain.brightness.BrightnessRepository
import com.supershade.domain.connectivity.ConnectivityRepository
import com.supershade.domain.media.MediaRepository
import com.supershade.domain.media.MediaState
import com.supershade.domain.notification.NotificationRepository
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.domain.tile.DEFAULT_TILES
import com.supershade.domain.tile.KNOWN_TILES
import com.supershade.domain.tile.TileCapability
import com.supershade.domain.tile.TILE_COMPONENTS
import com.supershade.domain.tile.TILE_SETTINGS_ACTIONS
import com.supershade.domain.tile.TileDefinition
import com.supershade.domain.tile.TileRepository
import com.supershade.domain.tile.TileToggler
import com.supershade.domain.tile.canonicalTileId
import com.supershade.domain.tile.humanizeTileLabel
import com.supershade.settings.ShadeSettings
import com.supershade.shizuku.StatusBarGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ShadeTransitionEvent {
    data class FinishOpen(val progress: Float, val velocity: Float) : ShadeTransitionEvent()
    data class FinishClose(val progress: Float, val velocity: Float) : ShadeTransitionEvent()
}

data class PendingShadeTransition(
    val id: Long,
    val event: ShadeTransitionEvent,
)

class ShadeViewModel(
    private val context: Context,
    private val notificationRepo: NotificationRepository,
    private val tileRepo: TileRepository,
    private val tileToggler: TileToggler,
    private val mediaRepo: MediaRepository,
    private val brightnessRepo: BrightnessRepository,
    private val settings: ShadeSettings,
    private val governor: StatusBarGovernor,
    private val audioRepo: com.supershade.domain.audio.AudioRepository? = null,
    private val systemStatusRepo: com.supershade.domain.system.SystemStatusRepository? = null,
    connectivityRepo: ConnectivityRepository? = null,
) : ViewModel() {

    private val connectivity: ConnectivityRepository =
        connectivityRepo ?: ConnectivityRepository(context, governor)

    private val _state = MutableStateFlow(ShadeState())
    val state: StateFlow<ShadeState> = _state.asStateFlow()

    private val _gestureProgress = MutableStateFlow(0f)
    val gestureProgress: StateFlow<Float> = _gestureProgress.asStateFlow()

    private val _isGestureDragging = MutableStateFlow(false)
    val isGestureDragging: StateFlow<Boolean> = _isGestureDragging.asStateFlow()

    private val _isClosing = MutableStateFlow(false)
    val isClosing: StateFlow<Boolean> = _isClosing.asStateFlow()

    private val _transitionEvent = MutableSharedFlow<ShadeTransitionEvent>(extraBufferCapacity = 8)
    val transitionEvent: SharedFlow<ShadeTransitionEvent> = _transitionEvent.asSharedFlow()

    private val _latestTransition = MutableStateFlow<PendingShadeTransition?>(null)
    val latestTransition: StateFlow<PendingShadeTransition?> = _latestTransition.asStateFlow()

    private var transitionSeq = 0L
    private var positionTickerJob: Job? = null
    private var brightnessJob: Job? = null
    private var connectivityRefreshJob: Job? = null

    private fun emitTransition(event: ShadeTransitionEvent) {
        _transitionEvent.tryEmit(event)
        transitionSeq += 1L
        _latestTransition.value = PendingShadeTransition(transitionSeq, event)
    }

    init {
        tileToggler.onExternalActivityLaunched = {
            closeTileDetail()
            requestAnimatedClose()
        }
        brightnessRepo.brightness
            .onEach { b -> _state.update { it.copy(brightness = b) } }
            .launchIn(viewModelScope)

        systemStatusRepo?.batteryState
            ?.onEach { bs ->
                _state.update { current ->
                    current.copy(
                        statusBar = current.statusBar.copy(
                            batteryPct = bs.levelPct,
                            isCharging = bs.isCharging
                        )
                    )
                }
            }
            ?.launchIn(viewModelScope)

        notificationRepo.notifications
            .onEach { notifications ->
                _state.update { current ->
                    current.copy(
                        allNotifications = notifications,
                        visibleNotifications = filterFor(notifications, current.selectedCategory, current.hiddenChannels, current.hideOngoingNotifications)
                    )
                }
            }
            .launchIn(viewModelScope)

        notificationRepo.dismissedHistory
            .onEach { history -> _state.update { it.copy(dismissedHistory = history) } }
            .launchIn(viewModelScope)

        notificationRepo.pinnedKeys
            .onEach { pins -> _state.update { it.copy(pinnedKeys = pins) } }
            .launchIn(viewModelScope)

        notificationRepo.lastDismissed
            .onEach { last -> _state.update { it.copy(lastDismissedNotification = last) } }
            .launchIn(viewModelScope)

        notificationRepo.snoozedRecords
            .onEach { snoozes -> _state.update { it.copy(snoozedRecords = snoozes) } }
            .launchIn(viewModelScope)

        settings.hiddenChannels
            .onEach { channels ->
                _state.update { current ->
                    current.copy(
                        hiddenChannels = channels,
                        visibleNotifications = filterFor(current.allNotifications, current.selectedCategory, channels, current.hideOngoingNotifications)
                    )
                }
            }
            .launchIn(viewModelScope)

        settings.hideOngoingNotifications
            .onEach { hide ->
                _state.update { current ->
                    current.copy(
                        hideOngoingNotifications = hide,
                        visibleNotifications = filterFor(current.allNotifications, current.selectedCategory, current.hiddenChannels, hide)
                    )
                }
            }
            .launchIn(viewModelScope)

        tileRepo.tiles
            .onEach { tiles -> _state.update { it.copy(tiles = tiles) } }
            .launchIn(viewModelScope)

        combine(mediaRepo.media, notificationRepo.voiceRecorderNotification) { media, voiceNote ->
            if (media != null && media.isPlaying) {
                media
            } else if (voiceNote != null) {
                val isPaused = voiceNote.title.contains("Pause", ignoreCase = true) ||
                    voiceNote.text.contains("Pause", ignoreCase = true) ||
                    voiceNote.actions.any { it.label.contains("Resume", ignoreCase = true) || it.label.contains("Record", ignoreCase = true) }
                val playPauseAction = voiceNote.actions.firstOrNull {
                    it.label.contains("Pause", ignoreCase = true) ||
                        it.label.contains("Resume", ignoreCase = true) ||
                        it.label.contains("Record", ignoreCase = true)
                } ?: voiceNote.actions.firstOrNull()
                val stopAction = voiceNote.actions.firstOrNull {
                    it.label.contains("Stop", ignoreCase = true) ||
                        it.label.contains("Save", ignoreCase = true) ||
                        it.label.contains("Done", ignoreCase = true)
                }

                MediaState(
                    title = voiceNote.title.ifBlank { "Voice Recorder" },
                    artist = voiceNote.text.ifBlank { "Recording" },
                    album = "Voice Note",
                    albumArt = voiceNote.picture,
                    isPlaying = !isPaused,
                    packageName = voiceNote.packageName,
                    isRecording = true,
                    customPlayPauseAction = {
                        try {
                            playPauseAction?.pendingIntent?.send()
                        } catch (_: Exception) {}
                    },
                    customStopAction = {
                        try {
                            stopAction?.pendingIntent?.send()
                        } catch (_: Exception) {}
                    },
                )
            } else {
                media
            }
        }
        .onEach { media ->
            _state.update { it.copy(media = media) }
            updatePositionTicker(media)
        }
        .launchIn(viewModelScope)

        settings.theme
            .onEach { t -> _state.update { it.copy(theme = t) } }
            .launchIn(viewModelScope)

        settings.darkThemeMode
            .onEach { m -> _state.update { it.copy(darkThemeMode = m) } }
            .launchIn(viewModelScope)

        settings.accentColor
            .onEach { a -> _state.update { it.copy(accentColor = a) } }
            .launchIn(viewModelScope)

        settings.tileShape
            .onEach { shape -> _state.update { it.copy(tileShape = shape) } }
            .launchIn(viewModelScope)

        settings.tileSize
            .onEach { size -> _state.update { it.copy(tileSize = size) } }
            .launchIn(viewModelScope)

        settings.tileColumns
            .onEach { cols -> _state.update { it.copy(tileColumns = cols) } }
            .launchIn(viewModelScope)

        settings.showWideCards
            .onEach { show -> _state.update { it.copy(showWideCards = show) } }
            .launchIn(viewModelScope)

        settings.cardBorderWidth
            .onEach { width -> _state.update { it.copy(cardBorderWidth = width) } }
            .launchIn(viewModelScope)

        settings.splitGestureMode
            .onEach { mode -> _state.update { it.copy(splitGestureMode = mode) } }
            .launchIn(viewModelScope)

        settings.showPanelSwitcherPill
            .onEach { show -> _state.update { it.copy(showPanelSwitcherPill = show) } }
            .launchIn(viewModelScope)

        settings.backdropTheme
            .onEach { backdrop -> _state.update { it.copy(backdropTheme = backdrop) } }
            .launchIn(viewModelScope)

        settings.backdropOpacity
            .onEach { opacity -> _state.update { it.copy(backdropOpacity = opacity) } }
            .launchIn(viewModelScope)

        settings.notificationDensity
            .onEach { density -> _state.update { it.copy(notificationDensity = density) } }
            .launchIn(viewModelScope)

        settings.deviceControlMode
            .onEach { mode -> _state.update { it.copy(deviceControlMode = mode) } }
            .launchIn(viewModelScope)

        settings.monetAccentStrength
            .onEach { strength -> _state.update { it.copy(monetAccentStrength = strength) } }
            .launchIn(viewModelScope)

        governor.isCommanderConnected
            .onEach { connected -> _state.update { it.copy(isShizukuConnected = connected) } }
            .launchIn(viewModelScope)

        settings.classificationMode
            .onEach { mode ->
                _state.update { it.copy(classificationMode = mode) }
                notificationRepo.updateClassificationConfig(mode, _state.value.appCategoryOverrides)
                val validCats = com.supershade.settings.categoriesForMode(mode)
                if (validCats.isNotEmpty() && _state.value.selectedCategory !in validCats) {
                    _state.update { it.copy(selectedCategory = ShadeCategory.All) }
                }
            }
            .launchIn(viewModelScope)

        settings.showCategoryBar
            .onEach { show -> _state.update { it.copy(showCategoryBar = show) } }
            .launchIn(viewModelScope)

        settings.appCategoryOverrides
            .onEach { overrides ->
                _state.update { it.copy(appCategoryOverrides = overrides) }
                notificationRepo.updateClassificationConfig(_state.value.classificationMode, overrides)
            }
            .launchIn(viewModelScope)
    }

    private fun updatePositionTicker(media: MediaState?) {
        if (media == null || !media.isPlaying || media.duration <= 0 || !_state.value.isOpen) {
            positionTickerJob?.cancel()
            positionTickerJob = null
            return
        }
        // Always cancel and restart to re-seed position from the latest MediaState
        positionTickerJob?.cancel()
        positionTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                if (!_state.value.isOpen) break
                _state.update { s ->
                    val current = s.media ?: return@update s
                    if (!current.isPlaying) return@update s
                    val newPos = (current.position + 1000L).coerceAtMost(current.duration)
                    s.copy(media = current.copy(position = newPos))
                }
            }
        }
    }

    fun beginInteractiveOpen(expandQs: Boolean = false) {
        _isClosing.value = false
        _isGestureDragging.value = true
        _gestureProgress.value = 0.025f
        _state.update {
            it.copy(
                isOpen = true,
                isQsExpanded = expandQs,
                activePanel = if (expandQs) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val currentBrightness = brightnessRepo.getCurrent()
            _state.update { it.copy(brightness = currentBrightness) }
            mediaRepo.refresh()
            if (_state.value.tiles.isEmpty()) {
                tileRepo.reload()
            } else {
                tileRepo.refreshActiveStates()
            }
            notificationRepo.refresh()
        }
    }

    fun updateInteractiveOpen(progress: Float) {
        _gestureProgress.value = progress.coerceIn(0f, 1.06f)
    }

    fun endInteractiveOpen(
        shouldOpen: Boolean,
        progress: Float,
        progressVelocity: Float,
    ) {
        val clamped = progress.coerceIn(0f, 1.06f)
        _isGestureDragging.value = false
        _isClosing.value = !shouldOpen
        _gestureProgress.value = clamped
        if (shouldOpen) {
            emitTransition(ShadeTransitionEvent.FinishOpen(clamped, progressVelocity))
            updatePositionTicker(_state.value.media)
        } else {
            emitTransition(ShadeTransitionEvent.FinishClose(clamped, progressVelocity))
        }
    }

    fun open(expandQs: Boolean = false) {
        _isClosing.value = false
        _isGestureDragging.value = false
        _gestureProgress.value = 0f
        _state.update {
            it.copy(
                isOpen = true,
                isQsExpanded = expandQs,
                activePanel = if (expandQs) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS,
            )
        }
        emitTransition(ShadeTransitionEvent.FinishOpen(0f, 0f))
        viewModelScope.launch(Dispatchers.IO) {
            val currentBrightness = brightnessRepo.getCurrent()
            _state.update { it.copy(brightness = currentBrightness) }
            mediaRepo.refresh()
            if (_state.value.tiles.isEmpty()) {
                tileRepo.reload()
            } else {
                tileRepo.refreshActiveStates()
            }
            notificationRepo.refresh()
        }
        updatePositionTicker(_state.value.media)
    }

    fun beginClosing() {
        _isGestureDragging.value = false
        _isClosing.value = true
    }

    fun requestAnimatedClose() {
        _isGestureDragging.value = false
        _isClosing.value = true
        val startP = _gestureProgress.value.takeIf { it > 0.01f } ?: 1f
        emitTransition(ShadeTransitionEvent.FinishClose(startP, -2.5f))
    }

    fun completeClose() {
        close()
    }

    fun setQsExpanded(expanded: Boolean) {
        _state.update {
            it.copy(
                isQsExpanded = expanded,
                activePanel = if (expanded) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS,
            )
        }
    }

    fun setActivePanel(panel: ShadePanel) {
        _state.update {
            it.copy(
                activePanel = panel,
                isQsExpanded = (panel == ShadePanel.QUICK_SETTINGS),
            )
        }
    }

    fun close() {
        connectivity.stopNearbyScan()
        connectivityRefreshJob?.cancel()
        _isClosing.value = false
        _isGestureDragging.value = false
        _gestureProgress.value = 0f
        _latestTransition.value = null
        _state.update { it.copy(isOpen = false, isQsExpanded = false, activeTileDetail = null) }
        positionTickerJob?.cancel()
        positionTickerJob = null
        brightnessJob?.cancel()
        brightnessJob = null
    }

    fun selectCategory(category: ShadeCategory) {
        _state.update { current ->
            current.copy(
                selectedCategory = category,
                visibleNotifications = filterFor(
                    current.allNotifications,
                    category,
                    current.hiddenChannels,
                    current.hideOngoingNotifications,
                )
            )
        }
    }

    fun hideNotificationChannel(pkg: String, channelId: String) {
        viewModelScope.launch { settings.hideChannel("$pkg/$channelId") }
    }

    fun unhideNotificationChannel(pkg: String, channelId: String) {
        viewModelScope.launch { settings.unhideChannel("$pkg/$channelId") }
    }

    fun toggleTile(tile: TileDefinition) {
        viewModelScope.launch { tileToggler.toggle(tile) }
    }

    fun moveTile(fromIndex: Int, toIndex: Int) {
        val currentTiles = _state.value.tiles.toMutableList()
        if (fromIndex in currentTiles.indices && toIndex in currentTiles.indices && fromIndex != toIndex) {
            val moved = currentTiles.removeAt(fromIndex)
            currentTiles.add(toIndex, moved)
            _state.update { it.copy(tiles = currentTiles) }
            viewModelScope.launch {
                settings.setEnabledTiles(currentTiles.map { it.id })
            }
        }
    }

    fun removeTile(tileId: String) {
        val canonical = canonicalTileId(tileId)
        val currentTiles = _state.value.tiles.filter { canonicalTileId(it.id) != canonical }
        _state.update { it.copy(tiles = currentTiles) }
        viewModelScope.launch {
            settings.setEnabledTiles(currentTiles.map { canonicalTileId(it.id) })
        }
    }

    fun addTile(tileId: String) {
        val canonical = canonicalTileId(tileId)
        if (_state.value.tiles.any { canonicalTileId(it.id) == canonical }) return
        val (label, capability) = KNOWN_TILES[canonical]
            ?: KNOWN_TILES[tileId]
            ?: (humanizeTileLabel(canonical) to TileCapability.FULL_TOGGLE)
        val newTile = TileDefinition(
            id = canonical,
            label = label,
            isActive = false,
            capability = capability,
            componentName = TILE_COMPONENTS[canonical] ?: TILE_COMPONENTS[tileId],
            settingsAction = TILE_SETTINGS_ACTIONS[canonical] ?: TILE_SETTINGS_ACTIONS[tileId],
            subtitle = null,
        )
        val updated = _state.value.tiles + newTile
        _state.update { it.copy(tiles = updated) }
        viewModelScope.launch {
            settings.setEnabledTiles(updated.map { canonicalTileId(it.id) })
        }
    }

    fun resetTiles() {
        viewModelScope.launch {
            settings.setEnabledTiles(DEFAULT_TILES.map { canonicalTileId(it) }.distinct())
        }
    }

    fun setDeviceControlMode(mode: com.supershade.settings.DeviceControlMode) {
        viewModelScope.launch {
            settings.setDeviceControlMode(mode)
        }
    }

    fun openTileDetail(tile: TileDefinition) {
        val id = tile.id.lowercase()
        when {
            id.contains("flashlight") -> {
                val max = tileToggler.getTorchMaxStrength()
                val current = if (_state.value.activeTileDetail?.type == TileDetailType.FLASHLIGHT) {
                    _state.value.activeTileDetail?.torchLevel ?: (if (tile.isActive) max else 1)
                } else {
                    if (tile.isActive) max else 1
                }
                _state.update {
                    it.copy(
                        activeTileDetail = TileDetailState(
                            type = TileDetailType.FLASHLIGHT,
                            title = "Flashlight",
                            subtitle = if (tile.isActive) "On • Level $current" else "Off",
                            isActive = tile.isActive,
                            torchLevel = current,
                            maxTorchLevel = max,
                            settingsAction = tile.settingsAction,
                        )
                    )
                }
            }
            id.contains("wifi") || id.contains("internet") -> {
                val wm = context.getSystemService(WifiManager::class.java)
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val info = try {
                    @Suppress("DEPRECATION")
                    wm?.connectionInfo
                } catch (_: Exception) { null }
                val rawSsid = info?.ssid?.trim('"')
                val ssid = if (rawSsid == "<unknown ssid>" || rawSsid.isNullOrBlank()) {
                    if (tile.isActive) "Connected Wi-Fi" else "Wi-Fi Disconnected"
                } else rawSsid

                val freq = info?.frequency ?: 0
                val band = when {
                    freq > 5925 -> "6 GHz (Wi-Fi 6E/7)"
                    freq > 4900 -> "5 GHz"
                    freq > 2400 -> "2.4 GHz"
                    else -> if (tile.isActive) "Wi-Fi" else "Disconnected"
                }
                val speed = if (info != null && info.linkSpeed > 0) "${info.linkSpeed} Mbps" else null
                val rssi = info?.rssi ?: -100
                val ip = try {
                    val activeNet = cm?.activeNetwork
                    val linkProps = cm?.getLinkProperties(activeNet)
                    linkProps?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
                } catch (_: Exception) { null }

                _state.update {
                    it.copy(
                        activeTileDetail = TileDetailState(
                            type = TileDetailType.WIFI,
                            title = "Wi-Fi",
                            subtitle = if (tile.isActive) ssid else "Off",
                            isActive = tile.isActive,
                            wifiSsid = ssid,
                            wifiBand = band,
                            wifiIp = ip,
                            wifiLinkSpeed = speed,
                            wifiRssi = rssi,
                            wifiIsScanning = tile.isActive,
                            settingsAction = tile.settingsAction ?: Settings.ACTION_WIFI_SETTINGS,
                        )
                    )
                }
                refreshWifiNetworks(triggerScan = true)
            }
            id.contains("bt") || id.contains("bluetooth") -> {
                val am = context.getSystemService(AudioManager::class.java)
                val audioDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                        }
                    } catch (_: Exception) { null }
                } else null

                val deviceName = audioDevice?.productName?.toString()
                    ?: if (tile.isActive) "Bluetooth Active" else "Bluetooth Off"

                _state.update {
                    it.copy(
                        activeTileDetail = TileDetailState(
                            type = TileDetailType.BLUETOOTH,
                            title = "Bluetooth",
                            subtitle = if (tile.isActive) deviceName else "Off",
                            isActive = tile.isActive,
                            btDeviceName = deviceName,
                            btAudioConnected = audioDevice != null,
                            btIsScanning = tile.isActive,
                            settingsAction = tile.settingsAction ?: Settings.ACTION_BLUETOOTH_SETTINGS,
                        )
                    )
                }
                refreshBluetoothDevices(scanNearby = false)
            }
            id.contains("mute") || id.contains("sound") || id.contains("volume") -> {
                openSoundModeDetail()
            }
            id.contains("dnd") || id.contains("donotdisturb") -> {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                val isDnd = if (nm?.isNotificationPolicyAccessGranted == true) {
                    nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                } else tile.isActive

                _state.update {
                    it.copy(
                        activeTileDetail = TileDetailState(
                            type = TileDetailType.DND,
                            title = "Do Not Disturb",
                            subtitle = if (isDnd) "Active • Calls and alerts muted" else "Off",
                            isActive = isDnd,
                            settingsAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                        )
                    )
                }
            }
            id.contains("hotspot") || id.contains("tether") -> {
                _state.update {
                    it.copy(
                        activeTileDetail = TileDetailState(
                            type = TileDetailType.HOTSPOT,
                            title = "Mobile Hotspot",
                            subtitle = if (tile.isActive) "Hotspot is active" else "Off",
                            isActive = tile.isActive,
                            hotspotSsid = "AndroidAP",
                            hotspotBand = "5 GHz",
                            settingsAction = "android.settings.TETHER_SETTINGS",
                        )
                    )
                }
            }
            else -> {
                tile.settingsAction?.let { action ->
                    try {
                        context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun setRingerModeInDetail(mode: Int) {
        val updated = audioRepo?.setRingerMode(mode) ?: mode
        val modeLabel = when (updated) {
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            AudioManager.RINGER_MODE_SILENT -> "Mute"
            else -> "Sound"
        }
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            if (detail.type == TileDetailType.SOUND_MODE) {
                current.copy(
                    activeTileDetail = detail.copy(
                        ringerMode = updated,
                        subtitle = modeLabel,
                        isActive = updated != AudioManager.RINGER_MODE_SILENT,
                    )
                )
            } else current
        }
        viewModelScope.launch {
            tileRepo.reload()
        }
    }

    fun setStreamVolumeInDetail(stream: Int, volume: Int) {
        audioRepo?.setStreamVolume(stream, volume)
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            if (detail.type == TileDetailType.SOUND_MODE) {
                current.copy(
                    activeTileDetail = when (stream) {
                        AudioManager.STREAM_MUSIC -> detail.copy(mediaVol = volume)
                        AudioManager.STREAM_RING -> detail.copy(ringVol = volume)
                        AudioManager.STREAM_NOTIFICATION -> detail.copy(notifVol = volume)
                        AudioManager.STREAM_SYSTEM -> detail.copy(sysVol = volume)
                        AudioManager.STREAM_ALARM -> detail.copy(alarmVol = volume)
                        else -> detail
                    }
                )
            } else current
        }
    }

    fun toggleDndInDetail() {
        val currentDetail = _state.value.activeTileDetail ?: return
        val newActive = !currentDetail.isActive
        val dndTile = _state.value.tiles.firstOrNull { it.id.lowercase().contains("dnd") }
        if (dndTile != null) {
            toggleTile(dndTile)
        }
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            current.copy(
                activeTileDetail = detail.copy(
                    isActive = newActive,
                    subtitle = if (newActive) "Active • Calls and alerts muted" else "Off"
                )
            )
        }
    }

    fun setDndDuration(minutes: Int) {
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            current.copy(
                activeTileDetail = detail.copy(dndDurationMinutes = minutes)
            )
        }
    }

    fun toggleHotspotInDetail() {
        val currentDetail = _state.value.activeTileDetail ?: return
        val newActive = !currentDetail.isActive
        val hotspotTile = _state.value.tiles.firstOrNull { it.id.lowercase().contains("hotspot") }
        if (hotspotTile != null) {
            toggleTile(hotspotTile)
        }
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            current.copy(
                activeTileDetail = detail.copy(
                    isActive = newActive,
                    subtitle = if (newActive) "Hotspot is active" else "Off"
                )
            )
        }
    }

    fun openSoundModeDetail() {
        val am = context.getSystemService(AudioManager::class.java)
        val currentRinger = audioRepo?.getCurrentRingerMode() ?: (am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL)
        val mediaVol = audioRepo?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
        val mediaMax = audioRepo?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
        val ringVol = audioRepo?.getStreamVolume(AudioManager.STREAM_RING) ?: (am?.getStreamVolume(AudioManager.STREAM_RING) ?: 0)
        val ringMax = audioRepo?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: (am?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 15)
        val notifVol = audioRepo?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) ?: (am?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) ?: 0)
        val notifMax = audioRepo?.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) ?: (am?.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) ?: 15)
        val sysVol = audioRepo?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: (am?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: 0)
        val sysMax = audioRepo?.getStreamMaxVolume(AudioManager.STREAM_SYSTEM) ?: (am?.getStreamMaxVolume(AudioManager.STREAM_SYSTEM) ?: 15)
        val alarmVol = audioRepo?.getStreamVolume(AudioManager.STREAM_ALARM) ?: (am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0)
        val alarmMax = audioRepo?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: (am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 15)

        val modeLabel = when (currentRinger) {
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            AudioManager.RINGER_MODE_SILENT -> "Mute"
            else -> "Sound"
        }

        _state.update {
            it.copy(
                activeTileDetail = TileDetailState(
                    type = TileDetailType.SOUND_MODE,
                    title = "Sound Mode",
                    subtitle = modeLabel,
                    isActive = currentRinger != AudioManager.RINGER_MODE_SILENT,
                    ringerMode = currentRinger,
                    mediaVol = mediaVol,
                    mediaMaxVol = mediaMax,
                    ringVol = ringVol,
                    ringMaxVol = ringMax,
                    notifVol = notifVol,
                    notifMaxVol = notifMax,
                    sysVol = sysVol,
                    sysMaxVol = sysMax,
                    alarmVol = alarmVol,
                    alarmMaxVol = alarmMax,
                    settingsAction = Settings.ACTION_SOUND_SETTINGS,
                )
            )
        }
    }

    fun closeTileDetail() {
        connectivity.stopNearbyScan()
        connectivityRefreshJob?.cancel()
        _state.update { it.copy(activeTileDetail = null) }
    }

    fun refreshWifiNetworks(triggerScan: Boolean = true) {
        connectivityRefreshJob?.cancel()
        connectivityRefreshJob = viewModelScope.launch {
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.WIFI) return@update current
                current.copy(activeTileDetail = detail.copy(wifiIsScanning = true))
            }
            val snap = connectivity.loadWifiSnapshot(triggerScan = triggerScan)
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.WIFI) return@update current
                current.copy(
                    activeTileDetail = detail.copy(
                        isActive = snap.isEnabled,
                        subtitle = if (snap.isEnabled) (snap.currentSsid ?: "On") else "Off",
                        wifiSsid = snap.currentSsid ?: if (snap.isEnabled) "Wi-Fi Enabled" else "Wi-Fi Off",
                        wifiBand = snap.band,
                        wifiIp = snap.ip,
                        wifiLinkSpeed = snap.linkSpeed,
                        wifiRssi = snap.rssi,
                        wifiNetworks = snap.networks,
                        wifiIsScanning = false,
                        wifiNeedsLocationPermission = snap.needsPermission,
                        wifiStatusMessage = snap.statusMessage,
                    )
                )
            }
        }
    }

    fun toggleWifiInDetail() {
        val detail = _state.value.activeTileDetail ?: return
        if (detail.type != TileDetailType.WIFI) return
        val newActive = !detail.isActive
        viewModelScope.launch {
            val res = connectivity.toggleWifi(newActive)
            if (res.launchedExternalPanel) {
                closeTileDetail()
                requestAnimatedClose()
            } else {
                _state.update { current ->
                    val d = current.activeTileDetail ?: return@update current
                    current.copy(
                        activeTileDetail = d.copy(
                            isActive = newActive,
                            subtitle = if (newActive) d.wifiSsid else "Off",
                            wifiStatusMessage = res.message,
                        )
                    )
                }
                delay(400L)
                tileRepo.refreshActiveStates()
                refreshWifiNetworks(triggerScan = newActive)
            }
        }
    }

    fun connectWifiInDetail(network: WifiNetworkItem, passphrase: String? = null) {
        viewModelScope.launch {
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.WIFI) return@update current
                current.copy(
                    activeTileDetail = detail.copy(
                        wifiIsScanning = true,
                        wifiStatusMessage = "Connecting to ${network.ssid}...",
                    )
                )
            }
            val res = connectivity.connectToWifi(
                ssid = network.ssid,
                passphrase = passphrase,
                isOpen = !network.isSecured,
                securityLabel = network.security,
                isSaved = network.isSaved,
            )
            if (res.launchedExternalPanel) {
                closeTileDetail()
                requestAnimatedClose()
            } else {
                _state.update { current ->
                    val detail = current.activeTileDetail ?: return@update current
                    if (detail.type != TileDetailType.WIFI) return@update current
                    current.copy(
                        activeTileDetail = detail.copy(
                            wifiIsScanning = false,
                            wifiStatusMessage = res.message,
                        )
                    )
                }
                delay(1200L)
                tileRepo.refreshActiveStates()
                refreshWifiNetworks(triggerScan = false)
            }
        }
    }

    fun openWifiPanelFromDetail() {
        if (connectivity.launchWifiPanel()) {
            closeTileDetail()
            requestAnimatedClose()
        }
    }

    fun openWifiPermissionsFromDetail() {
        if (connectivity.openAppPermissionsSettings()) {
            closeTileDetail()
            requestAnimatedClose()
        }
    }

    fun refreshBluetoothDevices(scanNearby: Boolean = false) {
        connectivityRefreshJob?.cancel()
        connectivityRefreshJob = viewModelScope.launch {
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.BLUETOOTH) return@update current
                current.copy(activeTileDetail = detail.copy(btIsScanning = true))
            }
            val snap = connectivity.loadBluetoothSnapshot()
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.BLUETOOTH) return@update current
                current.copy(
                    activeTileDetail = detail.copy(
                        isActive = snap.isEnabled,
                        subtitle = if (snap.isEnabled) (snap.activeDeviceName ?: "On") else "Off",
                        btDeviceName = snap.activeDeviceName ?: if (snap.isEnabled) "Bluetooth On" else "Bluetooth Off",
                        btAudioConnected = snap.isAudioConnected,
                        btDevices = snap.pairedDevices,
                        btIsScanning = scanNearby && snap.isEnabled,
                        btNeedsPermission = snap.needsPermission,
                        btStatusMessage = snap.statusMessage,
                    )
                )
            }
            if (scanNearby && snap.isEnabled && !snap.needsPermission) {
                val started = connectivity.startNearbyScan { nearby ->
                    _state.update { current ->
                        val detail = current.activeTileDetail ?: return@update current
                        if (detail.type != TileDetailType.BLUETOOTH) return@update current
                        current.copy(activeTileDetail = detail.copy(btNearbyDevices = nearby))
                    }
                }
                if (started) {
                    delay(8000L)
                    connectivity.stopNearbyScan()
                }
                _state.update { current ->
                    val detail = current.activeTileDetail ?: return@update current
                    if (detail.type != TileDetailType.BLUETOOTH) return@update current
                    current.copy(activeTileDetail = detail.copy(btIsScanning = false))
                }
            }
        }
    }

    fun toggleBluetoothInDetail() {
        val detail = _state.value.activeTileDetail ?: return
        if (detail.type != TileDetailType.BLUETOOTH) return
        val newActive = !detail.isActive
        viewModelScope.launch {
            val res = connectivity.toggleBluetooth(newActive)
            if (res.launchedExternalPanel) {
                closeTileDetail()
                requestAnimatedClose()
            } else {
                _state.update { current ->
                    val d = current.activeTileDetail ?: return@update current
                    current.copy(
                        activeTileDetail = d.copy(
                            isActive = newActive,
                            subtitle = if (newActive) "On" else "Off",
                            btStatusMessage = res.message,
                        )
                    )
                }
                delay(450L)
                tileRepo.refreshActiveStates()
                refreshBluetoothDevices(scanNearby = false)
            }
        }
    }

    fun connectBluetoothDeviceInDetail(device: BluetoothDeviceItem) {
        viewModelScope.launch {
            _state.update { current ->
                val detail = current.activeTileDetail ?: return@update current
                if (detail.type != TileDetailType.BLUETOOTH) return@update current
                current.copy(
                    activeTileDetail = detail.copy(
                        btStatusMessage = if (device.isConnected) "Disconnecting ${device.name}..."
                        else "Connecting ${device.name}..."
                    )
                )
            }
            val res = connectivity.toggleDeviceConnection(device)
            if (res.launchedExternalPanel) {
                closeTileDetail()
                requestAnimatedClose()
            } else {
                _state.update { current ->
                    val detail = current.activeTileDetail ?: return@update current
                    if (detail.type != TileDetailType.BLUETOOTH) return@update current
                    current.copy(activeTileDetail = detail.copy(btStatusMessage = res.message))
                }
                delay(800L)
                tileRepo.refreshActiveStates()
                refreshBluetoothDevices(scanNearby = false)
            }
        }
    }

    fun openBluetoothPairFromDetail() {
        if (connectivity.launchBluetoothPairScreen()) {
            closeTileDetail()
            requestAnimatedClose()
        }
    }

    fun openMediaOutputFromDetail() {
        if (connectivity.launchMediaOutputPanel() || connectivity.launchBluetoothSettings()) {
            closeTileDetail()
            requestAnimatedClose()
        }
    }

    fun openBluetoothPermissionsFromDetail() {
        if (connectivity.openAppPermissionsSettings()) {
            closeTileDetail()
            requestAnimatedClose()
        }
    }

    fun setTorchStrength(level: Int) {
        tileToggler.setTorchStrength(level)
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            if (detail.type == TileDetailType.FLASHLIGHT) {
                current.copy(
                    activeTileDetail = detail.copy(
                        torchLevel = level,
                        subtitle = if (detail.isActive) "On • Level $level" else "Off"
                    )
                )
            } else current
        }
    }

    fun toggleTorchInDetail() {
        val currentDetail = _state.value.activeTileDetail ?: return
        val newActive = !currentDetail.isActive
        val flashlightTile = _state.value.tiles.firstOrNull { it.id.lowercase().contains("flashlight") }
        if (flashlightTile != null) {
            toggleTile(flashlightTile)
        }
        if (newActive) {
            tileToggler.setTorchStrength(currentDetail.torchLevel)
        }
        _state.update { current ->
            val detail = current.activeTileDetail ?: return@update current
            current.copy(
                activeTileDetail = detail.copy(
                    isActive = newActive,
                    subtitle = if (newActive) "On • Level ${detail.torchLevel}" else "Off"
                )
            )
        }
    }

    fun dismissNotification(key: String) {
        notificationRepo.cancelAndRemove(key)
    }

    fun dismissNotifications(keys: List<String>) {
        if (keys.isEmpty()) return
        notificationRepo.cancelGroup(keys)
    }

    fun clearAllNotifications() {
        notificationRepo.cancelAll()
    }

    fun togglePinNotification(key: String) {
        notificationRepo.togglePin(key)
    }

    fun undoDismissNotification() {
        notificationRepo.undoLastDismiss()
    }

    fun clearUndoNotification() {
        notificationRepo.clearLastDismissed()
    }

    fun unsnoozeNotification(key: String) {
        notificationRepo.unsnooze(key)
    }

    fun snoozeNotification(key: String, delayMs: Long) {
        notificationRepo.snooze(key, delayMs)
        viewModelScope.launch {
            delay(delayMs)
            notificationRepo.refresh()
        }
    }

    fun launchNotification(notification: com.supershade.domain.notification.model.ShadeNotification) {
        notificationRepo.recordLaunch(notification)
        try {
            notification.contentIntent?.send()
        } catch (_: Exception) {}
        close()
    }

    fun recordReply(notification: com.supershade.domain.notification.model.ShadeNotification) {
        notificationRepo.recordReply(notification)
    }

    // --- Media transport controls ---

    fun mediaPlayPause() {
        val current = state.value.media
        if (current?.customPlayPauseAction != null) {
            current.customPlayPauseAction.invoke()
        } else {
            if (current?.isPlaying == true) mediaRepo.pause() else mediaRepo.play()
        }
    }

    fun mediaStop() {
        state.value.media?.customStopAction?.invoke()
    }

    fun mediaSkipNext() { mediaRepo.skipNext() }

    fun mediaSkipPrevious() { mediaRepo.skipPrevious() }

    fun mediaSeek(positionMs: Long) {
        mediaRepo.seekTo(positionMs)
        // Immediately reflect the seek in the UI so the thumb snaps to the
        // new position without waiting for the next MediaController callback.
        _state.update { s ->
            s.copy(media = s.media?.copy(position = positionMs))
        }
    }

    // --- Brightness ---

    fun setBrightness(value: Int) {
        _state.update { it.copy(brightness = value) }
        brightnessJob?.cancel()
        brightnessJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            brightnessRepo.set(value)
        }
    }

    // --- Audio & Volume ---

    fun setMusicVolume(value: Int) {
        audioRepo?.setMusicVolume(value)
    }

    fun cycleRingerMode(): Int {
        val next = audioRepo?.cycleRingerMode() ?: 0
        tileRepo.reload()
        return next
    }

    // --- Notification History ---

    fun openHistorySheet() {
        _state.update { it.copy(isHistorySheetOpen = true) }
    }

    fun closeHistorySheet() {
        _state.update { it.copy(isHistorySheetOpen = false) }
    }

    fun clearDismissedHistory() {
        notificationRepo.clearDismissedHistory()
    }

    // --- Power & Security actions ---

    fun lockScreen() {
        viewModelScope.launch {
            close()
            val handled = com.supershade.service.SuperShadeAccessibilityService.instance?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            ) == true
            if (!handled) {
                governor.runShell("input", "keyevent", "26")
            }
        }
    }

    fun restartDevice() {
        viewModelScope.launch {
            close()
            governor.reboot()
        }
    }

    fun powerOffDevice() {
        viewModelScope.launch {
            close()
            governor.shutdown()
        }
    }

    fun restartSystemUI() {
        viewModelScope.launch {
            close()
            governor.restartSystemUI()
        }
    }

    fun rebootRecovery() {
        viewModelScope.launch {
            close()
            governor.reboot("recovery")
        }
    }

    fun rebootBootloader() {
        viewModelScope.launch {
            close()
            governor.reboot("bootloader")
        }
    }

    fun openSystemPowerDialog() {
        viewModelScope.launch {
            close()
            val handled = com.supershade.service.SuperShadeAccessibilityService.instance?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            ) == true
            if (!handled) {
                governor.runShell("input", "keyevent", "--longpress", "26")
            }
        }
    }

    fun unhideNotificationChannel(channelKey: String) {
        viewModelScope.launch {
            settings.unhideChannel(channelKey)
        }
    }

    fun setHideOngoingNotifications(hide: Boolean) {
        viewModelScope.launch {
            settings.setHideOngoingNotifications(hide)
        }
    }

    fun takeScreenshot() {
        close()
        viewModelScope.launch {
            kotlinx.coroutines.delay(350L)
            val a11y = com.supershade.service.SuperShadeAccessibilityService.instance
            if (a11y != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else if (governor.canRunPrivileged) {
                governor.runShell("input", "keyevent", "120")
            }
        }
    }

    fun setClassificationMode(mode: com.supershade.settings.ClassificationMode) {
        viewModelScope.launch { settings.setClassificationMode(mode) }
    }

    fun setShowCategoryBar(show: Boolean) {
        viewModelScope.launch { settings.setShowCategoryBar(show) }
    }

    fun setAppCategoryOverride(packageName: String, category: ShadeCategory?) {
        viewModelScope.launch { settings.setAppCategoryOverride(packageName, category) }
    }

    fun clearAppCategoryOverrides() {
        viewModelScope.launch { settings.clearAppCategoryOverrides() }
    }

    // --- Lifecycle ---

    override fun onCleared() {
        super.onCleared()
        positionTickerJob?.cancel()
        mediaRepo.dispose()
    }

    // ---------------------------------------------------------------------------

    private fun filterFor(
        notifications: List<com.supershade.domain.notification.model.ShadeNotification>,
        category: ShadeCategory,
        hiddenChannels: Set<String> = _state.value.hiddenChannels,
        hideOngoing: Boolean = _state.value.hideOngoingNotifications,
    ): List<com.supershade.domain.notification.model.ShadeNotification> {
        val byOngoing = if (hideOngoing) notifications.filter { !it.isOngoing } else notifications
        val byCategory = if (category == ShadeCategory.All) byOngoing
            else byOngoing.filter { it.category == category }
        if (hiddenChannels.isEmpty()) return byCategory
        return byCategory.filter { n ->
            val channelKey = "${n.packageName}/${n.channelId.orEmpty()}"
            val pkgKey = n.packageName
            channelKey !in hiddenChannels && pkgKey !in hiddenChannels
        }
    }
}
