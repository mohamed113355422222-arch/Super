package com.supershade.statusbar

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.supershade.domain.system.CustomStatusBarState
import com.supershade.ui.settings.CustomStatusBarSettingsSection
import com.supershade.ui.statusbar.BatteryPercentMode
import com.supershade.ui.statusbar.ClockFormat
import com.supershade.ui.statusbar.StatusBarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CustomStatusBarSettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsSection_togglesClockBatteryAndResetsToDefaults() {
        var config by mutableStateOf(StatusBarConfig(enabled = false))

        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CustomStatusBarSettingsSection(
                        config = config,
                        onUpdateConfig = { transform -> config = transform(config) },
                        onResetConfig = { config = StatusBarConfig(enabled = config.enabled) },
                        previewState = CustomStatusBarState(timeText = "10:15", batteryPct = 78),
                        shadeActive = true,
                        overlayGranted = true,
                        shizukuConnected = true,
                        shizukuPermGranted = true,
                        isSystemStatusBarHidden = true,
                        hasPhoneStatePermission = true,
                        hasBluetoothPermission = true,
                        onGrantOverlay = {},
                        onRequestShizukuPermission = {},
                        onOpenShizukuManager = {},
                        onRequestTelemetryPermissions = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // Toggle master switch ON
        composeRule.onNodeWithTag("csb_master_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(config.enabled)

        // Expand Clock group & toggle Seconds + 24-Hour format
        composeRule.onNodeWithTag("csb_group_clock").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show Seconds").performScrollTo().performClick()
        composeRule.onNodeWithText("24-Hour").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(config.showClockSeconds)
        assertEquals(ClockFormat.HOUR_24, config.clockFormat)

        // Expand Battery group & switch percentage placement to Inside Icon
        composeRule.onNodeWithTag("csb_group_battery").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Inside Icon").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(BatteryPercentMode.INSIDE, config.batteryPercentMode)

        // Reset to defaults while keeping enabled=true
        composeRule.onNodeWithTag("csb_reset_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(config.enabled)
        assertFalse(config.showClockSeconds)
        assertEquals(ClockFormat.SYSTEM, config.clockFormat)
        assertEquals(BatteryPercentMode.BESIDE, config.batteryPercentMode)
        composeRule.onNodeWithTag("csb_master_toggle").assertIsDisplayed()
    }

    @Test
    fun settingsSection_notificationIconsToggleChipAndSilentSwitch() {
        var config by mutableStateOf(StatusBarConfig(enabled = true))

        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CustomStatusBarSettingsSection(
                        config = config,
                        onUpdateConfig = { transform -> config = transform(config) },
                        onResetConfig = { config = StatusBarConfig(enabled = config.enabled) },
                        previewState = CustomStatusBarState(timeText = "10:15", batteryPct = 78),
                        shadeActive = true,
                        overlayGranted = true,
                        shizukuConnected = true,
                        shizukuPermGranted = true,
                        isSystemStatusBarHidden = true,
                        hasPhoneStatePermission = true,
                        hasBluetoothPermission = true,
                        notificationAccessGranted = false,
                        onGrantOverlay = {},
                        onRequestShizukuPermission = {},
                        onOpenShizukuManager = {},
                        onRequestTelemetryPermissions = {},
                        onOpenNotificationAccess = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("csb_group_icons").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(config.showNotificationIcons)
        composeRule.onNodeWithText("Max Notification Icons").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").assertIsDisplayed()

        // Change max icon cap to 5 and enable silent notifications
        composeRule.onNodeWithText("5").performScrollTo().performClick()
        composeRule.onNodeWithTag("csb_include_silent_notifs_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(5, config.maxNotificationIcons)
        assertTrue(config.includeSilentNotifications)

        // Disable notification icons completely -> cap & silent sub-controls hide
        composeRule.onNodeWithTag("csb_notif_icons_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(config.showNotificationIcons)
        composeRule.onNodeWithTag("csb_include_silent_notifs_toggle").assertDoesNotExist()

        // Reset restores defaults (enabled=true, max=3, silent=false)
        composeRule.onNodeWithTag("csb_reset_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(config.showNotificationIcons)
        assertEquals(3, config.maxNotificationIcons)
        assertFalse(config.includeSilentNotifications)
    }
}
