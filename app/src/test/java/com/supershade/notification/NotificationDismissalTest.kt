package com.supershade.notification

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.supershade.domain.notification.NotificationRepository
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.domain.notification.model.ShadeNotification
import com.supershade.ui.shade.LocalSwipeToDismissActive
import com.supershade.ui.shade.NotificationFeed
import com.supershade.ui.theme.LocalShadeShapeScheme
import com.supershade.ui.theme.LocalShadeTheme
import com.supershade.ui.theme.ShadeShapeScheme
import com.supershade.ui.theme.ShadeTheme
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
class NotificationDismissalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun makeSbn(
        context: Context,
        pkg: String,
        id: Int,
        title: String,
        text: String,
        group: String? = null,
        isSummary: Boolean = false,
        ongoing: Boolean = false,
    ): StatusBarNotification {
        val builder = Notification.Builder(context, "test_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(ongoing)
        if (group != null) {
            builder.setGroup(group)
            builder.setGroupSummary(isSummary)
        }
        val notif = builder.build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            pkg,
            pkg,
            id,
            null,
            Process.myUid(),
            Process.myPid(),
            0,
            notif,
            Process.myUserHandle(),
            System.currentTimeMillis() + id,
        )
    }

    @Test
    fun repository_dismissGroupChildrenCancelsOrphanSummary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NotificationRepository()
        val cancelled = mutableListOf<String>()
        repo.canceller = { cancelled.add(it) }

        val summary = makeSbn(context, "com.chat.app", 1, "Chat", "2 messages", group = "chat_grp", isSummary = true)
        val child1 = makeSbn(context, "com.chat.app", 2, "Alice", "Hey", group = "chat_grp", isSummary = false)
        val child2 = makeSbn(context, "com.chat.app", 3, "Bob", "Yo", group = "chat_grp", isSummary = false)

        repo.onNotificationPosted(summary)
        repo.onNotificationPosted(child1)
        repo.onNotificationPosted(child2)

        assertEquals(2, repo.notifications.value.size)

        // Dismiss first child: summary must NOT be cancelled while second child remains
        repo.cancelAndRemove(child1.key)
        assertTrue(cancelled.contains(child1.key))
        assertFalse(cancelled.contains(summary.key))

        // Dismiss second child: orphan summary MUST now be cancelled automatically
        repo.cancelAndRemove(child2.key)
        assertTrue(cancelled.contains(child2.key))
        assertTrue("Expected orphan group summary to be cancelled", cancelled.contains(summary.key))
        assertTrue(repo.notifications.value.isEmpty())
    }

    @Test
    fun repository_cancelAllPreservesPinnedNotificationsAndSyncPrunesStale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NotificationRepository()
        val batchCancelled = mutableListOf<String>()
        var clearAllCalls = 0
        repo.batchCanceller = { keys -> batchCancelled.addAll(keys) }
        repo.clearAller = { clearAllCalls++ }

        val pinnedSbn = makeSbn(context, "com.app.a", 10, "Pinned Alert", "Keep me")
        val normalSbn = makeSbn(context, "com.app.b", 11, "Normal Alert", "Clear me")

        repo.onNotificationPosted(pinnedSbn)
        repo.onNotificationPosted(normalSbn)
        repo.togglePin(pinnedSbn.key)

        // When pinned items exist, cancelAll must cancel only non-pinned items instead of wiping SystemUI
        repo.cancelAll()
        assertEquals(0, clearAllCalls)
        assertEquals(listOf(normalSbn.key), batchCancelled)
        assertEquals(listOf(pinnedSbn.key), repo.notifications.value.map { it.key })

        // When no pinned items exist, clearAller is invoked
        repo.togglePin(pinnedSbn.key)
        repo.cancelAll()
        assertEquals(1, clearAllCalls)
        assertTrue(repo.notifications.value.isEmpty())

        // Undo preserves notification across immediate syncActiveNotifications
        val sbn3 = makeSbn(context, "com.app.c", 12, "Accidental", "Restorable")
        repo.onNotificationPosted(sbn3)
        repo.cancelAndRemove(sbn3.key)
        repo.undoLastDismiss()
        repo.syncActiveNotifications(emptyList())
        assertEquals(listOf(sbn3.key), repo.notifications.value.map { it.key })
    }

    @Test
    fun composeFeed_horizontalSwipeDismissesCardWithoutFlippingHorizontalPagerAndClearButtonWorks() {
        val dismissedKeys = mutableListOf<String>()
        var clearAllCount = 0
        var activePagerPage = -1

        val sampleNote = ShadeNotification(
            key = "sample|1",
            packageName = "com.example.msg",
            title = "Swipeable Message",
            text = "Swipe left to dismiss cleanly",
            subText = null,
            category = ShadeCategory.All,
            smallIcon = null,
            largeIcon = null,
            isGroupSummary = false,
            groupKey = null,
            postTime = System.currentTimeMillis(),
            actions = emptyList(),
            isClearable = true,
        )

        composeRule.setContent {
            MaterialTheme {
                var isCardSwiping by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
                activePagerPage = pagerState.currentPage

                CompositionLocalProvider(
                    LocalShadeTheme provides ShadeTheme.OneUI,
                    LocalShadeShapeScheme provides ShadeShapeScheme.forTheme(
                        ShadeTheme.OneUI,
                        com.supershade.settings.TileShape.SQUIRCLE,
                    ),
                    LocalSwipeToDismissActive provides { active -> isCardSwiping = active },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isCardSwiping,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        if (page == 0) {
                            NotificationFeed(
                                notifications = listOf(sampleNote),
                                onDismiss = { dismissedKeys.add(it) },
                                onClearAll = { clearAllCount++ },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()

        // Tap Clear all button first
        composeRule.onNodeWithTag("clear_all_notifications_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clearAllCount)

        // Swipe left across the notification card: should dismiss without paging HorizontalPager
        composeRule.onNodeWithText("Swipeable Message").performTouchInput {
            swipeLeft(startX = right - 20f, endX = left + 20f, durationMillis = 250)
        }
        composeRule.waitForIdle()

        assertTrue("Expected card swipe to invoke onDismiss", dismissedKeys.contains("sample|1"))
        assertEquals("Expected HorizontalPager to stay on page 0 while swiping card", 0, activePagerPage)
    }

    @Test
    fun repository_dismissedTombstonePreventsResurrectionDuringConcurrentRefresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NotificationRepository()
        val sbn = makeSbn(context, "com.msg.app", 42, "New Msg", "Hello world")
        val ongoing = makeSbn(context, "com.sys.app", 43, "VPN Connected", "Ongoing", ongoing = true)

        repo.onNotificationPosted(sbn)
        repo.onNotificationPosted(ongoing)
        assertEquals(2, repo.notifications.value.size)

        // Attempt to dismiss ongoing notification must fail
        assertFalse(repo.cancelAndRemove(ongoing.key))
        assertEquals(2, repo.notifications.value.size)

        // Dismiss clearable notification
        assertTrue(repo.cancelAndRemove(sbn.key))
        assertEquals(listOf(ongoing.key), repo.notifications.value.map { it.key })

        // Immediate syncActiveNotifications still returning the cancelled sbn must NOT resurrect it
        repo.syncActiveNotifications(listOf(sbn, ongoing))
        assertEquals(listOf(ongoing.key), repo.notifications.value.map { it.key })

        // Re-delivering the exact same postTime while NMS is still cancelling must also be ignored
        repo.onNotificationPosted(sbn)
        assertEquals(listOf(ongoing.key), repo.notifications.value.map { it.key })

        // Clear all clears single-item undo banner even when only ongoing remains
        val extra = makeSbn(context, "com.msg.app", 44, "Another", "Item")
        repo.onNotificationPosted(extra)
        repo.cancelAndRemove(extra.key)
        assertTrue(repo.lastDismissed.value != null)
        repo.cancelAll()
        assertEquals(null, repo.lastDismissed.value)

        // Clear all also removes clearable notifications while preserving ongoing ones and tombstoning cleared items
        val extra2 = makeSbn(context, "com.msg.app", 45, "Second", "Clearable")
        repo.onNotificationPosted(extra2)
        assertEquals(2, repo.notifications.value.size)
        repo.cancelAll()
        assertEquals(listOf(ongoing.key), repo.notifications.value.map { it.key })
        assertEquals(null, repo.lastDismissed.value)
        repo.syncActiveNotifications(listOf(extra2, ongoing))
        assertEquals(listOf(ongoing.key), repo.notifications.value.map { it.key })
    }

    @Test
    fun composeFeed_nonClearableHorizontalSwipeDoesNotFlipHorizontalPager() {
        val dismissedKeys = mutableListOf<String>()
        var activePagerPage = -1

        val ongoingNote = ShadeNotification(
            key = "ongoing|1",
            packageName = "com.example.vpn",
            title = "Ongoing Connection",
            text = "Protected tunnel active",
            subText = null,
            category = ShadeCategory.All,
            smallIcon = null,
            largeIcon = null,
            isGroupSummary = false,
            groupKey = null,
            postTime = System.currentTimeMillis(),
            actions = emptyList(),
            isClearable = false,
            isOngoing = true,
        )

        composeRule.setContent {
            MaterialTheme {
                var isCardSwiping by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
                activePagerPage = pagerState.currentPage

                CompositionLocalProvider(
                    LocalShadeTheme provides ShadeTheme.OneUI,
                    LocalShadeShapeScheme provides ShadeShapeScheme.forTheme(
                        ShadeTheme.OneUI,
                        com.supershade.settings.TileShape.SQUIRCLE,
                    ),
                    LocalSwipeToDismissActive provides { active -> isCardSwiping = active },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isCardSwiping,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        if (page == 0) {
                            NotificationFeed(
                                notifications = listOf(ongoingNote),
                                onDismiss = { dismissedKeys.add(it) },
                                onClearAll = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ongoing Connection").performTouchInput {
            swipeLeft(startX = right - 20f, endX = left + 20f, durationMillis = 250)
        }
        composeRule.waitForIdle()

        assertTrue("Non-clearable notification must not be dismissed", dismissedKeys.isEmpty())
        assertEquals("Swiping non-clearable card must not flip HorizontalPager", 0, activePagerPage)
    }
}
