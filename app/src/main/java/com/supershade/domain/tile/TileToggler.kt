package com.supershade.domain.tile

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.supershade.service.NotificationCollector
import com.supershade.service.SuperShadeAccessibilityService
import com.supershade.settings.ShadeSettings
import com.supershade.shizuku.StatusBarGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TileToggler(
    private val context: Context,
    private val governor: StatusBarGovernor,
    private val tileRepo: TileRepository? = null,
    private val settings: ShadeSettings? = null,
    private val audioRepo: com.supershade.domain.audio.AudioRepository? = null,
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    @Volatile private var preferredTorchLevel: Int = 3

    /** Invoked when a tile launches a system panel/activity so the overlay shade can collapse. */
    @Volatile var onExternalActivityLaunched: (() -> Unit)? = null

    init {
        settings?.let { s ->
            coroutineScope.launch {
                s.torchStrengthLevel.collect { preferredTorchLevel = it }
            }
        }
    }

    suspend fun toggle(tile: TileDefinition) {
        val canonical = canonicalTileId(tile.id)
        val id = canonical.lowercase()
        val newState = !tile.isActive

        try {
            when {
                // Flashlight: direct CameraManager
                id == "flashlight" -> {
                    tileRepo?.setTileActiveOptimistic(tile.id, newState)
                    toggleFlashlight(newState)
                }

                // Auto-Rotate: Shizuku or direct WRITE_SETTINGS
                id == "rotation" -> {
                    when {
                        governor.canRunPrivileged -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            governor.setAutoRotate(newState)
                        }
                        Settings.System.canWrite(context) -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            try {
                                Settings.System.putInt(
                                    context.contentResolver,
                                    Settings.System.ACCELEROMETER_ROTATION,
                                    if (newState) 1 else 0
                                )
                            } catch (_: Exception) {}
                        }
                        else -> launchWriteSettingsOrSettings(tile)
                    }
                    tileRepo?.reload()
                }

                // Do Not Disturb
                id == "dnd" -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    when {
                        governor.canRunPrivileged -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            governor.setDnd(newState)
                        }
                        nm?.isNotificationPolicyAccessGranted == true -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            val filter = if (newState) {
                                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            } else {
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            }
                            try { nm.setInterruptionFilter(filter) } catch (_: Exception) {}
                        }
                        NotificationCollector.instance != null -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            val filter = if (newState) {
                                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            } else {
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            }
                            try {
                                NotificationCollector.instance?.requestInterruptionFilter(filter)
                            } catch (_: Exception) {
                                openSettings(tile)
                            }
                        }
                        else -> openSettings(tile)
                    }
                }

                // Sound Mode / Mute
                id == "mute" -> {
                    if (audioRepo != null) {
                        audioRepo.cycleRingerMode()
                    } else {
                        val am = context.getSystemService(android.media.AudioManager::class.java)
                        val nm = context.getSystemService(android.app.NotificationManager::class.java)
                        val hasDnd = nm?.isNotificationPolicyAccessGranted == true
                        try {
                            when (am?.ringerMode) {
                                android.media.AudioManager.RINGER_MODE_NORMAL ->
                                    am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                                android.media.AudioManager.RINGER_MODE_VIBRATE ->
                                    am.ringerMode = if (hasDnd) {
                                        android.media.AudioManager.RINGER_MODE_SILENT
                                    } else {
                                        android.media.AudioManager.RINGER_MODE_NORMAL
                                    }
                                else -> am?.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                            }
                        } catch (_: Exception) {
                            openSettings(tile)
                        }
                    }
                    tileRepo?.reload()
                }

                // Volume
                id == "volume" -> {
                    if (!launchFirstAvailable(Settings.Panel.ACTION_VOLUME)) {
                        openSettings(tile)
                    }
                }

                // Battery Saver (single consolidated branch)
                id == "battery" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setBatterySaver(newState)
                    } else {
                        if (!launchFirstAvailable(Settings.ACTION_BATTERY_SAVER_SETTINGS, Intent.ACTION_POWER_USAGE_SUMMARY)) {
                            openSettings(tile)
                        }
                    }
                    tileRepo?.reload()
                }

                // Master Sync
                id == "sync" -> {
                    try {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        android.content.ContentResolver.setMasterSyncAutomatically(newState)
                    } catch (_: Exception) {
                        openSettings(tile)
                    }
                }

                // Wi-Fi
                id == "wifi" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setWifi(newState)
                    } else {
                        var handled = false
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                            try {
                                @Suppress("DEPRECATION")
                                handled = wm?.setWifiEnabled(newState) == true
                                if (handled) tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            } catch (_: Exception) {}
                        }
                        if (!handled) {
                            if (!launchFirstAvailable(Settings.Panel.ACTION_WIFI, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) {
                                openSettings(tile)
                            }
                        }
                    }
                }

                // Mobile Data
                id == "cell" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setData(newState)
                    } else {
                        if (!launchFirstAvailable(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_DATA_ROAMING_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Bluetooth
                id == "bt" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setBluetooth(newState)
                    } else {
                        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val adapter = bm?.adapter
                        var toggledDirectly = false
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null) {
                            try {
                                @Suppress("DEPRECATION")
                                toggledDirectly = if (newState) adapter.enable() else adapter.disable()
                                if (toggledDirectly) tileRepo?.setTileActiveOptimistic(tile.id, newState)
                            } catch (_: Exception) {}
                        }
                        if (!toggledDirectly) {
                            if (newState) {
                                if (!launchFirstAvailable(BluetoothAdapter.ACTION_REQUEST_ENABLE)) {
                                    openSettings(tile)
                                }
                            } else {
                                openSettings(tile)
                            }
                        }
                    }
                }

                // NFC
                id == "nfc" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setNfc(newState)
                    } else {
                        if (!launchFirstAvailable(Settings.Panel.ACTION_NFC)) {
                            openSettings(tile)
                        }
                    }
                }

                // Airplane Mode
                id == "airplane" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setAirplaneMode(newState)
                    } else {
                        if (!launchFirstAvailable(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_AIRPLANE_MODE_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Hotspot / Tethering
                id == "hotspot" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setHotspot(newState)
                    } else {
                        if (!launchFirstAvailable("android.settings.TETHER_SETTINGS")) {
                            openSettings(tile)
                        }
                    }
                }

                // Dark Mode (strictly dark/uimodenight, never night light!)
                id == "dark" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setUiModeNight(newState)
                    } else {
                        openSettings(tile)
                    }
                }

                // Night Light / Eye Comfort Shield
                id == "night" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.putSetting("system", "blue_light_filter", if (newState) "1" else "0")
                        governor.putSetting("secure", "night_display_activated", if (newState) "1" else "0")
                    } else {
                        if (!launchFirstAvailable(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Location
                id == "location" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setLocationEnabled(newState)
                    } else {
                        openSettings(tile)
                    }
                }

                // Extra Dim
                id == "extra_dim" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setExtraDim(newState)
                    } else {
                        if (!launchFirstAvailable("android.settings.REDUCE_BRIGHT_COLORS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Data Saver
                id == "datasaver" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.runShell("cmd", "netpolicy", "set", "restrict-background", if (newState) "true" else "false")
                    } else {
                        if (!launchFirstAvailable("android.settings.DATA_SAVER_SETTINGS", Settings.ACTION_DATA_USAGE_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Quick Share / Nearby Share (Real OS sharing integration)
                id == "quick_share" -> handleQuickShare(tile)

                // Always On Display
                id == "aod" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.setAlwaysOnDisplay(newState)
                    } else {
                        openSettings(tile)
                    }
                }

                // Dolby Atmos / Audio Effects
                id == "dolby" -> {
                    if (governor.canRunPrivileged) {
                        val comp = resolveTileServiceComponent(tile, "dolby") ?: "com.sec.android.app.soundalive/.DolbyTile"
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.clickTile(comp)
                    } else {
                        if (!launchFirstAvailable(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, Settings.ACTION_SOUND_SETTINGS)) {
                            openSettings(tile)
                        }
                    }
                }

                // Caffeinate / Screen Timeout cycle (30s -> 2m -> 5m -> 10m -> 30m -> 30s)
                id == "caffeinate" -> {
                    val currentTimeout = try {
                        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
                    } catch (_: Exception) { 30000 }
                    val nextTimeout = when {
                        currentTimeout < 120_000   -> 120_000
                        currentTimeout < 300_000   -> 300_000
                        currentTimeout < 600_000   -> 600_000
                        currentTimeout < 1_800_000 -> 1_800_000
                        else                       -> 30_000
                    }
                    when {
                        governor.canRunPrivileged -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, nextTimeout > 30_000)
                            governor.putSetting("system", "screen_off_timeout", nextTimeout.toString())
                        }
                        Settings.System.canWrite(context) -> {
                            tileRepo?.setTileActiveOptimistic(tile.id, nextTimeout > 30_000)
                            try {
                                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, nextTimeout)
                            } catch (_: Exception) {}
                        }
                        else -> launchWriteSettingsOrSettings(tile)
                    }
                    tileRepo?.reload()
                }

                // Alarm
                id == "alarm" -> {
                    if (!launchFirstAvailable(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM)) {
                        openSettings(tile)
                    }
                }

                // Privacy sensor toggles (Camera / Mic / Sensors Off)
                id == "cameratoggle" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        val comp = resolveTileServiceComponent(tile, "cameratoggle")
                        if (!governor.runShell("cmd", "sensor_privacy", if (newState) "enable" else "disable", "0", "camera") && comp != null) {
                            governor.clickTile(comp)
                        }
                    } else {
                        openSettings(tile)
                    }
                }
                id == "mictoggle" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        val comp = resolveTileServiceComponent(tile, "mic")
                        if (!governor.runShell("cmd", "sensor_privacy", if (newState) "enable" else "disable", "0", "microphone") && comp != null) {
                            governor.clickTile(comp)
                        }
                    } else {
                        openSettings(tile)
                    }
                }
                id == "sensors_off" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        val comp = resolveTileServiceComponent(tile, "sensorprivacy")
                        if (comp != null) governor.clickTile(comp)
                        else governor.runShell("cmd", "sensor_privacy", if (newState) "enable" else "disable", "0", "camera")
                    } else {
                        openSettings(tile)
                    }
                }

                // Screen Record
                id == "screenrecord" -> {
                    if (governor.canRunPrivileged) {
                        val comp = resolveTileServiceComponent(tile, "screenrecord")
                        if (comp != null) {
                            onExternalActivityLaunched?.invoke()
                            governor.clickTile(comp)
                        } else openSettings(tile)
                    } else {
                        openSettings(tile)
                    }
                }

                // Power Menu
                id == "powermenu" -> {
                    onExternalActivityLaunched?.invoke()
                    delay(120L)
                    val handled = SuperShadeAccessibilityService.instance?.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    ) == true
                    if (!handled && governor.canRunPrivileged) {
                        governor.runShell("input", "keyevent", "--longpress", "26")
                    }
                }

                // Screenshot
                id == "screenshot" -> {
                    onExternalActivityLaunched?.invoke()
                    delay(320L)
                    val a11y = SuperShadeAccessibilityService.instance
                    if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else if (governor.canRunPrivileged) {
                        governor.takeScreenshot()
                    }
                }

                // One-Handed Mode
                id == "onehanded" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.putSetting("secure", "one_handed_mode_enabled", if (newState) "1" else "0")
                    } else {
                        openSettings(tile)
                    }
                }

                // Wallet / Payments
                id == "wallet" -> {
                    if (!launchPackageIfInstalled("com.google.android.apps.walletnfcrel", "com.samsung.android.spay")) {
                        if (governor.canRunPrivileged) {
                            resolveTileServiceComponent(tile, "wallet")?.let {
                                onExternalActivityLaunched?.invoke()
                                governor.clickTile(it)
                                return
                            }
                        }
                        if (!launchFirstAvailable(Settings.ACTION_NFC_PAYMENT_SETTINGS)) openSettings(tile)
                    }
                }

                // QR Scanner
                id == "qr_code_scanner" -> {
                    if (governor.canRunPrivileged) {
                        val comp = resolveTileServiceComponent(tile, "qrcode")
                        if (comp != null) {
                            onExternalActivityLaunched?.invoke()
                            governor.clickTile(comp)
                            return
                        }
                    }
                    if (!launchFirstAvailable(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) {
                        openSettings(tile)
                    }
                }

                // Voice Recorder
                id == "voicenote" -> {
                    if (!launchPackageIfInstalled("com.sec.android.app.voicenote", "com.google.android.apps.recorder")) {
                        if (!launchFirstAvailable(MediaStore.Audio.Media.RECORD_SOUND_ACTION)) {
                            openSettings(tile)
                        }
                    }
                }

                // Smart View / Cast
                id == "smart_view" || id == "cast" -> {
                    if (governor.canRunPrivileged) {
                        val comp = resolveTileServiceComponent(tile, if (id == "smart_view") "smart" else "cast")
                        if (comp != null) {
                            onExternalActivityLaunched?.invoke()
                            governor.clickTile(comp)
                            return
                        }
                    }
                    openSettings(tile)
                }

                // Device Controls / Quick Note / SmartThings / Modes / Other vendor tiles
                id == "controls" -> {
                    if (!launchFirstAvailable("android.settings.ACTION_DEVICE_CONTROLS_SETTINGS", Settings.ACTION_HOME_SETTINGS)) {
                        openSettings(tile)
                    }
                }
                id == "notes" -> {
                    if (!launchFirstAvailable("android.intent.action.CREATE_NOTE")) {
                        openSettings(tile)
                    }
                }
                id == "smartthings" -> {
                    if (!launchPackageIfInstalled("com.samsung.android.oneconnect")) {
                        openSettings(tile)
                    }
                }
                id == "protect_battery" -> {
                    if (governor.canRunPrivileged) {
                        tileRepo?.setTileActiveOptimistic(tile.id, newState)
                        governor.putSetting("global", "protect_battery", if (newState) "1" else "0")
                    } else {
                        openSettings(tile)
                    }
                }

                // Generic privileged tile click (third-party / OEM TileService)
                tile.capability == TileCapability.FULL_TOGGLE && governor.canRunPrivileged -> {
                    val comp = resolveTileServiceComponent(tile, id) ?: tile.componentName ?: tile.id
                    tileRepo?.setTileActiveOptimistic(tile.id, newState)
                    governor.clickTile(comp)
                }

                else -> openSettings(tile)
            }
        } finally {
            delay(250L)
            tileRepo?.refreshActiveStates()
        }
    }

    private suspend fun handleQuickShare(tile: TileDefinition) {
        val pm = context.packageManager

        // 1. Direct Samsung Quick Share / Google Nearby Share UI if resolvable
        val directIntents = listOf(
            Intent("com.google.android.gms.settings.NEARBY_SETTINGS"),
            pm.getLaunchIntentForPackage("com.samsung.android.app.sharelive"),
        ).filterNotNull()
        for (candidate in directIntents) {
            try {
                if (candidate.resolveActivity(pm) != null) {
                    launchExternal(candidate)
                    return
                }
            } catch (_: Exception) {}
        }

        // 2. If Shizuku is connected, trigger resolved Quick Share / Nearby Share QS TileService
        if (governor.canRunPrivileged) {
            val comp = resolveTileServiceComponent(tile, "quickshare", "nearbyshare", "sharelive")
            if (comp != null) {
                tileRepo?.setTileActiveOptimistic(tile.id, !tile.isActive)
                governor.clickTile(comp)
                return
            }
        }

        // 3. Universal Android Share Sheet fallback (features Quick Share / Nearby Share on Android 10-16)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "")
        }
        val chooser = Intent.createChooser(shareIntent, "Quick Share").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            launchExternal(chooser)
        } catch (_: Exception) {
            openSettings(tile)
        }
    }

    private fun resolveTileServiceComponent(tile: TileDefinition, vararg hints: String): String? {
        val pm = context.packageManager
        tile.componentName?.let { raw ->
            try {
                val cn = android.content.ComponentName.unflattenFromString(raw)
                if (cn != null && pm.getServiceInfo(cn, 0) != null) {
                    return cn.flattenToShortString()
                }
            } catch (_: Exception) {}
        }
        return try {
            val services = pm.queryIntentServices(
                Intent("android.service.quicksettings.action.QS_TILE"),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            services.firstOrNull { info ->
                val flat = "${info.serviceInfo.packageName}/${info.serviceInfo.name}".lowercase()
                hints.any { hint -> flat.contains(hint.lowercase()) }
            }?.let { info ->
                android.content.ComponentName(info.serviceInfo.packageName, info.serviceInfo.name).flattenToShortString()
            }
        } catch (_: Exception) { null }
    }

    private fun launchPackageIfInstalled(vararg packages: String): Boolean {
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                return launchExternal(launch)
            } catch (_: Exception) {}
        }
        return false
    }

    private fun launchFirstAvailable(vararg actions: String): Boolean {
        val pm = context.packageManager
        for (action in actions) {
            try {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(pm) != null) {
                    return launchExternal(intent)
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun launchExternal(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            onExternalActivityLaunched?.invoke()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun launchWriteSettingsOrSettings(tile: TileDefinition) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!launchExternal(intent)) {
            openSettings(tile)
        }
    }

    private fun toggleFlashlight(on: Boolean) {
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val chars = cm.getCameraCharacteristics(cameraId)
                val maxStrength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                if (maxStrength > 1) {
                    if (on) {
                        val level = preferredTorchLevel.coerceIn(1, maxStrength)
                        cm.turnOnTorchWithStrengthLevel(cameraId, level)
                    } else {
                        cm.setTorchMode(cameraId, false)
                    }
                    return
                }
            }
            cm.setTorchMode(cameraId, on)
        } catch (_: Exception) {}
    }

    fun setTorchStrength(level: Int) {
        preferredTorchLevel = level
        settings?.let { s ->
            coroutineScope.launch { s.setTorchStrengthLevel(level) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            val chars = cm.getCameraCharacteristics(cameraId)
            val maxStrength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            if (maxStrength > 1) {
                val clamped = level.coerceIn(1, maxStrength)
                cm.turnOnTorchWithStrengthLevel(cameraId, clamped)
            }
        } catch (_: Exception) {}
    }

    fun getTorchMaxStrength(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 1
        return try {
            val cm = context.getSystemService(CameraManager::class.java)
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return 1
            cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (_: Exception) {
            1
        }
    }

    private fun openSettings(tile: TileDefinition) {
        val canonical = canonicalTileId(tile.id).lowercase()
        val action = tile.settingsAction
            ?: TILE_SETTINGS_ACTIONS[canonical]
            ?: when (canonical) {
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "battery"  -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "vpn"      -> Settings.ACTION_VPN_SETTINGS
                "cast"     -> Settings.ACTION_CAST_SETTINGS
                "volume"   -> Settings.ACTION_SOUND_SETTINGS
                else       -> Settings.ACTION_SETTINGS
            }
        if (!launchExternal(Intent(action))) {
            launchExternal(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
