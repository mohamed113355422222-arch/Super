package com.supershade.ui.shade

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.supershade.ui.theme.getCardBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import com.supershade.ui.theme.LocalShadeTheme
import com.supershade.ui.theme.ShadeTheme
import com.supershade.ui.theme.ChamferedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import com.supershade.haptics.LocalSuperHaptics
import com.supershade.haptics.SuperHaptics
import com.supershade.viewmodel.BluetoothDeviceCategory
import com.supershade.viewmodel.BluetoothDeviceItem
import com.supershade.viewmodel.TileDetailState
import com.supershade.viewmodel.TileDetailType
import com.supershade.viewmodel.WifiNetworkItem

@Composable
fun QuickTileDetailSheet(
    detailState: TileDetailState,
    onDismiss: () -> Unit,
    onSetTorchStrength: (Int) -> Unit = {},
    onToggleTorch: () -> Unit = {},
    onSetRingerMode: (Int) -> Unit = {},
    onSetStreamVolume: (stream: Int, volume: Int) -> Unit = { _, _ -> },
    onToggleDnd: () -> Unit = {},
    onSetDndDuration: (Int) -> Unit = {},
    onToggleHotspot: () -> Unit = {},
    onToggleWifi: () -> Unit = {},
    onRefreshWifi: () -> Unit = {},
    onConnectWifi: (WifiNetworkItem, String?) -> Unit = { _, _ -> },
    onOpenWifiPanel: () -> Unit = {},
    onOpenWifiPermissions: () -> Unit = {},
    onToggleBluetooth: () -> Unit = {},
    onRefreshBluetooth: (Boolean) -> Unit = {},
    onConnectBluetoothDevice: (BluetoothDeviceItem) -> Unit = {},
    onPairNewBluetooth: () -> Unit = {},
    onOpenMediaOutput: () -> Unit = {},
    onOpenBluetoothPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptics.sheetDetent()
                    onDismiss()
                },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shadeTheme = LocalShadeTheme.current
        val sheetShape = when (shadeTheme) {
            is ShadeTheme.Cyberpunk -> ChamferedCornerShape(12.dp)
            is ShadeTheme.Nothing -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            is ShadeTheme.Pixel -> RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            else -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        }
        val sheetBorder = when (shadeTheme) {
            is ShadeTheme.Cyberpunk -> BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.70f))
            is ShadeTheme.Nothing -> BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
            else -> getCardBorder(alpha = 0.35f)
        }
        val sheetBg = when (shadeTheme) {
            is ShadeTheme.Cyberpunk -> Color(0xFF090D18)
            is ShadeTheme.Nothing -> Color(0xFF0A0C0E)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // consume clicks so sheet doesn't dismiss
                ),
            shape = sheetShape,
            color = sheetBg,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = sheetBorder,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Drag handle indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 10.dp)
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)),
                )
            // Header Row: Title & Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    val headerIcon = when (detailState.type) {
                        TileDetailType.FLASHLIGHT -> if (detailState.isActive) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff
                        TileDetailType.WIFI -> Icons.Default.Wifi
                        TileDetailType.BLUETOOTH -> Icons.Default.Bluetooth
                        TileDetailType.SOUND_MODE -> when (detailState.ringerMode) {
                            android.media.AudioManager.RINGER_MODE_VIBRATE -> Icons.Default.Vibration
                            android.media.AudioManager.RINGER_MODE_SILENT -> Icons.AutoMirrored.Filled.VolumeOff
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        }
                        TileDetailType.DND -> Icons.Default.DoNotDisturbOn
                        TileDetailType.HOTSPOT -> Icons.Default.WifiTethering
                    }
                    val iconTint by animateColorAsState(
                        targetValue = if (detailState.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "iconTint",
                    )
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (detailState.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.60f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detailState.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        detailState.subtitle?.let { sub ->
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        haptics.lightTap()
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Body content according to type
            when (detailState.type) {
                TileDetailType.FLASHLIGHT -> {
                    FlashlightDetailContent(
                        isActive = detailState.isActive,
                        torchLevel = detailState.torchLevel,
                        maxTorchLevel = detailState.maxTorchLevel,
                        onToggle = {
                            if (!detailState.isActive) haptics.tileToggleOn() else haptics.tileToggleOff()
                            onToggleTorch()
                        },
                        onLevelChange = { level ->
                            haptics.sliderTick()
                            onSetTorchStrength(level)
                        },
                    )
                }
                TileDetailType.WIFI -> {
                    WifiDetailContent(
                        isActive = detailState.isActive,
                        ssid = detailState.wifiSsid ?: "Not Connected",
                        band = detailState.wifiBand ?: "Wi-Fi",
                        ip = detailState.wifiIp ?: "Unknown",
                        speed = detailState.wifiLinkSpeed ?: "Unknown",
                        rssi = detailState.wifiRssi,
                        networks = detailState.wifiNetworks,
                        isScanning = detailState.wifiIsScanning,
                        needsPermission = detailState.wifiNeedsLocationPermission,
                        statusMessage = detailState.wifiStatusMessage,
                        settingsAction = detailState.settingsAction,
                        onToggleWifi = onToggleWifi,
                        onRefresh = onRefreshWifi,
                        onConnectNetwork = onConnectWifi,
                        onOpenWifiPanel = onOpenWifiPanel,
                        onOpenPermissions = onOpenWifiPermissions,
                    )
                }
                TileDetailType.BLUETOOTH -> {
                    BluetoothDetailContent(
                        isActive = detailState.isActive,
                        deviceName = detailState.btDeviceName ?: "Bluetooth",
                        isAudioConnected = detailState.btAudioConnected,
                        pairedDevices = detailState.btDevices,
                        nearbyDevices = detailState.btNearbyDevices,
                        isScanning = detailState.btIsScanning,
                        needsPermission = detailState.btNeedsPermission,
                        statusMessage = detailState.btStatusMessage,
                        settingsAction = detailState.settingsAction,
                        onToggleBluetooth = onToggleBluetooth,
                        onRefresh = onRefreshBluetooth,
                        onConnectDevice = onConnectBluetoothDevice,
                        onPairNewDevice = onPairNewBluetooth,
                        onOpenMediaOutput = onOpenMediaOutput,
                        onOpenPermissions = onOpenBluetoothPermissions,
                    )
                }
                TileDetailType.SOUND_MODE -> {
                    SoundModeDetailContent(
                        ringerMode = detailState.ringerMode,
                        mediaVol = detailState.mediaVol,
                        mediaMaxVol = detailState.mediaMaxVol,
                        ringVol = detailState.ringVol,
                        ringMaxVol = detailState.ringMaxVol,
                        notifVol = detailState.notifVol,
                        notifMaxVol = detailState.notifMaxVol,
                        sysVol = detailState.sysVol,
                        sysMaxVol = detailState.sysMaxVol,
                        alarmVol = detailState.alarmVol,
                        alarmMaxVol = detailState.alarmMaxVol,
                        settingsAction = detailState.settingsAction,
                        onSetRingerMode = onSetRingerMode,
                        onSetStreamVolume = onSetStreamVolume,
                    )
                }
                TileDetailType.DND -> {
                    DndDetailContent(
                        isActive = detailState.isActive,
                        durationMinutes = detailState.dndDurationMinutes,
                        settingsAction = detailState.settingsAction,
                        onToggle = onToggleDnd,
                        onSetDuration = onSetDndDuration,
                    )
                }
                TileDetailType.HOTSPOT -> {
                    HotspotDetailContent(
                        isActive = detailState.isActive,
                        ssid = detailState.hotspotSsid ?: "AndroidAP",
                        band = detailState.hotspotBand ?: "5 GHz",
                        settingsAction = detailState.settingsAction,
                        onToggle = onToggleHotspot,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
    }
}

@Composable
private fun FlashlightDetailContent(
    isActive: Boolean,
    torchLevel: Int,
    maxTorchLevel: Int,
    onToggle: () -> Unit,
    onLevelChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = getCardBorder(alpha = 0.30f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Torch Status",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = if (isActive) "Flashlight is illuminating" else "Flashlight is powered off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
            )
        }
    }

    if (maxTorchLevel > 1) {
        val levelLabel = when (torchLevel) {
            1 -> "Level 1 • Soft Glow"
            2 -> "Level 2 • Moderate"
            3 -> "Level 3 • Standard"
            4 -> "Level 4 • High Brightness"
            maxTorchLevel -> "Level $maxTorchLevel • Maximum Power"
            else -> "Level $torchLevel"
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hardware Brightness Level",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = levelLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Slider(
                    value = torchLevel.toFloat(),
                    onValueChange = {
                        val newLevel = it.toInt()
                        if (newLevel != torchLevel) {
                            haptics.sliderTick()
                            onLevelChange(newLevel)
                        }
                    },
                    valueRange = 1f..maxTorchLevel.toFloat(),
                    steps = (maxTorchLevel - 2).coerceAtLeast(0),
                    enabled = isActive,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Quick Level Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (1..maxTorchLevel).forEach { level ->
                        val isSelected = level == torchLevel
                        val chipScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessHigh,
                            ),
                            label = "torchChipScale",
                        )
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (isSelected) null else getCardBorder(alpha = 0.35f),
                            modifier = Modifier
                                .size(36.dp)
                                .graphicsLayer {
                                    scaleX = chipScale
                                    scaleY = chipScale
                                }
                                .clickable(enabled = isActive) {
                                    haptics.sliderTick()
                                    onLevelChange(level)
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "$level",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiDetailContent(
    isActive: Boolean,
    ssid: String,
    band: String,
    ip: String,
    speed: String,
    rssi: Int,
    networks: List<WifiNetworkItem>,
    isScanning: Boolean,
    needsPermission: Boolean,
    statusMessage: String?,
    settingsAction: String?,
    onToggleWifi: () -> Unit,
    onRefresh: () -> Unit,
    onConnectNetwork: (WifiNetworkItem, String?) -> Unit,
    onOpenWifiPanel: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }
    var promptNetwork by remember { mutableStateOf<WifiNetworkItem?>(null) }
    var passwordInput by remember { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = getCardBorder(alpha = 0.30f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = if (isActive) "Connected Network" else "Wi-Fi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ssid,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            haptics.lightTap()
                            onRefresh()
                        },
                        modifier = Modifier.testTag("wifi_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan Wi-Fi networks",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isActive,
                        onCheckedChange = {
                            if (!isActive) haptics.tileToggleOn() else haptics.tileToggleOff()
                            onToggleWifi()
                        },
                        modifier = Modifier.testTag("wifi_detail_switch"),
                    )
                }
            }

            if (isActive && (speed != "Unknown" || ip != "Unknown" || (rssi != 0 && rssi != -127))) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DetailMetricTile(
                        icon = Icons.Default.Speed,
                        label = "Speed",
                        value = speed,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    DetailMetricTile(
                        icon = Icons.Default.Router,
                        label = "IP Address",
                        value = ip,
                        modifier = Modifier.weight(1f),
                    )
                    if (rssi != 0 && rssi != -127) {
                        Spacer(Modifier.width(8.dp))
                        DetailMetricTile(
                            icon = Icons.Default.NetworkCheck,
                            label = "Band",
                            value = band,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (isScanning) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape),
        )
    }

    if (needsPermission) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = statusMessage ?: "Grant permission to list nearby networks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(
                    onClick = {
                        haptics.lightTap()
                        onOpenPermissions()
                    },
                    modifier = Modifier.testTag("wifi_grant_permission_button"),
                ) {
                    Text("Permissions")
                }
            }
        }
    } else if (!statusMessage.isNullOrBlank()) {
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // Available & Saved Networks list inside the sheet
    if (isActive && networks.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Available Networks (${networks.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                networks.forEach { net ->
                    val isPrompting = promptNetwork?.ssid == net.ssid
                    Surface(
                        onClick = {
                            haptics.lightTap()
                            if (!net.isConnected) {
                                if (!net.isSecured || net.isSaved) {
                                    promptNetwork = null
                                    onConnectNetwork(net, null)
                                } else {
                                    promptNetwork = if (isPrompting) null else net
                                    passwordInput = ""
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (net.isConnected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = if (net.isConnected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wifi_network_${net.ssid}"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (net.isSecured) Icons.Default.WifiLock else Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = if (net.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = net.ssid,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (net.isConnected) FontWeight.Bold else FontWeight.Medium,
                                        ),
                                        color = if (net.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val meta = buildList {
                                        if (net.isConnected) add("Connected")
                                        else if (net.isSaved) add("Saved")
                                        if (net.bandLabel.isNotEmpty()) add(net.bandLabel)
                                        if (net.security.isNotEmpty() && net.security != "Connected") add(net.security)
                                    }.joinToString(" • ")
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (net.isConnected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Connected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else if (net.isSecured) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Secured",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }

                            AnimatedVisibility(visible = isPrompting) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = passwordInput,
                                        onValueChange = { passwordInput = it },
                                        label = { Text("Password for ${net.ssid}") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("wifi_password_input_${net.ssid}"),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                promptNetwork = null
                                                passwordInput = ""
                                            }
                                        ) {
                                            Text("Cancel")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                haptics.tileToggleOn()
                                                onConnectNetwork(net, passwordInput)
                                                promptNetwork = null
                                                passwordInput = ""
                                            },
                                            enabled = passwordInput.length >= 8,
                                            modifier = Modifier.testTag("wifi_connect_confirm_${net.ssid}"),
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {
                haptics.lightTap()
                onOpenWifiPanel()
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("wifi_panel_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Internet Panel", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        OutlinedButton(
            onClick = {
                haptics.lightTap()
                try {
                    val action = settingsAction ?: Settings.ACTION_WIFI_SETTINGS
                    context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                } catch (_: Exception) {}
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("wifi_settings_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BluetoothDetailContent(
    isActive: Boolean,
    deviceName: String,
    isAudioConnected: Boolean,
    pairedDevices: List<BluetoothDeviceItem>,
    nearbyDevices: List<BluetoothDeviceItem>,
    isScanning: Boolean,
    needsPermission: Boolean,
    statusMessage: String?,
    settingsAction: String?,
    onToggleBluetooth: () -> Unit,
    onRefresh: (Boolean) -> Unit,
    onConnectDevice: (BluetoothDeviceItem) -> Unit,
    onPairNewDevice: () -> Unit,
    onOpenMediaOutput: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = getCardBorder(alpha = 0.30f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        text = if (isActive) "Bluetooth" else "Bluetooth Off",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            haptics.lightTap()
                            onRefresh(true)
                        },
                        modifier = Modifier.testTag("bt_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan for Bluetooth devices",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isActive,
                        onCheckedChange = {
                            if (!isActive) haptics.tileToggleOn() else haptics.tileToggleOff()
                            onToggleBluetooth()
                        },
                        modifier = Modifier.testTag("bt_detail_switch"),
                    )
                }
            }
        }
    }

    if (isScanning) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape),
        )
    }

    if (needsPermission) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = statusMessage ?: "Grant Bluetooth permission to list paired devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(
                    onClick = {
                        haptics.lightTap()
                        onOpenPermissions()
                    },
                    modifier = Modifier.testTag("bt_grant_permission_button"),
                ) {
                    Text("Permissions")
                }
            }
        }
    } else if (!statusMessage.isNullOrBlank()) {
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (isActive && pairedDevices.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Paired Devices (${pairedDevices.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pairedDevices.forEach { dev ->
                    BluetoothDeviceRow(
                        device = dev,
                        onClick = {
                            haptics.lightTap()
                            onConnectDevice(dev)
                        },
                    )
                }
            }
        }
    }

    if (isActive && nearbyDevices.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Available Nearby (${nearbyDevices.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                nearbyDevices.forEach { dev ->
                    BluetoothDeviceRow(
                        device = dev,
                        onClick = {
                            haptics.lightTap()
                            onPairNewDevice()
                        },
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {
                haptics.lightTap()
                onPairNewDevice()
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("bt_pair_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Pair New", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (isAudioConnected) {
            OutlinedButton(
                onClick = {
                    haptics.sheetDetent()
                    onOpenMediaOutput()
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("bt_media_output_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Media Output", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            OutlinedButton(
                onClick = {
                    haptics.lightTap()
                    try {
                        val action = settingsAction ?: Settings.ACTION_BLUETOOTH_SETTINGS
                        context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (_: Exception) {}
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("bt_settings_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BluetoothDeviceRow(
    device: BluetoothDeviceItem,
    onClick: () -> Unit,
) {
    val icon = when (device.category) {
        BluetoothDeviceCategory.HEADPHONES -> Icons.Default.Headphones
        BluetoothDeviceCategory.SPEAKER -> Icons.Default.Speaker
        BluetoothDeviceCategory.WATCH -> Icons.Default.Watch
        BluetoothDeviceCategory.CAR -> Icons.Default.DirectionsCar
        BluetoothDeviceCategory.INPUT -> Icons.Default.Keyboard
        BluetoothDeviceCategory.PHONE -> Icons.Default.Smartphone
        BluetoothDeviceCategory.OTHER -> if (device.isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (device.isConnected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (device.isConnected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bt_device_${device.address}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (device.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (device.isConnected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.5.sp,
                    ),
                    color = if (device.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isConnected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (device.isConnected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Connected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailMetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SoundModeDetailContent(
    ringerMode: Int,
    mediaVol: Int,
    mediaMaxVol: Int,
    ringVol: Int,
    ringMaxVol: Int,
    notifVol: Int,
    notifMaxVol: Int,
    sysVol: Int,
    sysMaxVol: Int,
    alarmVol: Int,
    alarmMaxVol: Int,
    settingsAction: String?,
    onSetRingerMode: (Int) -> Unit,
    onSetStreamVolume: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    val shadeTheme = LocalShadeTheme.current

    val selectorContainerShape = when (shadeTheme) {
        is ShadeTheme.Cyberpunk -> ChamferedCornerShape(10.dp)
        is ShadeTheme.Nothing -> RoundedCornerShape(16.dp)
        is ShadeTheme.Pixel -> RoundedCornerShape(24.dp)
        else -> RoundedCornerShape(20.dp)
    }
    val selectorContainerBorder = when (shadeTheme) {
        is ShadeTheme.Cyberpunk -> BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.50f))
        is ShadeTheme.Nothing -> BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
        else -> getCardBorder(alpha = 0.30f)
    }
    val selectorContainerBg = when (shadeTheme) {
        is ShadeTheme.Cyberpunk -> Color(0xFF0A0F1D)
        is ShadeTheme.Nothing -> Color(0xFF101216)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Multi-Theme Tri-State Mode Selector
        Surface(
            shape = selectorContainerShape,
            color = selectorContainerBg,
            border = selectorContainerBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple(android.media.AudioManager.RINGER_MODE_NORMAL, "Sound", Icons.AutoMirrored.Filled.VolumeUp),
                    Triple(android.media.AudioManager.RINGER_MODE_VIBRATE, "Vibrate", Icons.Default.Vibration),
                    Triple(android.media.AudioManager.RINGER_MODE_SILENT, "Mute", Icons.AutoMirrored.Filled.VolumeOff),
                ).forEach { (mode, label, icon) ->
                    val isSelected = ringerMode == mode
                    val modeScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.02f else 1.0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "modeScale",
                    )

                    val itemShape = when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> ChamferedCornerShape(6.dp)
                        is ShadeTheme.Nothing -> RoundedCornerShape(12.dp)
                        is ShadeTheme.Pixel -> RoundedCornerShape(20.dp)
                        else -> RoundedCornerShape(14.dp)
                    }
                    val itemColor = when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> if (isSelected) Color(0xFF00F0FF).copy(alpha = 0.20f) else Color(0xFF060A14)
                        is ShadeTheme.Nothing -> if (isSelected) Color.White else Color(0xFF16191E)
                        is ShadeTheme.Pixel -> if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f)
                        else -> if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f)
                    }
                    val itemBorder = when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> if (isSelected) BorderStroke(1.2.dp, Color(0xFF00F0FF)) else BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.15f))
                        is ShadeTheme.Nothing -> if (isSelected) BorderStroke(1.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                        else -> if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    }
                    val itemContentColor = when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> if (isSelected) Color(0xFF00F0FF) else Color(0xFF80A8C0)
                        is ShadeTheme.Nothing -> if (isSelected) Color.Black else Color(0xFFB0B0B0)
                        else -> if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val displayLabel = when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> when (mode) {
                            android.media.AudioManager.RINGER_MODE_NORMAL -> "[SOUND]"
                            android.media.AudioManager.RINGER_MODE_VIBRATE -> "[VIB]"
                            else -> "[MUTE]"
                        }
                        is ShadeTheme.Nothing -> label.uppercase()
                        else -> label
                    }

                    Surface(
                        onClick = {
                            haptics.sliderTick()
                            onSetRingerMode(mode)
                        },
                        shape = itemShape,
                        color = itemColor,
                        border = itemBorder,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = modeScale
                                scaleY = modeScale
                            },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = itemContentColor,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = displayLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = if (shadeTheme is ShadeTheme.Cyberpunk) FontFamily.Monospace else FontFamily.Default,
                                    fontSize = 11.5.sp,
                                ),
                                color = itemContentColor,
                            )
                        }
                    }
                }
            }
        }

        // Live Audio Stream Sliders
        Surface(
            shape = selectorContainerShape,
            color = selectorContainerBg,
            border = selectorContainerBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (shadeTheme is ShadeTheme.Cyberpunk) "[VOLUME // CHANNELS]" else "Volume Levels",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = if (shadeTheme is ShadeTheme.Cyberpunk) FontFamily.Monospace else FontFamily.Default,
                    ),
                    color = if (shadeTheme is ShadeTheme.Cyberpunk) Color(0xFF00F0FF) else MaterialTheme.colorScheme.onSurface,
                )

                // Media volume
                VolumeStreamRow(
                    label = "Media",
                    icon = Icons.Default.MusicNote,
                    current = mediaVol,
                    max = mediaMaxVol,
                    onVolumeChange = { onSetStreamVolume(android.media.AudioManager.STREAM_MUSIC, it) },
                    haptics = haptics,
                )

                // Ringtone volume (muted if silent/vibrate)
                VolumeStreamRow(
                    label = "Ringtone",
                    icon = Icons.Default.Phone,
                    current = if (ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) ringVol else 0,
                    max = ringMaxVol,
                    enabled = ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL,
                    onVolumeChange = { onSetStreamVolume(android.media.AudioManager.STREAM_RING, it) },
                    haptics = haptics,
                )

                // Notification volume
                VolumeStreamRow(
                    label = "Notifications",
                    icon = Icons.Default.Notifications,
                    current = if (ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) notifVol else 0,
                    max = notifMaxVol,
                    enabled = ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL,
                    onVolumeChange = { onSetStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, it) },
                    haptics = haptics,
                )

                // System volume
                VolumeStreamRow(
                    label = "System",
                    icon = Icons.Default.Tune,
                    current = sysVol,
                    max = sysMaxVol,
                    onVolumeChange = { onSetStreamVolume(android.media.AudioManager.STREAM_SYSTEM, it) },
                    haptics = haptics,
                )

                // Alarm volume
                VolumeStreamRow(
                    label = "Alarm",
                    icon = Icons.Default.Alarm,
                    current = alarmVol,
                    max = alarmMaxVol,
                    onVolumeChange = { onSetStreamVolume(android.media.AudioManager.STREAM_ALARM, it) },
                    haptics = haptics,
                )
            }
        }

        // Bottom Settings Button
        settingsAction?.let { action ->
            OutlinedButton(
                onClick = {
                    haptics.lightTap()
                    try {
                        context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (_: Exception) {}
                },
                shape = when (shadeTheme) {
                    is ShadeTheme.Cyberpunk -> ChamferedCornerShape(8.dp)
                    is ShadeTheme.Nothing -> RoundedCornerShape(10.dp)
                    is ShadeTheme.Pixel -> RoundedCornerShape(20.dp)
                    else -> RoundedCornerShape(14.dp)
                },
                border = when (shadeTheme) {
                    is ShadeTheme.Cyberpunk -> BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.6f))
                    is ShadeTheme.Nothing -> BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                    else -> null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (shadeTheme is ShadeTheme.Cyberpunk) "[AUDIO_SETTINGS // CFG]" else "Sound & Vibration Settings",
                    fontFamily = if (shadeTheme is ShadeTheme.Cyberpunk) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

@Composable
private fun VolumeStreamRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    current: Int,
    max: Int,
    enabled: Boolean = true,
    onVolumeChange: (Int) -> Unit,
    haptics: SuperHaptics,
) {
    val shadeTheme = LocalShadeTheme.current
    val pct = if (max > 0) (current * 100 / max).coerceIn(0, 100) else 0
    val progress = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "volumeStreamProgress",
    )

    val trackHeight = when (shadeTheme) {
        is ShadeTheme.Cyberpunk -> 36.dp
        is ShadeTheme.Nothing -> 38.dp
        else -> 42.dp
    }
    val trackShape = when (shadeTheme) {
        is ShadeTheme.Cyberpunk -> ChamferedCornerShape(6.dp)
        is ShadeTheme.Nothing -> RoundedCornerShape(12.dp)
        is ShadeTheme.Pixel -> RoundedCornerShape(21.dp)
        else -> RoundedCornerShape(21.dp)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(trackShape)
                .background(
                    when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> Color(0xFF050B14)
                        is ShadeTheme.Nothing -> Color(0xFF14171C)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                    }
                )
                .then(
                    when (shadeTheme) {
                        is ShadeTheme.Cyberpunk -> Modifier.border(BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f)), trackShape)
                        is ShadeTheme.Nothing -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), trackShape)
                        else -> Modifier
                    }
                )
                .pointerInput(max, enabled) {
                    if (!enabled || max <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var currentVol = current
                        val update = { x: Float ->
                            val ratio = (x / size.width).coerceIn(0f, 1f)
                            val targetVol = (ratio * max).roundToInt()
                            if (targetVol != currentVol) {
                                haptics.segmentTick()
                                currentVol = targetVol
                                onVolumeChange(targetVol)
                            }
                        }
                        update(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            update(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            // Active Progress Fill
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .background(
                            when (shadeTheme) {
                                is ShadeTheme.Cyberpunk -> Brush.horizontalGradient(
                                    listOf(Color(0xFF00F0FF).copy(alpha = 0.75f), Color(0xFFFF007F).copy(alpha = 0.85f))
                                )
                                is ShadeTheme.Nothing -> Brush.horizontalGradient(
                                    listOf(Color.White.copy(alpha = 0.88f), Color.White)
                                )
                                else -> Brush.horizontalGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                                )
                            }
                        ),
                )
            }

            // Theme-specific decorative elements (Nothing 8 dot notches, Cyberpunk hash marks)
            when (shadeTheme) {
                is ShadeTheme.Nothing -> {
                    // 7 discrete dot notches
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(7) {
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.25f)),
                            )
                        }
                    }
                    // Red glyph dot at thumb edge if volume > 0
                    if (animatedProgress in 0.05f..0.98f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .wrapContentSize(Alignment.CenterEnd)
                                .offset(x = 3.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD71920)),
                        )
                    }
                }
                is ShadeTheme.Cyberpunk -> {
                    // 9 subtle vertical grid tick marks
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(9) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(10.dp)
                                    .background(Color(0xFF00F0FF).copy(alpha = 0.20f)),
                            )
                        }
                    }
                }
                else -> Unit
            }

            // Internal Label & Icon row
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val iconTint = when (shadeTheme) {
                        is ShadeTheme.Nothing -> if (animatedProgress > 0.35f) Color.Black else Color.White
                        is ShadeTheme.Cyberpunk -> Color(0xFF00F0FF)
                        else -> if (animatedProgress > 0.35f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    }
                    val textTint = when (shadeTheme) {
                        is ShadeTheme.Nothing -> if (animatedProgress > 0.35f) Color.Black else Color.White
                        is ShadeTheme.Cyberpunk -> Color(0xFF00F0FF)
                        else -> if (animatedProgress > 0.35f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) iconTint else iconTint.copy(alpha = 0.40f),
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = when (shadeTheme) {
                            is ShadeTheme.Cyberpunk -> "[${label.uppercase()}]"
                            is ShadeTheme.Nothing -> label.uppercase()
                            else -> label
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (shadeTheme is ShadeTheme.Cyberpunk) FontFamily.Monospace else FontFamily.Default,
                            fontSize = 12.sp,
                        ),
                        color = if (enabled) textTint else textTint.copy(alpha = 0.40f),
                    )
                }

                val pctColor = when (shadeTheme) {
                    is ShadeTheme.Nothing -> if (animatedProgress > 0.85f) Color.Black else Color.White
                    is ShadeTheme.Cyberpunk -> Color(0xFF00F0FF)
                    else -> if (animatedProgress > 0.85f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = if (enabled) "$pct%" else "MUTED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (shadeTheme is ShadeTheme.Cyberpunk) FontFamily.Monospace else FontFamily.Default,
                        fontSize = 11.5.sp,
                    ),
                    color = pctColor,
                )
            }
        }
    }
}

@Composable
private fun DndDetailContent(
    isActive: Boolean,
    durationMinutes: Int,
    settingsAction: String?,
    onToggle: () -> Unit,
    onSetDuration: (Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Do Not Disturb",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = if (isActive) "Mute calls, alerts, and media sounds" else "Allow all calls and notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = {
                        if (!isActive) haptics.tileToggleOn() else haptics.tileToggleOff()
                        onToggle()
                    },
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )

                listOf(
                    0 to "Until I turn it off",
                    60 to "For 1 hour",
                    120 to "For 2 hours",
                    -1 to "Until next alarm",
                ).forEach { (mins, label) ->
                    val isSelected = durationMinutes == mins
                    Surface(
                        onClick = {
                            haptics.sliderTick()
                            onSetDuration(mins)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        settingsAction?.let { action ->
            OutlinedButton(
                onClick = {
                    haptics.lightTap()
                    try {
                        context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (_: Exception) {}
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("DND Exceptions & Schedules")
            }
        }
    }
}

@Composable
private fun HotspotDetailContent(
    isActive: Boolean,
    ssid: String,
    band: String,
    settingsAction: String?,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Mobile Hotspot",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = if (isActive) "Sharing portable Wi-Fi connection" else "Hotspot is turned off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = {
                        if (!isActive) haptics.tileToggleOn() else haptics.tileToggleOff()
                        onToggle()
                    },
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = getCardBorder(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailMetricTile(
                        icon = Icons.Default.Wifi,
                        label = "Network Name",
                        value = ssid,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    DetailMetricTile(
                        icon = Icons.Default.Router,
                        label = "Band",
                        value = band,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        settingsAction?.let { action ->
            OutlinedButton(
                onClick = {
                    haptics.lightTap()
                    try {
                        context.startActivity(Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    } catch (_: Exception) {}
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Configure Mobile Hotspot")
            }
        }
    }
}
