package com.supershade.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.supershade.domain.system.CustomStatusBarRepository
import com.supershade.ui.statusbar.IosStatusBar
import com.supershade.ui.statusbar.StatusBarConfig

/**
 * Manages the lightweight, 100% transparent, non-touchable [TYPE_APPLICATION_OVERLAY]
 * window that renders the integrated iOS-style status bar when the native Android
 * status bar is hidden via [com.supershade.shizuku.StatusBarGovernor].
 *
 * Conflict-prevention guarantees:
 * - Uses [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] and [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]
 *   so pull-down and brightness-scrub gestures pass cleanly to [GestureOverlay] and
 *   [com.supershade.service.SuperShadeAccessibilityService].
 * - Hides (`View.GONE`) whenever [ShadeWindowManager] is showing the shade so it never
 *   overlaps `ShadeRoot`.
 * - Zero background fill (`Color.TRANSPARENT` + `PixelFormat.TRANSLUCENT` + no blur flags).
 * - Updates configuration reactively in Compose without recreating the overlay window.
 */
class CustomStatusBarOverlay(
    private val context: Context,
    private val repository: CustomStatusBarRepository,
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var lifecycleOwner: StatusBarOverlayLifecycleOwner? = null

    private var config by mutableStateOf(StatusBarConfig())

    private var barHeightPx by mutableIntStateOf(computeStatusBarHeightPx(0f))
    private var cutoutLeftPx by mutableIntStateOf(0)
    private var cutoutRightPx by mutableIntStateOf(0)
    private var safeInsetLeftPx by mutableIntStateOf(0)
    private var safeInsetRightPx by mutableIntStateOf(0)

    private var isSuppressedForShade = false

    val isAttached: Boolean
        get() = composeView?.isAttachedToWindow == true

    fun updateConfig(newConfig: StatusBarConfig) {
        val heightChanged = this.config.barHeightDp != newConfig.barHeightDp
        this.config = newConfig
        if (heightChanged) {
            val newHeightPx = computeStatusBarHeightPx(newConfig.barHeightDp)
            if (newHeightPx != barHeightPx) {
                barHeightPx = newHeightPx
                val view = composeView
                if (view != null && view.isAttachedToWindow) {
                    try {
                        windowManager?.updateViewLayout(view, buildLayoutParams(newHeightPx))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun setSuppressedForShade(suppressed: Boolean) {
        if (isSuppressedForShade == suppressed) return
        isSuppressedForShade = suppressed
        composeView?.visibility = if (suppressed) View.GONE else View.VISIBLE
    }

    fun attach() {
        if (composeView != null) return
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(context)) return

        refreshMetrics()
        repository.startListening()

        val owner = StatusBarOverlayLifecycleOwner().also { lifecycleOwner = it }
        owner.start()

        val params = buildLayoutParams(barHeightPx)
        val view = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            visibility = if (isSuppressedForShade) View.GONE else View.VISIBLE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setOnApplyWindowInsetsListener { _, insets ->
                    updateCutoutFromInsets(insets)
                    insets
                }
            }

            setContent {
                val state by repository.statusBarState.collectAsStateWithLifecycle()
                val heightDp = with(LocalDensity.current) { barHeightPx.toDp() }
                IosStatusBar(
                    state = state,
                    config = config,
                    cutoutLeftPx = cutoutLeftPx,
                    cutoutRightPx = cutoutRightPx,
                    safeInsetLeftPx = safeInsetLeftPx,
                    safeInsetRightPx = safeInsetRightPx,
                    barHeightDp = heightDp,
                )
            }
        }

        composeView = view
        try {
            wm.addView(view, params)
            view.requestApplyInsets()
        } catch (e: Exception) {
            android.util.Log.e("CustomStatusBarOverlay", "Failed to attach custom status bar", e)
            composeView = null
            owner.stop()
            lifecycleOwner = null
            repository.stopListening()
        }
    }

    fun detach() {
        val view = composeView ?: return
        composeView = null
        try {
            if (view.isAttachedToWindow) {
                windowManager?.removeViewImmediate(view)
            }
        } catch (_: Throwable) {}
        lifecycleOwner?.stop()
        lifecycleOwner = null
        repository.stopListening()
    }

    fun onConfigurationChanged() {
        val view = composeView ?: return
        refreshMetrics()
        if (view.isAttachedToWindow) {
            try {
                windowManager?.updateViewLayout(view, buildLayoutParams(barHeightPx))
                view.requestApplyInsets()
            } catch (_: Exception) {}
        }
    }

    private fun buildLayoutParams(heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun refreshMetrics() {
        barHeightPx = computeStatusBarHeightPx(config.barHeightDp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager?.currentWindowMetrics?.windowInsets?.let {
                    updateCutoutFromInsets(it)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun updateCutoutFromInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                val topRect = cutout.boundingRectTop
                if (!topRect.isEmpty) {
                    cutoutLeftPx = topRect.left
                    cutoutRightPx = topRect.right
                } else {
                    cutoutLeftPx = 0
                    cutoutRightPx = 0
                }
                safeInsetLeftPx = cutout.safeInsetLeft
                safeInsetRightPx = cutout.safeInsetRight
            } else {
                cutoutLeftPx = 0
                cutoutRightPx = 0
                safeInsetLeftPx = 0
                safeInsetRightPx = 0
            }
        }
    }

    private fun computeStatusBarHeightPx(customBarHeightDp: Float): Int {
        val density = context.resources.displayMetrics.density
        if (customBarHeightDp >= 24f) {
            return (customBarHeightDp.coerceIn(24f, 48f) * density).toInt()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager?.currentWindowMetrics?.windowInsets?.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()
                )
                val top = insets?.top ?: 0
                if (top > 0) return top
            } catch (_: Throwable) {}
        }
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) {
            try { context.resources.getDimensionPixelSize(resId) } catch (_: Exception) { 0 }
        } else 0
        val minPx = (24 * density).toInt()
        return h.coerceAtLeast(minPx)
    }
}

private class StatusBarOverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreInstance = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStoreInstance.clear()
    }
}
