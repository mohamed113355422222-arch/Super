package com.supershade.ui.shade

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.supershade.haptics.LocalSuperHaptics
import com.supershade.haptics.SuperHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Callback provided by parent [androidx.compose.foundation.pager.HorizontalPager] containers
 * so horizontal notification swipes temporarily lock page scrolling without contention.
 */
val LocalSwipeToDismissActive = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Finger-tracked horizontal swipe with release + fling physics:
 *  - synchronous 1:1 tracking while dragging, subtle tilt, rubber-band when a direction is disabled
 *  - fling velocity or distance past the threshold commits the dismissal, carrying the
 *    release velocity into the exit spring
 *  - otherwise springs back with a lively overshoot, again seeded with the release velocity
 *  - haptic tick when crossing the commit threshold in either direction
 */
@Stable
class FluidSwipeState internal constructor(
    internal val scope: CoroutineScope,
) {
    internal val offset = Animatable(0f)
    internal var isDragging by mutableStateOf(false)
    internal var isDismissing by mutableStateOf(false)
    internal var dragOffsetPx by mutableFloatStateOf(0f)
    internal var widthPx by mutableFloatStateOf(1f)
    internal var gestureGeneration by mutableIntStateOf(0)

    /** Current horizontal translation in px (positive = to the right). */
    val offsetPx: Float get() = if (isDragging) dragOffsetPx else offset.value

    /** Distance after which a release commits the dismissal. */
    val thresholdPx: Float get() = widthPx * 0.28f

    /** 0..1 progress towards the commit threshold-ish range, used for background effects. */
    val progress: Float get() = (abs(offsetPx) / (widthPx * 0.5f)).coerceIn(0f, 1f)

    val isAtRest: Boolean get() = !isDragging && !isDismissing && abs(offsetPx) < 4f
}

@Composable
fun rememberFluidSwipeState(key: Any?): FluidSwipeState {
    val scope = rememberCoroutineScope()
    return remember(key) { FluidSwipeState(scope) }
}

@Composable
fun FluidSwipeToDismiss(
    state: FluidSwipeState,
    onDismissed: (towardsEnd: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enableStartToEnd: Boolean = true,
    enableEndToStart: Boolean = true,
    backgroundContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val haptics: SuperHaptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }
    val density = LocalDensity.current
    val flingVelocityPx = with(density) { 420.dp.toPx() }
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val allowStart by rememberUpdatedState(enableStartToEnd)
    val allowEnd by rememberUpdatedState(enableEndToStart)
    val onSwipeActive = LocalSwipeToDismissActive.current
    val currentOnSwipeActive by rememberUpdatedState(onSwipeActive)

    val maxResistanceOffsetPx = with(density) { 42.dp.toPx() }
    var reportedSwipeActive by remember(state) { mutableStateOf(false) }

    fun setSwipeActive(active: Boolean) {
        if (reportedSwipeActive != active) {
            reportedSwipeActive = active
            currentOnSwipeActive(active)
        }
    }

    DisposableEffect(state) {
        onDispose {
            state.isDragging = false
            state.isDismissing = false
            setSwipeActive(false)
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { abs(state.offsetPx) > state.thresholdPx }
            .distinctUntilChanged()
            .collect { past -> if (past && !state.isAtRest) haptics.notificationDismissTick() }
    }

    fun allowed(dir: Float) = if (dir > 0f) allowStart else allowEnd

    Box(modifier = modifier) {
        Box(modifier = Modifier.matchParentSize()) { backgroundContent() }
        Box(
            modifier = Modifier
                .onSizeChanged { state.widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    val tx = state.offsetPx
                    translationX = tx
                    rotationZ = (tx / state.widthPx) * 3.5f
                }
                .pointerInput(state, allowStart, allowEnd) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (state.isDismissing) return@awaitEachGesture
                        var lockedHorizontal = false
                        var totalDx = 0f
                        var totalDy = 0f
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (!change.pressed) {
                                    if (lockedHorizontal) {
                                        change.consume()
                                        val finalOffset = state.dragOffsetPx
                                        val velocity = tracker.calculateVelocity().x
                                        val myGen = ++state.gestureGeneration
                                        state.scope.launch {
                                            state.offset.snapTo(finalOffset)
                                            state.isDragging = false
                                            setSwipeActive(false)
                                            val dir = sign(finalOffset)
                                            val flung = abs(velocity) > flingVelocityPx &&
                                                sign(velocity) == dir &&
                                                abs(finalOffset) > 6f
                                            val commit = dir != 0f && allowed(dir) &&
                                                (abs(finalOffset) > state.thresholdPx || flung)
                                            if (commit) {
                                                state.isDismissing = true
                                                haptics.notificationDismissCommit()
                                                state.offset.animateTo(
                                                    dir * state.widthPx * 1.2f,
                                                    spring(Spring.DampingRatioNoBouncy, 420f),
                                                    initialVelocity = velocity,
                                                )
                                                if (myGen == state.gestureGeneration) {
                                                    currentOnDismissed(dir > 0f)
                                                }
                                                delay(180L)
                                                // If the item survives (e.g. dismissal rejected), glide back.
                                                if (myGen == state.gestureGeneration) {
                                                    state.isDismissing = false
                                                    state.offset.animateTo(0f, spring(0.8f, 300f))
                                                }
                                            } else {
                                                state.offset.animateTo(
                                                    0f,
                                                    spring(0.62f, 450f),
                                                    initialVelocity = velocity,
                                                )
                                            }
                                        }
                                    }
                                    break
                                }

                                if (change.isConsumed && !lockedHorizontal) {
                                    break
                                }

                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y
                                tracker.addPosition(change.uptimeMillis, change.position)

                                if (!lockedHorizontal) {
                                    val absDx = abs(totalDx)
                                    val absDy = abs(totalDy)
                                    if (absDy > touchSlop && absDy > absDx * 1.15f) {
                                        // Yield clearly vertical gestures immediately to LazyColumn / VerticalScroll.
                                        break
                                    }
                                    if (absDx > touchSlop * 0.85f && absDx > absDy * 0.85f) {
                                        lockedHorizontal = true
                                        state.gestureGeneration++
                                        state.isDismissing = false
                                        state.dragOffsetPx = state.offset.value
                                        state.isDragging = true
                                        setSwipeActive(true)
                                        change.consume()
                                        val dir = sign(totalDx)
                                        val effective = if (allowed(dir)) {
                                            delta.x
                                        } else {
                                            (delta.x * 0.18f).coerceIn(-maxResistanceOffsetPx, maxResistanceOffsetPx)
                                        }
                                        state.dragOffsetPx = if (allowed(dir)) {
                                            state.dragOffsetPx + effective
                                        } else {
                                            (state.dragOffsetPx + effective).coerceIn(-maxResistanceOffsetPx, maxResistanceOffsetPx)
                                        }
                                    }
                                } else {
                                    change.consume()
                                    val cur = state.dragOffsetPx
                                    val targetDir = if (cur + delta.x != 0f) sign(cur + delta.x) else sign(delta.x)
                                    if (allowed(targetDir)) {
                                        state.dragOffsetPx = cur + delta.x
                                    } else {
                                        state.dragOffsetPx = (cur + delta.x * 0.18f)
                                            .coerceIn(-maxResistanceOffsetPx, maxResistanceOffsetPx)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            state.isDragging = false
                            setSwipeActive(false)
                            throw t
                        } finally {
                            if (!lockedHorizontal) {
                                state.isDragging = false
                                setSwipeActive(false)
                            }
                        }
                    }
                },
        ) { content() }
    }
}
