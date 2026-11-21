package com.supershade.domain.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.supershade.shizuku.StatusBarGovernor
import com.supershade.viewmodel.BluetoothDeviceCategory
import com.supershade.viewmodel.BluetoothDeviceItem
import com.supershade.viewmodel.WifiNetworkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

data class WifiSnapshot(
    val isEnabled: Boolean,
    val currentSsid: String?,
    val band: String,
    val ip: String?,
    val linkSpeed: String?,
    val rssi: Int,
    val networks: List<WifiNetworkItem>,
    val needsPermission: Boolean,
    val statusMessage: String? = null,
)

data class WifiConnectResult(
    val success: Boolean,
    val launchedExternalPanel: Boolean = false,
    val message: String? = null,
)

data class BluetoothSnapshot(
    val isEnabled: Boolean,
    val activeDeviceName: String?,
    val isAudioConnected: Boolean,
    val pairedDevices: List<BluetoothDeviceItem>,
    val needsPermission: Boolean,
    val statusMessage: String? = null,
)

data class BluetoothActionResult(
    val success: Boolean,
    val launchedExternalPanel: Boolean = false,
    val message: String? = null,
)

class ConnectivityRepository(
    private val context: Context,
    private val governor: StatusBarGovernor,
) {
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var btDiscoveryReceiver: BroadcastReceiver? = null

    fun hasWifiScanPermission(): Boolean {
        if (governor.canRunPrivileged) return true
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else false
        return fineGranted || nearbyGranted
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasBluetoothPermission() && (
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun loadWifiSnapshot(triggerScan: Boolean = true): WifiSnapshot = withContext(Dispatchers.IO) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isEnabled = wm?.isWifiEnabled == true

        val info = try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo
        } catch (_: Exception) { null }

        val rawSsid = info?.ssid?.trim('"')
        val connectedSsid = if (rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>") null else rawSsid
        val freq = info?.frequency ?: 0
        val band = when {
            freq > 5925 -> "6 GHz (Wi-Fi 6E/7)"
            freq > 4900 -> "5 GHz"
            freq > 2400 -> "2.4 GHz"
            isEnabled -> "Wi-Fi"
            else -> "Off"
        }
        val speed = if (info != null && info.linkSpeed > 0) "${info.linkSpeed} Mbps" else null
        val rssi = info?.rssi ?: -100
        val ip = try {
            val activeNet = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(activeNet)
            linkProps?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        } catch (_: Exception) { null }

        if (!isEnabled) {
            return@withContext WifiSnapshot(
                isEnabled = false,
                currentSsid = null,
                band = "Off",
                ip = null,
                linkSpeed = null,
                rssi = -100,
                networks = emptyList(),
                needsPermission = false,
                statusMessage = "Turn on Wi-Fi to view nearby networks",
            )
        }

        if (triggerScan && wm != null) {
            try {
                @Suppress("DEPRECATION")
                wm.startScan()
            } catch (_: Exception) {}
        }

        val savedSsids = mutableSetOf<String>()
        if (governor.canRunPrivileged) {
            val listed = governor.runShellOutput("cmd", "wifi", "list-networks")
            parseSavedWifiSsids(listed, savedSsids)
        } else {
            try {
                @Suppress("DEPRECATION")
                wm?.configuredNetworks?.forEach { cfg ->
                    val s = cfg.SSID?.trim('"')
                    if (!s.isNullOrBlank()) savedSsids.add(s)
                }
            } catch (_: Exception) {}
        }

        val itemsBySsid = linkedMapOf<String, WifiNetworkItem>()
        var permissionDenied = false

        try {
            @Suppress("DEPRECATION")
            val results: List<ScanResult> = wm?.scanResults ?: emptyList()
            for (sr in results) {
                val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sr.wifiSsid?.toString()?.trim('"')
                } else {
                    @Suppress("DEPRECATION")
                    sr.SSID?.trim('"')
                }
                if (ssid.isNullOrBlank()) continue
                val sec = parseWifiSecurityLabel(sr.capabilities)
                val isSecured = sec != "Open"
                val isCurrent = connectedSsid != null && ssid == connectedSsid
                val existing = itemsBySsid[ssid]
                if (existing == null || sr.level > existing.rssi) {
                    itemsBySsid[ssid] = WifiNetworkItem(
                        ssid = ssid,
                        security = sec,
                        rssi = sr.level,
                        frequencyMhz = sr.frequency,
                        isConnected = isCurrent,
                        isSaved = isCurrent || ssid in savedSsids,
                        isSecured = isSecured,
                    )
                }
            }
        } catch (_: SecurityException) {
            permissionDenied = !governor.canRunPrivileged
        } catch (_: Exception) {}

        // If standard scanResults were blocked/empty and Shizuku is available, query shell scan results
        if (itemsBySsid.isEmpty() && governor.canRunPrivileged) {
            val shellScan = governor.runShellOutput("cmd", "wifi", "list-scan-results")
            parseShellScanResults(shellScan, connectedSsid, savedSsids, itemsBySsid)
        }

        // Always keep connected SSID at the top even if scan list missed it
        if (connectedSsid != null && !itemsBySsid.containsKey(connectedSsid)) {
            itemsBySsid[connectedSsid] = WifiNetworkItem(
                ssid = connectedSsid,
                security = "Connected",
                rssi = if (rssi > -100) rssi else -55,
                frequencyMhz = freq,
                isConnected = true,
                isSaved = true,
                isSecured = true,
            )
        }

        val sorted = itemsBySsid.values.sortedWith(
            compareByDescending<WifiNetworkItem> { it.isConnected }
                .thenByDescending { it.isSaved }
                .thenByDescending { it.rssi }
        )

        val needsPermission = (permissionDenied || (!hasWifiScanPermission() && sorted.size <= 1)) && !governor.canRunPrivileged
        val message = when {
            needsPermission -> "Grant Location/Nearby permission for full scan, or use Internet Panel"
            sorted.isEmpty() -> "No Wi-Fi networks detected yet"
            else -> null
        }

        WifiSnapshot(
            isEnabled = true,
            currentSsid = connectedSsid,
            band = band,
            ip = ip,
            linkSpeed = speed,
            rssi = rssi,
            networks = sorted,
            needsPermission = needsPermission,
            statusMessage = message,
        )
    }

    suspend fun toggleWifi(enabled: Boolean): WifiConnectResult = withContext(Dispatchers.IO) {
        if (governor.canRunPrivileged) {
            val ok = governor.setWifi(enabled)
            return@withContext WifiConnectResult(
                success = ok,
                launchedExternalPanel = false,
                message = if (ok) null else "Could not toggle Wi-Fi via Shizuku",
            )
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wm != null) {
            try {
                @Suppress("DEPRECATION")
                val ok = wm.setWifiEnabled(enabled)
                if (ok) return@withContext WifiConnectResult(success = true)
            } catch (_: Exception) {}
        }
        val launched = launchWifiPanel()
        WifiConnectResult(
            success = launched,
            launchedExternalPanel = launched,
            message = if (launched) "Opened Android Wi-Fi Panel" else null,
        )
    }

    suspend fun connectToWifi(
        ssid: String,
        passphrase: String? = null,
        isOpen: Boolean = false,
        securityLabel: String = "WPA2",
        isSaved: Boolean = false,
    ): WifiConnectResult = withContext(Dispatchers.IO) {
        if (ssid.isBlank()) return@withContext WifiConnectResult(false, message = "Invalid network name")

        // 1. Privileged direct connection via Shizuku shell `cmd wifi connect-network`
        if (governor.canRunPrivileged) {
            val secArg = when {
                isOpen -> "open"
                securityLabel.contains("WPA3", ignoreCase = true) -> "wpa3"
                securityLabel.contains("WEP", ignoreCase = true) -> "wep"
                else -> "wpa2"
            }
            val ok = if (isOpen) {
                governor.runShell("cmd", "wifi", "connect-network", ssid, "open")
            } else if (!passphrase.isNullOrEmpty()) {
                governor.runShell("cmd", "wifi", "connect-network", ssid, secArg, passphrase)
            } else if (isSaved) {
                // Reconnect to already-saved network ID if available
                val savedId = findSavedNetworkId(ssid)
                if (savedId != null) {
                    // Disable then enable forces framework reconnection, or use connect-network if possible
                    governor.runShell("cmd", "wifi", "enable-network", savedId.toString()) ||
                        governor.runShell("cmd", "wifi", "connect-network", ssid, secArg)
                } else {
                    false
                }
            } else false

            if (ok) {
                return@withContext WifiConnectResult(
                    success = true,
                    launchedExternalPanel = false,
                    message = "Connecting to $ssid...",
                )
            }
        }

        // 2. Android 11+ (API 30+): WifiNetworkSuggestion + Settings.ACTION_WIFI_ADD_NETWORKS system dialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (!passphrase.isNullOrEmpty() || isOpen)) {
            try {
                val suggestionBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                if (!isOpen && !passphrase.isNullOrEmpty()) {
                    if (securityLabel.contains("WPA3", ignoreCase = true)) {
                        suggestionBuilder.setWpa3Passphrase(passphrase)
                    } else {
                        suggestionBuilder.setWpa2Passphrase(passphrase)
                    }
                }
                val suggestion = suggestionBuilder.build()
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                try { wm?.addNetworkSuggestions(listOf(suggestion)) } catch (_: Exception) {}

                val addIntent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                    putParcelableArrayListExtra(
                        Settings.EXTRA_WIFI_NETWORK_LIST,
                        arrayListOf(suggestion),
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (addIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(addIntent)
                    return@withContext WifiConnectResult(
                        success = true,
                        launchedExternalPanel = true,
                        message = "Confirm adding $ssid in system dialog",
                    )
                }
            } catch (_: Exception) {}
        }

        // 3. Android 10+ (API 29+): WifiNetworkSpecifier triggers system connection chooser for the network
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (isOpen || !passphrase.isNullOrEmpty())) {
            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    activeNetworkCallback?.let { prev ->
                        try { cm.unregisterNetworkCallback(prev) } catch (_: Exception) {}
                    }
                    val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    if (!isOpen && !passphrase.isNullOrEmpty()) {
                        if (securityLabel.contains("WPA3", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            specBuilder.setWpa3Passphrase(passphrase)
                        } else {
                            specBuilder.setWpa2Passphrase(passphrase)
                        }
                    }
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specBuilder.build())
                        .build()
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {}
                    }
                    activeNetworkCallback = callback
                    cm.requestNetwork(req, callback)
                    return@withContext WifiConnectResult(
                        success = true,
                        launchedExternalPanel = true,
                        message = "Select Connect in system prompt for $ssid",
                    )
                }
            } catch (_: Exception) {}
        }

        // 4. Saved network without passphrase on non-privileged Android: open native floating Wi-Fi / Internet panel
        val panelOpened = launchWifiPanel()
        WifiConnectResult(
            success = panelOpened,
            launchedExternalPanel = panelOpened,
            message = if (panelOpened) "Choose $ssid in Android's Wi-Fi panel" else "Unable to open Wi-Fi panel",
        )
    }

    fun launchWifiPanel(): Boolean {
        val actions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                Settings.Panel.ACTION_WIFI,
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                Settings.ACTION_WIFI_SETTINGS,
            )
        } else {
            listOf(Settings.ACTION_WIFI_SETTINGS)
        }
        for (action in actions) {
            try {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null || action == Settings.ACTION_WIFI_SETTINGS) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    @SuppressLint("MissingPermission")
    suspend fun loadBluetoothSnapshot(): BluetoothSnapshot = withContext(Dispatchers.IO) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        val isEnabled = adapter?.isEnabled == true

        val am = context.getSystemService(AudioManager::class.java)
        val audioOutputs = try {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }.orEmpty()
        } catch (_: Exception) { emptyList() }

        val activeAudioNames = audioOutputs.mapNotNull { it.productName?.toString() }.toSet()

        if (!isEnabled || adapter == null) {
            return@withContext BluetoothSnapshot(
                isEnabled = false,
                activeDeviceName = null,
                isAudioConnected = false,
                pairedDevices = emptyList(),
                needsPermission = false,
                statusMessage = "Turn on Bluetooth to manage devices",
            )
        }

        if (!hasBluetoothPermission()) {
            return@withContext BluetoothSnapshot(
                isEnabled = true,
                activeDeviceName = activeAudioNames.firstOrNull(),
                isAudioConnected = activeAudioNames.isNotEmpty(),
                pairedDevices = emptyList(),
                needsPermission = true,
                statusMessage = "Grant Bluetooth permission to view & connect paired devices",
            )
        }

        val proxyConnectedAddresses = mutableSetOf<String>()
        try {
            proxyConnectedAddresses.addAll(queryConnectedAddressesForProfile(adapter, BluetoothProfile.A2DP))
            proxyConnectedAddresses.addAll(queryConnectedAddressesForProfile(adapter, BluetoothProfile.HEADSET))
        } catch (_: Exception) {}

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            return@withContext BluetoothSnapshot(
                isEnabled = true,
                activeDeviceName = activeAudioNames.firstOrNull(),
                isAudioConnected = activeAudioNames.isNotEmpty(),
                pairedDevices = emptyList(),
                needsPermission = true,
                statusMessage = "Bluetooth permission required",
            )
        } catch (_: Exception) { emptyList() }

        val items = bonded.map { dev ->
            val name = try {
                dev.name?.takeIf { it.isNotBlank() } ?: dev.address
            } catch (_: Exception) { dev.address }
            val connectedByReflect = try {
                val m = dev.javaClass.getMethod("isConnected")
                (m.invoke(dev) as? Boolean) == true
            } catch (_: Exception) { false }
            val connectedByAudio = activeAudioNames.any { it.equals(name, ignoreCase = true) }
            val connected = connectedByReflect || connectedByAudio || dev.address in proxyConnectedAddresses
            val battery = readBluetoothBatteryLevel(dev)
            val status = when {
                connected && battery != null -> "Connected • $battery%"
                connected -> "Connected"
                else -> "Paired"
            }
            BluetoothDeviceItem(
                name = name,
                address = dev.address,
                isConnected = connected,
                isPaired = true,
                batteryLevel = battery,
                category = classifyBluetoothDevice(dev, name),
                statusText = status,
            )
        }.sortedWith(
            compareByDescending<BluetoothDeviceItem> { it.isConnected }
                .thenBy { it.name.lowercase() }
        )

        val primaryActive = items.firstOrNull { it.isConnected }?.name ?: activeAudioNames.firstOrNull()
        BluetoothSnapshot(
            isEnabled = true,
            activeDeviceName = primaryActive,
            isAudioConnected = primaryActive != null || activeAudioNames.isNotEmpty(),
            pairedDevices = items,
            needsPermission = false,
            statusMessage = if (items.isEmpty()) "No paired Bluetooth devices found" else null,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun toggleBluetooth(enabled: Boolean): BluetoothActionResult = withContext(Dispatchers.IO) {
        if (governor.canRunPrivileged) {
            val ok = governor.setBluetooth(enabled)
            return@withContext BluetoothActionResult(
                success = ok,
                launchedExternalPanel = false,
                message = if (ok) null else "Could not toggle Bluetooth via Shizuku",
            )
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null && hasBluetoothPermission()) {
            try {
                @Suppress("DEPRECATION")
                val ok = if (enabled) adapter.enable() else adapter.disable()
                if (ok) return@withContext BluetoothActionResult(success = true)
            } catch (_: Exception) {}
        }
        return@withContext if (enabled) {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                BluetoothActionResult(success = true, launchedExternalPanel = true)
            } catch (_: Exception) {
                val opened = launchBluetoothSettings()
                BluetoothActionResult(success = opened, launchedExternalPanel = opened)
            }
        } else {
            val opened = launchBluetoothSettings()
            BluetoothActionResult(
                success = opened,
                launchedExternalPanel = opened,
                message = "Android 13+ requires system dialog or Shizuku to turn off Bluetooth",
            )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun toggleDeviceConnection(deviceItem: BluetoothDeviceItem): BluetoothActionResult = withContext(Dispatchers.IO) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return@withContext BluetoothActionResult(false, message = "Bluetooth unavailable")

        if (!hasBluetoothPermission()) {
            return@withContext BluetoothActionResult(false, message = "Bluetooth permission required")
        }

        val target = try {
            adapter.bondedDevices?.firstOrNull { it.address == deviceItem.address }
        } catch (_: Exception) { null }

        if (target == null) {
            val opened = launchBluetoothPairScreen()
            return@withContext BluetoothActionResult(
                success = opened,
                launchedExternalPanel = opened,
                message = if (opened) "Complete pairing in Android Bluetooth settings" else "Device not paired",
            )
        }

        val methodName = if (deviceItem.isConnected) "disconnect" else "connect"
        var invoked = false
        for (profileType in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val profileOk = invokeProfileConnection(adapter, profileType, target, methodName)
            if (profileOk) invoked = true
        }

        if (invoked) {
            return@withContext BluetoothActionResult(
                success = true,
                launchedExternalPanel = false,
                message = if (deviceItem.isConnected) "Disconnecting ${deviceItem.name}..." else "Connecting ${deviceItem.name}...",
            )
        }

        // Standard Android API restrictions prevent third-party apps from calling hidden A2DP.connect without BLUETOOTH_PRIVILEGED.
        // Fall back to system Media Output Switcher panel first, then Bluetooth settings.
        val mediaOutputOpened = launchMediaOutputPanel()
        if (mediaOutputOpened) {
            return@withContext BluetoothActionResult(
                success = true,
                launchedExternalPanel = true,
                message = "Use Media Output dialog to route ${deviceItem.name}",
            )
        }
        val settingsOpened = launchBluetoothSettings()
        BluetoothActionResult(
            success = settingsOpened,
            launchedExternalPanel = settingsOpened,
            message = "Android requires system panel to switch ${deviceItem.name}",
        )
    }

    @SuppressLint("MissingPermission")
    fun startNearbyScan(onDevicesUpdated: (List<BluetoothDeviceItem>) -> Unit): Boolean {
        stopNearbyScan()
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return false
        if (!adapter.isEnabled || !hasBluetoothScanPermission()) return false

        val found = linkedMapOf<String, BluetoothDeviceItem>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val name = try { dev.name?.takeIf { it.isNotBlank() } } catch (_: Exception) { null } ?: return
                        val addr = dev.address ?: return
                        val bonded = try { dev.bondState == BluetoothDevice.BOND_BONDED } catch (_: Exception) { false }
                        if (!bonded) {
                            found[addr] = BluetoothDeviceItem(
                                name = name,
                                address = addr,
                                isConnected = false,
                                isPaired = false,
                                category = classifyBluetoothDevice(dev, name),
                                statusText = "Tap to pair",
                            )
                            onDevicesUpdated(found.values.toList())
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopNearbyScan()
                    }
                }
            }
        }
        return try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            btDiscoveryReceiver = receiver
            adapter.startDiscovery()
        } catch (_: Exception) {
            stopNearbyScan()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopNearbyScan() {
        btDiscoveryReceiver?.let { r ->
            try { context.unregisterReceiver(r) } catch (_: Exception) {}
            btDiscoveryReceiver = null
        }
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            if (hasBluetoothScanPermission() && adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {}
    }

    fun launchBluetoothPairScreen(): Boolean {
        val candidates = listOf(
            Intent("android.settings.BLUETOOTH_PAIRING_SETTINGS"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null || intent.action == Settings.ACTION_BLUETOOTH_SETTINGS) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    fun launchMediaOutputPanel(): Boolean {
        val candidates = listOf(
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT"),
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").setPackage("com.android.systemui"),
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    fun launchBluetoothSettings(): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    fun openAppPermissionsSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private suspend fun queryConnectedAddressesForProfile(
        adapter: BluetoothAdapter,
        profileType: Int,
    ): Set<String> = withTimeoutOrNull(750L) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    val addrs = try {
                        proxy?.connectedDevices?.mapNotNull { it.address }?.toSet().orEmpty()
                    } catch (_: Exception) { emptySet() }
                    try { if (proxy != null) adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(addrs)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(emptySet())
                    }
                }
            }
            val started = try {
                adapter.getProfileProxy(context, listener, profileType)
            } catch (_: Exception) { false }
            if (!started && !resumed && cont.isActive) {
                resumed = true
                cont.resume(emptySet())
            }
        }
    }.orEmpty()

    @SuppressLint("MissingPermission")
    private suspend fun invokeProfileConnection(
        adapter: BluetoothAdapter,
        profileType: Int,
        device: BluetoothDevice,
        methodName: String,
    ): Boolean = withTimeoutOrNull(900L) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    val ok = try {
                        if (proxy == null) false
                        else {
                            val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                            (method.invoke(proxy, device) as? Boolean) == true
                        }
                    } catch (_: Exception) { false }
                    try { if (proxy != null) adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(ok)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(false)
                    }
                }
            }
            val started = try {
                adapter.getProfileProxy(context, listener, profileType)
            } catch (_: Exception) { false }
            if (!started && !resumed && cont.isActive) {
                resumed = true
                cont.resume(false)
            }
        }
    } ?: false

    private fun readBluetoothBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            val m = device.javaClass.getMethod("getBatteryLevel")
            val level = (m.invoke(device) as? Int) ?: -1
            if (level in 0..100) level else null
        } catch (_: Exception) { null }
    }

    @SuppressLint("MissingPermission")
    private fun classifyBluetoothDevice(device: BluetoothDevice, name: String): BluetoothDeviceCategory {
        val lower = name.lowercase()
        val devClass = try { device.bluetoothClass } catch (_: Exception) { null }
        val major = devClass?.majorDeviceClass
        val minor = devClass?.deviceClass
        return when {
            minor == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                minor == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
                lower.contains("speaker") || lower.contains("soundbar") || lower.contains("jbl") ->
                BluetoothDeviceCategory.SPEAKER
            minor == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                lower.contains("car") || lower.contains("auto") || lower.contains("sync") ->
                BluetoothDeviceCategory.CAR
            major == BluetoothClass.Device.Major.WEARABLE ||
                lower.contains("watch") || lower.contains("band") || lower.contains("fit") ->
                BluetoothDeviceCategory.WATCH
            major == BluetoothClass.Device.Major.PERIPHERAL ||
                lower.contains("keyboard") || lower.contains("mouse") || lower.contains("controller") ->
                BluetoothDeviceCategory.INPUT
            major == BluetoothClass.Device.Major.PHONE -> BluetoothDeviceCategory.PHONE
            major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                lower.contains("buds") || lower.contains("pods") || lower.contains("head") || lower.contains("ear") ->
                BluetoothDeviceCategory.HEADPHONES
            else -> BluetoothDeviceCategory.OTHER
        }
    }

    private suspend fun findSavedNetworkId(ssid: String): Int? {
        val output = governor.runShellOutput("cmd", "wifi", "list-networks")
        if (output.isBlank()) return null
        val regex = Regex("""^\s*(\d+)\s+(.+?)\s+(?:WPA|OPEN|OWE|SAE|WEP|NONE)""", RegexOption.IGNORE_CASE)
        for (line in output.lines()) {
            val m = regex.find(line) ?: continue
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val rowSsid = m.groupValues[2].trim().trim('"')
            if (rowSsid == ssid) return id
        }
        return null
    }

    private fun parseSavedWifiSsids(output: String, out: MutableSet<String>) {
        if (output.isBlank()) return
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Network Id")) continue
            val parts = trimmed.split(Regex("\\s{2,}"))
            if (parts.size >= 2) {
                val candidate = parts[1].trim().trim('"')
                if (candidate.isNotBlank() && candidate != "<unknown ssid>") {
                    out.add(candidate)
                }
            }
        }
    }

    private fun parseShellScanResults(
        output: String,
        connectedSsid: String?,
        savedSsids: Set<String>,
        out: MutableMap<String, WifiNetworkItem>,
    ) {
        if (output.isBlank()) return
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("BSSID")) continue
            // Format varies: BSSID  FREQ  RSSI  AGE  FLAGS  SSID
            val tokens = trimmed.split(Regex("\\s+"), limit = 6)
            if (tokens.size < 6) continue
            val freq = tokens[1].toIntOrNull() ?: 0
            val rssi = tokens[2].toIntOrNull() ?: -80
            val flags = tokens[4]
            val ssid = tokens[5].trim().trim('"')
            if (ssid.isBlank() || ssid == "<unknown ssid>") continue
            val sec = parseWifiSecurityLabel(flags)
            val isCurrent = connectedSsid != null && ssid == connectedSsid
            val existing = out[ssid]
            if (existing == null || rssi > existing.rssi) {
                out[ssid] = WifiNetworkItem(
                    ssid = ssid,
                    security = sec,
                    rssi = rssi,
                    frequencyMhz = freq,
                    isConnected = isCurrent,
                    isSaved = isCurrent || ssid in savedSsids,
                    isSecured = sec != "Open",
                )
            }
        }
    }

    private fun parseWifiSecurityLabel(capabilities: String?): String {
        val caps = capabilities?.uppercase().orEmpty()
        return when {
            caps.contains("SAE") || caps.contains("WPA3") -> "WPA3"
            caps.contains("EAP") -> "Enterprise"
            caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            else -> "Open"
        }
    }
}
