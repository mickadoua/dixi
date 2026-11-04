package tech.dmconsulting.voxbulle.bubble

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder

/**
 * The bubble windows: the disc (takes touches, never the focus), its shadow and
 * halo behind (no touch), and the hide zone while dragging. Drag moves the
 * bubble; on release it snaps to the nearest side, or hides over the cross.
 */
class BubbleOverlay(
    private val context: Context,
    private val controller: BubbleController,
    private val store: BubblePositionStore,
    private val onTap: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val size = px(56f)
    private val halo = px(8f)
    private val margin = px(8f)
    private val hideZoneSize = px(96f)
    private val hideZoneBottom = px(32f)

    private val bubble = BubbleView(context)
    private val backdrop = BubbleBackdropView(context)
    private val hideZone = HideZoneView(context)
    private val bubbleParams = params(size, touchable = true)
    private val backdropParams = params(size + 2 * halo, touchable = false)
    private val hideZoneParams = params(hideZoneSize, touchable = false)
    private val tracker = TapDragTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    private var geometry = geometry()
    private var shown = false
    private var hideZoneShown = false
    private var ignoreGesture = false
    private var startX = 0
    private var startY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var snapAnimator: ValueAnimator? = null

    init {
        bubble.setOnClickListener { onTap() }
        bubble.setOnTouchListener(::onTouch)
        ViewCompat.addAccessibilityAction(bubble, context.getString(R.string.bubble_action_hide)) { _, _ ->
            onHide()
            true
        }
    }

    fun show() {
        if (shown) return
        geometry = geometry()
        render(controller.state)
        moveTo(geometry.place(store.position))
        // Added first, so the bubble stays above the cross while dragging.
        placeHideZone()
        hideZone.visibility = View.INVISIBLE
        windowManager.addView(hideZone, hideZoneParams)
        windowManager.addView(backdrop, backdropParams)
        windowManager.addView(bubble, bubbleParams)
        shown = true
        logCenter("bubble shown")
    }

    fun remove() {
        snapAnimator?.cancel()
        hideHideZone()
        if (!shown) return
        windowManager.removeView(bubble)
        windowManager.removeView(backdrop)
        windowManager.removeView(hideZone)
        shown = false
    }

    fun render(state: BubbleState) {
        bubble.state = state
        backdrop.listening = state == BubbleState.Listening
        backdrop.alpha = state.opacity
    }

    fun setVoiceLevel(level: Float) {
        backdrop.voiceLevel = level
    }

    /** Rotation or screen size change: same side, same vertical place. */
    fun onConfigurationChanged() {
        bubble.post {
            geometry = geometry()
            if (!shown || tracker.isDragging) return@post
            snapAnimator?.cancel()
            moveTo(geometry.place(store.position))
            logCenter("bubble placed after configuration change")
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called on tap.
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The window is square: a touch in its corners, off the disc, does nothing.
                val r = view.width / 2f
                val dx = event.x - r
                val dy = event.y - r
                ignoreGesture = dx * dx + dy * dy > r * r
                if (ignoreGesture) return true
                snapAnimator?.cancel()
                // Bars and cutout can change while the bubble is shown: measure again.
                geometry = geometry()
                tracker.down(event.rawX, event.rawY)
                downRawX = event.rawX
                downRawY = event.rawY
                startX = bubbleParams.x
                startY = bubbleParams.y
                view.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreGesture) return true
                if (tracker.move(event.rawX, event.rawY)) {
                    view.isPressed = false
                    if (controller.canHide) showHideZone()
                }
                if (tracker.isDragging) dragTo(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                if (ignoreGesture) return true
                // Slow devices can drop the last moves: the release point wins.
                if (tracker.isDragging) dragTo(event)
                val wasTap = tracker.up()
                if (wasTap) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                } else if (hideZoneShown && hideZone.active) {
                    hideHideZone()
                    Log.i(TAG, "bubble dropped on hide zone")
                    onHide()
                } else {
                    hideHideZone()
                    snap()
                }
            }
        }
        return true
    }

    private fun dragTo(event: MotionEvent) {
        val x = (startX + (event.rawX - downRawX)).toInt().coerceIn(0, geometry.screenWidth - size)
        val y = geometry.clampY((startY + (event.rawY - downRawY)).toInt())
        moveTo(x to y)
        updateHideZone()
    }

    private fun snap() {
        val position = geometry.snap(bubbleParams.x, bubbleParams.y)
        store.position = position
        val (toX, toY) = geometry.place(position)
        val fromX = bubbleParams.x
        val fromY = bubbleParams.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                moveTo((fromX + (toX - fromX) * f).toInt() to (fromY + (toY - fromY) * f).toInt())
            }
            start()
        }
        Log.i(TAG, "bubble snapped side=${position.side} yFraction=${"%.3f".format(position.yFraction)} to x=${toX + size / 2} y=${toY + size / 2}")
    }

    private fun moveTo(xy: Pair<Int, Int>) {
        bubbleParams.x = xy.first
        bubbleParams.y = xy.second
        backdropParams.x = xy.first - halo
        backdropParams.y = xy.second - halo
        if (shown) {
            windowManager.updateViewLayout(backdrop, backdropParams)
            windowManager.updateViewLayout(bubble, bubbleParams)
        }
    }

    private fun placeHideZone() {
        hideZoneParams.x = (geometry.screenWidth - hideZoneSize) / 2
        hideZoneParams.y = geometry.screenHeight - geometry.insets.bottom - hideZoneBottom - hideZoneSize
    }

    private fun showHideZone() {
        if (hideZoneShown || !shown) return
        placeHideZone()
        windowManager.updateViewLayout(hideZone, hideZoneParams)
        hideZone.active = false
        hideZone.alpha = 0f
        hideZone.visibility = View.VISIBLE
        hideZone.animate().alpha(1f).setDuration(FADE_MS).start()
        hideZoneShown = true
    }

    private fun updateHideZone() {
        if (!hideZoneShown) return
        val over = geometry.isOverHideTarget(
            centerX = bubbleParams.x + size / 2f,
            centerY = bubbleParams.y + size / 2f,
            targetX = hideZoneParams.x + hideZoneSize / 2f,
            targetY = hideZoneParams.y + hideZoneSize / 2f,
            radius = hideZoneSize / 2f,
        )
        if (over && !hideZone.active) hideZone.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        hideZone.active = over
    }

    private fun hideHideZone() {
        if (!hideZoneShown) return
        hideZone.animate().cancel()
        hideZone.visibility = View.INVISIBLE
        hideZoneShown = false
    }

    private fun params(side: Int, touchable: Boolean) = WindowManager.LayoutParams(
        side,
        side,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun geometry(): BubbleGeometry {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val i = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return BubbleGeometry(
                metrics.bounds.width(), metrics.bounds.height(), size, margin,
                BubbleGeometry.Insets(i.left, i.top, i.right, i.bottom),
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        // Before Android 11 there is no public inset query here: typical bar heights.
        return BubbleGeometry(
            metrics.widthPixels, metrics.heightPixels, size, margin,
            BubbleGeometry.Insets(0, px(24f), 0, px(48f)),
        )
    }

    private fun logCenter(what: String) {
        Log.i(TAG, "$what at x=${bubbleParams.x + size / 2} y=${bubbleParams.y + size / 2}")
    }

    private fun px(dp: Float) = (dp * density).toInt()

    private companion object {
        const val TAG = MicrophoneRecorder.TAG
        const val SNAP_MS = 200L
        const val FADE_MS = 150L
    }
}
