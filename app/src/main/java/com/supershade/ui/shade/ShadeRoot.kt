package com.supershade.ui.shade

import android.content.Intent
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import com.supershade.haptics.LocalSuperHaptics
import com.supershade.haptics.SuperHaptics
import com.supershade.ui.shade.pixel.PixelHeader
import com.supershade.ui.shade.pixel.PixelQuickSettingsGrid
import com.supershade.ui.shade.pixel.PixelBrightnessSlider
import com.supershade.ui.shade.pixel.PixelMediaCard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.supershade.overlay.TopSwipeGestureHelper
import com.supershade.settings.SplitGestureMode
import com.supershade.ui.theme.BackdropTheme
import com.supershade.ui.theme.LocalBackdropTheme
import com.supershade.ui.theme.LocalCardBorderWidth
import com.supershade.ui.theme.LocalShadeShapeScheme
import com.supershade.ui.theme.LocalShadeTheme
import com.supershade.ui.theme.ShadeShapeScheme
import com.supershade.ui.theme.getCardBorder
import com.supershade.viewmodel.ShadePanel
import com.supershade.viewmodel.ShadeTransitionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.ui.theme.CyberpunkShadeTheme
import com.supershade.ui.theme.NothingShadeTheme
import com.supershade.ui.theme.OneUiShadeTheme
import com.supershade.ui.theme.PixelShadeTheme
import com.supershade.ui.theme.PureMaterialShadeTheme
import com.supershade.ui.theme.ShadeTheme
import com.supershade.viewmodel.ShadeViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ShadeRoot(
    viewModel: ShadeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isEditingTiles by remember { mutableStateOf(false) }
    val isQsExpanded = state.isQsExpanded || isEditingTiles
    var mediaCollapsed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var showPowerMenu by remember { mutableStateOf(false) }

    val categoryCounts by remember {
        derivedStateOf {
            val all = state.allNotifications
            val counts = mutableMapOf<ShadeCategory, Int>()
            all.forEach { n -> counts[n.category] = (counts[n.category] ?: 0) + 1 }
            counts[ShadeCategory.All] = all.size
            counts as Map<ShadeCategory, Int>
        }
    }

    val isAmoled = state.darkThemeMode == com.supershade.ui.theme.DarkThemeMode.AMOLED
    val themeWrapper: @Composable (@Composable () -> Unit) -> Unit = when (state.theme) {
        ShadeTheme.Pixel -> { content ->
            PixelShadeTheme(
                isAmoled = isAmoled,
                darkThemeMode = state.darkThemeMode,
                accentColor = state.accentColor,
                monetBlendStrength = state.monetAccentStrength,
                content = content,
            )
        }
        ShadeTheme.PureMaterial -> { content ->
            PureMaterialShadeTheme(
                isAmoled = isAmoled,
                darkThemeMode = state.darkThemeMode,
                accentColor = state.accentColor,
                monetBlendStrength = state.monetAccentStrength,
                content = content,
            )
        }
        ShadeTheme.Nothing -> { content ->
            NothingShadeTheme(
                isAmoled = isAmoled,
                darkThemeMode = state.darkThemeMode,
                accentColor = state.accentColor,
                monetBlendStrength = state.monetAccentStrength,
                content = content,
            )
        }
        ShadeTheme.Cyberpunk -> { content ->
            CyberpunkShadeTheme(
                isAmoled = isAmoled,
                darkThemeMode = state.darkThemeMode,
                accentColor = state.accentColor,
                monetBlendStrength = state.monetAccentStrength,
                content = content,
            )
        }
        else -> { content ->
            OneUiShadeTheme(
                isAmoled = isAmoled,
                darkThemeMode = state.darkThemeMode,
                accentColor = state.accentColor,
                monetBlendStrength = state.monetAccentStrength,
                content = content,
            )
        }
    }

    val coroutineScope = rememberCoroutineScope()
    // Collect interactive gesture flows as State<T> objects (not delegated) and read .value only inside
    // graphicsLayer / drawBehind lambdas so drag frames never trigger recomposition or relayout.
    val gestureProgressState = viewModel.gestureProgress.collectAsStateWithLifecycle()
    val isGestureDraggingState = viewModel.isGestureDragging.collectAsStateWithLifecycle()
    val revealAnimatable = remember { Animatable(0f) }
    val progressProvider: () -> Float = remember {
        {
            if (isGestureDraggingState.value) gestureProgressState.value else revealAnimatable.value
        }
    }

    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }.coerceAtLeast(1f)
    val revealDistancePx = remember(screenHeightPx, density) {
        TopSwipeGestureHelper.computeRevealDistancePx(screenHeightPx.toInt(), density.density)
    }

    var openTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val pagerState = rememberPagerState(
        initialPage = if (state.activePanel == ShadePanel.QUICK_SETTINGS) 1 else 0,
        pageCount = { 2 }
    )

    val haptics = LocalSuperHaptics.current ?: remember(context) { SuperHaptics(context) }

    val handleTileClick: (com.supershade.domain.tile.TileDefinition) -> Unit = { tile ->
        when (tile.id.lowercase()) {
            "powermenu", "power_menu" -> {
                haptics.sheetDetent()
                showPowerMenu = true
            }
            "screenshot", "take_screenshot" -> {
                haptics.sheetDetent()
                viewModel.takeScreenshot()
            }
            else -> viewModel.toggleTile(tile)
        }
    }

    LaunchedEffect(state.activePanel) {
        val targetPage = if (state.activePanel == ShadePanel.QUICK_SETTINGS) 1 else 0
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(
                targetPage,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            )
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val targetPanel = if (pagerState.currentPage == 1) ShadePanel.QUICK_SETTINGS else ShadePanel.NOTIFICATIONS
        if (state.activePanel != targetPanel) {
            haptics.sheetDetent()
            viewModel.setActivePanel(targetPanel)
        }
    }

    // Drive smooth open/close springs directly from interactive drag releases and close requests.
    LaunchedEffect(viewModel) {
        launch {
            viewModel.gestureProgress.collect { p ->
                if (viewModel.isGestureDragging.value) {
                    revealAnimatable.snapTo(p)
                }
            }
        }
        launch {
            viewModel.transitionEvent.collectLatest { event ->
                when (event) {
                    is ShadeTransitionEvent.FinishOpen -> {
                        revealAnimatable.snapTo(event.progress.coerceIn(0f, 1.06f))
                        revealAnimatable.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 460f,
                            ),
                            initialVelocity = event.velocity.coerceIn(0f, 12f),
                        )
                    }
                    is ShadeTransitionEvent.FinishClose -> {
                        val startP = if (event.progress > 0.001f) event.progress else revealAnimatable.value
                        revealAnimatable.snapTo(startP.coerceIn(0f, 1.06f))
                        if (revealAnimatable.value > 0.001f) {
                            revealAnimatable.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.94f,
                                    stiffness = 560f,
                                ),
                                initialVelocity = event.velocity.coerceIn(-12f, 2f),
                            )
                        }
                        if (!viewModel.isGestureDragging.value) {
                            onDismiss()
                        }
                    }
                }
            }
        }
    }

    // Sync programmatic opens (e.g. cold open before transition collector started) and resets on close.
    LaunchedEffect(state.isOpen) {
        if (state.isOpen) {
            openTimeMs = System.currentTimeMillis()
            if (!viewModel.isGestureDragging.value && revealAnimatable.value <= 0.001f && !revealAnimatable.isRunning) {
                revealAnimatable.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 460f),
                )
            }
        } else {
            isEditingTiles = false
            revealAnimatable.snapTo(0f)
        }
    }

    val isTogether = state.splitGestureMode.isTogether
    val activePanel = state.activePanel

    val nestedScrollConnection = remember(isTogether, isQsExpanded, activePanel, isEditingTiles, revealDistancePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isEditingTiles) return Offset.Zero
                val dy = available.y
                if (revealAnimatable.value < 0.999f && dy > 0f) {
                    val next = (revealAnimatable.value + dy / revealDistancePx).coerceIn(0f, 1.06f)
                    coroutineScope.launch { revealAnimatable.snapTo(next) }
                    return Offset(0f, dy)
                }
                // Swiping UP in Together mode while Quick Settings is expanded:
                if (dy < -12f && isTogether && isQsExpanded) {
                    haptics.sheetDetent()
                    viewModel.setQsExpanded(false)
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (isEditingTiles) return Offset.Zero
                val dy = available.y
                // Pulled down in Together mode: expand Quick Settings
                if (dy > 20f && isTogether && !isQsExpanded) {
                    haptics.sheetDetent()
                    viewModel.setQsExpanded(true)
                    return Offset(0f, dy)
                }
                // Overscrolling upward past list bottom smoothly collapses the shade
                if (source == NestedScrollSource.UserInput && dy < 0f) {
                    val next = (revealAnimatable.value + dy / revealDistancePx).coerceIn(0f, 1.06f)
                    coroutineScope.launch { revealAnimatable.snapTo(next) }
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val progressVelocity = (available.y / revealDistancePx).coerceIn(-12f, 12f)
                val p = revealAnimatable.value
                if (p < 0.999f || progressVelocity < -2.2f) {
                    val shouldClose = p < 0.72f || (progressVelocity < -1.25f && p < 0.98f)
                    if (shouldClose) {
                        haptics.sheetDetent()
                        revealAnimatable.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.94f, stiffness = 560f),
                            initialVelocity = progressVelocity.coerceIn(-12f, -1.5f),
                        )
                        onDismiss()
                    } else {
                        revealAnimatable.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(dampingRatio = 0.84f, stiffness = 440f),
                            initialVelocity = progressVelocity.coerceIn(-2f, 12f),
                        )
                    }
                    return available
                } else if (p > 1.001f) {
                    revealAnimatable.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 440f))
                }
                return Velocity.Zero
            }
        }
    }

    val shapeScheme = remember(state.theme, state.tileShape) {
        ShadeShapeScheme.forTheme(state.theme, state.tileShape)
    }

    val backdropTheme = state.backdropTheme

    BackHandler(enabled = state.isOpen) {
        if (showPowerMenu) {
            haptics.sheetDetent()
            showPowerMenu = false
        } else if (state.isHistorySheetOpen) {
            haptics.sheetDetent()
            viewModel.closeHistorySheet()
        } else if (state.activeTileDetail != null) {
            viewModel.closeTileDetail()
        } else if (isTogether && state.isQsExpanded) {
            haptics.sheetDetent()
            viewModel.setQsExpanded(false)
        } else {
            if (System.currentTimeMillis() - openTimeMs < 200L) return@BackHandler
            haptics.sheetDetent()
            viewModel.requestAnimatedClose()
        }
    }

    val revealClipShape = remember {
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                val p = progressProvider().coerceIn(0f, 1.06f)
                val visualP = p.coerceIn(0f, 1f)
                if (visualP >= 0.999f) {
                    return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
                }
                val revealedHeight = (size.height * visualP).coerceAtLeast(0f)
                val cornerT = 1f - ((visualP - 0.84f) / 0.16f).coerceIn(0f, 1f)
                val bottomRadiusPx = with(density) { 34.dp.toPx() } * cornerT
                return Outline.Rounded(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = revealedHeight,
                        topLeftCornerRadius = CornerRadius.Zero,
                        topRightCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius(bottomRadiusPx, bottomRadiusPx),
                        bottomRightCornerRadius = CornerRadius(bottomRadiusPx, bottomRadiusPx),
                    )
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalShadeTheme provides state.theme,
        LocalCardBorderWidth provides state.cardBorderWidth,
        LocalShadeShapeScheme provides shapeScheme,
        LocalBackdropTheme provides backdropTheme,
    ) {
        themeWrapper {
            Box(modifier = Modifier.fillMaxSize()) {
                val opacity = state.backdropOpacity.coerceIn(0.20f, 1.00f)
                val baseScrimAlpha = when {
                    opacity >= 0.99f -> 0.70f
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> (opacity * 0.36f).coerceIn(0.12f, 0.45f)
                    else -> (opacity * 0.65f).coerceIn(0.25f, 0.75f)
                }

                // Scrim fades proportionally with progress in Draw phase without recomposing children.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val p = progressProvider().coerceIn(0f, 1f)
                            val eased = p * p * (3f - 2f * p)
                            val liveScrimAlpha = (baseScrimAlpha * eased).coerceIn(0f, 1f)
                            if (liveScrimAlpha > 0.002f) {
                                drawRect(Color.Black.copy(alpha = liveScrimAlpha))
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (!isGestureDraggingState.value && progressProvider() > 0.35f) {
                                    haptics.lightTap()
                                    viewModel.requestAnimatedClose()
                                }
                            }
                        ),
                )

                // Pre-warmed shade panel: revealed progressively from the top edge with GPU layer transforms.
                run {
                    val glassBackdrop = when {
                        opacity >= 0.99f -> {
                            if (isAmoled) Color(0xFF000000) else MaterialTheme.colorScheme.surface
                        }
                        isAmoled -> Color(0xFF05070A).copy(alpha = opacity)
                        else -> MaterialTheme.colorScheme.surface.copy(alpha = opacity)
                    }
                    val isTogether = state.splitGestureMode.isTogether
                    val isCombined = isTogether ||
                                     state.splitGestureMode == SplitGestureMode.ALWAYS_NOTIFICATIONS ||
                                     state.splitGestureMode == SplitGestureMode.ALWAYS_QUICK_SETTINGS
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = progressProvider().coerceIn(0f, 1.06f)
                                val visualP = p.coerceIn(0f, 1f)
                                val overPull = (p - 1f).coerceAtLeast(0f)
                                val s = (0.95f + 0.05f * visualP + overPull * 0.03f).coerceIn(0.94f, 1.025f)
                                scaleX = s
                                scaleY = s
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                translationY = -(1f - visualP) * (size.height * 0.035f) + overPull * 12.dp.toPx()
                                alpha = (visualP / 0.14f).coerceIn(0f, 1f)
                                shape = revealClipShape
                                clip = visualP < 0.999f
                            }
                            .background(glassBackdrop)
                            .pointerInput(isQsExpanded, isEditingTiles, isTogether, state.activePanel, state.visibleNotifications.size, revealDistancePx) {
                                if (isEditingTiles) return@pointerInput
                                val px = this.density
                                val edgeZonePx = (20f * px).toInt()
                                val headerZonePx = (72f * px).toInt()
                                val bottomHomeZonePx = (80f * px).toInt()
                                val slopeMin = 0.55f

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var consumed = false
                                    var lastY = down.position.y
                                    var totalDy = 0f
                                    var hasFiredDismissThresholdHaptic = false
                                    val tracker = VelocityTracker()
                                    tracker.addPosition(down.uptimeMillis, down.position)

                                    val isTouchInHomeBarZone = down.position.y >= (size.height - bottomHomeZonePx)
                                    val hasNotifications = state.visibleNotifications.isNotEmpty()
                                    val isTouchOverScrollableFeed = !isTouchInHomeBarZone &&
                                                                    down.position.y >= headerZonePx &&
                                                                    down.position.y <= (size.height - bottomHomeZonePx) &&
                                                                    (hasNotifications || (isTogether && !isQsExpanded))

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        tracker.addPosition(change.uptimeMillis, change.position)

                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y
                                        val deltaY = change.position.y - lastY
                                        lastY = change.position.y

                                        // Side edge inward swipe (predictive back)
                                        val isHorizontalDominant = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f
                                        val isEdgeInwardDist = (down.position.x < edgeZonePx && dx > (42f * px)) ||
                                                               (down.position.x > (size.width - edgeZonePx) && dx < -(42f * px))
                                        val isInwardSwipe = isHorizontalDominant && isEdgeInwardDist && kotlin.math.abs(dy) < (40f * px)

                                        if (!consumed && !isTouchOverScrollableFeed && isInwardSwipe) {
                                            change.consume()
                                            consumed = true
                                            haptics.sheetDetent()
                                            if (isTogether && isQsExpanded) {
                                                coroutineScope.launch { viewModel.setQsExpanded(false) }
                                            } else {
                                                viewModel.requestAnimatedClose()
                                            }
                                            break
                                        }

                                        if (!isTouchOverScrollableFeed) {
                                            val isDominantVertical = isTouchInHomeBarZone || kotlin.math.abs(dy) > kotlin.math.abs(dx) * slopeMin
                                            if (isDominantVertical && (dy < -4f || dy > 4f || revealAnimatable.value < 0.999f)) {
                                                totalDy = dy
                                                val currentP = revealAnimatable.value
                                                val deltaP = deltaY / revealDistancePx
                                                val nextP = if (deltaY > 0f && currentP >= 1f) {
                                                    (currentP + deltaP * 0.25f).coerceIn(1f, 1.06f)
                                                } else {
                                                    (currentP + deltaP).coerceIn(0f, 1.06f)
                                                }
                                                val thresholdP = if (isTouchInHomeBarZone) 0.85f else 0.72f
                                                if (nextP < thresholdP && !hasFiredDismissThresholdHaptic) {
                                                    haptics.sheetDetent()
                                                    hasFiredDismissThresholdHaptic = true
                                                } else if (nextP >= thresholdP + 0.06f) {
                                                    hasFiredDismissThresholdHaptic = false
                                                }
                                                coroutineScope.launch {
                                                    revealAnimatable.snapTo(nextP)
                                                }
                                            }

                                            if (!change.pressed) {
                                                val velocityY = tracker.calculateVelocity().y
                                                val progressVelocity = (velocityY / revealDistancePx).coerceIn(-12f, 12f)
                                                val p = revealAnimatable.value
                                                val thresholdP = if (isTouchInHomeBarZone) 0.85f else 0.72f
                                                val minFlickVel = if (isTouchInHomeBarZone) -0.85f else -1.25f
                                                val shouldClose = (p < thresholdP) ||
                                                    (progressVelocity < minFlickVel && p < 0.98f && totalDy < -(8f * px))

                                                if (shouldClose) {
                                                    consumed = true
                                                    change.consume()
                                                    if (!isTouchInHomeBarZone && isTogether && isQsExpanded && progressVelocity > -2.5f && p > 0.65f) {
                                                        haptics.sheetDetent()
                                                        coroutineScope.launch {
                                                            viewModel.setQsExpanded(false)
                                                            revealAnimatable.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 440f))
                                                        }
                                                    } else {
                                                        haptics.sheetDetent()
                                                        coroutineScope.launch {
                                                            revealAnimatable.animateTo(
                                                                targetValue = 0f,
                                                                animationSpec = spring(dampingRatio = 0.94f, stiffness = 560f),
                                                                initialVelocity = progressVelocity.coerceIn(-12f, -1.5f),
                                                            )
                                                            onDismiss()
                                                        }
                                                    }
                                                } else if (kotlin.math.abs(p - 1f) > 0.001f) {
                                                    coroutineScope.launch {
                                                        revealAnimatable.animateTo(
                                                            targetValue = 1f,
                                                            animationSpec = spring(dampingRatio = 0.84f, stiffness = 440f),
                                                            initialVelocity = progressVelocity.coerceIn(-2f, 12f),
                                                        )
                                                    }
                                                }
                                                break
                                            }
                                        } else {
                                            if (!change.pressed) {
                                                break
                                            }
                                        }
                                    }
                                }
                            },
                    )
 {
                        if (backdropTheme == BackdropTheme.LIQUID_GLASS) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.12f),
                                                Color.White.copy(alpha = 0.03f),
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                                                Color.Transparent,
                                            ),
                                            start = Offset.Zero,
                                            end = Offset(1200f, 2200f),
                                        )
                                    ),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .displayCutoutPadding()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        ) {
                        // Top Status Bar (Clock, Battery, Lock, Settings, Power, Edit)
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val onOpenEditAction: () -> Unit = {
                                isEditingTiles = !isEditingTiles
                                if (isEditingTiles) {
                                    viewModel.setQsExpanded(true)
                                    if (state.activePanel != ShadePanel.QUICK_SETTINGS) {
                                        viewModel.setActivePanel(ShadePanel.QUICK_SETTINGS)
                                    }
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(1)
                                    }
                                }
                            }

                            if (state.theme == ShadeTheme.Pixel) {
                                PixelHeader(
                                    statusBar = state.statusBar,
                                    isEditing = isEditingTiles,
                                    onOpenPowerMenu = { showPowerMenu = true },
                                    onOpenEdit = onOpenEditAction,
                                    onOpenDeviceSettings = { onDismiss() },
                                    onOpenSettings = {
                                        try {
                                            val intent = Intent(context, com.supershade.MainActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                            onDismiss()
                                        } catch (_: Exception) {}
                                    },
                                    onLockScreen = { viewModel.lockScreen() },
                                )
                            } else {
                                StatusBarRow(
                                    statusBar = state.statusBar,
                                    isEditing = isEditingTiles,
                                    onOpenPowerMenu = { showPowerMenu = true },
                                    onOpenEdit = onOpenEditAction,
                                    onOpenDeviceSettings = {
                                        onDismiss()
                                    },
                                    onOpenSettings = {
                                        try {
                                            val intent = Intent(context, com.supershade.MainActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                            onDismiss()
                                        } catch (_: Exception) {}
                                    },
                                    onLockScreen = { viewModel.lockScreen() },
                                )
                            }
                        }

                        // Active View Content
                        // TOGETHER mode: single unified vertical feed (One UI 8/9 style)
                        // Separate modes: fluid horizontal panel slide
                        if (isTogether) {
                            // ── TOGETHER MODE: QS tiles on top, notifications below, one scrollable feed ──
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .nestedScroll(nestedScrollConnection)
                                    .verticalScroll(rememberScrollState(), enabled = !isEditingTiles),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (state.theme == ShadeTheme.Pixel) {
                                    // ── Pixel Material Expressive Layout in Together Mode ──
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .draggable(
                                                orientation = Orientation.Vertical,
                                                enabled = !isEditingTiles,
                                                state = rememberDraggableState { delta ->
                                                    if (delta > 10f && !isQsExpanded) {
                                                        haptics.sheetDetent()
                                                        viewModel.setQsExpanded(true)
                                                    } else if (delta < -10f && isQsExpanded) {
                                                        haptics.sheetDetent()
                                                        viewModel.setQsExpanded(false)
                                                    }
                                                }
                                            )
                                    ) {
                                        PixelQuickSettingsGrid(
                                            tiles = state.tiles,
                                            isExpanded = isQsExpanded,
                                            isEditing = isEditingTiles,
                                            onToggleEdit = { isEditingTiles = !isEditingTiles },
                                            onMoveTile = { from, to -> viewModel.moveTile(from, to) },
                                            onRemoveTile = { viewModel.removeTile(it) },
                                            onAddTile = { viewModel.addTile(it) },
                                            onResetTiles = { viewModel.resetTiles() },
                                            onTileClick = handleTileClick,
                                            onTileLongClick = { viewModel.openTileDetail(it) },
                                        )
                                    }

                                    PixelBrightnessSlider(
                                        brightness = state.brightness,
                                        onBrightnessChange = { viewModel.setBrightness(it) },
                                    )

                                    state.media?.let { media ->
                                        PixelMediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                    }
                                } else {
                                    // ── One UI Layout in Together Mode ──
                                    // Compact QS row (always visible at top, draggable to expand/collapse with spring physics)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .draggable(
                                                orientation = Orientation.Vertical,
                                                enabled = !isEditingTiles,
                                                state = rememberDraggableState { delta ->
                                                    if (delta > 10f && !isQsExpanded) {
                                                        haptics.sheetDetent()
                                                        viewModel.setQsExpanded(true)
                                                    } else if (delta < -10f && isQsExpanded) {
                                                        haptics.sheetDetent()
                                                        viewModel.setQsExpanded(false)
                                                    }
                                                }
                                            )
                                    ) {
                                        QuickSettingsGrid(
                                            tiles = state.tiles,
                                            theme = state.theme,
                                            isShizukuConnected = state.isShizukuConnected,
                                            isExpanded = isQsExpanded,
                                            tileShape = state.tileShape,
                                            tileSize = state.tileSize,
                                            tileColumns = state.tileColumns,
                                            showWideCards = isQsExpanded && state.showWideCards,
                                            isEditing = isEditingTiles,
                                            onToggleEdit = { isEditingTiles = !isEditingTiles },
                                            onMoveTile = { from, to -> viewModel.moveTile(from, to) },
                                            onRemoveTile = { viewModel.removeTile(it) },
                                            onAddTile = { viewModel.addTile(it) },
                                            onResetTiles = { viewModel.resetTiles() },
                                            onTileClick = handleTileClick,
                                            onTileLongClick = { viewModel.openTileDetail(it) },
                                            deviceControlMode = state.deviceControlMode,
                                            onRestoreDeviceControl = { viewModel.setDeviceControlMode(com.supershade.settings.DeviceControlMode.SHOW_WHEN_EXPANDED) },
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = (state.theme == ShadeTheme.OneUI) && (isEditingTiles || (state.deviceControlMode == com.supershade.settings.DeviceControlMode.SHOW_ALWAYS) || (state.deviceControlMode == com.supershade.settings.DeviceControlMode.SHOW_WHEN_EXPANDED && isQsExpanded)),
                                        enter = expandVertically(spring(0.8f, 380f)) + fadeIn(tween(140)),
                                        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                                    ) {
                                        OneUIDeviceControlRow(
                                            mode = state.deviceControlMode,
                                            isExpanded = isQsExpanded,
                                            isEditing = isEditingTiles,
                                            onRemove = { viewModel.setDeviceControlMode(com.supershade.settings.DeviceControlMode.DONT_SHOW) },
                                            onChangeMode = { viewModel.setDeviceControlMode(it) },
                                            onDismissShade = onDismiss,
                                        )
                                    }

                                    // Tactile Sliders Island in Together mode:
                                    // Brightness slider is always accessible at top (matching One UI compact quick panel);
                                    // Volume slider expands smoothly when QS is expanded.
                                    Surface(
                                        shape = shapeScheme.container,
                                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                                        border = getCardBorder(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 2.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            BrightnessSlider(
                                                brightness = state.brightness,
                                                onBrightnessChange = { viewModel.setBrightness(it) },
                                                compact = false,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            AnimatedVisibility(
                                                visible = isQsExpanded,
                                                enter = expandVertically(spring(0.8f, 380f)) + fadeIn(tween(140)),
                                                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                                            ) {
                                                VolumeSlider(
                                                    compact = false,
                                                    onOpenVolumeMixer = { viewModel.openSoundModeDetail() },
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }

                                    // Media card
                                    state.media?.let { media ->
                                        MediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                    }
                                }

                                // Category bar + notification feed (inline, not in a nested LazyColumn
                                // since we're inside verticalScroll — so we expand the list inline)
                                val showCategoriesTogether = state.showCategoryBar &&
                                    state.classificationMode != com.supershade.settings.ClassificationMode.UNIFIED &&
                                    state.allNotifications.isNotEmpty()
                                if (showCategoriesTogether) {
                                    val activeCategories = remember(state.classificationMode) {
                                        com.supershade.settings.categoriesForMode(state.classificationMode)
                                    }
                                    CategoryBar(
                                        categories = activeCategories,
                                        selected = state.selectedCategory,
                                        onSelect = { viewModel.selectCategory(it) },
                                        counts = categoryCounts,
                                        mode = state.classificationMode,
                                    )
                                }

                                // In TOGETHER mode we use a non-lazy feed to stay inside the outer scroll
                                AnimatedContent(
                                    targetState = state.selectedCategory,
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(180)) + slideInHorizontally(
                                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                                            initialOffsetX = { fullWidth -> if (targetState.ordinal > initialState.ordinal) fullWidth / 4 else -fullWidth / 4 }
                                        )).togetherWith(
                                            fadeOut(animationSpec = tween(140)) + slideOutHorizontally(
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                                                targetOffsetX = { fullWidth -> if (targetState.ordinal > initialState.ordinal) -fullWidth / 4 else fullWidth / 4 }
                                            )
                                        )
                                    },
                                    label = "togetherCategoryTransition",
                                ) { _ ->
                                    TogetherNotificationFeed(
                                        notifications = state.visibleNotifications,
                                        onDismiss = { viewModel.dismissNotification(it) },
                                        onDismissGroup = { viewModel.dismissNotifications(it) },
                                        onClearAll = { viewModel.clearAllNotifications() },
                                        onNotificationClick = { notification ->
                                            viewModel.launchNotification(notification)
                                            onDismiss()
                                        },
                                        onSnooze = { key, delayMs -> viewModel.snoozeNotification(key, delayMs) },
                                        onHideChannel = { pkg, ch -> viewModel.hideNotificationChannel(pkg, ch) },
                                        onOpenHistory = { viewModel.openHistorySheet() },
                                        pinnedKeys = state.pinnedKeys,
                                        onTogglePin = { viewModel.togglePinNotification(it) },
                                        compact = state.isNotificationCompact,
                                        onReply = { note, _ -> viewModel.recordReply(note) },
                                    )
                                }
                            }
                        } else {

                        // Separate/Combined panels: fluid continuous horizontal page tracking (One UI 9 style)
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            userScrollEnabled = !isEditingTiles,
                            beyondViewportPageCount = 1,
                        ) { page ->
                            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                            val clampedOffset = pageOffset.coerceIn(-1f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = 1f - kotlin.math.abs(clampedOffset) * 0.20f
                                        scaleX = 1f - kotlin.math.abs(clampedOffset) * 0.025f
                                        scaleY = 1f - kotlin.math.abs(clampedOffset) * 0.025f
                                    }
                            ) {
                                if (page == 0) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Media playback card (if active)
                                    state.media?.let { media ->
                                        if (state.theme == ShadeTheme.Pixel) {
                                            PixelMediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                        } else {
                                            MediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                        }
                                    }

                                    // Notification category bar
                                    val showCategoriesSplit = state.showCategoryBar &&
                                        state.classificationMode != com.supershade.settings.ClassificationMode.UNIFIED &&
                                        state.allNotifications.isNotEmpty()
                                    if (showCategoriesSplit) {
                                        val activeCategories = remember(state.classificationMode) {
                                            com.supershade.settings.categoriesForMode(state.classificationMode)
                                        }
                                        CategoryBar(
                                            categories = activeCategories,
                                            selected = state.selectedCategory,
                                            onSelect = { viewModel.selectCategory(it) },
                                            counts = categoryCounts,
                                            mode = state.classificationMode,
                                        )
                                    }

                                    // Notification feed with full remaining space and responsive nested-scroll coordination
                                    AnimatedContent(
                                        targetState = state.selectedCategory,
                                        transitionSpec = {
                                            (fadeIn(animationSpec = tween(180)) + slideInHorizontally(
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                                                initialOffsetX = { fullWidth -> if (targetState.ordinal > initialState.ordinal) fullWidth / 4 else -fullWidth / 4 }
                                            )).togetherWith(
                                                fadeOut(animationSpec = tween(140)) + slideOutHorizontally(
                                                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
                                                    targetOffsetX = { fullWidth -> if (targetState.ordinal > initialState.ordinal) -fullWidth / 4 else fullWidth / 4 }
                                                )
                                            )
                                        },
                                        label = "separateCategoryTransition",
                                        modifier = Modifier
                                            .weight(1f)
                                            .nestedScroll(nestedScrollConnection),
                                    ) { _ ->
                                        NotificationFeed(
                                            notifications = state.visibleNotifications,
                                            onDismiss = { viewModel.dismissNotification(it) },
                                            onDismissGroup = { viewModel.dismissNotifications(it) },
                                            onClearAll = { viewModel.clearAllNotifications() },
                                            onNotificationClick = { notification ->
                                                viewModel.launchNotification(notification)
                                                onDismiss()
                                            },
                                            onSnooze = { key, delayMs -> viewModel.snoozeNotification(key, delayMs) },
                                            onHideChannel = { pkg, ch -> viewModel.hideNotificationChannel(pkg, ch) },
                                            onOpenHistory = { viewModel.openHistorySheet() },
                                            pinnedKeys = state.pinnedKeys,
                                            onTogglePin = { viewModel.togglePinNotification(it) },
                                            compact = state.isNotificationCompact,
                                            onReply = { note, _ -> viewModel.recordReply(note) },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            } else {
                                // QUICK SETTINGS PANEL (Full Control Center)
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .nestedScroll(nestedScrollConnection)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (state.theme == ShadeTheme.Pixel) {
                                        PixelQuickSettingsGrid(
                                            tiles = state.tiles,
                                            isExpanded = true,
                                            isEditing = isEditingTiles,
                                            onToggleEdit = { isEditingTiles = !isEditingTiles },
                                            onMoveTile = { from, to -> viewModel.moveTile(from, to) },
                                            onRemoveTile = { viewModel.removeTile(it) },
                                            onAddTile = { viewModel.addTile(it) },
                                            onResetTiles = { viewModel.resetTiles() },
                                            onTileClick = handleTileClick,
                                            onTileLongClick = { viewModel.openTileDetail(it) },
                                        )

                                        PixelBrightnessSlider(
                                            brightness = state.brightness,
                                            onBrightnessChange = { viewModel.setBrightness(it) },
                                        )

                                        state.media?.let { media ->
                                            PixelMediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                        }
                                    } else {
                                        QuickSettingsGrid(
                                            tiles = state.tiles,
                                            theme = state.theme,
                                            isShizukuConnected = state.isShizukuConnected,
                                            isExpanded = true,
                                            tileShape = state.tileShape,
                                            tileSize = state.tileSize,
                                            tileColumns = state.tileColumns,
                                            showWideCards = state.showWideCards,
                                            isEditing = isEditingTiles,
                                            onToggleEdit = { isEditingTiles = !isEditingTiles },
                                            onMoveTile = { from, to -> viewModel.moveTile(from, to) },
                                            onRemoveTile = { viewModel.removeTile(it) },
                                            onAddTile = { viewModel.addTile(it) },
                                            onResetTiles = { viewModel.resetTiles() },
                                            onTileClick = handleTileClick,
                                            onTileLongClick = { viewModel.openTileDetail(it) },
                                            deviceControlMode = state.deviceControlMode,
                                            onRestoreDeviceControl = { viewModel.setDeviceControlMode(com.supershade.settings.DeviceControlMode.SHOW_WHEN_EXPANDED) },
                                        )

                                        if (state.theme == ShadeTheme.OneUI) {
                                            OneUIDeviceControlRow(
                                                mode = state.deviceControlMode,
                                                isExpanded = true,
                                                isEditing = isEditingTiles,
                                                onRemove = { viewModel.setDeviceControlMode(com.supershade.settings.DeviceControlMode.DONT_SHOW) },
                                                onChangeMode = { viewModel.setDeviceControlMode(it) },
                                                onDismissShade = onDismiss,
                                            )
                                        }

                                        // Full tactile sliders island (Brightness & Volume)
                                        Surface(
                                            shape = shapeScheme.container,
                                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                                            border = getCardBorder(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 3.dp),
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                BrightnessSlider(
                                                    brightness = state.brightness,
                                                    onBrightnessChange = { viewModel.setBrightness(it) },
                                                    compact = false,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                VolumeSlider(
                                                    compact = false,
                                                    onOpenVolumeMixer = { viewModel.openSoundModeDetail() },
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }

                                        // Media playback card (if active)
                                        state.media?.let { media ->
                                            MediaCard(
                                                media = media,
                                                onPlayPause = { viewModel.mediaPlayPause() },
                                                onSkipNext = { viewModel.mediaSkipNext() },
                                                onSkipPrevious = { viewModel.mediaSkipPrevious() },
                                                onSeek = { viewModel.mediaSeek(it) },
                                                mediaCollapsed = mediaCollapsed,
                                                onToggleCollapse = { mediaCollapsed = !mediaCollapsed },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // end else isTogether

                        // Bottom Panel Switcher Pill (accessibility and quick-switching dock)
                        if (state.showPanelSwitcherPill) {
                            if (!isCombined) {
                                // Separate (Split) mode: Horizontal segmented pill dock
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f),
                                        border = getCardBorder(),
                                    ) {
                                        Row(modifier = Modifier.padding(3.dp)) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = if (state.activePanel == ShadePanel.NOTIFICATIONS) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable {
                                                        haptics.lightTap()
                                                        viewModel.setActivePanel(ShadePanel.NOTIFICATIONS)
                                                    },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        text = "Notifications",
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        color = if (state.activePanel == ShadePanel.NOTIFICATIONS) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (state.allNotifications.isNotEmpty()) {
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = if (state.activePanel == ShadePanel.NOTIFICATIONS) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                                                            modifier = Modifier.size(18.dp),
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Text(
                                                                    text = "${state.allNotifications.size}",
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                                    color = if (state.activePanel == ShadePanel.NOTIFICATIONS) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = if (state.activePanel == ShadePanel.QUICK_SETTINGS) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable {
                                                        haptics.lightTap()
                                                        viewModel.setActivePanel(ShadePanel.QUICK_SETTINGS)
                                                    },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        text = "Quick Settings",
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        color = if (state.activePanel == ShadePanel.QUICK_SETTINGS) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Combined (Together) mode: Vertical compact pill button
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f),
                                        border = getCardBorder(),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                haptics.lightTap()
                                                if (state.activePanel == ShadePanel.NOTIFICATIONS) {
                                                    viewModel.setActivePanel(ShadePanel.QUICK_SETTINGS)
                                                } else {
                                                    viewModel.setActivePanel(ShadePanel.NOTIFICATIONS)
                                                }
                                            },
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (state.activePanel == ShadePanel.NOTIFICATIONS)
                                                    Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = if (state.activePanel == ShadePanel.NOTIFICATIONS)
                                                    "Quick Settings" else "Notifications",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom drag-handle — swipe up to dismiss progressively with spring physics, or tap for animated quick-close
                        var hasHandleFiredHaptic by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .clickable {
                                    haptics.sheetDetent()
                                    viewModel.requestAnimatedClose()
                                }
                                .padding(vertical = 10.dp)
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    enabled = !isEditingTiles,
                                    state = rememberDraggableState { delta ->
                                        coroutineScope.launch {
                                            val nextP = (revealAnimatable.value + delta / revealDistancePx).coerceIn(0f, 1.02f)
                                            if (nextP < 0.85f && !hasHandleFiredHaptic) {
                                                haptics.sheetDetent()
                                                hasHandleFiredHaptic = true
                                            } else if (nextP >= 0.90f) {
                                                hasHandleFiredHaptic = false
                                            }
                                            revealAnimatable.snapTo(nextP)
                                        }
                                    },
                                    onDragStopped = { velocity ->
                                        hasHandleFiredHaptic = false
                                        val progressVelocity = (velocity / revealDistancePx).coerceIn(-12f, 12f)
                                        val p = revealAnimatable.value
                                        coroutineScope.launch {
                                            if (p < 0.85f || (progressVelocity < -0.85f && p < 0.99f)) {
                                                haptics.sheetDetent()
                                                revealAnimatable.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(dampingRatio = 0.94f, stiffness = 560f),
                                                    initialVelocity = progressVelocity.coerceIn(-12f, -1.5f),
                                                )
                                                onDismiss()
                                            } else {
                                                revealAnimatable.animateTo(
                                                    targetValue = 1f,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.84f,
                                                        stiffness = 440f,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val handleColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(5.dp)
                                    .graphicsLayer {
                                        val pull = (1f - progressProvider().coerceIn(0f, 1f)).coerceIn(0f, 1f)
                                        scaleX = 1f + 0.55f * pull
                                        alpha = (0.45f + 0.45f * pull).coerceIn(0.45f, 0.95f)
                                    }
                                    .clip(CircleShape)
                                    .background(handleColor),
                            )
                        }
                    }

                    // Floating Notification Undo Pill
                    NotificationUndoBar(
                        lastDismissed = state.lastDismissedNotification,
                        onUndo = { viewModel.undoDismissNotification() },
                        onClearUndo = { viewModel.clearUndoNotification() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 36.dp),
                    )
                }
            }
            }

            // In-shade Tile Detail Sheet (Flashlight multi-level control, Wi-Fi details, Bluetooth devices)
            AnimatedVisibility(
                visible = state.activeTileDetail != null,
                enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                state.activeTileDetail?.let { detail ->
                    QuickTileDetailSheet(
                        detailState = detail,
                        onDismiss = { viewModel.closeTileDetail() },
                        onSetTorchStrength = { viewModel.setTorchStrength(it) },
                        onToggleTorch = { viewModel.toggleTorchInDetail() },
                        onSetRingerMode = { viewModel.setRingerModeInDetail(it) },
                        onSetStreamVolume = { stream, vol -> viewModel.setStreamVolumeInDetail(stream, vol) },
                        onToggleDnd = { viewModel.toggleDndInDetail() },
                        onSetDndDuration = { viewModel.setDndDuration(it) },
                        onToggleHotspot = { viewModel.toggleHotspotInDetail() },
                        onToggleWifi = { viewModel.toggleWifiInDetail() },
                        onRefreshWifi = { viewModel.refreshWifiNetworks(triggerScan = true) },
                        onConnectWifi = { network, pass -> viewModel.connectWifiInDetail(network, pass) },
                        onOpenWifiPanel = { viewModel.openWifiPanelFromDetail() },
                        onOpenWifiPermissions = { viewModel.openWifiPermissionsFromDetail() },
                        onToggleBluetooth = { viewModel.toggleBluetoothInDetail() },
                        onRefreshBluetooth = { scanNearby -> viewModel.refreshBluetoothDevices(scanNearby = scanNearby) },
                        onConnectBluetoothDevice = { device -> viewModel.connectBluetoothDeviceInDetail(device) },
                        onPairNewBluetooth = { viewModel.openBluetoothPairFromDetail() },
                        onOpenMediaOutput = { viewModel.openMediaOutputFromDetail() },
                        onOpenBluetoothPermissions = { viewModel.openBluetoothPermissionsFromDetail() },
                    )
                }
            }

            // Quick Power Menu Dialog (Overlaid on top of shade panel)
            AnimatedVisibility(
                visible = showPowerMenu,
                enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.82f, stiffness = 420f), initialScale = 0.92f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.92f),
            ) {
                PowerMenuDialog(
                    onDismiss = { showPowerMenu = false },
                    onLockScreen = {
                        showPowerMenu = false
                        viewModel.lockScreen()
                        onDismiss()
                    },
                    onRestart = {
                        showPowerMenu = false
                        viewModel.restartDevice()
                        onDismiss()
                    },
                    onPowerOff = {
                        showPowerMenu = false
                        viewModel.powerOffDevice()
                        onDismiss()
                    },
                    onSystemPowerDialog = {
                        showPowerMenu = false
                        viewModel.openSystemPowerDialog()
                        onDismiss()
                    },
                    onRestartSystemUI = {
                        showPowerMenu = false
                        viewModel.restartSystemUI()
                        onDismiss()
                    },
                    onRebootRecovery = {
                        showPowerMenu = false
                        viewModel.rebootRecovery()
                        onDismiss()
                    },
                    onRebootBootloader = {
                        showPowerMenu = false
                        viewModel.rebootBootloader()
                        onDismiss()
                    },
                    isShizukuActive = state.isShizukuConnected,
                )
            }

            // In-shade Notification History Sheet (Dismissed notifications log)
            NotificationHistorySheet(
                isOpen = state.isHistorySheetOpen,
                history = state.dismissedHistory,
                onDismiss = { viewModel.closeHistorySheet() },
                onClearHistory = { viewModel.clearDismissedHistory() },
                snoozedRecords = state.snoozedRecords,
                onUnsnooze = { viewModel.unsnoozeNotification(it) },
            )
        }
    }
}
