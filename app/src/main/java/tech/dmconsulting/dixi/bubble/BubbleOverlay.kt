package tech.dmconsulting.dixi.bubble

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
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.recording.MicrophoneRecorder

/**
 * The bubble windows: the disc (takes touches, never the focus), its glow and voice wave
 * behind (no touch), and the hide zone while dragging. Drag moves the bubble; on release
 * it snaps to the nearest side, hides over the cross, or tucks half behind the edge when
 * dropped against it or flung at it (mockups DMC-764 § 1). Everything shows on the bubble:
 * no panel, no dimmed screen during a dictation (§ 2). A long press while listening
 * cancels. After the dictation, « Texte copié » or the cause of the error next to it.
 */
class BubbleOverlay(
    private val context: Context,
    private val controller: BubbleController,
    private val store: BubblePositionStore,
    private val onTap: () -> Unit,
    private val onHide: () -> Unit,
    private val onCancel: () -> Unit = {},
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    /** The bubble window: the 48 dp touch target around the 44 dp disc. */
    private val size = px(BubbleState.TOUCH_DP)

    /** Room around the bubble window in the backdrop window: glow and voice wave. */
    private val glow = px(BubbleState.GLOW_MARGIN_DP)
    private val margin = px(8f)
    private val hideZoneWidth = px(240f)
    private val hideZoneHeight = px(180f)

    /** Drop target radius around the disc center (the disc itself is 72 dp). */
    private val hideTargetRadius = px(48f)

    /** Disc bottom 52 dp above the navigation bar, as before the neon pass. */
    private val hideZoneBottom = px(52f - HideZoneView.GLOW_DP)

    /** Dropped with its center this close to an edge, the bubble tucks away. */
    private val dockDistance = px(16f)
    private val flingVelocity = 1_000f * density

    private val bubble = BubbleView(context)
    private val backdrop = BubbleBackdropView(context)
    private val hideZone = HideZoneView(context)
    private val bubbleParams = params(size, touchable = true)
    private val backdropParams = params(size + 2 * glow, touchable = false)
    private val hideZoneParams = params(hideZoneWidth, touchable = false, height = hideZoneHeight)
    private val toast = BubbleToastView(context)

    // Touchable: Android 12+ draws a non-touchable overlay at 80 % at most, and the toast must be opaque.
    private val toastParams = params(WindowManager.LayoutParams.WRAP_CONTENT, touchable = true, height = WindowManager.LayoutParams.WRAP_CONTENT)
    private var toastShown = false

    /** The last text went straight into the field (auto-paste, DMC-141): « Texte collé ». */
    var toastPasted = false

    /** Cause of the last error, shown next to the bubble while it is in error. */
    @StringRes
    var errorMessage: Int? = null

    private val tracker = TapDragTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var velocity: VelocityTracker? = null

    private var geometry = geometry()
    private var shown = false
    private var hideZoneShown = false
    private var ignoreGesture = false
    private var longPressed = false
    private var startX = 0
    private var startY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var snapAnimator: ValueAnimator? = null
    private var state = controller.state

    /** Tucked at the saved side, half hidden. */
    val docked: Boolean get() = bubble.dockedSide != null

    private val longPress = Runnable {
        if (!controller.longPressCancels) return@Runnable
        longPressed = true
        Log.i(TAG, "bubble long press: cancel")
        onCancel()
    }

    init {
        // TalkBack calls performClick() directly: the half-bubble only comes back there too.
        bubble.setOnClickListener { if (docked) undock(animate = true) else onTap() }
        bubble.setOnTouchListener(::onTouch)
        ViewCompat.addAccessibilityAction(bubble, context.getString(R.string.bubble_action_hide)) { _, _ ->
            controller.hideIfAllowed(onHide)
        }
        ViewCompat.addAccessibilityAction(bubble, context.getString(R.string.bubble_action_dock)) { _, _ ->
            if (!controller.canDock || docked) return@addAccessibilityAction false
            dock(store.position.side, animate = false)
            true
        }
        ViewCompat.addAccessibilityAction(bubble, context.getString(R.string.bubble_action_cancel)) { _, _ ->
            if (!controller.longPressCancels) return@addAccessibilityAction false
            onCancel()
            true
        }
    }

    /** Throws if the system refuses a window; then no window stays attached. */
    fun show() {
        if (shown) return
        geometry = geometry()
        render(controller.state)
        if (store.docked) placeDocked(store.position.side) else moveTo(geometry.place(store.position))
        // Added first, so the bubble stays above the cross while dragging.
        placeHideZone()
        hideZone.visibility = View.INVISIBLE
        addAllOrNone(
            listOf(hideZone to hideZoneParams, backdrop to backdropParams, bubble to bubbleParams),
            add = { (view, params) -> windowManager.addView(view, params) },
            remove = { (view, _) -> windowManager.removeView(view) },
        )
        shown = true
        moveTo(bubbleParams.x to bubbleParams.y)
        BubbleEvents.setDocked(docked)
        logCenter(if (docked) "bubble shown docked" else "bubble shown")
    }

    fun remove() {
        snapAnimator?.cancel()
        bubble.removeCallbacks(longPress)
        hideHideZone()
        if (!shown) return
        removeToast()
        windowManager.removeView(bubble)
        windowManager.removeView(backdrop)
        windowManager.removeView(hideZone)
        shown = false
        BubbleEvents.removed()
    }

    fun render(newState: BubbleState) {
        val previous = state
        state = newState
        // A dictation started from the app (« Dicter ici ») brings a tucked bubble back first.
        if (docked && newState != BubbleState.Idle) undock(animate = false)
        bubble.state = newState
        backdrop.state = newState
        backdrop.alpha = newState.opacity
        if (shown) play(bubble, hapticsFor(previous, newState))
        when (newState) {
            BubbleState.Ready -> showToast(if (toastPasted) R.string.toast_pasted else R.string.toast_copied, error = false)
            BubbleState.Error -> errorMessage?.let { showToast(it, error = true) } ?: removeToast()
            else -> removeToast()
        }
    }

    /** [level]: 0 to 1. */
    fun setVoiceLevel(level: Float) {
        backdrop.voiceLevel = level
    }

    /** While listening: [elapsedMs] for TalkBack, and the wave turns magenta near the limit (with one haptic tap). */
    fun setListening(elapsedMs: Long, nearLimit: Boolean) {
        bubble.listeningElapsed = formatElapsed(elapsedMs)
        if (nearLimit && !backdrop.nearLimit && shown) play(bubble, listOf(Haptic.Warn))
        backdrop.nearLimit = nearLimit
    }

    /** Next to the bubble, on the screen side, centered on it. */
    private fun showToast(@StringRes message: Int, error: Boolean) {
        if (!shown) return
        toast.show(message, error)
        toast.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val glowPx = px(TOAST_GLOW_DP)
        val gap = px(8f)
        toastParams.x = if (store.position.side == Side.Right) {
            bubbleParams.x - gap - toast.measuredWidth + glowPx
        } else {
            bubbleParams.x + size + gap - glowPx
        }
        toastParams.y = bubbleParams.y + (size - toast.measuredHeight) / 2
        try {
            if (toastShown) {
                windowManager.updateViewLayout(toast, toastParams)
            } else {
                windowManager.addView(toast, toastParams)
                toastShown = true
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "bubble toast FAILED", e)
        }
    }

    private fun removeToast() {
        if (!toastShown) return
        windowManager.removeView(toast)
        toastShown = false
    }

    /** Rotation or screen size change: same side, same vertical place, tucked or not. */
    fun onConfigurationChanged() {
        bubble.post {
            geometry = geometry()
            if (!shown || tracker.isDragging) return@post
            snapAnimator?.cancel()
            if (docked) placeDocked(store.position.side) else moveTo(geometry.place(store.position))
            logCenter("bubble placed after configuration change")
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called on tap.
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The window is square: a touch in its corners, off the disc, does nothing.
                // Tucked, the whole window is the target: only half of the disc shows.
                val r = view.width / 2f
                val dx = event.x - r
                val dy = event.y - r
                ignoreGesture = !docked && dx * dx + dy * dy > r * r
                if (ignoreGesture) return true
                snapAnimator?.cancel()
                // Bars and cutout can change while the bubble is shown: measure again.
                geometry = geometry()
                tracker.down(event.rawX, event.rawY)
                downRawX = event.rawX
                downRawY = event.rawY
                startX = bubbleParams.x
                startY = bubbleParams.y
                longPressed = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                if (controller.longPressCancels) view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.isPressed = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreGesture || longPressed) return true
                if (tracker.move(event.rawX, event.rawY)) {
                    view.isPressed = false
                    view.removeCallbacks(longPress)
                    // Pulled out of the edge: whole again, and dragged from where it shows.
                    if (docked) {
                        undock(animate = false)
                        startX = bubbleParams.x
                    }
                    if (controller.canHide) showHideZone()
                }
                if (tracker.isDragging) dragTo(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                view.removeCallbacks(longPress)
                if (ignoreGesture) return true
                if (longPressed) {
                    tracker.up()
                    return true
                }
                // Slow devices can drop the last moves: the release point wins.
                if (tracker.isDragging) dragTo(event)
                val release = releaseAction(
                    wasTap = tracker.up(),
                    cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    overHideZone = hideZoneShown && hideZone.active,
                )
                hideHideZone()
                when (release) {
                    // The click listener brings the half-bubble back instead of dictating.
                    Release.Click -> view.performClick()

                    Release.Hide -> {
                        Log.i(TAG, "bubble dropped on hide zone")
                        onHide()
                    }

                    Release.Snap -> {
                        val fling = velocity?.run {
                            computeCurrentVelocity(1000)
                            flingSide(xVelocity, yVelocity, flingVelocity)
                        }
                        val side = geometry.dockSide(bubbleParams.x, dockDistance) ?: fling
                        if (side != null && controller.canDock && event.actionMasked == MotionEvent.ACTION_UP) dock(side, animate = true) else snap()
                    }

                    Release.None -> Unit
                }
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun dragTo(event: MotionEvent) {
        val x = geometry.clampDragX((startX + (event.rawX - downRawX)).toInt())
        val y = geometry.clampY((startY + (event.rawY - downRawY)).toInt())
        moveTo(x to y)
        updateHideZone()
    }

    private fun snap() {
        val position = geometry.snap(bubbleParams.x, bubbleParams.y)
        store.position = position
        animateTo(geometry.place(position))
        val (toX, toY) = geometry.place(position)
        Log.i(TAG, "bubble snapped side=${position.side} yFraction=${"%.3f".format(position.yFraction)} to x=${toX + size / 2} y=${toY + size / 2}")
    }

    /** Slides the bubble half behind the [side] edge, at its height, and keeps it there. */
    private fun dock(side: Side, animate: Boolean) {
        val y = geometry.clampY(bubbleParams.y)
        store.position = geometry.snap(geometry.xFor(side), y).copy(side = side)
        store.docked = true
        removeToast()
        val target = geometry.halfHiddenX(side) to y
        if (animate) animateTo(target) { placeDocked(side) } else placeDocked(side)
        bubble.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Log.i(TAG, "bubble docked side=$side at y=${y + size / 2}")
    }

    /** The tucked look: the window against the edge, the disc drawn half past it, no glow. */
    private fun placeDocked(side: Side) {
        bubble.dockedSide = side
        BubbleEvents.setDocked(true)
        backdrop.visibility = View.GONE
        moveTo(geometry.dockedX(side) to geometry.place(store.position).second)
    }

    /** Whole again at the saved side and height, 92 %. */
    private fun undock(animate: Boolean) {
        val side = bubble.dockedSide ?: return
        store.docked = false
        bubble.dockedSide = null
        BubbleEvents.setDocked(false)
        backdrop.visibility = View.VISIBLE
        // Same pixels as the tucked look: the disc center stays on the edge, then slides in.
        moveTo(geometry.halfHiddenX(side) to bubbleParams.y)
        val target = geometry.place(store.position)
        if (animate) animateTo(target) else moveTo(target)
        bubble.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Log.i(TAG, "bubble undocked side=$side")
    }

    private fun animateTo(target: Pair<Int, Int>, then: () -> Unit = {}) {
        val (toX, toY) = target
        val fromX = bubbleParams.x
        val fromY = bubbleParams.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                moveTo((fromX + (toX - fromX) * f).toInt() to (fromY + (toY - fromY) * f).toInt())
            }
            // Also when a new touch cancels it: the bubble never stays between two looks.
            doOnEnd { then() }
            start()
        }
    }

    private fun moveTo(xy: Pair<Int, Int>) {
        bubbleParams.x = xy.first
        bubbleParams.y = xy.second
        backdropParams.x = xy.first - glow
        backdropParams.y = xy.second - glow
        if (shown) {
            windowManager.updateViewLayout(backdrop, backdropParams)
            windowManager.updateViewLayout(bubble, bubbleParams)
            val centerX = xy.first + size / 2
            BubbleEvents.place(if (centerX < geometry.screenWidth / 2) Side.Left else Side.Right, xy.second + size / 2)
        }
    }

    private fun placeHideZone() {
        hideZoneParams.x = (geometry.screenWidth - hideZoneWidth) / 2
        hideZoneParams.y = geometry.screenHeight - geometry.insets.bottom - hideZoneBottom - hideZoneHeight
    }

    private fun showHideZone() {
        if (hideZoneShown || !shown) return
        placeHideZone()
        // Android 12+ caps the opacity of a non-touchable overlay at 80 %: the
        // zone takes touches only while shown, so it draws fully opaque.
        hideZoneParams.flags = hideZoneParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
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
            targetX = hideZoneParams.x + hideZoneWidth / 2f,
            targetY = hideZoneParams.y + hideZoneHeight - hideZone.discCenterFromBottom,
            radius = hideTargetRadius.toFloat(),
        )
        if (over && !hideZone.active) hideZone.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        hideZone.active = over
    }

    private fun hideHideZone() {
        if (!hideZoneShown) return
        hideZone.animate().cancel()
        hideZone.visibility = View.INVISIBLE
        hideZoneParams.flags = hideZoneParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(hideZone, hideZoneParams)
        hideZoneShown = false
    }

    private fun params(side: Int, touchable: Boolean, height: Int = side) = WindowManager.LayoutParams(
        side,
        height,
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
                metrics.bounds.width(),
                metrics.bounds.height(),
                size,
                margin,
                BubbleGeometry.Insets(i.left, i.top, i.right, i.bottom),
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        // Before Android 11 there is no public inset query here: typical bar heights.
        return BubbleGeometry(
            metrics.widthPixels,
            metrics.heightPixels,
            size,
            margin,
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

/**
 * Adds each item in order. If one add fails, removes the items already added,
 * last first, then throws: no window stays attached without its owner knowing.
 */
internal fun <T> addAllOrNone(items: List<T>, add: (T) -> Unit, remove: (T) -> Unit) {
    val added = mutableListOf<T>()
    try {
        items.forEach {
            add(it)
            added += it
        }
    } catch (e: RuntimeException) {
        added.asReversed().forEach { runCatching { remove(it) } }
        throw e
    }
}
