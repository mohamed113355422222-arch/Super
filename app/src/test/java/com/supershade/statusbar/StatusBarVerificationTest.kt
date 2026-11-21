package com.supershade.statusbar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.domain.notification.model.ShadeNotification
import com.supershade.domain.system.CustomStatusBarRepository
import com.supershade.domain.system.CustomStatusBarState
import com.supershade.domain.system.StatusBarNotificationIcon
import com.supershade.domain.system.SystemStatusRepository
import com.supershade.settings.ShadeSettings
import com.supershade.ui.statusbar.BatteryPercentMode
import com.supershade.ui.statusbar.ClockFormat
import com.supershade.ui.statusbar.IosStatusBar
import com.supershade.ui.statusbar.StatusBarConfig
import com.supershade.ui.statusbar.StatusBarHorizontalAlign
import com.supershade.ui.statusbar.StatusBarTintMode
import com.supershade.ui.statusbar.StatusBarVerticalAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StatusBarVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clockFormatting_supports12h_24h_system_and_seconds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = CustomStatusBarRepository(context, SystemStatusRepository(context))

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 7)
            set(Calendar.SECOND, 9)
        }
        val millis = cal.timeInMillis

        assertEquals("3:07", repo.formatTimeForMillis(millis, is24HourSystem = true, ClockFormat.HOUR_12, false))
        assertEquals("3:07:09", repo.formatTimeForMillis(millis, is24HourSystem = true, ClockFormat.HOUR_12, true))
        assertEquals("15:07", repo.formatTimeForMillis(millis, is24HourSystem = false, ClockFormat.HOUR_24, false))
        assertEquals("15:07:09", repo.formatTimeForMillis(millis, is24HourSystem = false, ClockFormat.HOUR_24, true))
        assertEquals("3:07", repo.formatTimeForMillis(millis, is24HourSystem = false, ClockFormat.SYSTEM, false))
        assertEquals("15:07", repo.formatTimeForMillis(millis, is24HourSystem = true, ClockFormat.SYSTEM, false))
    }

    @Test
    fun repository_updatesBatteryAndRingerAndAirplaneOnBroadcasts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = CustomStatusBarRepository(context, SystemStatusRepository(context))
        repo.startListening()

        val collected = mutableListOf<CustomStatusBarState>()
        val collectJob = launch(Dispatchers.Unconfined) {
            repo.statusBarState.collect { collected.add(it) }
        }

        try {
            val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 73)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            }
            @Suppress("DEPRECATION")
            context.sendStickyBroadcast(batteryIntent)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))

            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
            context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
            shadowOf(android.os.Looper.getMainLooper()).idle()

            repo.refreshSnapshot()
            val state = repo.statusBarState.first {
                it.batteryPct == 73 && it.isCharging && it.isSilent && it.isAirplaneMode
            }
            assertEquals(73, state.batteryPct)
            assertTrue(state.isCharging)
            assertTrue(state.isSilent)
            assertFalse(state.isVibrate)
            assertTrue(state.isAirplaneMode)

            // Switch to Vibrate mode and verify update
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            repo.refreshSnapshot()
            val vibrateState = repo.statusBarState.first { it.isVibrate && !it.isSilent }
            assertTrue(vibrateState.isVibrate)
            assertFalse(vibrateState.isSilent)
        } finally {
            collectJob.cancel()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            repo.stopListening()
        }
    }

    @Test
    fun settingsPersistence_savesAndResetsStatusBarConfig() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ShadeSettings(context)

        settings.updateStatusBarConfig {
            it.copy(
                enabled = true,
                showClock = true,
                showClockSeconds = true,
                clockFormat = ClockFormat.HOUR_24,
                clockTextSizeSp = 16f,
                clockAlignment = StatusBarHorizontalAlign.CENTER,
                clockOffsetXDp = 6f,
                showBatteryIcon = true,
                batteryPercentMode = BatteryPercentMode.INSIDE,
                batteryIconScale = 1.25f,
                showChargingIndicator = false,
                showBluetoothIcon = false,
                showLocationIcon = true,
                indicatorAlignment = StatusBarHorizontalAlign.LEFT,
                barHeightDp = 38f,
                verticalAlignment = StatusBarVerticalAlign.BOTTOM,
                verticalOffsetDp = -3f,
                iconSizeDp = 15f,
                iconSpacingDp = 7f,
                tintMode = StatusBarTintMode.CUSTOM,
                customClockColorArgb = 0xFF7CC4FFL,
                customIconColorArgb = 0xFF6EE7B7L,
                textShadowEnabled = false,
            )
        }

        val loaded = settings.statusBarConfig.first()
        assertTrue(loaded.enabled)
        assertTrue(loaded.showClockSeconds)
        assertEquals(ClockFormat.HOUR_24, loaded.clockFormat)
        assertEquals(16f, loaded.clockTextSizeSp, 0.01f)
        assertEquals(StatusBarHorizontalAlign.CENTER, loaded.clockAlignment)
        assertEquals(6f, loaded.clockOffsetXDp, 0.01f)
        assertEquals(BatteryPercentMode.INSIDE, loaded.batteryPercentMode)
        assertEquals(1.25f, loaded.batteryIconScale, 0.01f)
        assertFalse(loaded.showChargingIndicator)
        assertFalse(loaded.showBluetoothIcon)
        assertTrue(loaded.showLocationIcon)
        assertEquals(StatusBarHorizontalAlign.LEFT, loaded.indicatorAlignment)
        assertEquals(38f, loaded.barHeightDp, 0.01f)
        assertEquals(StatusBarVerticalAlign.BOTTOM, loaded.verticalAlignment)
        assertEquals(-3f, loaded.verticalOffsetDp, 0.01f)
        assertEquals(15f, loaded.iconSizeDp, 0.01f)
        assertEquals(7f, loaded.iconSpacingDp, 0.01f)
        assertEquals(StatusBarTintMode.CUSTOM, loaded.tintMode)
        assertEquals(0xFF7CC4FFL, loaded.customClockColorArgb)
        assertEquals(0xFF6EE7B7L, loaded.customIconColorArgb)
        assertFalse(loaded.textShadowEnabled)

        settings.resetStatusBarConfig(keepEnabled = true)
        val reset = settings.statusBarConfig.first()
        assertTrue(reset.enabled)
        assertFalse(reset.showClockSeconds)
        assertEquals(BatteryPercentMode.BESIDE, reset.batteryPercentMode)
        assertEquals(StatusBarTintMode.AUTO_CONTRAST, reset.tintMode)
        assertEquals(0f, reset.barHeightDp, 0.01f)
    }

    @Test
    fun iosStatusBar_rendersBatteryPercentModesWithoutDuplication() {
        val sampleState = CustomStatusBarState(
            timeText = "9:41:22",
            batteryPct = 84,
            isCharging = true,
            isWifiConnected = true,
            wifiLevel = 3,
            hasCellularService = true,
            cellularLevel = 4,
            isBluetoothEnabled = true,
            isBluetoothConnected = true,
            isDndActive = true,
            hasAlarm = true,
        )

        var currentConfig = StatusBarConfig(
            enabled = true,
            showClock = true,
            showClockSeconds = true,
            batteryPercentMode = BatteryPercentMode.BESIDE,
        )

        val configState = androidx.compose.runtime.mutableStateOf(currentConfig)
        composeRule.setContent {
            Box(modifier = Modifier.width(400.dp)) {
                IosStatusBar(
                    state = sampleState,
                    config = configState.value,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("9:41:22").assertIsDisplayed()
        composeRule.onAllNodesWithText("84%").assertCountEquals(1)
        composeRule.onNodeWithTag("ios_statusbar_battery_icon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bluetooth connected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Do Not Disturb").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alarm set").assertIsDisplayed()

        // Switch to INSIDE mode: no separate beside percentage node, custom inside node active
        configState.value = currentConfig.copy(batteryPercentMode = BatteryPercentMode.INSIDE)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("84%").assertCountEquals(0)
        composeRule.onNodeWithTag("ios_statusbar_battery_pct").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_battery_inside").assertIsDisplayed()

        // Switch to HIDDEN mode and hide clock
        configState.value = currentConfig.copy(
            showClock = false,
            batteryPercentMode = BatteryPercentMode.HIDDEN,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ios_statusbar_clock").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_battery_pct").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_battery_inside").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_battery_icon").assertIsDisplayed()
    }

    @Test
    fun iosStatusBar_respectsCenterWaterdropNotchCutoutWithoutOverlap() {
        val sampleState = CustomStatusBarState(
            timeText = "12:34",
            batteryPct = 92,
            isWifiConnected = true,
            wifiLevel = 3,
            hasCellularService = true,
            cellularLevel = 4,
            isSilent = true,
        )

        composeRule.setContent {
            val density = Density(1f)
            Box(modifier = Modifier.width(360.dp)) {
                IosStatusBar(
                    state = sampleState,
                    config = StatusBarConfig(enabled = true),
                    cutoutLeftPx = 155,
                    cutoutRightPx = 205,
                )
            }
        }

        composeRule.waitForIdle()
        val clockBounds = composeRule.onNodeWithTag("ios_statusbar_clock").getUnclippedBoundsInRoot()
        val indicatorsBounds = composeRule.onNodeWithTag("ios_statusbar_indicators").getUnclippedBoundsInRoot()
        assertTrue(
            "Clock right edge (${clockBounds.right}) should stay left of cutout (155.dp)",
            clockBounds.right <= 155.dp,
        )
        assertTrue(
            "Indicators left edge (${indicatorsBounds.left}) should stay right of cutout (205.dp)",
            indicatorsBounds.left >= 205.dp,
        )
    }

    @Test
    fun settingsPersistence_savesAndResetsNotificationIconOptions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ShadeSettings(context)

        settings.updateStatusBarConfig {
            it.copy(
                showNotificationIcons = false,
                maxNotificationIcons = 5,
                includeSilentNotifications = true,
            )
        }

        val loaded = settings.statusBarConfig.first()
        assertFalse(loaded.showNotificationIcons)
        assertEquals(5, loaded.maxNotificationIcons)
        assertTrue(loaded.includeSilentNotifications)

        settings.resetStatusBarConfig(keepEnabled = true)
        val reset = settings.statusBarConfig.first()
        assertTrue(reset.showNotificationIcons)
        assertEquals(3, reset.maxNotificationIcons)
        assertFalse(reset.includeSilentNotifications)
    }

    @Test
    fun notificationIconSelection_deduplicatesExcludesSilentAndCapsWithOverflow() {
        fun fakeNotif(
            key: String,
            pkg: String,
            isGroupSummary: Boolean = false,
            isSilent: Boolean = false,
        ) = ShadeNotification(
            key = key,
            packageName = pkg,
            title = "Title",
            text = "Body",
            subText = null,
            category = ShadeCategory.All,
            smallIcon = null,
            isGroupSummary = isGroupSummary,
            groupKey = null,
            postTime = 1000L,
            actions = emptyList(),
            isClearable = true,
            isSilent = isSilent,
        )

        val notifications = listOf(
            fakeNotif("k_sum", "com.chat.app", isGroupSummary = true),
            fakeNotif("k_self", "com.supershade"),
            fakeNotif("k1", "com.chat.app"),
            fakeNotif("k2", "com.chat.app"), // duplicate app
            fakeNotif("k_silent", "com.weather.silent", isSilent = true),
            fakeNotif("k3", "com.mail.app"),
            fakeNotif("k4", "com.music.app"),
        )

        // Exclude silent, max 2 -> chat + mail, overflow = true (music excluded by cap)
        val (capped, overflow) = CustomStatusBarRepository.selectStatusBarNotifications(
            notifications = notifications,
            showNotificationIcons = true,
            maxNotificationIcons = 2,
            includeSilentNotifications = false,
        )
        assertEquals(listOf("com.chat.app", "com.mail.app"), capped.map { it.packageName })
        assertTrue(overflow)

        // Include silent, max 4 -> chat, silent weather, mail, music, overflow = false
        val (withSilent, noOverflow) = CustomStatusBarRepository.selectStatusBarNotifications(
            notifications = notifications,
            showNotificationIcons = true,
            maxNotificationIcons = 4,
            includeSilentNotifications = true,
        )
        assertEquals(
            listOf("com.chat.app", "com.weather.silent", "com.mail.app", "com.music.app"),
            withSilent.map { it.packageName },
        )
        assertFalse(noOverflow)

        // Disabled -> empty and no overflow
        val (disabledList, disabledOverflow) = CustomStatusBarRepository.selectStatusBarNotifications(
            notifications = notifications,
            showNotificationIcons = false,
            maxNotificationIcons = 3,
            includeSilentNotifications = true,
        )
        assertTrue(disabledList.isEmpty())
        assertFalse(disabledOverflow)
    }

    @Test
    fun iosStatusBar_rendersNotificationAppIconsAndOverflowDotAndCutoutClearance() {
        val sampleState = CustomStatusBarState(
            timeText = "9:41",
            batteryPct = 88,
            notificationIcons = listOf(
                StatusBarNotificationIcon(key = "k1", packageName = "com.app.one", appName = "Messages"),
                StatusBarNotificationIcon(key = "k2", packageName = "com.app.two", appName = "Mail"),
                StatusBarNotificationIcon(key = "k3", packageName = "com.app.three", appName = "Calendar"),
            ),
            hasOverflowNotifications = true,
        )

        val configState = androidx.compose.runtime.mutableStateOf(
            StatusBarConfig(
                enabled = true,
                showNotificationIcons = true,
                maxNotificationIcons = 2,
            )
        )

        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                IosStatusBar(
                    state = sampleState,
                    config = configState.value,
                    cutoutLeftPx = 155,
                    cutoutRightPx = 205,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ios_statusbar_notif_icons").assertIsDisplayed()
        composeRule.onNodeWithTag("ios_statusbar_notif_icon_0").assertIsDisplayed()
        composeRule.onNodeWithTag("ios_statusbar_notif_icon_1").assertIsDisplayed()
        composeRule.onNodeWithTag("ios_statusbar_notif_icon_2").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_notif_overflow_dot").assertIsDisplayed()

        val notifBounds = composeRule.onNodeWithTag("ios_statusbar_notif_icons").getUnclippedBoundsInRoot()
        assertTrue(
            "Notification icons right edge (${notifBounds.right}) should stay left of cutout (155.dp)",
            notifBounds.right <= 155.dp,
        )

        // Turn off notification icons -> entire row disappears
        configState.value = configState.value.copy(showNotificationIcons = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ios_statusbar_notif_icons").assertDoesNotExist()
        composeRule.onNodeWithTag("ios_statusbar_notif_overflow_dot").assertDoesNotExist()
    }
}
