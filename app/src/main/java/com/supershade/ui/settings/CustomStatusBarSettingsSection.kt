package com.supershade.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supershade.domain.system.CustomStatusBarState
import com.supershade.haptics.LocalSuperHaptics
import com.supershade.ui.statusbar.BatteryPercentMode
import com.supershade.ui.statusbar.ClockFormat
import com.supershade.ui.statusbar.IosStatusBar
import com.supershade.ui.statusbar.STATUS_BAR_COLOR_PRESETS
import com.supershade.ui.statusbar.StatusBarConfig
import com.supershade.ui.statusbar.StatusBarHorizontalAlign
import com.supershade.ui.statusbar.StatusBarTintMode
import com.supershade.ui.statusbar.StatusBarVerticalAlign
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomStatusBarSettingsSection(
    config: StatusBarConfig,
    onUpdateConfig: ((StatusBarConfig) -> StatusBarConfig) -> Unit,
    onResetConfig: () -> Unit,
    previewState: CustomStatusBarState,
    shadeActive: Boolean,
    overlayGranted: Boolean,
    shizukuConnected: Boolean,
    shizukuPermGranted: Boolean,
    isSystemStatusBarHidden: Boolean,
    hasPhoneStatePermission: Boolean,
    hasBluetoothPermission: Boolean,
    onGrantOverlay: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizukuManager: () -> Unit,
    onRequestTelemetryPermissions: () -> Unit,
    notificationAccessGranted: Boolean = true,
    onOpenNotificationAccess: () -> Unit = {},
) {
    val haptics = LocalSuperHaptics.current
    val shizukuOk = shizukuConnected && shizukuPermGranted
    val statusBarReady = shadeActive && overlayGranted && shizukuOk && isSystemStatusBarHidden
    val statusBarBadgeText = when {
        !config.enabled -> "Off"
        statusBarReady -> "Active • Native bar hidden"
        !overlayGranted -> "Needs Overlay"
        !shizukuOk -> "Needs Shizuku"
        !shadeActive -> "Standby"
        else -> "Syncing..."
    }
    val statusBarBadgeColor = when {
        !config.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        statusBarReady -> MaterialTheme.colorScheme.primary
        !overlayGranted || !shizukuOk -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    // Section Header matching SettingsScreen style
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CUSTOM iOS STATUS BAR",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = statusBarBadgeColor.copy(alpha = 0.15f),
        ) {
            Text(
                text = statusBarBadgeText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = statusBarBadgeColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Master Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        val next = !config.enabled
                        if (next) haptics?.tileToggleOn() else haptics?.tileToggleOff()
                        onUpdateConfig { it.copy(enabled = next) }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("csb_master_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "iPhone-Style Status Bar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "100% transparent overlay with real system telemetry; hides native status bar via Shizuku",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled) haptics?.tileToggleOn() else haptics?.tileToggleOff()
                        onUpdateConfig { it.copy(enabled = enabled) }
                    },
                )
            }

            // Live Transparent Preview with Backdrop & Waterdrop Notch Simulation
            var previewBackdropIdx by remember { mutableIntStateOf(0) } // 0=Dark, 1=Light, 2=Color
            var simulateCenterNotch by remember { mutableStateOf(false) }
            var previewWidthPx by remember { mutableIntStateOf(0) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Live Transparent Preview",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Dark", "Light", "Color").forEachIndexed { idx, label ->
                            PreviewChip(
                                label = label,
                                selected = previewBackdropIdx == idx,
                                onClick = {
                                    haptics?.lightTap()
                                    previewBackdropIdx = idx
                                },
                            )
                        }
                        PreviewChip(
                            label = if (simulateCenterNotch) "Notch On" else "Notch",
                            selected = simulateCenterNotch,
                            onClick = {
                                haptics?.lightTap()
                                simulateCenterNotch = !simulateCenterNotch
                            },
                        )
                    }
                }

                val backdropBrush = when (previewBackdropIdx) {
                    1 -> Brush.horizontalGradient(listOf(Color(0xFFE0EAFC), Color(0xFFF3F6FB), Color(0xFFCFDEF3)))
                    2 -> Brush.horizontalGradient(listOf(Color(0xFF2B5876), Color(0xFF6A3093), Color(0xFFC04848)))
                    else -> Brush.horizontalGradient(listOf(Color(0xFF141E30), Color(0xFF243B55), Color(0xFF1A2980)))
                }

                val cutoutLeft = if (simulateCenterNotch && previewWidthPx > 0) (previewWidthPx * 0.44f).roundToInt() else 0
                val cutoutRight = if (simulateCenterNotch && previewWidthPx > 0) (previewWidthPx * 0.56f).roundToInt() else 0

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(backdropBrush)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .onSizeChanged { previewWidthPx = it.width },
                    contentAlignment = Alignment.Center,
                ) {
                    IosStatusBar(
                        state = previewState,
                        config = config,
                        cutoutLeftPx = cutoutLeft,
                        cutoutRightPx = cutoutRight,
                        barHeightDp = if (config.barHeightDp >= 24f) config.barHeightDp.dp else 34.dp,
                    )
                    if (simulateCenterNotch) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(34.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                                .background(Color.Black),
                        )
                    }
                }
            }

            // Inline permission / readiness guidance
            if (config.enabled && (!overlayGranted || !shizukuOk)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = when {
                                    !overlayGranted -> "Overlay permission needed"
                                    shizukuConnected && !shizukuPermGranted -> "Authorize Shizuku permission"
                                    else -> "Shizuku service required to hide native bar"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = if (!overlayGranted) {
                                    "Required to draw the transparent status bar."
                                } else {
                                    "Hides SystemUI's native bar cleanly so icons never duplicate."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                when {
                                    !overlayGranted -> onGrantOverlay()
                                    shizukuConnected && !shizukuPermGranted -> onRequestShizukuPermission()
                                    else -> onOpenShizukuManager()
                                }
                            },
                        ) {
                            Text(
                                text = when {
                                    !overlayGranted -> "Grant"
                                    shizukuConnected && !shizukuPermGranted -> "Authorize"
                                    else -> "Open Shizuku"
                                },
                            )
                        }
                    }
                }
            }

            if (config.enabled && (!hasPhoneStatePermission || !hasBluetoothPermission)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Optional Android 13 telemetry access",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = "Grant Phone & Nearby Devices access to read 5G/LTE data type and active Bluetooth headset state.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = onRequestTelemetryPermissions) {
                            Text("Allow")
                        }
                    }
                }
            }

            // ===================================================================
            // 1. CLOCK CUSTOMIZATION
            // ===================================================================
            var expandClock by remember { mutableStateOf(false) }
            CustomizationGroupCard(
                icon = Icons.Rounded.AccessTime,
                title = "1. Clock",
                summary = if (!config.showClock) "Hidden" else "${config.clockFormat.label} • ${config.clockTextSizeSp.roundToInt()}sp${if (config.showClockSeconds) " • Seconds" else ""}",
                expanded = expandClock,
                onToggleExpand = { expandClock = !expandClock },
                tag = "csb_group_clock",
            ) {
                SettingToggleRow(
                    title = "Show Clock",
                    subtitle = "Display live system time on the status bar",
                    checked = config.showClock,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showClock = v) } },
                )
                SettingToggleRow(
                    title = "Show Seconds",
                    subtitle = "Updates once per second only while screen is on and bar is visible",
                    checked = config.showClockSeconds,
                    enabled = config.showClock,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showClockSeconds = v) } },
                )
                ChipSelectorRow(
                    label = "Time Format",
                    options = ClockFormat.entries.map { it to it.label },
                    selected = config.clockFormat,
                    onSelect = { fmt -> onUpdateConfig { it.copy(clockFormat = fmt) } },
                )
                SliderSettingRow(
                    label = "Clock Size",
                    value = config.clockTextSizeSp,
                    valueRange = 10f..18f,
                    valueLabel = "${config.clockTextSizeSp.roundToInt()} sp",
                    onValueChange = { s -> onUpdateConfig { it.copy(clockTextSizeSp = s.roundToInt().toFloat()) } },
                )
                ChipSelectorRow(
                    label = "Clock Horizontal Alignment",
                    options = StatusBarHorizontalAlign.entries.map { it to it.label },
                    selected = config.clockAlignment,
                    onSelect = { align -> onUpdateConfig { it.copy(clockAlignment = align) } },
                )
                SliderSettingRow(
                    label = "Clock Horizontal Offset",
                    value = config.clockOffsetXDp,
                    valueRange = -32f..32f,
                    valueLabel = "${config.clockOffsetXDp.roundToInt()} dp",
                    onValueChange = { x -> onUpdateConfig { it.copy(clockOffsetXDp = x.roundToInt().toFloat()) } },
                )
            }

            // ===================================================================
            // 2. BATTERY CUSTOMIZATION
            // ===================================================================
            var expandBattery by remember { mutableStateOf(false) }
            CustomizationGroupCard(
                icon = Icons.Rounded.BatteryChargingFull,
                title = "2. Battery",
                summary = "${config.batteryPercentMode.label} • ${(config.batteryIconScale * 100).roundToInt()}%",
                expanded = expandBattery,
                onToggleExpand = { expandBattery = !expandBattery },
                tag = "csb_group_battery",
            ) {
                SettingToggleRow(
                    title = "Show Battery Icon",
                    subtitle = "Display the iOS battery level capsule",
                    checked = config.showBatteryIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showBatteryIcon = v) } },
                )
                ChipSelectorRow(
                    label = "Percentage Placement",
                    options = BatteryPercentMode.entries.map { it to it.label },
                    selected = config.batteryPercentMode,
                    subtitle = config.batteryPercentMode.subtitle,
                    onSelect = { mode -> onUpdateConfig { it.copy(batteryPercentMode = mode) } },
                )
                SliderSettingRow(
                    label = "Battery Icon Size",
                    value = config.batteryIconScale,
                    valueRange = 0.75f..1.40f,
                    valueLabel = "${(config.batteryIconScale * 100).roundToInt()}%",
                    onValueChange = { scale ->
                        val snapped = (scale * 20f).roundToInt() / 20f
                        onUpdateConfig { it.copy(batteryIconScale = snapped) }
                    },
                )
                SettingToggleRow(
                    title = "Show Charging Indicator",
                    subtitle = "Green charging fill and lightning bolt while plugged in",
                    checked = config.showChargingIndicator,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showChargingIndicator = v) } },
                )
            }

            // ===================================================================
            // 3. NETWORK & SYSTEM ICONS
            // ===================================================================
            var expandIcons by remember { mutableStateOf(false) }
            val enabledIconCount = listOf(
                config.showNotificationIcons,
                config.showWifiIcon,
                config.showMobileSignal,
                config.showNetworkType,
                config.showBluetoothIcon,
                config.showSilentIcon,
                config.showDndIcon,
                config.showAlarmIcon,
                config.showLocationIcon,
                config.showAirplaneMode,
            ).count { it }

            CustomizationGroupCard(
                icon = Icons.Rounded.Wifi,
                title = "3. Network & System Icons",
                summary = "$enabledIconCount of 10 indicators enabled",
                expanded = expandIcons,
                onToggleExpand = { expandIcons = !expandIcons },
                tag = "csb_group_icons",
            ) {
                SettingToggleRow(
                    title = "Notification App Icons",
                    subtitle = "Show monochrome app icons beside the clock for active notifications",
                    checked = config.showNotificationIcons,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showNotificationIcons = v) } },
                    testTag = "csb_notif_icons_toggle",
                )
                if (config.showNotificationIcons) {
                    ChipSelectorRow(
                        label = "Max Notification Icons",
                        options = (1..6).map { it to "$it" },
                        selected = config.maxNotificationIcons.coerceIn(1, 6),
                        subtitle = "Shows up to ${config.maxNotificationIcons.coerceIn(1, 6)} app icons, plus a dot indicator when more notifications exist",
                        onSelect = { count -> onUpdateConfig { it.copy(maxNotificationIcons = count) } },
                    )
                    SettingToggleRow(
                        title = "Include Silent Notifications",
                        subtitle = "Show low-priority and silent notification app icons in the status bar",
                        checked = config.includeSilentNotifications,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(includeSilentNotifications = v) } },
                        testTag = "csb_include_silent_notifs_toggle",
                    )
                    if (!notificationAccessGranted) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Grant Notification Access to display live notification icons.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                FilledTonalButton(onClick = onOpenNotificationAccess) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
                SettingToggleRow(
                    title = "Wi-Fi Signal",
                    subtitle = "3-arc iOS Wi-Fi strength indicator",
                    checked = config.showWifiIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showWifiIcon = v) } },
                )
                SettingToggleRow(
                    title = "Mobile Network Bars",
                    subtitle = "4-bar cellular signal staircase",
                    checked = config.showMobileSignal,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showMobileSignal = v) } },
                )
                SettingToggleRow(
                    title = "Mobile Data Type (5G / LTE / 4G)",
                    subtitle = "Shown when cellular data is active without Wi-Fi",
                    checked = config.showNetworkType,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showNetworkType = v) } },
                )
                SettingToggleRow(
                    title = "Airplane Mode",
                    subtitle = "Replaces network indicators when Airplane Mode is active",
                    checked = config.showAirplaneMode,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showAirplaneMode = v) } },
                )
                SettingToggleRow(
                    title = "Bluetooth",
                    subtitle = "Shows when Bluetooth is turned on or connected to audio",
                    checked = config.showBluetoothIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showBluetoothIcon = v) } },
                )
                SettingToggleRow(
                    title = "Silent & Vibrate Mode",
                    subtitle = "Shows icon when ringer is set to Silent or Vibrate",
                    checked = config.showSilentIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showSilentIcon = v) } },
                )
                SettingToggleRow(
                    title = "Do Not Disturb",
                    subtitle = "Shows crescent icon while Do Not Disturb is active",
                    checked = config.showDndIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showDndIcon = v) } },
                )
                SettingToggleRow(
                    title = "Scheduled Alarm",
                    subtitle = "Shows alarm icon when an upcoming alarm clock is set",
                    checked = config.showAlarmIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showAlarmIcon = v) } },
                )
                SettingToggleRow(
                    title = "System Location",
                    subtitle = "Shows when Android device Location is turned on (Android 13 restricts third-party app GPS privacy dots)",
                    checked = config.showLocationIcon,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(showLocationIcon = v) } },
                )
            }

            // ===================================================================
            // 4. POSITION & LAYOUT
            // ===================================================================
            var expandLayout by remember { mutableStateOf(false) }
            CustomizationGroupCard(
                icon = Icons.Rounded.Tune,
                title = "4. Position & Layout",
                summary = "${if (config.barHeightDp < 24f) "Auto height" else "${config.barHeightDp.roundToInt()}dp"} • ${config.iconSizeDp.roundToInt()}dp icons",
                expanded = expandLayout,
                onToggleExpand = { expandLayout = !expandLayout },
                tag = "csb_group_layout",
            ) {
                ChipSelectorRow(
                    label = "Indicator Group Alignment",
                    options = StatusBarHorizontalAlign.entries.map { it to it.label },
                    selected = config.indicatorAlignment,
                    onSelect = { align -> onUpdateConfig { it.copy(indicatorAlignment = align) } },
                )
                SliderSettingRow(
                    label = "Indicator Horizontal Offset",
                    value = config.indicatorOffsetXDp,
                    valueRange = -32f..32f,
                    valueLabel = "${config.indicatorOffsetXDp.roundToInt()} dp",
                    onValueChange = { x -> onUpdateConfig { it.copy(indicatorOffsetXDp = x.roundToInt().toFloat()) } },
                )
                SliderSettingRow(
                    label = "Status Bar Height",
                    value = if (config.barHeightDp < 24f) 22f else config.barHeightDp,
                    valueRange = 22f..48f,
                    valueLabel = if (config.barHeightDp < 24f) "Auto" else "${config.barHeightDp.roundToInt()} dp",
                    onValueChange = { h ->
                        val v = if (h < 23.5f) 0f else h.roundToInt().toFloat()
                        onUpdateConfig { it.copy(barHeightDp = v) }
                    },
                )
                ChipSelectorRow(
                    label = "Vertical Alignment",
                    options = StatusBarVerticalAlign.entries.map { it to it.label },
                    selected = config.verticalAlignment,
                    onSelect = { va -> onUpdateConfig { it.copy(verticalAlignment = va) } },
                )
                SliderSettingRow(
                    label = "Vertical Offset",
                    value = config.verticalOffsetDp,
                    valueRange = -8f..8f,
                    valueLabel = "${config.verticalOffsetDp.roundToInt()} dp",
                    onValueChange = { y -> onUpdateConfig { it.copy(verticalOffsetDp = y.roundToInt().toFloat()) } },
                )
                SliderSettingRow(
                    label = "Icon Size",
                    value = config.iconSizeDp,
                    valueRange = 10f..18f,
                    valueLabel = "${config.iconSizeDp.roundToInt()} dp",
                    onValueChange = { sz -> onUpdateConfig { it.copy(iconSizeDp = sz.roundToInt().toFloat()) } },
                )
                SliderSettingRow(
                    label = "Spacing Between Icons",
                    value = config.iconSpacingDp,
                    valueRange = 2f..10f,
                    valueLabel = "${config.iconSpacingDp.roundToInt()} dp",
                    onValueChange = { sp -> onUpdateConfig { it.copy(iconSpacingDp = sp.roundToInt().toFloat()) } },
                )
            }

            // ===================================================================
            // 5. APPEARANCE (ALWAYS TRANSPARENT)
            // ===================================================================
            var expandAppearance by remember { mutableStateOf(false) }
            CustomizationGroupCard(
                icon = Icons.Rounded.Palette,
                title = "5. Appearance",
                summary = "${config.tintMode.label} • Transparent background",
                expanded = expandAppearance,
                onToggleExpand = { expandAppearance = !expandAppearance },
                tag = "csb_group_appearance",
            ) {
                ChipSelectorRow(
                    label = "Color & Contrast Mode",
                    options = StatusBarTintMode.entries.map { it to it.label },
                    selected = config.tintMode,
                    subtitle = config.tintMode.subtitle,
                    onSelect = { mode -> onUpdateConfig { it.copy(tintMode = mode) } },
                )

                SettingToggleRow(
                    title = "Protective Readability Shadow",
                    subtitle = "Adds subtle soft drop-shadow behind icons and clock for bright wallpapers",
                    checked = config.textShadowEnabled,
                    enabled = config.tintMode != StatusBarTintMode.LIGHT && config.tintMode != StatusBarTintMode.DARK,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(textShadowEnabled = v) } },
                )

                ColorSwatchPickerRow(
                    label = "Clock Text Color",
                    selectedArgb = config.customClockColorArgb,
                    onSelectColor = { argb ->
                        onUpdateConfig {
                            it.copy(
                                tintMode = StatusBarTintMode.CUSTOM,
                                customClockColorArgb = argb,
                            )
                        }
                    },
                )

                ColorSwatchPickerRow(
                    label = "Status Icon Color",
                    selectedArgb = config.customIconColorArgb,
                    onSelectColor = { argb ->
                        onUpdateConfig {
                            it.copy(
                                tintMode = StatusBarTintMode.CUSTOM,
                                customIconColorArgb = argb,
                            )
                        }
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f))

            // Reset to defaults + Attribution
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Native status bar hiding via Shizuku adapted from Corniche (MIT License).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                )
                OutlinedButton(
                    onClick = {
                        haptics?.lightTap()
                        onResetConfig()
                    },
                    modifier = Modifier.testTag("csb_reset_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun PreviewChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CustomizationGroupCard(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    tag: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalSuperHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) {
                val next = !checked
                if (next) haptics?.tileToggleOn() else haptics?.tileToggleOff()
                onCheckedChange(next)
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { v ->
                if (v) haptics?.tileToggleOn() else haptics?.tileToggleOff()
                onCheckedChange(v)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipSelectorRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    subtitle: String? = null,
    onSelect: (T) -> Unit,
) {
    val haptics = LocalSuperHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (item, itemLabel) ->
                val isSelected = item == selected
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics?.sliderTick()
                            onSelect(item)
                        },
                ) {
                    Text(
                        text = itemLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchPickerRow(
    label: String,
    selectedArgb: Long,
    onSelectColor: (Long) -> Unit,
) {
    val haptics = LocalSuperHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            STATUS_BAR_COLOR_PRESETS.forEach { preset ->
                val selected = preset.argb == selectedArgb
                val swatchColor = Color(preset.argb)
                val checkTint = if (preset.argb == 0xFF111317L) Color.White else Color(0xFF111317)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            shape = CircleShape,
                        )
                        .clickable {
                            haptics?.sliderTick()
                            onSelectColor(preset.argb)
                        },
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = preset.name,
                            tint = checkTint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
