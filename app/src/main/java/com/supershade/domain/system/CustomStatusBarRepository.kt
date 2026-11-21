package com.supershade.domain.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.supershade.domain.notification.NotificationRepository
import com.supershade.domain.notification.model.ShadeNotification
import com.supershade.ui.statusbar.ClockFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StatusBarNotificationIcon(
    val key: String,
    val packageName: String,
    val bitmap: ImageBitmap? = null,
    val appName: String = "",
)

data class CustomStatusBarState(
    val timeText: String = "",
    val batteryPct: Int = 100,
    val isCharging: Boolean = false,
    val isPowerSave: Boolean = false,
    val isWifiConnected: Boolean = false,
    val wifiLevel: Int = 0, // 0..3
    val isCellularConnected: Boolean = false,
    val hasCellularService: Boolean = false,
    val cellularLevel: Int = 0, // 0..4
    val cellularTypeLabel: String = "", // "5G", "LTE", "4G", "3G", "E", ""
    val isAirplaneMode: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isBluetoothConnected: Boolean = false,
    val isDndActive: Boolean = false,
    val isSilent: Boolean = false,
    val isVibrate: Boolean = false,
    val hasAlarm: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val notificationIcons: List<StatusBarNotificationIcon> = emptyList(),
    val hasOverflowNotifications: Boolean = false,
)

/**
 * Event-driven system telemetry provider for the integrated iOS-style status bar.
 *
 * Uses system broadcasts, ConnectivityManager NetworkCallbacks, and TelephonyCallback
 * with zero polling when clock seconds are disabled. When seconds are enabled, runs a
 * boundary-aligned 1-second ticker only while visible collectors are active and the
 * screen is interactive.
 *
 * Inspired by status bar telemetry patterns in Corniche (MIT License).
 */
class CustomStatusBarRepository(
    private val context: Context,
    systemStatusRepo: SystemStatusRepository,
    private val notificationRepo: NotificationRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile private var showSeconds: Boolean = false
    @Volatile private var clockFormat: ClockFormat = ClockFormat.SYSTEM
    @Volatile private var isScreenInteractive: Boolean = true
    @Volatile private var serviceInServiceKnown: Boolean? = null
    @Volatile private var lastSignalRssiFallback: Int? = null

    private val timeFlow = MutableStateFlow(formatCurrentTime())
    private val wifiFlow = MutableStateFlow(WifiSnapshot(false, 0))
    private val cellularFlow = MutableStateFlow(CellularSnapshot(false, false, 0, ""))
    private val systemFlagsFlow = MutableStateFlow(readSystemFlags())
    private val notificationOptionsFlow = MutableStateFlow(NotificationIconOptions())
    private val iconCache = LruCache<String, ImageBitmap>(24)
    private val appNameCache = LruCache<String, String>(32)

    @Volatile private var activeCollectorCount = 0
    private val lock = Any()

    private var broadcastReceiver: BroadcastReceiver? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null
    private var secondsTickerJob: Job? = null

    private val systemTelemetryFlow = combine(
        timeFlow,
        systemStatusRepo.batteryState,
        wifiFlow,
        cellularFlow,
        systemFlagsFlow,
    ) { time, battery, wifi, cell, flags ->
        CustomStatusBarState(
            timeText = time,
            batteryPct = battery.levelPct,
            isCharging = battery.isCharging,
            isPowerSave = battery.isPowerSave,
            isWifiConnected = wifi.connected,
            wifiLevel = wifi.level,
            isCellularConnected = cell.dataConnected,
            hasCellularService = cell.hasService,
            cellularLevel = cell.level,
            cellularTypeLabel = cell.typeLabel,
            isAirplaneMode = flags.airplaneMode,
            isBluetoothEnabled = flags.bluetoothEnabled,
            isBluetoothConnected = flags.bluetoothConnected,
            isDndActive = flags.dndActive,
            isSilent = flags.silent,
            isVibrate = flags.vibrate,
            hasAlarm = flags.hasAlarm,
            isLocationEnabled = flags.locationEnabled,
        )
    }

    private val notificationDisplayFlow = combine(
        notificationRepo?.notifications ?: flowOf(emptyList()),
        notificationOptionsFlow,
    ) { notifications, options ->
        buildNotificationIconSnapshot(notifications, options)
    }

    val statusBarState: StateFlow<CustomStatusBarState> = combine(
        systemTelemetryFlow,
        notificationDisplayFlow,
    ) { telemetry, notifSnap ->
        telemetry.copy(
            notificationIcons = notifSnap.icons,
            hasOverflowNotifications = notifSnap.hasOverflow,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000L),
        initialValue = CustomStatusBarState(timeText = formatCurrentTime()),
    )

    fun hasReadPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun setClockOptions(showSeconds: Boolean, clockFormat: ClockFormat) {
        val changed = this.showSeconds != showSeconds || this.clockFormat != clockFormat
        this.showSeconds = showSeconds
        this.clockFormat = clockFormat
        if (changed) {
            timeFlow.value = formatCurrentTime()
            synchronized(lock) {
                updateSecondsTickerLocked()
            }
        }
    }

    fun setNotificationIconOptions(
        showNotificationIcons: Boolean,
        maxNotificationIcons: Int,
        includeSilentNotifications: Boolean,
    ) {
        val next = NotificationIconOptions(
            show = showNotificationIcons,
            maxIcons = maxNotificationIcons.coerceIn(1, 6),
            includeSilent = includeSilentNotifications,
        )
        if (notificationOptionsFlow.value != next) {
            notificationOptionsFlow.value = next
        }
    }

    fun startListening() {
        synchronized(lock) {
            activeCollectorCount++
            isScreenInteractive = try { powerManager?.isInteractive != false } catch (_: Exception) { true }
            if (activeCollectorCount > 1) {
                refreshSnapshot()
                updateSecondsTickerLocked()
                return
            }
            registerAll()
            refreshSnapshot()
            updateSecondsTickerLocked()
        }
    }

    fun stopListening() {
        synchronized(lock) {
            activeCollectorCount = (activeCollectorCount - 1).coerceAtLeast(0)
            if (activeCollectorCount == 0) {
                secondsTickerJob?.cancel()
                secondsTickerJob = null
                unregisterAll()
            }
        }
    }

    fun refreshSnapshot() {
        timeFlow.value = formatCurrentTime()
        systemFlagsFlow.value = readSystemFlags()
        refreshWifiState(explicitRssi = null)
        refreshCellularFallback()
    }

    private fun updateSecondsTickerLocked() {
        val shouldTick = showSeconds && activeCollectorCount > 0 && isScreenInteractive
        if (!shouldTick) {
            secondsTickerJob?.cancel()
            secondsTickerJob = null
            return
        }
        if (secondsTickerJob?.isActive == true) return
        secondsTickerJob = scope.launch {
            while (isActive && showSeconds && activeCollectorCount > 0 && isScreenInteractive) {
                timeFlow.value = formatCurrentTime()
                val now = System.currentTimeMillis()
                val waitMs = (1000L - (now % 1000L) + 12L).coerceIn(50L, 1050L)
                delay(waitMs)
            }
        }
    }

    private fun registerAll() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        timeFlow.value = formatCurrentTime()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenInteractive = true
                        timeFlow.value = formatCurrentTime()
                        synchronized(lock) { updateSecondsTickerLocked() }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenInteractive = false
                        synchronized(lock) { updateSecondsTickerLocked() }
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        refreshWifiState(explicitRssi = null)
                    }
                    WifiManager.RSSI_CHANGED_ACTION -> {
                        val rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE)
                        if (rssi != Int.MIN_VALUE && rssi > -127) {
                            lastSignalRssiFallback = rssi
                        }
                        refreshWifiState(explicitRssi = if (rssi != Int.MIN_VALUE && rssi > -127) rssi else null)
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED,
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
                    AudioManager.RINGER_MODE_CHANGED_ACTION,
                    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                    LocationManager.MODE_CHANGED_ACTION -> {
                        systemFlagsFlow.value = readSystemFlags()
                        if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                            refreshCellularFallback()
                        }
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            broadcastReceiver = receiver
        } catch (_: Exception) {}

        registerNetworkCallbacks()
        registerTelephonyListeners()
    }

    private fun unregisterAll() {
        broadcastReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        broadcastReceiver = null

        wifiCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        wifiCallback = null

        cellularCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        cellularCallback = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { cb ->
                try { telephonyManager?.unregisterTelephonyCallback(cb) } catch (_: Exception) {}
            }
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneListener?.let { listener ->
                try { telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
            }
        }
        telephonyCallback = null
        legacyPhoneListener = null
    }

    private fun registerNetworkCallbacks() {
        val cm = connectivityManager ?: return

        val wifiCb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) =
                    refreshWifiState(explicitRssi = null, forceConnected = true)
                override fun onLost(network: Network) =
                    refreshWifiState(explicitRssi = null, lostNetwork = network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo
                    val infoRssi = info?.rssi?.takeIf { it > -127 }
                    val capRssi = caps.signalStrength.takeIf {
                        it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it > -127
                    }
                    val rssi = infoRssi ?: capRssi
                    if (rssi != null) lastSignalRssiFallback = rssi
                    refreshWifiState(explicitRssi = rssi, forceConnected = true)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) =
                    refreshWifiState(explicitRssi = null, forceConnected = true)
                override fun onLost(network: Network) =
                    refreshWifiState(explicitRssi = null, lostNetwork = network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED &&
                        caps.signalStrength > -127
                    ) caps.signalStrength else null
                    if (rssi != null) lastSignalRssiFallback = rssi
                    refreshWifiState(explicitRssi = rssi, forceConnected = true)
                }
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, wifiCb)
            wifiCallback = wifiCb
        } catch (_: Exception) {}

        val cellCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshCellularFallback()
            }
            override fun onLost(network: Network) {
                refreshCellularFallback(lostNetwork = network)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                refreshCellularFallback()
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.registerNetworkCallback(req, cellCb)
            cellularCallback = cellCb
        } catch (_: Exception) {}
    }

    private fun registerTelephonyListeners() {
        val tm = telephonyManager ?: return
        val hasPhonePerm = hasReadPhoneStatePermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val executor = ContextCompat.getMainExecutor(context)
            if (hasPhonePerm) {
                val cb = object : TelephonyCallback(),
                    TelephonyCallback.SignalStrengthsListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.DataConnectionStateListener {

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        val level = signalStrength.level.coerceIn(0, 4)
                        val cur = cellularFlow.value
                        val inSvc = serviceInServiceKnown ?: (level > 0 || cur.dataConnected)
                        cellularFlow.value = cur.copy(
                            level = if (inSvc) level else 0,
                            hasService = inSvc,
                        )
                    }

                    override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                        val label = mapDisplayInfoToLabel(displayInfo)
                        val cur = cellularFlow.value
                        cellularFlow.value = cur.copy(typeLabel = label)
                    }

                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        val inService = serviceState.state == ServiceState.STATE_IN_SERVICE
                        serviceInServiceKnown = inService
                        val cur = cellularFlow.value
                        cellularFlow.value = cur.copy(
                            hasService = inService,
                            level = if (!inService) 0 else cur.level,
                        )
                    }

                    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                        val connected = state == TelephonyManager.DATA_CONNECTED
                        val label = mapNetworkTypeToLabel(networkType)
                        val cur = cellularFlow.value
                        cellularFlow.value = cur.copy(
                            dataConnected = connected,
                            typeLabel = if (cur.typeLabel.isNotBlank() && connected) cur.typeLabel else label,
                        )
                    }
                }
                try {
                    tm.registerTelephonyCallback(executor, cb)
                    telephonyCallback = cb
                    return
                } catch (_: SecurityException) {
                } catch (_: Exception) {}
            }

            // Fallback without READ_PHONE_STATE: SignalStrengthsListener works unprivileged on Android 12+
            val basicCb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    val level = signalStrength.level.coerceIn(0, 4)
                    val cur = cellularFlow.value
                    cellularFlow.value = cur.copy(
                        level = level,
                        hasService = level > 0 || cur.dataConnected,
                    )
                }
            }
            try {
                tm.registerTelephonyCallback(executor, basicCb)
                telephonyCallback = basicCb
            } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    val level = signalStrength?.level?.coerceIn(0, 4) ?: 0
                    val cur = cellularFlow.value
                    val inSvc = serviceInServiceKnown ?: (level > 0 || cur.dataConnected)
                    cellularFlow.value = cur.copy(
                        level = if (inSvc) level else 0,
                        hasService = inSvc,
                    )
                }

                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    val inService = serviceState?.state == ServiceState.STATE_IN_SERVICE
                    serviceInServiceKnown = inService
                    val cur = cellularFlow.value
                    cellularFlow.value = cur.copy(
                        hasService = inService,
                        level = if (!inService) 0 else cur.level,
                    )
                }

                @Deprecated("Deprecated in Java")
                override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                    val connected = state == TelephonyManager.DATA_CONNECTED
                    val cur = cellularFlow.value
                    cellularFlow.value = cur.copy(
                        dataConnected = connected,
                        typeLabel = mapNetworkTypeToLabel(networkType),
                    )
                }
            }
            val events = if (hasPhonePerm) {
                @Suppress("DEPRECATION")
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                    PhoneStateListener.LISTEN_SERVICE_STATE or
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
            } else {
                @Suppress("DEPRECATION")
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            }
            try {
                @Suppress("DEPRECATION")
                tm.listen(listener, events)
                legacyPhoneListener = listener
            } catch (_: Exception) {}
        }
    }

    private fun refreshWifiState(
        explicitRssi: Int?,
        forceConnected: Boolean = false,
        lostNetwork: Network? = null,
    ) {
        val cm = connectivityManager
        val wm = wifiManager
        val isConnected = forceConnected || try {
            @Suppress("DEPRECATION")
            val hasMatchingNet = cm?.allNetworks?.any { net ->
                if (lostNetwork != null && net == lostNetwork) return@any false
                val caps = cm.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
            val activeHasWifi = cm?.activeNetwork?.let { active ->
                (lostNetwork == null || active != lostNetwork) &&
                    cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } == true
            hasMatchingNet || activeHasWifi
        } catch (_: Exception) {
            false
        }

        if (!isConnected || wm?.isWifiEnabled != true) {
            lastSignalRssiFallback = null
            wifiFlow.value = WifiSnapshot(connected = false, level = 0)
            return
        }

        val infoRssi = try {
            @Suppress("DEPRECATION")
            wm.connectionInfo?.rssi?.takeIf { it > -127 }
        } catch (_: Exception) { null }
        val rssi = explicitRssi ?: infoRssi ?: lastSignalRssiFallback ?: -65
        val level = computeWifiBars(rssi)
        wifiFlow.value = WifiSnapshot(connected = true, level = level)
    }

    private fun computeWifiBars(rssi: Int): Int {
        if (rssi <= -127) return 1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiManager != null) {
            try {
                val max = wifiManager.maxSignalLevel.coerceAtLeast(1)
                val raw = wifiManager.calculateSignalLevel(rssi)
                ((raw.toFloat() / max.toFloat()) * 3f).toInt().coerceIn(1, 3)
            } catch (_: Exception) {
                rssiThresholdsToBars(rssi)
            }
        } else {
            rssiThresholdsToBars(rssi)
        }
    }

    private fun rssiThresholdsToBars(rssi: Int): Int = when {
        rssi >= -58 -> 3
        rssi >= -72 -> 2
        else -> 1
    }

    private fun refreshCellularFallback(lostNetwork: Network? = null) {
        val flags = readSystemFlags()
        if (flags.airplaneMode) {
            cellularFlow.value = CellularSnapshot(false, false, 0, "")
            return
        }
        val cm = connectivityManager
        val cellActive = try {
            @Suppress("DEPRECATION")
            cm?.allNetworks?.any { net ->
                if (lostNetwork != null && net == lostNetwork) return@any false
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            } == true
        } catch (_: Exception) { false }

        val tm = telephonyManager
        val simReady = try {
            tm?.simState == TelephonyManager.SIM_STATE_READY
        } catch (_: Exception) { false }

        val signalLevel = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm?.signalStrength?.level?.coerceIn(0, 4) ?: cellularFlow.value.level
            } else {
                cellularFlow.value.level
            }
        } catch (_: Exception) { cellularFlow.value.level }

        val netType = try {
            if (hasReadPhoneStatePermission()) {
                @Suppress("DEPRECATION")
                mapNetworkTypeToLabel(tm?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN)
            } else {
                cellularFlow.value.typeLabel
            }
        } catch (_: Exception) { cellularFlow.value.typeLabel }

        val cur = cellularFlow.value
        val hasService = serviceInServiceKnown ?: (simReady || cellActive || signalLevel > 0)
        cellularFlow.value = cur.copy(
            dataConnected = cellActive,
            hasService = hasService,
            level = if (hasService) signalLevel else 0,
            typeLabel = if (netType.isNotBlank()) netType else if (cellActive) cur.typeLabel else "",
        )
    }

    private fun readSystemFlags(): SystemFlagsSnapshot {
        val airplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }

        val dnd = try {
            val filter = notificationManager?.currentInterruptionFilter
            filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) { false }

        val ringerMode = try {
            audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        } catch (_: Exception) { AudioManager.RINGER_MODE_NORMAL }
        val silent = ringerMode == AudioManager.RINGER_MODE_SILENT
        val vibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE

        val alarm = try {
            alarmManager?.nextAlarmClock != null
        } catch (_: Exception) { false }

        val (btEnabled, btConnected) = readBluetoothFlags()

        val locationEnabled = try {
            locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } == true
        } catch (_: Exception) { false }

        return SystemFlagsSnapshot(
            airplaneMode = airplane,
            bluetoothEnabled = btEnabled,
            bluetoothConnected = btConnected,
            dndActive = dnd,
            silent = silent,
            vibrate = vibrate,
            hasAlarm = alarm,
            locationEnabled = locationEnabled,
        )
    }

    private fun readBluetoothFlags(): Pair<Boolean, Boolean> {
        val adapter = try { bluetoothManager?.adapter } catch (_: Exception) { null } ?: return false to false
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (!enabled) return false to false
        if (!hasBluetoothConnectPermission()) return true to false

        val connected = try {
            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            val headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
            a2dp || headset
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        return true to connected
    }

    private fun formatCurrentTime(): String {
        val is24HourSystem = try {
            DateFormat.is24HourFormat(context)
        } catch (_: Exception) { false }
        return formatTimeForMillis(
            millis = System.currentTimeMillis(),
            is24HourSystem = is24HourSystem,
            clockFormat = clockFormat,
            showSeconds = showSeconds,
        )
    }

    internal fun formatTimeForMillis(
        millis: Long,
        is24HourSystem: Boolean,
        clockFormat: ClockFormat,
        showSeconds: Boolean,
    ): String {
        val use24Hour = when (clockFormat) {
            ClockFormat.SYSTEM -> is24HourSystem
            ClockFormat.HOUR_12 -> false
            ClockFormat.HOUR_24 -> true
        }
        val pattern = when {
            use24Hour && showSeconds -> "HH:mm:ss"
            use24Hour -> "H:mm"
            showSeconds -> "h:mm:ss"
            else -> "h:mm"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    private fun mapDisplayInfoToLabel(info: TelephonyDisplayInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (info.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
                else -> mapNetworkTypeToLabel(info.networkType)
            }
        }
        return ""
    }

    @Suppress("DEPRECATION")
    private fun mapNetworkTypeToLabel(networkType: Int): String = when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA -> "H"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "E"
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        else -> ""
    }

    private fun buildNotificationIconSnapshot(
        notifications: List<ShadeNotification>,
        options: NotificationIconOptions,
    ): NotificationIconSnapshot {
        val (selected, hasOverflow) = selectStatusBarNotifications(
            notifications = notifications,
            showNotificationIcons = options.show,
            maxNotificationIcons = options.maxIcons,
            includeSilentNotifications = options.includeSilent,
        )
        if (selected.isEmpty()) return NotificationIconSnapshot(emptyList(), false)
        val icons = selected.map { note ->
            StatusBarNotificationIcon(
                key = note.key,
                packageName = note.packageName,
                bitmap = loadIconBitmap(note),
                appName = resolveAppLabel(note.packageName),
            )
        }
        return NotificationIconSnapshot(icons = icons, hasOverflow = hasOverflow)
    }

    private fun loadIconBitmap(note: ShadeNotification): ImageBitmap? {
        val icon = note.smallIcon ?: return null
        val cacheKey = buildIconCacheKey(note.packageName, icon, note.key)
        iconCache.get(cacheKey)?.let { return it }
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            val px = 36
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
            bmp.asImageBitmap().also { iconCache.put(cacheKey, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildIconCacheKey(packageName: String, icon: Icon, fallbackKey: String): String {
        return try {
            if (icon.type == Icon.TYPE_RESOURCE) {
                "${icon.resPackage}:$packageName:${icon.resId}"
            } else {
                "$packageName:$fallbackKey"
            }
        } catch (_: Throwable) {
            "$packageName:$fallbackKey"
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        appNameCache.get(packageName)?.let { return it }
        val resolved = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
        } catch (_: Throwable) {
            packageName
        }
        appNameCache.put(packageName, resolved)
        return resolved
    }

    private data class NotificationIconOptions(
        val show: Boolean = true,
        val maxIcons: Int = 3,
        val includeSilent: Boolean = false,
    )

    private data class NotificationIconSnapshot(
        val icons: List<StatusBarNotificationIcon>,
        val hasOverflow: Boolean,
    )

    companion object {
        /**
         * Filters active notifications for the status bar, deduplicating by app package
         * while preserving newest order, enforcing [maxNotificationIcons], and reporting
         * whether extra notifications remain hidden behind an overflow dot.
         */
        internal fun selectStatusBarNotifications(
            notifications: List<ShadeNotification>,
            showNotificationIcons: Boolean,
            maxNotificationIcons: Int,
            includeSilentNotifications: Boolean,
        ): Pair<List<ShadeNotification>, Boolean> {
            if (!showNotificationIcons) return emptyList<ShadeNotification>() to false
            val limit = maxNotificationIcons.coerceIn(1, 6)
            val eligible = ArrayList<ShadeNotification>(notifications.size)
            val seenPackages = HashSet<String>(notifications.size)
            for (n in notifications) {
                if (n.isGroupSummary) continue
                if (n.packageName == "com.supershade") continue
                if (!includeSilentNotifications && n.isSilent) continue
                if (seenPackages.add(n.packageName)) {
                    eligible.add(n)
                }
            }
            if (eligible.isEmpty()) return emptyList<ShadeNotification>() to false
            val capped = eligible.take(limit)
            val hasOverflow = eligible.size > limit
            return capped to hasOverflow
        }
    }

    private data class WifiSnapshot(
        val connected: Boolean,
        val level: Int,
    )

    private data class CellularSnapshot(
        val dataConnected: Boolean,
        val hasService: Boolean,
        val level: Int,
        val typeLabel: String,
    )

    private data class SystemFlagsSnapshot(
        val airplaneMode: Boolean,
        val bluetoothEnabled: Boolean,
        val bluetoothConnected: Boolean,
        val dndActive: Boolean,
        val silent: Boolean,
        val vibrate: Boolean,
        val hasAlarm: Boolean,
        val locationEnabled: Boolean,
    )
}
