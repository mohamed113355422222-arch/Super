package com.supershade.overlay

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import com.supershade.settings.SplitGestureMode
import kotlin.math.abs

/**
 * Unified top-edge touch gesture detector shared between [GestureOverlay] and
 * [com.supershade.service.SuperShadeAccessibilityService].
 *
 * Streams continuous pull progress (0f..1.06f) directly as the user drags down from
 * the top strip, handles horizontal brightness scrubbing when moving sideways first,
 * and computes release open/collapse decisions from both distance and vertical velocity.
 */
class TopSwipeGestureHelper(
    private val context: Context,
    private val isShadeOpen: () -> Boolean,
    private val splitGestureMode: () -> SplitGestureMode,
    private val onBeginSwipeOpen: (expandQs: Boolean) -> Unit,
    private val onUpdateSwipeProgress: (progress: Float) -> Unit,
    private val onEndSwipeOpen: (shouldOpen: Boolean, progress: Float, progressVelocity: Float) -> Unit,
    private val onBrightnessSwipe: ((Int) -> Unit)? = null,
) {
    private enum class GestureMode {
        IDLE,
        BRIGHTNESS,
        SHADE_PULL,
    }

    private var mode = GestureMode.IDLE
    private var startX = 0f
    private var startY = 0f
    private var pullOriginY = 0f
    private var lastProgress = 0f
    private var lastBrightnessStep = -1
    private var velocityTracker: VelocityTracker? = null

    fun createTouchListener(): View.OnTouchListener = View.OnTouchListener { v, event ->
        onTouch(v, event)
    }

    fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Reset any leftover gesture mode from a missed UP/CANCEL before evaluating open state.
            resetState()
            if (isShadeOpen()) return false
        } else if (isShadeOpen() && mode != GestureMode.SHADE_PULL) {
            return false
        }

        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenWidth = dm.widthPixels.coerceAtLeast(1)
        val screenHeight = dm.heightPixels.coerceAtLeast(1)
        val revealDistancePx = computeRevealDistancePx(screenHeight, density)
        val pullStartThresholdPx = (6f * density).coerceAtLeast(10f)
        val horizontalScrubThresholdPx = 20f * density
        val edgeExclusionPx = 10f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                pullOriginY = startY
                lastProgress = 0f
                lastBrightnessStep = -1
                mode = GestureMode.IDLE
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply {
                    addMovement(event)
                }
                if (startX < edgeExclusionPx || startX > (screenWidth - edgeExclusionPx)) {
                    return false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val yVelocity = velocityTracker?.yVelocity ?: 0f
                val deltaX = abs(event.rawX - startX)
                val deltaY = event.rawY - startY

                when (mode) {
                    GestureMode.IDLE -> {
                        val isHorizontalScrub =
                            deltaX > horizontalScrubThresholdPx && deltaX > abs(deltaY) * 1.75f
                        if (isHorizontalScrub && onBrightnessSwipe != null) {
                            mode = GestureMode.BRIGHTNESS
                            emitBrightness(v, event.rawX, screenWidth)
                            return true
                        }

                        val isFlingDown =
                            yVelocity > (600f * density) && deltaY > (4f * density) && deltaY > deltaX
                        val isStandardPull =
                            deltaY > pullStartThresholdPx && deltaY > deltaX * 1.05f

                        if (isFlingDown || isStandardPull) {
                            mode = GestureMode.SHADE_PULL
                            // Anchor pull origin slightly behind current finger position so initial progress
                            // starts seamlessly around ~0.02f without a visual jump.
                            pullOriginY = (event.rawY - (revealDistancePx * 0.025f)).coerceAtLeast(startY)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val expandQs = resolveExpandQs(v, startX, screenWidth, density, splitGestureMode())
                            onBeginSwipeOpen(expandQs)
                            lastProgress = computeProgress(event.rawY - pullOriginY, revealDistancePx)
                            onUpdateSwipeProgress(lastProgress)
                        }
                        return true
                    }

                    GestureMode.BRIGHTNESS -> {
                        emitBrightness(v, event.rawX, screenWidth)
                        return true
                    }

                    GestureMode.SHADE_PULL -> {
                        lastProgress = computeProgress(event.rawY - pullOriginY, revealDistancePx)
                        onUpdateSwipeProgress(lastProgress)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val yVelocity = velocityTracker?.yVelocity ?: 0f
                val progressVelocity = (yVelocity / revealDistancePx).coerceIn(-12f, 12f)
                val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL

                if (mode == GestureMode.SHADE_PULL) {
                    val finalProgress = computeProgress(event.rawY - pullOriginY, revealDistancePx)
                    val shouldOpen = shouldFinishOpen(finalProgress, progressVelocity, isCancel)
                    onEndSwipeOpen(shouldOpen, finalProgress, progressVelocity)
                }

                resetState()
                return true
            }

            else -> return false
        }
    }

    private fun emitBrightness(v: View, rawX: Float, screenWidth: Int) {
        val ratio = (rawX / screenWidth.toFloat()).coerceIn(0.04f, 1.0f)
        val brightnessLevel = (ratio * 255f).toInt()
        val step = brightnessLevel / 20
        if (step != lastBrightnessStep) {
            lastBrightnessStep = step
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onBrightnessSwipe?.invoke(brightnessLevel)
    }

    private fun resetState() {
        mode = GestureMode.IDLE
        lastProgress = 0f
        lastBrightnessStep = -1
        velocityTracker?.recycle()
        velocityTracker = null
    }

    fun cancelActiveTracking() {
        if (mode == GestureMode.SHADE_PULL) {
            onEndSwipeOpen(false, lastProgress, -2f)
        }
        resetState()
    }

    companion object {
        internal fun computeRevealDistancePx(screenHeightPx: Int, density: Float): Float =
            (screenHeightPx * 0.42f).coerceIn(200f * density, 440f * density)

        internal fun computeProgress(dyFromOriginPx: Float, revealDistancePx: Float): Float =
            (dyFromOriginPx / revealDistancePx.coerceAtLeast(1f)).coerceIn(0.01f, 1.06f)

        internal fun shouldFinishOpen(
            progress: Float,
            progressVelocity: Float,
            isCancel: Boolean = false,
        ): Boolean {
            if (isCancel) return progress >= 0.45f
            if (progressVelocity > 1.35f) return true
            if (progressVelocity < -1.15f) return false
            return progress >= 0.28f
        }

        internal fun resolveExpandQs(
            v: View,
            startX: Float,
            screenWidth: Int,
            density: Float,
            mode: SplitGestureMode,
        ): Boolean {
            val ratio = startX / screenWidth.coerceAtLeast(1).toFloat()
            val isCutoutDeadband = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = v.rootWindowInsets?.displayCutout
                val topRect = cutout?.boundingRectTop
                if (topRect != null && !topRect.isEmpty) {
                    startX >= (topRect.left - 12f * density) && startX <= (topRect.right + 12f * density)
                } else false
            } else false

            return when {
                mode == SplitGestureMode.ALWAYS_NOTIFICATIONS -> false
                mode == SplitGestureMode.ALWAYS_QUICK_SETTINGS -> true
                mode.isTogether -> false
                isCutoutDeadband -> false
                mode == SplitGestureMode.SEPARATE_30_70 -> ratio < 0.30f
                mode == SplitGestureMode.SEPARATE_50_50 -> ratio > 0.50f
                mode == SplitGestureMode.SEPARATE_70_30 -> ratio > 0.70f
                else -> false
            }
        }
    }
}
