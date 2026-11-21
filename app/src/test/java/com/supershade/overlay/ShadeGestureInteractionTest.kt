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
        val job = launch(Dispatchers.Main) {
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

            // 4. Request animated close -> emits FinishClose, then completeClose resets isOpen=false
            vm.requestAnimatedClose()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(events.lastOrNull() is ShadeTransitionEvent.FinishClose)
            vm.completeClose()
            assertFalse(vm.state.value.isOpen)
            assertEquals(0f, vm.gestureProgress.value, 0.001f)
        } finally {
            job.cancel()
        }
    }
}
