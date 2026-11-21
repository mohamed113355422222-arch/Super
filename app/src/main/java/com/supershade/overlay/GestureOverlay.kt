package com.supershade.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

import com.supershade.settings.SplitGestureMode

/**
 * An invisible touch-capture strip positioned across the top status bar zone.
 *
 * Streams continuous pull-down progress to [TopSwipeGestureHelper] so the shade
 * follows the user's finger 1:1 from the very first pixel of downward movement.
 */
class GestureOverlay(
    private val context: Context,
    private val isShadeOpen: () -> Boolean = { false },
    private val splitGestureMode: () -> SplitGestureMode = { SplitGestureMode.SEPARATE_70_30 },
    private val onSwipeDown: (expandQs: Boolean) -> Unit = {},
    private val onBeginSwipeOpen: ((expandQs: Boolean) -> Unit)? = null,
    private val onUpdateSwipeProgress: ((progress: Float) -> Unit)? = null,
    private val onEndSwipeOpen: ((shouldOpen: Boolean, progress: Float, progressVelocity: Float) -> Unit)? = null,
    private val onBrightnessSwipe: ((Int) -> Unit)? = null,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var gestureHelper: TopSwipeGestureHelper? = null

    private val captureHeight = run {
        val extra = (20 * context.resources.displayMetrics.density).toInt()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val insets = windowManager?.currentWindowMetrics?.windowInsets?.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars()
                )
                val top = insets?.top ?: 0
                if (top > 0) return@run top + extra
            } catch (_: Throwable) {}
        }
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
        h.coerceAtLeast((28 * context.resources.displayMetrics.density).toInt()) + extra
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        captureHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val display = context.display
                val maxRate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
                preferredRefreshRate = maxRate.coerceAtLeast(60f)
            } catch (_: Throwable) {
                preferredRefreshRate = 120f
            }
        }
    }

    val isAttached: Boolean
        get() = overlayView?.isAttachedToWindow == true

    fun attach(): Boolean {
        if (overlayView?.isAttachedToWindow == true) return true
        if (overlayView != null) detach()
        if (!android.provider.Settings.canDrawOverlays(context)) return false
        val wm = windowManager ?: return false

        val helper = TopSwipeGestureHelper(
            context = context,
            isShadeOpen = isShadeOpen,
            splitGestureMode = splitGestureMode,
            onBeginSwipeOpen = { expandQs ->
                if (onBeginSwipeOpen != null) {
                    onBeginSwipeOpen.invoke(expandQs)
                } else {
                    onSwipeDown(expandQs)
                }
            },
            onUpdateSwipeProgress = { progress ->
                onUpdateSwipeProgress?.invoke(progress)
            },
            onEndSwipeOpen = { shouldOpen, progress, progressVelocity ->
                onEndSwipeOpen?.invoke(shouldOpen, progress, progressVelocity)
            },
            onBrightnessSwipe = onBrightnessSwipe,
        )

        val view = View(context).apply {
            setOnTouchListener(helper.createTouchListener())
        }
        return try {
            wm.addView(view, params)
            gestureHelper = helper
            overlayView = view
            true
        } catch (e: Exception) {
            android.util.Log.e("GestureOverlay", "Failed to attach top gesture overlay", e)
            gestureHelper = null
            overlayView = null
            false
        }
    }

    fun cancelActiveTracking() {
        gestureHelper?.cancelActiveTracking()
    }

    fun detach() {
        gestureHelper?.cancelActiveTracking()
        gestureHelper = null
        overlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (_: Throwable) {}
        }
        overlayView = null
    }
}
