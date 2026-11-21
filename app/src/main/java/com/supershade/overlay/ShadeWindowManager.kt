package com.supershade.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.KeyEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.supershade.ui.theme.BackdropTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.supershade.ui.shade.ShadeRoot
import com.supershade.viewmodel.ShadeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Creates and manages the full-screen [TYPE_APPLICATION_OVERLAY] Compose window
 * that renders [ShadeRoot].
 *
 * Because this object lives inside a [android.app.Service] — not an Activity —
 * it provides its own [LifecycleOwner], [ViewModelStoreOwner], [SavedStateRegistryOwner],
 * and [OnBackPressedDispatcherOwner] to the hosted [ComposeView] so that all Compose
 * APIs (including [BackHandler]) and hardware/gesture back keys work seamlessly.
 *
 * API:
 *   [show]  — present the shade (no-op if already visible)
 *   [hide]  — dismiss the shade (no-op if not visible)
 *   [isShowing] — query current visibility
 */
class ShadeWindowManager(
    private val context: Context,
    private val viewModel: ShadeViewModel,
    private val governor: com.supershade.shizuku.StatusBarGovernor? = null,
    private val haptics: com.supershade.haptics.SuperHaptics? = null,
) {

    private val windowManager: WindowManager = context.getSystemService()!!
    private var overlayView: ComposeView? = null
    private var statusBarBlockerView: View? = null
    private var lifecycleOwner: ShadeLifecycleOwner? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null
    private var isVisible = false
    private var isInteractiveDragging = false
    private var showTimestampMs = 0L

    private val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private val passthroughFlags = baseFlags or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        passthroughFlags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = context.display
                val maxRate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
                preferredRefreshRate = maxRate.coerceAtLeast(60f)
            } catch (_: Throwable) {
                preferredRefreshRate = 120f
            }
        }
    }

    init {
        scope.launch {
            viewModel.state
                .map { it.backdropTheme to it.backdropOpacity }
                .distinctUntilChanged()
                .collect { (backdrop, opacity) ->
                    if (isVisible && !isInteractiveDragging) {
                        if (syncWindowLayoutParams(backdrop, opacity, touchable = true, allowBlur = true)) {
                            applyParamsToAttachedView()
                        }
                    }
                }
        }
    }

    private fun syncWindowLayoutParams(
        backdrop: BackdropTheme,
        opacity: Float,
        touchable: Boolean,
        allowBlur: Boolean,
    ): Boolean {
        val oldFlags = params.flags
        val oldBlurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.blurBehindRadius else 0

        var newFlags = if (touchable) baseFlags else passthroughFlags
        var newBlurRadius = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val useBlur = allowBlur && opacity < 0.99f && backdrop != BackdropTheme.OPAQUE
            if (useBlur) {
                val density = context.resources.displayMetrics.density
                // Modest blur radius tuned for smooth 60fps on 4GB RAM mid-range GPUs
                newBlurRadius = when (backdrop) {
                    BackdropTheme.TRANSPARENT -> (12 * density).toInt().coerceIn(24, 42)
                    BackdropTheme.LIQUID_GLASS -> (16 * density).toInt().coerceIn(36, 56)
                    BackdropTheme.FROSTED_GLASS -> (20 * density).toInt().coerceIn(44, 68)
                    BackdropTheme.BLURRY -> (26 * density).toInt().coerceIn(56, 84)
                    else -> (18 * density).toInt().coerceIn(36, 68)
                }
                @Suppress("DEPRECATION")
                newFlags = newFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
            params.blurBehindRadius = newBlurRadius
        }
        params.flags = newFlags

        return oldFlags != newFlags ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && oldBlurRadius != newBlurRadius)
    }

    private fun applyParamsToAttachedView() {
        val view = overlayView ?: return
        if (view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private fun ensureAttachedView(): ComposeView? {
        overlayView?.let { existing ->
            if (existing.isAttachedToWindow) return existing
        }
        if (!android.provider.Settings.canDrawOverlays(context)) return null

        val owner = ShadeLifecycleOwner().also { lifecycleOwner = it }
        owner.create()

        val s = viewModel.state.value
        syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = false, allowBlur = false)

        val view = ComposeView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeOnBackPressedDispatcherOwner(owner)
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (System.currentTimeMillis() - showTimestampMs < 250L) {
                        return@setOnKeyListener true
                    }
                    if (owner.onBackPressedDispatcher.hasEnabledCallbacks()) {
                        owner.onBackPressedDispatcher.onBackPressed()
                    } else {
                        hide()
                    }
                    true
                } else {
                    false
                }
            }
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.supershade.haptics.LocalSuperHaptics provides (haptics ?: com.supershade.haptics.SuperHaptics(context))
                ) {
                    ShadeRoot(
                        viewModel = viewModel,
                        onDismiss = { viewModel.completeClose() },
                    )
                }
            }
        }
        overlayView = view
        return try {
            windowManager.addView(view, params)
            view.requestApplyInsets()
            view
        } catch (e: Exception) {
            android.util.Log.e("ShadeWindowManager", "Failed to add overlay window", e)
            overlayView = null
            owner.destroy()
            lifecycleOwner = null
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Pre-warms and caches the Compose overlay in GONE state to eliminate open latency. */
    fun prewarm() {
        ensureAttachedView()
    }

    /** Presents the pre-warmed window in passthrough mode so top overlay keeps receiving finger moves. */
    fun beginInteractiveShow(expandQs: Boolean = false) {
        hideJob?.cancel()
        hideJob = null
        isInteractiveDragging = true
        isVisible = true

        val view = ensureAttachedView() ?: return
        val s = viewModel.state.value
        if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = false, allowBlur = false)) {
            applyParamsToAttachedView()
        }
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        lifecycleOwner?.resume()
        view.requestApplyInsets()
    }

    /** Finalizes top swipe gesture: either grants touch focus to open shade or leaves passthrough for collapse. */
    fun finishInteractiveShow(shouldOpen: Boolean) {
        isInteractiveDragging = false
        val view = overlayView ?: return
        val s = viewModel.state.value
        if (shouldOpen) {
            showTimestampMs = System.currentTimeMillis()
            isVisible = true
            if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = true, allowBlur = true)) {
                applyParamsToAttachedView()
            }
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
            }
            lifecycleOwner?.resume()
            view.requestFocus()
            attachStatusBarBlocker()
            scope.launch { governor?.collapse() }
        } else {
            detachStatusBarBlocker()
            if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = false, allowBlur = false)) {
                applyParamsToAttachedView()
            }
            // Safety fallback in case collapse animation didn't emit completion.
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(400L)
                if (!viewModel.isGestureDragging.value && isVisible) {
                    viewModel.completeClose()
                }
            }
        }
    }

    /** Presents the shade overlay (used for quick-open buttons, broadcasts, accessibility actions). */
    fun show(expandQs: Boolean = false) {
        if (isInteractiveDragging || viewModel.isGestureDragging.value) return
        showTimestampMs = System.currentTimeMillis()
        hideJob?.cancel()
        hideJob = null

        val view = ensureAttachedView() ?: return
        isVisible = true
        val s = viewModel.state.value
        if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = true, allowBlur = true)) {
            applyParamsToAttachedView()
        }
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        lifecycleOwner?.resume()
        view.requestFocus()
        view.requestApplyInsets()
        attachStatusBarBlocker()

        if (!viewModel.state.value.isOpen) {
            viewModel.open(expandQs = expandQs)
        }
        scope.launch { governor?.collapse() }
    }

    /** Requests an animated dismissal and makes background immediately touchable. */
    fun hide() {
        if (!isVisible) return
        isInteractiveDragging = false
        detachStatusBarBlocker()
        val s = viewModel.state.value
        if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = false, allowBlur = false)) {
            applyParamsToAttachedView()
        }
        if (viewModel.state.value.isOpen) {
            viewModel.requestAnimatedClose()
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(380L)
                if (isVisible) {
                    viewModel.completeClose()
                }
            }
        } else {
            hideImmediate()
        }
    }

    /** Hides the pre-warmed window immediately while keeping Compose alive for instant next open. */
    fun hideImmediate() {
        hideJob?.cancel()
        hideJob = null
        isInteractiveDragging = false
        isVisible = false
        detachStatusBarBlocker()

        val view = overlayView ?: return
        val s = viewModel.state.value
        if (syncWindowLayoutParams(s.backdropTheme, s.backdropOpacity, touchable = false, allowBlur = false)) {
            applyParamsToAttachedView()
        }
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
        }
        lifecycleOwner?.pauseToCreated()
    }

    /** Tears down the pre-warmed Compose overlay completely on service stop. */
    fun destroy() {
        hideImmediate()
        val view = overlayView
        overlayView = null
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            } catch (_: Throwable) {}
        }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    /** Returns true when the shade overlay is currently visible. */
    fun isShowing(): Boolean = isVisible

    // Adds an invisible full-width view at the top of the screen that consumes all touch events
    // in the status bar height zone, preventing the native status bar from intercepting swipes
    // while our shade is open. Removed on hide().
    private fun attachStatusBarBlocker() {
        if (statusBarBlockerView != null) return
        val calculatedHeight: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                if (insets.top > 0) insets.top else null
            } catch (_: Throwable) { null }
        } else null

        val statusBarHeight = (calculatedHeight ?: run {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) {
                try {
                    context.resources.getDimensionPixelSize(resId)
                } catch (_: Exception) { 48 }
            } else 48
        }).coerceAtLeast(48)

        val blockerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val blocker = View(context).apply {
            setOnTouchListener { _, event ->
                event.action in listOf(
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                )
            }
        }
        try {
            windowManager.addView(blocker, blockerParams)
            statusBarBlockerView = blocker
        } catch (_: Exception) {}
    }

    private fun detachStatusBarBlocker() {
        val v = statusBarBlockerView ?: return
        statusBarBlockerView = null
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }
}

/**
 * A LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner / OnBackPressedDispatcherOwner
 * for use with ComposeView displayed in a persistent WindowManager overlay.
 */
private class ShadeLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreInstance = ViewModelStore()
    private val onBackPressedDispatcherInstance = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance
    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = onBackPressedDispatcherInstance

    fun create() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun pauseToCreated() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStoreInstance.clear()
    }
}
