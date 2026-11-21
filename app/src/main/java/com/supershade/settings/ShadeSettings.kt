package com.supershade.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.ui.theme.BackdropTheme
import com.supershade.ui.theme.ShadeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class QsTileTapAction {
    TOGGLE_ACTIVE,
    OPEN_SHADE,
    SHOW_MENU,
}

enum class TileShape(val id: String, val label: String, val cornerRadiusDp: Int) {
    SQUIRCLE("squircle", "Squircle", 22),
    ROUNDED("rounded", "Rounded", 16),
    CIRCLE("circle", "Circle", 50),
    PILL("pill", "Stadium Pill", 28),
    SOFT("soft", "Soft Minimal", 12),
    LEAF("leaf", "Asymmetric Leaf", 24),
    SHARP("sharp", "Sharp Modern", 6),
    CLOVER("clover", "Clover 4-Leaf", 20),
    BURST("burst", "M3E SoftBurst", 20);
}

enum class CardBorderWidth(val id: String, val label: String, val widthDp: Float) {
    NONE("none", "Borderless (0dp)", 0f),
    THIN("thin", "Subtle (1dp)", 1f),
    DISTINCT("distinct", "Distinct (1.5dp)", 1.5f),
    BOLD("bold", "Bold (2dp)", 2f);
}

enum class TileSize(
    val id: String,
    val label: String,
    val subtitle: String,
    val heightDp: Int,
    val iconSizeDp: Int,
) {
    COMPACT("compact", "Compact (58dp)", "Space-saving height; fits more notifications and media", 58, 20),
    STANDARD("standard", "Standard (72dp)", "Balanced One UI 8 height with clear icon, label & status", 72, 22),
    COMFORTABLE("comfortable", "Comfortable (84dp)", "Spacious height with large iconography for easy reach", 84, 26);
}

enum class TileGridColumns(val id: String, val label: String, val count: Int) {
    COMFORTABLE("comfortable", "Comfortable (3)", 3),
    STANDARD("standard", "Standard (4)", 4),
    COMPACT("compact", "Compact (5)", 5);
}

enum class NotificationDensity(
    val id: String,
    val label: String,
    val subtitle: String,
) {
    COMPACT("compact", "Compact (Space-saving)", "28dp icons, single-line headers, dense layout fits 2-3x more notifications"),
    BALANCED("balanced", "Balanced (Standard)", "Comfortable One UI spacing with full previews and action chips"),
    EXPANSIVE("expansive", "Expansive (Detailed)", "Generous padding with multi-line text and prominent media");
}

enum class ClassificationMode(
    val id: String,
    val label: String,
    val subtitle: String,
) {
    ONE_UI(
        id = "one_ui",
        label = "One UI 9 (Domain Buckets)",
        subtitle = "Messages, Social, Email, Calls, Tasks, Media, System & Apps",
    ),
    AOSP(
        id = "aosp",
        label = "Pixel / AOSP (Priority)",
        subtitle = "Android 15/16 channels: Conversations, Alerting / Important, and Silent",
    ),
    ESSENTIAL(
        id = "essential",
        label = "Nothing OS (Essential)",
        subtitle = "Streamlined glyph focus: Essential alerts (VIPs, Comms, Alarms) vs General",
    ),
    CYBERPUNK(
        id = "cyberpunk",
        label = "Cyberpunk HUD (Telemetry)",
        subtitle = "Matrix packet routing: Comms, Task Cycles, Audio Feed, and Net Kernel",
    ),
    UNIFIED(
        id = "unified",
        label = "Unified (Flat Feed)",
        subtitle = "No category chips; all alerts shown in a clean, chronological feed",
    );

    companion object {
        fun fromId(id: String?): ClassificationMode = entries.find { it.id == id } ?: ONE_UI
    }
}

fun categoriesForMode(mode: ClassificationMode): List<ShadeCategory> = when (mode) {
    ClassificationMode.ONE_UI -> listOf(
        ShadeCategory.All,
        ShadeCategory.Messages,
        ShadeCategory.Social,
        ShadeCategory.Email,
        ShadeCategory.Calls,
        ShadeCategory.Productivity,
        ShadeCategory.Media,
        ShadeCategory.Alarms,
        ShadeCategory.System,
        ShadeCategory.Apps,
    )
    ClassificationMode.AOSP -> listOf(
        ShadeCategory.All,
        ShadeCategory.Conversations,
        ShadeCategory.Alerting,
        ShadeCategory.Silent,
    )
    ClassificationMode.ESSENTIAL -> listOf(
        ShadeCategory.All,
        ShadeCategory.Essential,
        ShadeCategory.General,
    )
    ClassificationMode.CYBERPUNK -> listOf(
        ShadeCategory.All,
        ShadeCategory.Messages,
        ShadeCategory.Productivity,
        ShadeCategory.Media,
        ShadeCategory.System,
    )
    ClassificationMode.UNIFIED -> emptyList()
}

enum class AccentColor(val label: String, val hex: Long) {
    GALAXY_BLUE("Galaxy Blue", 0xFF2575FC),
    EMERALD("Emerald", 0xFF10B981),
    VIOLET("Violet", 0xFF8B5CF6),
    AMBER("Amber", 0xFFF59E0B),
    CORAL("Coral", 0xFFF43F5E),
    MONET("Dynamic", 0L);
}

enum class SplitGestureMode(
    val id: String,
    val label: String,
    val subtitle: String,
    val qsThreshold: Float,
    val isLeftQs: Boolean = false,
) {
    SEPARATE_70_30(
        "split_70_30",
        "Right 30% (Standard)",
        "Pull right 30% for Quick Settings, left 70% for Notifications",
        0.70f,
    ),
    SEPARATE_50_50(
        "split_50_50",
        "Half & Half (50/50)",
        "One UI 8 / iOS style: pull right half for Quick Settings, left half for Notifications",
        0.50f,
    ),
    SEPARATE_30_70(
        "split_30_70",
        "Left-Handed (30/70)",
        "Pull left 30% for Quick Settings, right 70% for Notifications",
        0.30f,
        isLeftQs = true,
    ),
    ALWAYS_NOTIFICATIONS(
        "always_notifs",
        "Notifications Only",
        "Pulling anywhere along the status bar always opens Notifications first",
        1.01f,
    ),
    ALWAYS_QUICK_SETTINGS(
        "always_qs",
        "Quick Settings Only",
        "Pulling anywhere along the status bar directly expands Quick Settings",
        -0.01f,
    ),
    TOGETHER(
        "together",
        "Together (One UI style)",
        "One feed: compact QS tiles on top, notifications below — swipe down expands QS, swipe up collapses",
        0.50f,
    );

    /** True when both panels coexist in a single vertical scroll (no horizontal split). */
    val isTogether: Boolean get() = this == TOGETHER
}

/**
 * Official Samsung One UI 8.5/9 Quick Panel Device control & Media output layout modes.
 */
enum class DeviceControlMode(
    val id: String,
    val label: String,
    val subtitle: String,
) {
    SHOW_WHEN_EXPANDED(
        "show_when_expanded",
        "Show when expanded",
        "Show buttons when the Quick Panel is fully expanded",
    ),
    SHOW_ALWAYS(
        "show_always",
        "Show always",
        "Always show buttons in compact and expanded views",
    ),
    DONT_SHOW(
        "dont_show",
        "Don't show",
        "Remove buttons from the Quick Panel",
    );
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "supershade_prefs")

class ShadeSettings(private val context: Context) {

    companion object {
        private val THEME_KEY = stringPreferencesKey("theme")
        private val DARK_MODE_KEY = stringPreferencesKey("dark_mode")
        private val ACCENT_COLOR_KEY = stringPreferencesKey("accent_color")
        private val IS_ACTIVE_KEY = booleanPreferencesKey("is_active")
        private val ENABLED_TILES_KEY = stringPreferencesKey("enabled_tiles")
        private val LAST_SEEN_VERSION_KEY = stringPreferencesKey("last_seen_version")
        private val LAST_UPDATE_CHECK_KEY = longPreferencesKey("last_update_check_ms")
        private val BLOCK_SYSTEM_SHADE_KEY = booleanPreferencesKey("block_system_shade")
        private val QS_TILE_TAP_ACTION_KEY = stringPreferencesKey("qs_tile_tap_action")
        private val TILE_SHAPE_KEY = stringPreferencesKey("tile_shape")
        private val TILE_SIZE_KEY = stringPreferencesKey("tile_size")
        private val TILE_COLUMNS_KEY = stringPreferencesKey("tile_columns")
        private val SHOW_WIDE_CARDS_KEY = booleanPreferencesKey("show_wide_cards")
        private val SPLIT_GESTURE_MODE_KEY = stringPreferencesKey("split_gesture_mode")
        private val TORCH_STRENGTH_LEVEL_KEY = intPreferencesKey("torch_strength_level")
        private val CARD_BORDER_WIDTH_KEY = stringPreferencesKey("card_border_width")
        private val SHOW_PANEL_SWITCHER_PILL_KEY = booleanPreferencesKey("show_panel_switcher_pill")
        private val NOTIFICATION_DENSITY_KEY = stringPreferencesKey("notification_density")
        private val BACKDROP_THEME_KEY = stringPreferencesKey("backdrop_theme")
        private val BACKDROP_OPACITY_KEY = floatPreferencesKey("backdrop_opacity")
        private val HIDDEN_CHANNELS_KEY = stringPreferencesKey("hidden_notification_channels")
        private val HIDE_ONGOING_NOTIFICATIONS_KEY = booleanPreferencesKey("hide_ongoing_notifications")
        private val DEVICE_CONTROL_MODE_KEY = stringPreferencesKey("device_control_mode")
        private val MONET_ACCENT_STRENGTH_KEY = floatPreferencesKey("monet_accent_strength")
        private val AMBIENT_MEDIA_WIDGET_KEY = booleanPreferencesKey("ambient_media_widget_enabled")
        private val CLASSIFICATION_MODE_KEY = stringPreferencesKey("classification_mode")
        private val SHOW_CATEGORY_BAR_KEY = booleanPreferencesKey("show_category_bar")
        private val APP_CATEGORY_OVERRIDES_KEY = stringPreferencesKey("app_category_overrides")
        private val CUSTOM_STATUS_BAR_ENABLED_KEY = booleanPreferencesKey("custom_status_bar_enabled")
        private val CUSTOM_STATUS_BAR_BATTERY_PCT_KEY = booleanPreferencesKey("custom_status_bar_battery_pct")
        private val CUSTOM_STATUS_BAR_NET_TYPE_KEY = booleanPreferencesKey("custom_status_bar_net_type")
        private val CUSTOM_STATUS_BAR_INDICATORS_KEY = booleanPreferencesKey("custom_status_bar_indicators")
        private val CUSTOM_STATUS_BAR_TINT_MODE_KEY = stringPreferencesKey("custom_status_bar_tint_mode")
        private val CSB_SHOW_CLOCK_KEY = booleanPreferencesKey("csb_show_clock")
        private val CSB_SHOW_SECONDS_KEY = booleanPreferencesKey("csb_show_seconds")
        private val CSB_CLOCK_FORMAT_KEY = stringPreferencesKey("csb_clock_format")
        private val CSB_CLOCK_SIZE_KEY = floatPreferencesKey("csb_clock_size")
        private val CSB_CLOCK_ALIGN_KEY = stringPreferencesKey("csb_clock_align")
        private val CSB_CLOCK_OFFSET_X_KEY = floatPreferencesKey("csb_clock_offset_x")
        private val CSB_SHOW_BATTERY_ICON_KEY = booleanPreferencesKey("csb_show_battery_icon")
        private val CSB_BATTERY_PCT_MODE_KEY = stringPreferencesKey("csb_battery_pct_mode")
        private val CSB_BATTERY_SCALE_KEY = floatPreferencesKey("csb_battery_scale")
        private val CSB_SHOW_CHARGING_KEY = booleanPreferencesKey("csb_show_charging")
        private val CSB_SHOW_WIFI_KEY = booleanPreferencesKey("csb_show_wifi")
        private val CSB_SHOW_SIGNAL_KEY = booleanPreferencesKey("csb_show_signal")
        private val CSB_SHOW_AIRPLANE_KEY = booleanPreferencesKey("csb_show_airplane")
        private val CSB_SHOW_BLUETOOTH_KEY = booleanPreferencesKey("csb_show_bluetooth")
        private val CSB_SHOW_SILENT_KEY = booleanPreferencesKey("csb_show_silent")
        private val CSB_SHOW_DND_KEY = booleanPreferencesKey("csb_show_dnd")
        private val CSB_SHOW_ALARM_KEY = booleanPreferencesKey("csb_show_alarm")
        private val CSB_SHOW_LOCATION_KEY = booleanPreferencesKey("csb_show_location")
        private val CSB_INDICATOR_ALIGN_KEY = stringPreferencesKey("csb_indicator_align")
        private val CSB_INDICATOR_OFFSET_X_KEY = floatPreferencesKey("csb_indicator_offset_x")
        private val CSB_BAR_HEIGHT_DP_KEY = floatPreferencesKey("csb_bar_height_dp")
        private val CSB_VERTICAL_ALIGN_KEY = stringPreferencesKey("csb_vertical_align")
        private val CSB_VERTICAL_OFFSET_DP_KEY = floatPreferencesKey("csb_vertical_offset_dp")
        private val CSB_ICON_SIZE_DP_KEY = floatPreferencesKey("csb_icon_size_dp")
        private val CSB_ICON_SPACING_DP_KEY = floatPreferencesKey("csb_icon_spacing_dp")
        private val CSB_CUSTOM_CLOCK_COLOR_KEY = longPreferencesKey("csb_custom_clock_color")
        private val CSB_CUSTOM_ICON_COLOR_KEY = longPreferencesKey("csb_custom_icon_color")
        private val CSB_TEXT_SHADOW_KEY = booleanPreferencesKey("csb_text_shadow")
        private val CSB_SHOW_NOTIF_ICONS_KEY = booleanPreferencesKey("csb_show_notif_icons")
        private val CSB_MAX_NOTIF_ICONS_KEY = intPreferencesKey("csb_max_notif_icons")
        private val CSB_INCLUDE_SILENT_NOTIFS_KEY = booleanPreferencesKey("csb_include_silent_notifs")
    }

    val backdropTheme: Flow<BackdropTheme> = context.dataStore.data.map { prefs ->
        BackdropTheme.fromId(prefs[BACKDROP_THEME_KEY])
    }

    val backdropOpacity: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[BACKDROP_OPACITY_KEY] ?: when (prefs[BACKDROP_THEME_KEY]) {
            "opaque" -> 1.00f
            "blurry" -> 0.90f
            "frosted" -> 0.78f
            "liquid" -> 0.68f
            "transparent" -> 0.50f
            else -> 0.78f
        }
    }

    val theme: Flow<ShadeTheme> = context.dataStore.data.map { prefs ->
        when (prefs[THEME_KEY]) {
            "pixel" -> ShadeTheme.Pixel
            "material" -> ShadeTheme.PureMaterial
            "nothing" -> ShadeTheme.Nothing
            "cyberpunk" -> ShadeTheme.Cyberpunk
            else -> ShadeTheme.OneUI
        }
    }

    val notificationDensity: Flow<NotificationDensity> = context.dataStore.data.map { prefs ->
        when (prefs[NOTIFICATION_DENSITY_KEY]) {
            "compact" -> NotificationDensity.COMPACT
            "expansive" -> NotificationDensity.EXPANSIVE
            else -> NotificationDensity.BALANCED
        }
    }

    val darkThemeMode: Flow<com.supershade.ui.theme.DarkThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[DARK_MODE_KEY]) {
            "dark" -> com.supershade.ui.theme.DarkThemeMode.DARK
            "light" -> com.supershade.ui.theme.DarkThemeMode.LIGHT
            "amoled" -> com.supershade.ui.theme.DarkThemeMode.AMOLED
            else -> com.supershade.ui.theme.DarkThemeMode.SYSTEM
        }
    }

    val accentColor: Flow<AccentColor> = context.dataStore.data.map { prefs ->
        when (prefs[ACCENT_COLOR_KEY]) {
            "emerald" -> AccentColor.EMERALD
            "violet" -> AccentColor.VIOLET
            "amber" -> AccentColor.AMBER
            "coral" -> AccentColor.CORAL
            "monet" -> AccentColor.MONET
            else -> AccentColor.GALAXY_BLUE
        }
    }

    val isActive: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[IS_ACTIVE_KEY] ?: false
    }

    val enabledTiles: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[ENABLED_TILES_KEY] ?: ""
        if (raw.isBlank()) emptyList() else raw.split(",").map { it.trim() }
    }

    val lastSeenVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LAST_SEEN_VERSION_KEY] ?: ""
    }

    val lastUpdateCheckMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_UPDATE_CHECK_KEY] ?: 0L
    }

    val blockSystemShade: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BLOCK_SYSTEM_SHADE_KEY] ?: true
    }

    val qsTileTapAction: Flow<QsTileTapAction> = context.dataStore.data.map { prefs ->
        when (prefs[QS_TILE_TAP_ACTION_KEY]) {
            "open_shade", "show_menu" -> QsTileTapAction.OPEN_SHADE
            else -> QsTileTapAction.TOGGLE_ACTIVE
        }
    }

    val tileShape: Flow<TileShape> = context.dataStore.data.map { prefs ->
        when (prefs[TILE_SHAPE_KEY]) {
            "rounded" -> TileShape.ROUNDED
            "circle" -> TileShape.CIRCLE
            "pill" -> TileShape.PILL
            "soft" -> TileShape.SOFT
            "leaf" -> TileShape.LEAF
            "sharp" -> TileShape.SHARP
            else -> TileShape.SQUIRCLE
        }
    }

    val tileSize: Flow<TileSize> = context.dataStore.data.map { prefs ->
        when (prefs[TILE_SIZE_KEY]) {
            "compact" -> TileSize.COMPACT
            "comfortable" -> TileSize.COMFORTABLE
            else -> TileSize.STANDARD
        }
    }

    val torchStrengthLevel: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TORCH_STRENGTH_LEVEL_KEY] ?: 3
    }

    val tileColumns: Flow<TileGridColumns> = context.dataStore.data.map { prefs ->
        when (prefs[TILE_COLUMNS_KEY]) {
            "comfortable" -> TileGridColumns.COMFORTABLE
            "compact" -> TileGridColumns.COMPACT
            else -> TileGridColumns.STANDARD
        }
    }

    val showWideCards: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_WIDE_CARDS_KEY] ?: true
    }

    val splitGestureMode: Flow<SplitGestureMode> = context.dataStore.data.map { prefs ->
        when (prefs[SPLIT_GESTURE_MODE_KEY]) {
            "split_50_50" -> SplitGestureMode.SEPARATE_50_50
            "split_30_70" -> SplitGestureMode.SEPARATE_30_70
            "always_notifs" -> SplitGestureMode.ALWAYS_NOTIFICATIONS
            "always_qs" -> SplitGestureMode.ALWAYS_QUICK_SETTINGS
            "together" -> SplitGestureMode.TOGETHER
            else -> SplitGestureMode.SEPARATE_70_30
        }
    }

    val cardBorderWidth: Flow<CardBorderWidth> = context.dataStore.data.map { prefs ->
        when (prefs[CARD_BORDER_WIDTH_KEY]) {
            "none" -> CardBorderWidth.NONE
            "distinct" -> CardBorderWidth.DISTINCT
            "bold" -> CardBorderWidth.BOLD
            else -> CardBorderWidth.THIN
        }
    }

    val showPanelSwitcherPill: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_PANEL_SWITCHER_PILL_KEY] ?: false
    }

    val deviceControlMode: Flow<DeviceControlMode> = context.dataStore.data.map { prefs ->
        when (prefs[DEVICE_CONTROL_MODE_KEY]) {
            "show_always" -> DeviceControlMode.SHOW_ALWAYS
            "dont_show" -> DeviceControlMode.DONT_SHOW
            else -> DeviceControlMode.SHOW_WHEN_EXPANDED
        }
    }

    suspend fun setDeviceControlMode(mode: DeviceControlMode) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_CONTROL_MODE_KEY] = mode.id
        }
    }

    suspend fun setTheme(theme: ShadeTheme) {
        context.dataStore.edit { prefs ->
            prefs[THEME_KEY] = when (theme) {
                is ShadeTheme.Pixel -> "pixel"
                is ShadeTheme.PureMaterial -> "material"
                is ShadeTheme.Nothing -> "nothing"
                is ShadeTheme.Cyberpunk -> "cyberpunk"
                else -> "oneui"
            }
        }
    }

    suspend fun setNotificationDensity(density: NotificationDensity) {
        context.dataStore.edit { prefs ->
            prefs[NOTIFICATION_DENSITY_KEY] = density.id
        }
    }

    suspend fun setDarkThemeMode(mode: com.supershade.ui.theme.DarkThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[DARK_MODE_KEY] = when (mode) {
                com.supershade.ui.theme.DarkThemeMode.DARK -> "dark"
                com.supershade.ui.theme.DarkThemeMode.LIGHT -> "light"
                com.supershade.ui.theme.DarkThemeMode.AMOLED -> "amoled"
                com.supershade.ui.theme.DarkThemeMode.SYSTEM -> "system"
            }
        }
    }

    suspend fun setAccentColor(accent: AccentColor) {
        context.dataStore.edit { prefs ->
            prefs[ACCENT_COLOR_KEY] = when (accent) {
                AccentColor.EMERALD -> "emerald"
                AccentColor.VIOLET -> "violet"
                AccentColor.AMBER -> "amber"
                AccentColor.CORAL -> "coral"
                AccentColor.MONET -> "monet"
                AccentColor.GALAXY_BLUE -> "galaxy_blue"
            }
        }
    }

    suspend fun setActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_ACTIVE_KEY] = active
        }
    }

    suspend fun setQsTileTapAction(action: QsTileTapAction) {
        context.dataStore.edit { prefs ->
            prefs[QS_TILE_TAP_ACTION_KEY] = when (action) {
                QsTileTapAction.OPEN_SHADE -> "open_shade"
                QsTileTapAction.SHOW_MENU -> "show_menu"
                QsTileTapAction.TOGGLE_ACTIVE -> "toggle_active"
            }
        }
    }

    suspend fun setTileShape(shape: TileShape) {
        context.dataStore.edit { prefs ->
            prefs[TILE_SHAPE_KEY] = shape.id
        }
    }

    suspend fun setTileSize(size: TileSize) {
        context.dataStore.edit { prefs ->
            prefs[TILE_SIZE_KEY] = size.id
        }
    }

    suspend fun setTorchStrengthLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[TORCH_STRENGTH_LEVEL_KEY] = level
        }
    }

    suspend fun setTileColumns(columns: TileGridColumns) {
        context.dataStore.edit { prefs ->
            prefs[TILE_COLUMNS_KEY] = columns.id
        }
    }

    suspend fun setShowWideCards(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SHOW_WIDE_CARDS_KEY] = show
        }
    }

    suspend fun setEnabledTiles(tileIds: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[ENABLED_TILES_KEY] = tileIds.joinToString(",")
        }
    }

    suspend fun setLastSeenVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SEEN_VERSION_KEY] = version
        }
    }

    suspend fun setLastUpdateCheckMs(ms: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_UPDATE_CHECK_KEY] = ms
        }
    }

    suspend fun setBlockSystemShade(block: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BLOCK_SYSTEM_SHADE_KEY] = block
        }
    }

    suspend fun setSplitGestureMode(mode: SplitGestureMode) {
        context.dataStore.edit { prefs ->
            prefs[SPLIT_GESTURE_MODE_KEY] = mode.id
        }
    }

    suspend fun setCardBorderWidth(width: CardBorderWidth) {
        context.dataStore.edit { prefs ->
            prefs[CARD_BORDER_WIDTH_KEY] = width.id
        }
    }

    suspend fun setShowPanelSwitcherPill(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SHOW_PANEL_SWITCHER_PILL_KEY] = show
        }
    }

    suspend fun setBackdropTheme(theme: BackdropTheme) {
        context.dataStore.edit { prefs ->
            prefs[BACKDROP_THEME_KEY] = theme.id
            prefs[BACKDROP_OPACITY_KEY] = when (theme) {
                BackdropTheme.OPAQUE -> 1.00f
                BackdropTheme.BLURRY -> 0.90f
                BackdropTheme.FROSTED_GLASS -> 0.78f
                BackdropTheme.LIQUID_GLASS -> 0.68f
                BackdropTheme.TRANSPARENT -> 0.50f
            }
        }
    }

    suspend fun setBackdropOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.20f, 1.00f)
        val matchingTheme = when {
            clamped >= 0.98f -> BackdropTheme.OPAQUE
            clamped >= 0.86f -> BackdropTheme.BLURRY
            clamped >= 0.74f -> BackdropTheme.FROSTED_GLASS
            clamped >= 0.58f -> BackdropTheme.LIQUID_GLASS
            else -> BackdropTheme.TRANSPARENT
        }
        context.dataStore.edit { prefs ->
            prefs[BACKDROP_OPACITY_KEY] = clamped
            prefs[BACKDROP_THEME_KEY] = matchingTheme.id
        }
    }

    val hiddenChannels: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[HIDDEN_CHANNELS_KEY] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    suspend fun hideChannel(channelKey: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[HIDDEN_CHANNELS_KEY] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            current.add(channelKey)
            prefs[HIDDEN_CHANNELS_KEY] = current.joinToString(",")
        }
    }

    suspend fun unhideChannel(channelKey: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[HIDDEN_CHANNELS_KEY] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            current.remove(channelKey)
            prefs[HIDDEN_CHANNELS_KEY] = current.joinToString(",")
        }
    }

    val hideOngoingNotifications: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HIDE_ONGOING_NOTIFICATIONS_KEY] ?: false
    }

    suspend fun setHideOngoingNotifications(hide: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[HIDE_ONGOING_NOTIFICATIONS_KEY] = hide
        }
    }

    val monetAccentStrength: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[MONET_ACCENT_STRENGTH_KEY] ?: 1.0f
    }

    suspend fun setMonetAccentStrength(strength: Float) {
        context.dataStore.edit { prefs ->
            prefs[MONET_ACCENT_STRENGTH_KEY] = strength.coerceIn(0.0f, 1.0f)
        }
    }

    val ambientMediaWidgetEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AMBIENT_MEDIA_WIDGET_KEY] ?: true
    }

    suspend fun setAmbientMediaWidgetEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AMBIENT_MEDIA_WIDGET_KEY] = enabled
        }
    }

    val classificationMode: Flow<ClassificationMode> = context.dataStore.data.map { prefs ->
        ClassificationMode.fromId(prefs[CLASSIFICATION_MODE_KEY])
    }

    suspend fun setClassificationMode(mode: ClassificationMode) {
        context.dataStore.edit { prefs ->
            prefs[CLASSIFICATION_MODE_KEY] = mode.id
        }
    }

    val showCategoryBar: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_CATEGORY_BAR_KEY] ?: true
    }

    suspend fun setShowCategoryBar(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SHOW_CATEGORY_BAR_KEY] = enabled
        }
    }

    val appCategoryOverrides: Flow<Map<String, ShadeCategory>> = context.dataStore.data.map { prefs ->
        val raw = prefs[APP_CATEGORY_OVERRIDES_KEY].orEmpty()
        if (raw.isBlank()) emptyMap()
        else {
            raw.split(",").mapNotNull { entry ->
                val parts = entry.split("=")
                if (parts.size == 2) {
                    val pkg = parts[0].trim()
                    val catName = parts[1].trim()
                    val cat = ShadeCategory.entries.find { it.name.equals(catName, ignoreCase = true) }
                    if (cat != null) pkg to cat else null
                } else null
            }.toMap()
        }
    }

    suspend fun setAppCategoryOverride(packageName: String, category: ShadeCategory?) {
        context.dataStore.edit { prefs ->
            val raw = prefs[APP_CATEGORY_OVERRIDES_KEY].orEmpty()
            val current = if (raw.isBlank()) mutableMapOf()
            else {
                raw.split(",").mapNotNull { entry ->
                    val parts = entry.split("=")
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.toMap().toMutableMap()
            }
            if (category != null) {
                current[packageName] = category.name
            } else {
                current.remove(packageName)
            }
            prefs[APP_CATEGORY_OVERRIDES_KEY] = current.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    suspend fun clearAppCategoryOverrides() {
        context.dataStore.edit { prefs ->
            prefs.remove(APP_CATEGORY_OVERRIDES_KEY)
        }
    }

    val customStatusBarEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_STATUS_BAR_ENABLED_KEY] ?: false
    }

    suspend fun setCustomStatusBarEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_STATUS_BAR_ENABLED_KEY] = enabled
        }
    }

    val statusBarConfig: Flow<com.supershade.ui.statusbar.StatusBarConfig> = context.dataStore.data.map { prefs ->
        readStatusBarConfigFromPrefs(prefs)
    }

    private fun readStatusBarConfigFromPrefs(prefs: Preferences): com.supershade.ui.statusbar.StatusBarConfig {
        val legacyIndicators = prefs[CUSTOM_STATUS_BAR_INDICATORS_KEY] ?: true
        val legacyPctBool = prefs[CUSTOM_STATUS_BAR_BATTERY_PCT_KEY] ?: true
        val batteryPctMode = prefs[CSB_BATTERY_PCT_MODE_KEY]?.let {
            com.supershade.ui.statusbar.BatteryPercentMode.fromId(it)
        } ?: if (legacyPctBool) {
            com.supershade.ui.statusbar.BatteryPercentMode.BESIDE
        } else {
            com.supershade.ui.statusbar.BatteryPercentMode.HIDDEN
        }

        return com.supershade.ui.statusbar.StatusBarConfig(
            enabled = prefs[CUSTOM_STATUS_BAR_ENABLED_KEY] ?: false,
            showClock = prefs[CSB_SHOW_CLOCK_KEY] ?: true,
            showClockSeconds = prefs[CSB_SHOW_SECONDS_KEY] ?: false,
            clockFormat = com.supershade.ui.statusbar.ClockFormat.fromId(prefs[CSB_CLOCK_FORMAT_KEY]),
            clockTextSizeSp = prefs[CSB_CLOCK_SIZE_KEY] ?: 14f,
            clockAlignment = com.supershade.ui.statusbar.StatusBarHorizontalAlign.fromId(
                prefs[CSB_CLOCK_ALIGN_KEY],
                com.supershade.ui.statusbar.StatusBarHorizontalAlign.LEFT,
            ),
            clockOffsetXDp = prefs[CSB_CLOCK_OFFSET_X_KEY] ?: 0f,
            showBatteryIcon = prefs[CSB_SHOW_BATTERY_ICON_KEY] ?: true,
            batteryPercentMode = batteryPctMode,
            batteryIconScale = prefs[CSB_BATTERY_SCALE_KEY] ?: 1.0f,
            showChargingIndicator = prefs[CSB_SHOW_CHARGING_KEY] ?: true,
            showWifiIcon = prefs[CSB_SHOW_WIFI_KEY] ?: true,
            showMobileSignal = prefs[CSB_SHOW_SIGNAL_KEY] ?: true,
            showNetworkType = prefs[CUSTOM_STATUS_BAR_NET_TYPE_KEY] ?: true,
            showNotificationIcons = prefs[CSB_SHOW_NOTIF_ICONS_KEY] ?: true,
            maxNotificationIcons = (prefs[CSB_MAX_NOTIF_ICONS_KEY] ?: 3).coerceIn(1, 6),
            includeSilentNotifications = prefs[CSB_INCLUDE_SILENT_NOTIFS_KEY] ?: false,
            showAirplaneMode = prefs[CSB_SHOW_AIRPLANE_KEY] ?: true,
            showBluetoothIcon = prefs[CSB_SHOW_BLUETOOTH_KEY] ?: true,
            showSilentIcon = prefs[CSB_SHOW_SILENT_KEY] ?: true,
            showDndIcon = prefs[CSB_SHOW_DND_KEY] ?: legacyIndicators,
            showAlarmIcon = prefs[CSB_SHOW_ALARM_KEY] ?: legacyIndicators,
            showLocationIcon = prefs[CSB_SHOW_LOCATION_KEY] ?: false,
            indicatorAlignment = com.supershade.ui.statusbar.StatusBarHorizontalAlign.fromId(
                prefs[CSB_INDICATOR_ALIGN_KEY],
                com.supershade.ui.statusbar.StatusBarHorizontalAlign.RIGHT,
            ),
            indicatorOffsetXDp = prefs[CSB_INDICATOR_OFFSET_X_KEY] ?: 0f,
            barHeightDp = prefs[CSB_BAR_HEIGHT_DP_KEY] ?: 0f,
            verticalAlignment = com.supershade.ui.statusbar.StatusBarVerticalAlign.fromId(prefs[CSB_VERTICAL_ALIGN_KEY]),
            verticalOffsetDp = prefs[CSB_VERTICAL_OFFSET_DP_KEY] ?: 0f,
            iconSizeDp = prefs[CSB_ICON_SIZE_DP_KEY] ?: 13f,
            iconSpacingDp = prefs[CSB_ICON_SPACING_DP_KEY] ?: 5f,
            tintMode = com.supershade.ui.statusbar.StatusBarTintMode.fromId(prefs[CUSTOM_STATUS_BAR_TINT_MODE_KEY]),
            customClockColorArgb = prefs[CSB_CUSTOM_CLOCK_COLOR_KEY] ?: 0xFFFFFFFFL,
            customIconColorArgb = prefs[CSB_CUSTOM_ICON_COLOR_KEY] ?: 0xFFFFFFFFL,
            textShadowEnabled = prefs[CSB_TEXT_SHADOW_KEY] ?: true,
        )
    }

    suspend fun updateStatusBarConfig(
        transform: (com.supershade.ui.statusbar.StatusBarConfig) -> com.supershade.ui.statusbar.StatusBarConfig,
    ) {
        context.dataStore.edit { prefs ->
            val current = readStatusBarConfigFromPrefs(prefs)
            val updated = transform(current)
            writeStatusBarConfigToPrefs(prefs, updated)
        }
    }

    suspend fun resetStatusBarConfig(keepEnabled: Boolean = true) {
        context.dataStore.edit { prefs ->
            val wasEnabled = if (keepEnabled) (prefs[CUSTOM_STATUS_BAR_ENABLED_KEY] ?: false) else false
            writeStatusBarConfigToPrefs(
                prefs,
                com.supershade.ui.statusbar.StatusBarConfig(enabled = wasEnabled),
            )
        }
    }

    private fun writeStatusBarConfigToPrefs(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        cfg: com.supershade.ui.statusbar.StatusBarConfig,
    ) {
        prefs[CUSTOM_STATUS_BAR_ENABLED_KEY] = cfg.enabled
        prefs[CSB_SHOW_CLOCK_KEY] = cfg.showClock
        prefs[CSB_SHOW_SECONDS_KEY] = cfg.showClockSeconds
        prefs[CSB_CLOCK_FORMAT_KEY] = cfg.clockFormat.id
        prefs[CSB_CLOCK_SIZE_KEY] = cfg.clockTextSizeSp
        prefs[CSB_CLOCK_ALIGN_KEY] = cfg.clockAlignment.id
        prefs[CSB_CLOCK_OFFSET_X_KEY] = cfg.clockOffsetXDp
        prefs[CSB_SHOW_BATTERY_ICON_KEY] = cfg.showBatteryIcon
        prefs[CSB_BATTERY_PCT_MODE_KEY] = cfg.batteryPercentMode.id
        prefs[CUSTOM_STATUS_BAR_BATTERY_PCT_KEY] =
            cfg.batteryPercentMode != com.supershade.ui.statusbar.BatteryPercentMode.HIDDEN
        prefs[CSB_BATTERY_SCALE_KEY] = cfg.batteryIconScale
        prefs[CSB_SHOW_CHARGING_KEY] = cfg.showChargingIndicator
        prefs[CSB_SHOW_WIFI_KEY] = cfg.showWifiIcon
        prefs[CSB_SHOW_SIGNAL_KEY] = cfg.showMobileSignal
        prefs[CUSTOM_STATUS_BAR_NET_TYPE_KEY] = cfg.showNetworkType
        prefs[CSB_SHOW_NOTIF_ICONS_KEY] = cfg.showNotificationIcons
        prefs[CSB_MAX_NOTIF_ICONS_KEY] = cfg.maxNotificationIcons.coerceIn(1, 6)
        prefs[CSB_INCLUDE_SILENT_NOTIFS_KEY] = cfg.includeSilentNotifications
        prefs[CSB_SHOW_AIRPLANE_KEY] = cfg.showAirplaneMode
        prefs[CSB_SHOW_BLUETOOTH_KEY] = cfg.showBluetoothIcon
        prefs[CSB_SHOW_SILENT_KEY] = cfg.showSilentIcon
        prefs[CSB_SHOW_DND_KEY] = cfg.showDndIcon
        prefs[CSB_SHOW_ALARM_KEY] = cfg.showAlarmIcon
        prefs[CSB_SHOW_LOCATION_KEY] = cfg.showLocationIcon
        prefs[CUSTOM_STATUS_BAR_INDICATORS_KEY] = cfg.showDndIcon || cfg.showAlarmIcon
        prefs[CSB_INDICATOR_ALIGN_KEY] = cfg.indicatorAlignment.id
        prefs[CSB_INDICATOR_OFFSET_X_KEY] = cfg.indicatorOffsetXDp
        prefs[CSB_BAR_HEIGHT_DP_KEY] = cfg.barHeightDp
        prefs[CSB_VERTICAL_ALIGN_KEY] = cfg.verticalAlignment.id
        prefs[CSB_VERTICAL_OFFSET_DP_KEY] = cfg.verticalOffsetDp
        prefs[CSB_ICON_SIZE_DP_KEY] = cfg.iconSizeDp
        prefs[CSB_ICON_SPACING_DP_KEY] = cfg.iconSpacingDp
        prefs[CUSTOM_STATUS_BAR_TINT_MODE_KEY] = cfg.tintMode.id
        prefs[CSB_CUSTOM_CLOCK_COLOR_KEY] = cfg.customClockColorArgb
        prefs[CSB_CUSTOM_ICON_COLOR_KEY] = cfg.customIconColorArgb
        prefs[CSB_TEXT_SHADOW_KEY] = cfg.textShadowEnabled
    }
}
