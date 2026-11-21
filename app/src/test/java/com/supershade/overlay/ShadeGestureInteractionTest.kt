package com.supershade.overlay

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.supershade.domain.audio.AudioRepository
import com.supershade.domain.brightness.BrightnessRepository
import com.supershade.domain.media.MediaRepository
import com.supershade.domain.notification.AdaptivePriorityLoop
import com.supershade.domain.notification.NotificationRepository
import com.supershade.domain.system.SystemStatusRepository
import com.supershade.domain.tile.TileRepository
import com.supershade.domain.tile.TileToggler
import com.supershade.settings.ShadeSettings
import com.supershade.shizuku.ShizukuPlusConnector
import com.supershade.shizuku.StatusBarGovernor
import com.supershade.viewmodel.ShadeTransitionEvent
import com.supershade.viewmodel.ShadeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ShadeGestureInteractionTest {

    private fun createViewModel(context: Context): ShadeViewModel {
        val settings = ShadeSettings(context)
        val connector = ShizukuPlusConnector(context)
        val governor = StatusBarGovernor(context, connector)
        val adaptiveLoop = AdaptivePriorityLoop(context)
        val notificationRepo = NotificationRepository(adaptiveLoop)
        val systemStatusRepo = SystemStatusRepository(context)
        val audioRepo = AudioRepository(context)
        val tileRepo = TileRepository(context, governor, settings)
        val tileToggler = TileToggler(context, governor, tileRepo, settings, audioRepo)
        val mediaRepo = MediaRepository(context)
        val brightnessRepo = BrightnessRepository(context, governor)
        return ShadeViewModel(
            context = context,
            notificationRepo = notificationRepo,
            tileRepo = tileRepo,
            tileToggler = tileToggler,
            mediaRepo = mediaRepo,
            brightnessRepo = brightnessRepo,
            settings = settings,
            governor = governor,
            audioRepo = audioRepo,
            systemStatusRepo = systemStatusRepo,
        )
    }

    @Test
    fun topSwipeHelper_progressTracksDistanceProportionally() {
        val revealDist = TopSwipeGestureHelper.computeRevealDistancePx(screenHeightPx = 1600, density = 2f)
        val shortProgress = TopSwipeGestureHelper.computeProgress(dyFromOriginPx = revealDist * 0.15f, revealDistancePx = revealDist)
        val longProgress = TopSwipeGestureHelper.computeProgress(dyFromOriginPx = revealDist * 0.65f, revealDistancePx = revealDist)
        val overPullProgress = TopSwipeGestureHelper.computeProgress(dyFromOriginPx = revealDist * 1.40f, revealDistancePx = revealDist)

        assertEquals(0.15f, shortProgress, 0.01f)
        assertEquals(0.65f, longProgress, 0.01f)
        assertTrue(longProgress > shortProgress)
        assertEquals(1.06f, overPullProgress, 0.001f)
    }

    @Test
    fun topSwipeHelper_releaseDecisionUsesDistanceAndVelocity() {
        // Short slow pull cancels closed
        assertFalse(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.14f, progressVelocity = 0.2f))
        // Longer slow pull opens
        assertTrue(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.42f, progressVelocity = 0.0f))
        // Short fast downward flick still opens cleanly
        assertTrue(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.12f, progressVelocity = 2.0f))
        // Reversal upward fling cancels closed even past midpoint
        assertFalse(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.60f, progressVelocity = -1.6f))
        // Cancelled gesture requires higher distance
        assertFalse(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.35f, progressVelocity = 0.5f, isCancel = true))
        assertTrue(TopSwipeGestureHelper.shouldFinishOpen(progress = 0.50f, progressVelocity = 0.0f, isCancel = true))
    }

    @Test
    fun viewModel_interactiveDragOpenAndCancelTransitionsCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = createViewModel(context)
        val events = mutableListOf<ShadeTransitionEvent>()
        val job = launch(Dispatchers.Unconfined) {
            vm.transitionEvent.collect { events.add(it) }
        }
        try {
            // 1. Start interactive open -> enters drag mode immediately with isOpen=true
            vm.beginInteractiveOpen(expandQs = true)
            assertTrue(vm.state.value.isOpen)
            assertTrue(vm.state.value.isQsExpanded)
            assertTrue(vm.isGestureDragging.value)
            assertTrue(vm.gestureProgress.value > 0f)

            // 2. Continuous progress updates reflect finger displacement
            vm.updateInteractiveOpen(0.18f)
            assertEquals(0.18f, vm.gestureProgress.value, 0.001f)
            vm.updateInteractiveOpen(0.54f)
            assertEquals(0.54f, vm.gestureProgress.value, 0.001f)

            // 3. Release to open -> ends drag and emits FinishOpen with velocity
            vm.endInteractiveOpen(shouldOpen = true, progress = 0.54f, progressVelocity = 3.1f)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(vm.isGestureDragging.value)
            assertTrue(vm.state.value.isOpen)
            val openEvent = events.lastOrNull() as? ShadeTransitionEvent.FinishOpen
            assertTrue("Expected FinishOpen event", openEvent != null)
            assertEquals(0.54f, openEvent!!.progress, 0.001f)
            assertEquals(3.1f, openEvent.velocity, 0.001f)

            // 4. Request animated close -> emits FinishClose with isClosing=true, then completeClose resets everything
            vm.requestAnimatedClose()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(vm.isClosing.value)
            assertTrue(events.lastOrNull() is ShadeTransitionEvent.FinishClose)
            assertTrue(vm.latestTransition.value?.event is ShadeTransitionEvent.FinishClose)
            val closeId = vm.latestTransition.value!!.id

            // 5. Re-opening mid-close clears isClosing and delivers a strictly newer transition ID
            vm.beginInteractiveOpen(expandQs = false)
            assertFalse(vm.isClosing.value)
            assertTrue(vm.state.value.isOpen)
            vm.endInteractiveOpen(shouldOpen = true, progress = 0.65f, progressVelocity = 2.0f)
            assertTrue((vm.latestTransition.value?.id ?: 0L) > closeId)

            // 6. Local upward collapse immediately marks isClosing so shade is re-openable mid-spring
            vm.beginClosing()
            assertTrue(vm.isClosing.value)
            assertFalse(vm.isGestureDragging.value)

            vm.completeClose()
            assertFalse(vm.state.value.isOpen)
            assertFalse(vm.isClosing.value)
            assertEquals(0f, vm.gestureProgress.value, 0.001f)
            assertEquals(null, vm.latestTransition.value)
        } finally {
            job.cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun topSwipeHelper_streamsContinuousSwipeOpenAndRecoversFromMissedUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = android.view.View(context)
        var shadeOpen = false
        var began = false
        val progressSamples = mutableListOf<Float>()
        var endedShouldOpen: Boolean? = null

        val helper = TopSwipeGestureHelper(
            context = context,
            isShadeOpen = { shadeOpen },
            splitGestureMode = { com.supershade.settings.SplitGestureMode.ALWAYS_NOTIFICATIONS },
            onBeginSwipeOpen = { began = true },
            onUpdateSwipeProgress = { progressSamples.add(it) },
            onEndSwipeOpen = { shouldOpen, _, _ -> endedShouldOpen = shouldOpen },
        )

        // Start a pull down, then abandon without UP/CANCEL, then start a second fresh pull down
        val t0 = 1000L
        val down1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, 300f, 10f, 0)
        assertTrue(helper.onTouch(view, down1))
        down1.recycle()

        // Fresh ACTION_DOWN must reset prior state and accept events when !isShadeOpen
        val t1 = 2000L
        val down2 = android.view.MotionEvent.obtain(t1, t1, android.view.MotionEvent.ACTION_DOWN, 300f, 10f, 0)
        assertTrue(helper.onTouch(view, down2))

        val move1 = android.view.MotionEvent.obtain(t1, t1 + 16, android.view.MotionEvent.ACTION_MOVE, 300f, 80f, 0)
        val move2 = android.view.MotionEvent.obtain(t1, t1 + 32, android.view.MotionEvent.ACTION_MOVE, 300f, 260f, 0)
        val up = android.view.MotionEvent.obtain(t1, t1 + 48, android.view.MotionEvent.ACTION_UP, 300f, 340f, 0)

        assertTrue(helper.onTouch(view, move1))
        assertTrue(helper.onTouch(view, move2))
        assertTrue(helper.onTouch(view, up))

        down2.recycle()
        move1.recycle()
        move2.recycle()
        up.recycle()

        assertTrue("Expected interactive open to start", began)
        assertTrue("Expected >= 2 continuous progress updates", progressSamples.size >= 2)
        assertTrue("Expected monotonically increasing progress", progressSamples.last() > progressSamples.first())
        assertEquals(true, endedShouldOpen)

        // When shade reports open (and not closing), helper refuses new pull-down
        shadeOpen = true
        val downBlocked = android.view.MotionEvent.obtain(3000L, 3000L, android.view.MotionEvent.ACTION_DOWN, 300f, 10f, 0)
        assertFalse(helper.onTouch(view, downBlocked))
        downBlocked.recycle()
    }

    @Test
    fun statusBarGovernor_neverDisablesExpansionWithoutTouchCapture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = ShizukuPlusConnector(context)
        val governor = StatusBarGovernor(context, connector)

        governor.disableExpansion()
        // Safety invariant: must stay false while touchCaptureAvailable == false
        assertFalse(governor.isExpansionEffectivelyDisabled())

        governor.updateTouchCaptureAvailability(true)
        assertTrue(governor.isExpansionEffectivelyDisabled())

        // Detaching overlay/accessibility capture must immediately re-enable native expansion
        governor.updateTouchCaptureAvailability(false)
        assertFalse(governor.isExpansionEffectivelyDisabled())
    }
}
