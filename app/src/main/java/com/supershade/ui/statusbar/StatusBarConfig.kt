package com.supershade.ui.statusbar

import androidx.compose.runtime.Immutable

/**
 * Contrast & tint presets for the transparent iOS-style status bar.
 */
enum class StatusBarTintMode(
    val id: String,
    val label: String,
    val subtitle: String,
) {
    AUTO_CONTRAST(
        id = "auto_contrast",
        label = "Auto",
        subtitle = "Crisp white icons with subtle protective shadow for any wallpaper or app",
    ),
    LIGHT(
        id = "light",
        label = "Light",
        subtitle = "Pure white iOS icons and clock without shadow",
    ),
    DARK(
        id = "dark",
        label = "Dark",
        subtitle = "Deep dark icons ideal for bright light themes",
    ),
    FOLLOW_SYSTEM(
        id = "follow_system",
        label = "System",
        subtitle = "Adapts to system dark/light theme automatically",
    ),
    CUSTOM(
        id = "custom",
        label = "Custom",
        subtitle = "Use independent custom colors for the clock and status icons",
    );

    companion object {
        fun fromId(id: String?): StatusBarTintMode = entries.find { it.id == id } ?: AUTO_CONTRAST
    }
}

enum class ClockFormat(
    val id: String,
    val label: String,
) {
    SYSTEM("system", "System"),
    HOUR_12("12h", "12-Hour"),
    HOUR_24("24h", "24-Hour");

    companion object {
        fun fromId(id: String?): ClockFormat = entries.find { it.id == id } ?: SYSTEM
    }
}

enum class BatteryPercentMode(
    val id: String,
    val label: String,
    val subtitle: String,
) {
    BESIDE("beside", "Beside Icon", "Shows percentage label next to the battery pill"),
    INSIDE("inside", "Inside Icon", "Renders iOS 16+ percentage digits directly inside the battery pill"),
    HIDDEN("hidden", "Hidden", "Shows only the battery level capsule without numeric text");

    companion object {
        fun fromId(id: String?): BatteryPercentMode = entries.find { it.id == id } ?: BESIDE
    }
}

enum class StatusBarHorizontalAlign(
    val id: String,
    val label: String,
) {
    LEFT("left", "Left"),
    CENTER("center", "Center"),
    RIGHT("right", "Right");

    companion object {
        fun fromId(id: String?, default: StatusBarHorizontalAlign): StatusBarHorizontalAlign =
            entries.find { it.id == id } ?: default
    }
}

enum class StatusBarVerticalAlign(
    val id: String,
    val label: String,
) {
    TOP("top", "Top"),
    CENTER("center", "Center"),
    BOTTOM("bottom", "Bottom");

    companion object {
        fun fromId(id: String?): StatusBarVerticalAlign = entries.find { it.id == id } ?: CENTER
    }
}

data class StatusBarColorPreset(
    val name: String,
    val argb: Long,
)

val STATUS_BAR_COLOR_PRESETS = listOf(
    StatusBarColorPreset("White", 0xFFFFFFFFL),
    StatusBarColorPreset("Soft Ivory", 0xFFF5F2E8L),
    StatusBarColorPreset("Ink Black", 0xFF111317L),
    StatusBarColorPreset("Sky Blue", 0xFF7CC4FFL),
    StatusBarColorPreset("Mint", 0xFF6EE7B7L),
    StatusBarColorPreset("Amber", 0xFFFCD34DL),
    StatusBarColorPreset("Coral", 0xFFFDA4AFL),
    StatusBarColorPreset("Lavender", 0xFFC4B5FDL),
)

/**
 * Immutable configuration snapshot for the integrated transparent iOS-style status bar.
 */
@Immutable
data class StatusBarConfig(
    val enabled: Boolean = false,
    // 1. Clock
    val showClock: Boolean = true,
    val showClockSeconds: Boolean = false,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val clockTextSizeSp: Float = 14f,
    val clockAlignment: StatusBarHorizontalAlign = StatusBarHorizontalAlign.LEFT,
    val clockOffsetXDp: Float = 0f,
    // 2. Battery
    val showBatteryIcon: Boolean = true,
    val batteryPercentMode: BatteryPercentMode = BatteryPercentMode.BESIDE,
    val batteryIconScale: Float = 1.0f,
    val showChargingIndicator: Boolean = true,
    // 3. Network, Notification & System Icons
    val showNotificationIcons: Boolean = true,
    val maxNotificationIcons: Int = 3,
    val includeSilentNotifications: Boolean = false,
    val showWifiIcon: Boolean = true,
    val showMobileSignal: Boolean = true,
    val showNetworkType: Boolean = true,
    val showAirplaneMode: Boolean = true,
    val showBluetoothIcon: Boolean = true,
    val showSilentIcon: Boolean = true,
    val showDndIcon: Boolean = true,
    val showAlarmIcon: Boolean = true,
    val showLocationIcon: Boolean = false,
    // 4. Position & Layout
    val indicatorAlignment: StatusBarHorizontalAlign = StatusBarHorizontalAlign.RIGHT,
    val indicatorOffsetXDp: Float = 0f,
    val barHeightDp: Float = 0f, // 0f = System status bar height; 24f..48f = custom height
    val verticalAlignment: StatusBarVerticalAlign = StatusBarVerticalAlign.CENTER,
    val verticalOffsetDp: Float = 0f,
    val iconSizeDp: Float = 13f,
    val iconSpacingDp: Float = 5f,
    // 5. Appearance (background stays 100% transparent)
    val tintMode: StatusBarTintMode = StatusBarTintMode.AUTO_CONTRAST,
    val customClockColorArgb: Long = 0xFFFFFFFFL,
    val customIconColorArgb: Long = 0xFFFFFFFFL,
    val textShadowEnabled: Boolean = true,
)
