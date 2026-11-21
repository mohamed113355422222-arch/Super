package com.supershade.ui.statusbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supershade.domain.system.CustomStatusBarState
import kotlin.math.max

/**
 * Pure transparent, iPhone-inspired Status Bar composable.
 *
 * Strictly background-free (no Surface, no Card, no opaque strip): only crisp Canvas/vector
 * icons and tabular typography are rendered, sized and positioned around any display cutout
 * (such as center waterdrop notches or corner punch-hole cameras).
 *
 * Adapted from status bar replacement concepts in Corniche (MIT License).
 */
@Composable
fun IosStatusBar(
    state: CustomStatusBarState,
    config: StatusBarConfig = StatusBarConfig(),
    cutoutLeftPx: Int = 0,
    cutoutRightPx: Int = 0,
    safeInsetLeftPx: Int = 0,
    safeInsetRightPx: Int = 0,
    barHeightDp: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val isSystemDark = isSystemInDarkTheme()
    val effectiveBarHeight = if (config.barHeightDp >= 24f) config.barHeightDp.dp else barHeightDp

    val clockColor: Color
    val iconColor: Color
    val useLightShadowBase: Boolean

    when (config.tintMode) {
        StatusBarTintMode.AUTO_CONTRAST -> {
            clockColor = Color.White
            iconColor = Color.White
            useLightShadowBase = true
        }
        StatusBarTintMode.LIGHT -> {
            clockColor = Color.White
            iconColor = Color.White
            useLightShadowBase = true
        }
        StatusBarTintMode.DARK -> {
            clockColor = Color(0xFF111317)
            iconColor = Color(0xFF111317)
            useLightShadowBase = false
        }
        StatusBarTintMode.FOLLOW_SYSTEM -> {
            val fg = if (isSystemDark) Color.White else Color(0xFF111317)
            clockColor = fg
            iconColor = fg
            useLightShadowBase = isSystemDark
        }
        StatusBarTintMode.CUSTOM -> {
            clockColor = Color(config.customClockColorArgb)
            iconColor = Color(config.customIconColorArgb)
            val luminance = (0.299f * iconColor.red + 0.587f * iconColor.green + 0.114f * iconColor.blue)
            useLightShadowBase = luminance >= 0.5f
        }
    }

    val applyShadow = config.textShadowEnabled &&
        config.tintMode != StatusBarTintMode.LIGHT &&
        config.tintMode != StatusBarTintMode.DARK

    val shadowColor = if (useLightShadowBase) {
        Color.Black.copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val textShadow = if (applyShadow) {
        Shadow(color = shadowColor, offset = Offset(0f, 1f), blurRadius = 4f)
    } else null

    val density = LocalDensity.current
    val verticalAlign = when (config.verticalAlignment) {
        StatusBarVerticalAlign.TOP -> Alignment.Top
        StatusBarVerticalAlign.CENTER -> Alignment.CenterVertically
        StatusBarVerticalAlign.BOTTOM -> Alignment.Bottom
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(effectiveBarHeight)
            .testTag("ios_statusbar_root"),
        contentAlignment = Alignment.Center,
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val hasCenterCutout = cutoutRightPx > cutoutLeftPx &&
            cutoutLeftPx > totalWidthPx * 0.15f &&
            cutoutRightPx < totalWidthPx * 0.85f

        val startPadding = with(density) {
            max(safeInsetLeftPx.toFloat(), 14.dp.toPx()).toDp()
        }
        val endPadding = with(density) {
            max(safeInsetRightPx.toFloat(), 14.dp.toPx()).toDp()
        }

        // Corner punch-hole clearance
        val effectiveStartPad = if (!hasCenterCutout && cutoutRightPx > 0 && cutoutRightPx < totalWidthPx * 0.35f) {
            with(density) { (cutoutRightPx + 8.dp.toPx()).toDp() }
        } else startPadding

        val effectiveEndPad = if (!hasCenterCutout && cutoutLeftPx > totalWidthPx * 0.65f && cutoutRightPx > cutoutLeftPx) {
            with(density) { ((totalWidthPx - cutoutLeftPx) + 8.dp.toPx()).toDp() }
        } else endPadding

        val leftZoneMaxWidth = if (hasCenterCutout) {
            with(density) { (cutoutLeftPx - 6.dp.toPx()).coerceAtLeast(0f).toDp() } - effectiveStartPad
        } else null

        val rightZoneMaxWidth = if (hasCenterCutout) {
            with(density) { ((totalWidthPx - cutoutRightPx) - 6.dp.toPx()).coerceAtLeast(0f).toDp() } - effectiveEndPad
        } else null

        val verticalOffsetPx = with(density) { config.verticalOffsetDp.dp.toPx() }
        val clockOffsetXPx = with(density) { config.clockOffsetXDp.dp.toPx() }
        val indicatorOffsetXPx = with(density) { config.indicatorOffsetXDp.dp.toPx() }

        val verticalPadModifier = when (config.verticalAlignment) {
            StatusBarVerticalAlign.TOP -> Modifier.padding(top = 2.dp)
            StatusBarVerticalAlign.BOTTOM -> Modifier.padding(bottom = 2.dp)
            StatusBarVerticalAlign.CENTER -> Modifier
        }

        // Resolve effective slot for Clock & Indicator group so center cutouts never occlude elements
        val effectiveClockAlign = if (hasCenterCutout && config.clockAlignment == StatusBarHorizontalAlign.CENTER) {
            StatusBarHorizontalAlign.LEFT
        } else {
            config.clockAlignment
        }
        val effectiveIndicatorAlign = if (hasCenterCutout && config.indicatorAlignment == StatusBarHorizontalAlign.CENTER) {
            StatusBarHorizontalAlign.RIGHT
        } else {
            config.indicatorAlignment
        }

        @Composable
        fun ClockContent(modifier: Modifier = Modifier) {
            if (!config.showClock) return
            Text(
                text = state.timeText.ifBlank { "--:--" },
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = config.clockTextSizeSp.coerceIn(10f, 18f).sp,
                    letterSpacing = (-0.25).sp,
                    fontFeatureSettings = "tnum",
                    shadow = textShadow,
                ),
                color = clockColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = modifier
                    .graphicsLayer {
                        translationX = clockOffsetXPx
                        translationY = verticalOffsetPx
                    }
                    .testTag("ios_statusbar_clock"),
            )
        }

        val maxIcons = config.maxNotificationIcons.coerceIn(1, 6)
        val hasVisibleNotifIcons = config.showNotificationIcons && state.notificationIcons.isNotEmpty()
        val displayedNotifIcons = if (hasVisibleNotifIcons) state.notificationIcons.take(maxIcons) else emptyList()
        val showNotifOverflowDot = hasVisibleNotifIcons &&
            (state.hasOverflowNotifications || state.notificationIcons.size > maxIcons)

        @Composable
        fun NotificationIconsContent(modifier: Modifier = Modifier) {
            if (!hasVisibleNotifIcons) return
            val notifIconDp = config.iconSizeDp.coerceIn(10f, 18f).dp
            val notifSpacingDp = (config.iconSpacingDp * 0.8f).coerceIn(2f, 8f).dp
            val dotSizeDp = (config.iconSizeDp * 0.30f).coerceIn(3.5f, 5.5f).dp

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(notifSpacingDp),
                modifier = modifier
                    .graphicsLayer {
                        translationX = if (effectiveClockAlign == StatusBarHorizontalAlign.LEFT) clockOffsetXPx else 0f
                        translationY = verticalOffsetPx
                    }
                    .clipToBounds()
                    .testTag("ios_statusbar_notif_icons"),
            ) {
                displayedNotifIcons.forEachIndexed { idx, item ->
                    val label = item.appName.ifBlank { item.packageName }
                    val tag = "ios_statusbar_notif_icon_$idx"
                    if (item.bitmap != null) {
                        Image(
                            bitmap = item.bitmap,
                            contentDescription = label,
                            colorFilter = ColorFilter.tint(iconColor),
                            modifier = Modifier
                                .size(notifIconDp)
                                .testTag(tag),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = label,
                            tint = iconColor,
                            modifier = Modifier
                                .size(notifIconDp)
                                .testTag(tag),
                        )
                    }
                }
                if (showNotifOverflowDot) {
                    Canvas(
                        modifier = Modifier
                            .size(dotSizeDp)
                            .semantics { contentDescription = "More notifications" }
                            .testTag("ios_statusbar_notif_overflow_dot"),
                    ) {
                        if (applyShadow) {
                            drawCircle(
                                color = shadowColor,
                                center = Offset(size.width / 2f, size.height / 2f + 0.5.dp.toPx()),
                            )
                        }
                        drawCircle(color = iconColor.copy(alpha = 0.88f))
                    }
                }
            }
        }

        @Composable
        fun IndicatorContent(modifier: Modifier = Modifier) {
            val iconScale = (config.iconSizeDp / 13f).coerceIn(0.75f, 1.45f)
            val iconDp = config.iconSizeDp.coerceIn(10f, 18f).dp
            val spacingDp = config.iconSpacingDp.coerceIn(2f, 10f).dp

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacingDp),
                modifier = modifier
                    .graphicsLayer {
                        translationX = indicatorOffsetXPx
                        translationY = verticalOffsetPx
                    }
                    .testTag("ios_statusbar_indicators"),
            ) {
                // System status icons
                if (config.showLocationIcon && state.isLocationEnabled) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = "Location active",
                        tint = iconColor.copy(alpha = 0.94f),
                        modifier = Modifier.size(iconDp),
                    )
                }

                if (config.showDndIcon && state.isDndActive) {
                    Icon(
                        imageVector = Icons.Rounded.NightsStay,
                        contentDescription = "Do Not Disturb",
                        tint = iconColor,
                        modifier = Modifier.size(iconDp),
                    )
                }

                if (config.showSilentIcon && (state.isSilent || state.isVibrate)) {
                    Icon(
                        imageVector = if (state.isSilent) Icons.AutoMirrored.Rounded.VolumeOff else Icons.Rounded.Vibration,
                        contentDescription = if (state.isSilent) "Silent mode" else "Vibrate mode",
                        tint = iconColor.copy(alpha = 0.94f),
                        modifier = Modifier.size(iconDp),
                    )
                }

                if (config.showAlarmIcon && state.hasAlarm) {
                    Icon(
                        imageVector = Icons.Rounded.Alarm,
                        contentDescription = "Alarm set",
                        tint = iconColor.copy(alpha = 0.94f),
                        modifier = Modifier.size(iconDp),
                    )
                }

                if (config.showBluetoothIcon && state.isBluetoothEnabled) {
                    Icon(
                        imageVector = if (state.isBluetoothConnected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                        contentDescription = if (state.isBluetoothConnected) "Bluetooth connected" else "Bluetooth enabled",
                        tint = if (state.isBluetoothConnected) iconColor else iconColor.copy(alpha = 0.72f),
                        modifier = Modifier.size(iconDp),
                    )
                }

                // Network indicators
                if (config.showAirplaneMode && state.isAirplaneMode) {
                    Icon(
                        imageVector = Icons.Rounded.AirplanemodeActive,
                        contentDescription = "Airplane mode",
                        tint = iconColor,
                        modifier = Modifier.size(iconDp + 1.dp),
                    )
                } else {
                    if (config.showMobileSignal &&
                        (state.hasCellularService || state.isCellularConnected || state.cellularLevel > 0)
                    ) {
                        IosSignalBars(
                            level = state.cellularLevel,
                            fgColor = iconColor,
                            shadowColor = if (applyShadow) shadowColor else null,
                            scale = iconScale,
                        )
                    }

                    if (config.showNetworkType &&
                        !state.isWifiConnected &&
                        state.isCellularConnected &&
                        state.cellularTypeLabel.isNotBlank()
                    ) {
                        Text(
                            text = state.cellularTypeLabel,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = (11f * iconScale).coerceIn(9f, 14f).sp,
                                letterSpacing = (-0.2).sp,
                                shadow = textShadow,
                            ),
                            color = iconColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    if (config.showWifiIcon && state.isWifiConnected) {
                        IosWifiIcon(
                            level = state.wifiLevel,
                            fgColor = iconColor,
                            shadowColor = if (applyShadow) shadowColor else null,
                            scale = iconScale,
                        )
                    }
                }

                // Battery capsule & percentage (never duplicated)
                if (config.showBatteryIcon || config.batteryPercentMode != BatteryPercentMode.HIDDEN) {
                    IosBatteryIndicator(
                        batteryPct = state.batteryPct,
                        isCharging = state.isCharging,
                        isPowerSave = state.isPowerSave,
                        showBatteryIcon = config.showBatteryIcon,
                        percentMode = config.batteryPercentMode,
                        showChargingIndicator = config.showChargingIndicator,
                        batteryScale = config.batteryIconScale.coerceIn(0.75f, 1.40f),
                        fgColor = iconColor,
                        useLightForeground = useLightShadowBase,
                        textShadow = textShadow,
                        shadowColor = if (applyShadow) shadowColor else null,
                    )
                }
            }
        }

        if (hasCenterCutout) {
            // Two-zone cutout-safe layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(verticalPadModifier)
                    .padding(start = effectiveStartPad, end = effectiveEndPad),
                verticalAlignment = verticalAlign,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .width((leftZoneMaxWidth ?: 80.dp).coerceAtLeast(48.dp))
                        .fillMaxHeight(),
                    contentAlignment = when (verticalAlign) {
                        Alignment.Top -> Alignment.TopStart
                        Alignment.Bottom -> Alignment.BottomStart
                        else -> Alignment.CenterStart
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(config.iconSpacingDp.dp),
                    ) {
                        if (effectiveClockAlign == StatusBarHorizontalAlign.LEFT) ClockContent()
                        NotificationIconsContent(modifier = Modifier.weight(1f, fill = false))
                        if (effectiveIndicatorAlign == StatusBarHorizontalAlign.LEFT) IndicatorContent()
                    }
                }

                val centerGapDp = with(density) {
                    ((cutoutRightPx - cutoutLeftPx) + 12.dp.toPx()).coerceAtLeast(16f).toDp()
                }
                Spacer(modifier = Modifier.width(centerGapDp))

                Box(
                    modifier = Modifier
                        .width((rightZoneMaxWidth ?: 100.dp).coerceAtLeast(72.dp))
                        .fillMaxHeight(),
                    contentAlignment = when (verticalAlign) {
                        Alignment.Top -> Alignment.TopEnd
                        Alignment.Bottom -> Alignment.BottomEnd
                        else -> Alignment.CenterEnd
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(config.iconSpacingDp.dp),
                    ) {
                        if (effectiveClockAlign == StatusBarHorizontalAlign.RIGHT) ClockContent()
                        if (effectiveIndicatorAlign == StatusBarHorizontalAlign.RIGHT) IndicatorContent()
                    }
                }
            }
        } else {
            // Unobstructed top edge (no center cutout): true Left / Center / Right placement
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(verticalPadModifier)
                    .padding(start = effectiveStartPad, end = effectiveEndPad),
            ) {
                val leftItems: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(config.iconSpacingDp.dp),
                    ) {
                        if (effectiveClockAlign == StatusBarHorizontalAlign.LEFT) ClockContent()
                        NotificationIconsContent()
                        if (effectiveIndicatorAlign == StatusBarHorizontalAlign.LEFT) IndicatorContent()
                    }
                }
                val centerItems: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(config.iconSpacingDp.dp),
                    ) {
                        if (effectiveClockAlign == StatusBarHorizontalAlign.CENTER) ClockContent()
                        if (effectiveIndicatorAlign == StatusBarHorizontalAlign.CENTER) IndicatorContent()
                    }
                }
                val rightItems: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(config.iconSpacingDp.dp),
                    ) {
                        if (effectiveClockAlign == StatusBarHorizontalAlign.RIGHT) ClockContent()
                        if (effectiveIndicatorAlign == StatusBarHorizontalAlign.RIGHT) IndicatorContent()
                    }
                }

                if (effectiveClockAlign == StatusBarHorizontalAlign.LEFT ||
                    effectiveIndicatorAlign == StatusBarHorizontalAlign.LEFT ||
                    hasVisibleNotifIcons
                ) {
                    Box(
                        modifier = Modifier.align(
                            when (verticalAlign) {
                                Alignment.Top -> Alignment.TopStart
                                Alignment.Bottom -> Alignment.BottomStart
                                else -> Alignment.CenterStart
                            }
                        ),
                    ) { leftItems() }
                }

                if (effectiveClockAlign == StatusBarHorizontalAlign.CENTER ||
                    effectiveIndicatorAlign == StatusBarHorizontalAlign.CENTER
                ) {
                    Box(
                        modifier = Modifier.align(
                            when (verticalAlign) {
                                Alignment.Top -> Alignment.TopCenter
                                Alignment.Bottom -> Alignment.BottomCenter
                                else -> Alignment.Center
                            }
                        ),
                    ) { centerItems() }
                }

                if (effectiveClockAlign == StatusBarHorizontalAlign.RIGHT ||
                    effectiveIndicatorAlign == StatusBarHorizontalAlign.RIGHT
                ) {
                    Box(
                        modifier = Modifier.align(
                            when (verticalAlign) {
                                Alignment.Top -> Alignment.TopEnd
                                Alignment.Bottom -> Alignment.BottomEnd
                                else -> Alignment.CenterEnd
                            }
                        ),
                    ) { rightItems() }
                }
            }
        }
    }
}

/**
 * Authentic 4-bar iOS cellular signal staircase icon drawn via Canvas.
 */
@Composable
private fun IosSignalBars(
    level: Int,
    fgColor: Color,
    shadowColor: Color?,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = (16f * scale).dp, height = (11f * scale).dp)) {
        val barCount = 4
        val gap = (1.6f * scale).dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val minHeight = size.height * 0.30f
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val fraction = (i + 1).toFloat() / barCount.toFloat()
            val h = minHeight + (maxHeight - minHeight) * fraction
            val left = i * (barWidth + gap)
            val top = size.height - h
            val isLit = (i + 1) <= level
            val color = if (isLit) fgColor else fgColor.copy(alpha = 0.30f)

            if (shadowColor != null && isLit) {
                drawRoundRect(
                    color = shadowColor,
                    topLeft = Offset(left, top + 0.6.dp.toPx()),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/**
 * Authentic iOS 3-arc Wi-Fi fan icon drawn via Canvas.
 */
@Composable
private fun IosWifiIcon(
    level: Int,
    fgColor: Color,
    shadowColor: Color?,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = (15f * scale).dp, height = (11.5f * scale).dp)) {
        val centerX = size.width / 2f
        val bottomY = size.height - (1.2f * scale).dp.toPx()
        val stroke = (1.6f * scale).dp.toPx()

        val dotRadius = (1.15f * scale).dp.toPx()
        val dotActive = level >= 1
        val dotColor = if (dotActive) fgColor else fgColor.copy(alpha = 0.30f)
        if (shadowColor != null && dotActive) {
            drawCircle(
                color = shadowColor,
                radius = dotRadius,
                center = Offset(centerX, bottomY + 0.5.dp.toPx()),
            )
        }
        drawCircle(
            color = dotColor,
            radius = dotRadius,
            center = Offset(centerX, bottomY),
        )

        val radii = listOf(4.2f * scale, 7.2f * scale, 10.2f * scale).map { it.dp.toPx() }
        radii.forEachIndexed { idx, r ->
            val arcActive = level >= (idx + 1)
            val color = if (arcActive) fgColor else fgColor.copy(alpha = 0.28f)
            val rect = Rect(
                left = centerX - r,
                top = bottomY - r,
                right = centerX + r,
                bottom = bottomY + r,
            )
            if (shadowColor != null && arcActive) {
                drawArc(
                    color = shadowColor,
                    startAngle = -135f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(rect.left, rect.top + 0.5.dp.toPx()),
                    size = rect.size,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawArc(
                color = color,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Modern iPhone-style battery capsule with three non-duplicating percentage modes:
 * - [BatteryPercentMode.BESIDE]: percentage label beside the battery capsule
 * - [BatteryPercentMode.INSIDE]: custom iOS 16+ Canvas renderer drawing percentage numerals inside the capsule
 * - [BatteryPercentMode.HIDDEN]: battery capsule only
 */
@Composable
private fun IosBatteryIndicator(
    batteryPct: Int,
    isCharging: Boolean,
    isPowerSave: Boolean,
    showBatteryIcon: Boolean,
    percentMode: BatteryPercentMode,
    showChargingIndicator: Boolean,
    batteryScale: Float,
    fgColor: Color,
    useLightForeground: Boolean,
    textShadow: Shadow?,
    shadowColor: Color?,
    modifier: Modifier = Modifier,
) {
    val clamped = batteryPct.coerceIn(0, 100)
    val showBolt = showChargingIndicator && isCharging
    val fillColor = when {
        showChargingIndicator && isCharging -> Color(0xFF34C759)
        isPowerSave -> Color(0xFFFFCC00)
        clamped <= 20 -> Color(0xFFFF3B30)
        else -> fgColor
    }
    val pctTextColor = when {
        showChargingIndicator && isCharging -> Color(0xFF34C759)
        clamped <= 20 -> Color(0xFFFF3B30)
        else -> fgColor
    }
    val outlineColor = fgColor.copy(alpha = 0.45f)

    // When user hides the battery icon itself but keeps percentage mode active, show text once.
    if (!showBatteryIcon) {
        if (percentMode != BatteryPercentMode.HIDDEN) {
            Text(
                text = "$clamped%",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (11.5f * batteryScale).coerceIn(9.5f, 15f).sp,
                    letterSpacing = (-0.25).sp,
                    fontFeatureSettings = "tnum",
                    shadow = textShadow,
                ),
                color = pctTextColor,
                maxLines = 1,
                softWrap = false,
                modifier = modifier.testTag("ios_statusbar_battery_pct"),
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        if (percentMode == BatteryPercentMode.BESIDE) {
            Text(
                text = "$clamped%",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (11.5f * batteryScale).coerceIn(9.5f, 15f).sp,
                    letterSpacing = (-0.25).sp,
                    fontFeatureSettings = "tnum",
                    shadow = textShadow,
                ),
                color = pctTextColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("ios_statusbar_battery_pct"),
            )
        }

        val isInsidePct = percentMode == BatteryPercentMode.INSIDE
        val pillWidth = (if (isInsidePct) 28f else 24f) * batteryScale
        val pillHeight = (if (isInsidePct) 13f else 11.5f) * batteryScale
        val insideText = if (isInsidePct) "$clamped" else ""

        Canvas(
            modifier = Modifier
                .size(width = pillWidth.dp, height = pillHeight.dp)
                .semantics {
                    contentDescription = if (isInsidePct) "Battery $clamped%" else "Battery icon $clamped%"
                }
                .then(
                    if (isInsidePct) Modifier.testTag("ios_statusbar_battery_inside")
                    else Modifier.testTag("ios_statusbar_battery_icon")
                ),
        ) {
            val stroke = (if (isInsidePct) 1.1f else 1.15f).dp.toPx() * batteryScale.coerceAtMost(1.2f)
            val nubWidth = 1.6.dp.toPx() * batteryScale
            val nubGap = 0.8.dp.toPx() * batteryScale
            val bodyWidth = size.width - nubWidth - nubGap
            val cornerRadiusPx = (if (isInsidePct) 3.8f else 3.2f).dp.toPx() * batteryScale
            val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

            if (shadowColor != null) {
                drawRoundRect(
                    color = shadowColor.copy(alpha = 0.35f),
                    topLeft = Offset(0f, 0.5.dp.toPx()),
                    size = Size(bodyWidth, size.height),
                    cornerRadius = corner,
                    style = Stroke(width = stroke),
                )
            }

            if (isInsidePct) {
                // Translucent capsule backdrop inside pill so unfilled portion contrasts digits
                drawRoundRect(
                    color = fgColor.copy(alpha = 0.24f),
                    topLeft = Offset.Zero,
                    size = Size(bodyWidth, size.height),
                    cornerRadius = corner,
                )
            }

            drawRoundRect(
                color = outlineColor,
                topLeft = Offset.Zero,
                size = Size(bodyWidth, size.height),
                cornerRadius = corner,
                style = Stroke(width = stroke),
            )

            // Positive terminal nub
            drawRoundRect(
                color = outlineColor,
                topLeft = Offset(bodyWidth + nubGap, size.height * 0.30f),
                size = Size(nubWidth, size.height * 0.40f),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )

            val inset = if (isInsidePct) stroke * 0.85f else stroke + 0.9.dp.toPx()
            val maxFillW = (bodyWidth - inset * 2f).coerceAtLeast(0f)
            val fillW = (maxFillW * (clamped / 100f)).coerceAtLeast(if (clamped > 0) 2.dp.toPx() else 0f)
            val innerCorner = CornerRadius((cornerRadiusPx - inset).coerceAtLeast(1f), (cornerRadiusPx - inset).coerceAtLeast(1f))

            if (fillW > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(inset, inset),
                    size = Size(fillW, size.height - inset * 2f),
                    cornerRadius = innerCorner,
                )
            }

            if (isInsidePct) {
                // Dual-clip text rendering: digits over fill use contrast color, unfilled part uses fgColor
                val fontSizeSp = (9.2f * batteryScale).coerceIn(7.5f, 12.5f).sp
                val style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSizeSp,
                    letterSpacing = (-0.4).sp,
                    fontFeatureSettings = "tnum",
                )
                val layoutResult = textMeasurer.measure(text = insideText, style = style)
                val tx = ((bodyWidth - layoutResult.size.width) / 2f).coerceAtLeast(0f)
                val ty = ((size.height - layoutResult.size.height) / 2f).coerceAtLeast(0f)

                val filledTextColor = when {
                    fillColor == Color(0xFF34C759) -> Color.White
                    fillColor == Color(0xFFFF3B30) -> Color.White
                    fillColor == Color(0xFFFFCC00) -> Color.Black
                    useLightForeground -> Color(0xFF111317)
                    else -> Color.White
                }
                val unfilledTextColor = if (useLightForeground) Color.White else Color(0xFF111317)
                val splitX = (inset + fillW).coerceIn(0f, bodyWidth)

                clipRect(left = 0f, top = 0f, right = splitX, bottom = size.height) {
                    drawText(
                        textLayoutResult = layoutResult,
                        color = filledTextColor,
                        topLeft = Offset(tx, ty),
                    )
                }
                if (splitX < bodyWidth) {
                    clipRect(left = splitX, top = 0f, right = bodyWidth, bottom = size.height) {
                        drawText(
                            textLayoutResult = layoutResult,
                            color = unfilledTextColor,
                            topLeft = Offset(tx, ty),
                        )
                    }
                }
            } else if (showBolt) {
                // Centered charging lightning bolt inside capsule when digits are not inside
                val cx = bodyWidth / 2f
                val cy = size.height / 2f
                val boltColor = if (useLightForeground) Color.White else Color.Black
                val s = batteryScale
                val bolt = Path().apply {
                    moveTo(cx + (0.8f * s).dp.toPx(), cy - (3.6f * s).dp.toPx())
                    lineTo(cx - (2.3f * s).dp.toPx(), cy + (0.4f * s).dp.toPx())
                    lineTo(cx - (0.2f * s).dp.toPx(), cy + (0.4f * s).dp.toPx())
                    lineTo(cx - (0.8f * s).dp.toPx(), cy + (3.6f * s).dp.toPx())
                    lineTo(cx + (2.3f * s).dp.toPx(), cy - (0.4f * s).dp.toPx())
                    lineTo(cx + (0.2f * s).dp.toPx(), cy - (0.4f * s).dp.toPx())
                    close()
                }
                drawPath(path = bolt, color = Color.Black.copy(alpha = 0.35f))
                drawPath(path = bolt, color = boltColor)
            }
        }

        // When percentage is inside the pill AND charging, render a crisp tiny bolt beside the pill
        if (isInsidePct && showBolt) {
            Canvas(modifier = Modifier.size(width = (6.5f * batteryScale).dp, height = (10f * batteryScale).dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val bolt = Path().apply {
                    moveTo(cx + size.width * 0.12f, 0f)
                    lineTo(0f, cy + size.height * 0.08f)
                    lineTo(cx - size.width * 0.04f, cy + size.height * 0.08f)
                    lineTo(cx - size.width * 0.12f, size.height)
                    lineTo(size.width, cy - size.height * 0.08f)
                    lineTo(cx + size.width * 0.04f, cy - size.height * 0.08f)
                    close()
                }
                if (shadowColor != null) {
                    drawPath(path = bolt, color = shadowColor)
                }
                drawPath(path = bolt, color = Color(0xFF34C759))
            }
        }
    }
}
