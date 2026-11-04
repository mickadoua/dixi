package tech.dmconsulting.voxbulle.bubble

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.withTranslation
import tech.dmconsulting.voxbulle.R

private fun View.dp(value: Float) = value * resources.displayMetrics.density

/** Wave bar heights in a 28-unit box (mockups, screens 6 and 11). */
private val LOW_WAVE = floatArrayOf(6f, 12f, 18f, 12f, 6f)
private val HIGH_WAVE = floatArrayOf(10f, 20f, 26f, 16f, 22f)
private const val CHECK_PATH = "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"

/** The 56 dp disc and its content for each state (charter § 6). Takes the touches. */
class BubbleView(context: Context) : View(context) {

    var state: BubbleState = BubbleState.Idle
        set(value) {
            field = value
            contentDescription = context.getString(value.description)
            alpha = value.opacity
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bar = RectF()
    private val arc = RectF()
    private val check = PathParser.createPathFromPathData(CHECK_PATH)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        state = BubbleState.Idle
    }

    // Taps come from BubbleOverlay's touch listener, TalkBack from here.
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        fill.color = ContextCompat.getColor(context, state.background)
        canvas.drawCircle(r, r, r, fill)
        val content = ContextCompat.getColor(context, state.content)
        when (state) {
            BubbleState.Idle -> drawWave(canvas, LOW_WAVE, content)
            BubbleState.Listening -> drawWave(canvas, HIGH_WAVE, content)
            BubbleState.Processing -> drawSpinner(canvas, content)
            BubbleState.Ready -> drawCheck(canvas, content)
        }
    }

    /** 5 bars, 3 units wide every 5 units, in a box of 60 % of the diameter. */
    private fun drawWave(canvas: Canvas, heights: FloatArray, color: Int) {
        val box = width * 0.6f
        val unit = box / 28f
        val left = (width - box) / 2f
        val top = (height - box) / 2f
        ink.color = color
        heights.forEachIndexed { i, h ->
            val x = left + (3f + 5f * i) * unit
            val y = top + (14f - h / 2f) * unit
            bar.set(x, y, x + 3f * unit, y + h * unit)
            canvas.drawRoundRect(bar, 1.5f * unit, 1.5f * unit, ink)
        }
    }

    /** 28 dp ring, 4 dp stroke: a light track and a turning quarter. */
    private fun drawSpinner(canvas: Canvas, color: Int) {
        val size = dp(28f)
        val inset = (width - size) / 2f + dp(2f)
        arc.set(inset, inset, width - inset, height - inset)
        stroke.strokeWidth = dp(4f)
        stroke.color = ColorUtils.setAlphaComponent(color, 77)
        canvas.drawOval(arc, stroke)
        stroke.color = color
        val angle = (SystemClock.uptimeMillis() % SPIN_MS) * 360f / SPIN_MS
        canvas.drawArc(arc, angle - 90f, 90f, false, stroke)
        postInvalidateOnAnimation()
    }

    /** 28 dp white check (24-unit Material path). */
    private fun drawCheck(canvas: Canvas, color: Int) {
        val size = dp(28f)
        val scale = size / 24f
        ink.color = color
        canvas.withTranslation((width - size) / 2f, (height - size) / 2f) {
            scale(scale, scale)
            drawPath(check, ink)
        }
    }

    private companion object {
        const val SPIN_MS = 1_000L
    }
}

/**
 * Drawn behind the disc in a window that takes no touch: the soft shadow
 * (elevation 6 dp) and, when listening, the 8 dp halo that follows the voice.
 */
class BubbleBackdropView(context: Context) : View(context) {

    private val discRadius = dp(28f)

    var listening = false
        set(value) {
            field = value
            invalidate()
        }

    /** Voice level, 0 to 1: the halo thickens with it. */
    var voiceLevel = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (listening) invalidate()
        }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000
        setShadowLayer(dp(6f), 0f, dp(2f), 0x4D000000)
    }
    private val punch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.CLEAR
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val layer = canvas.saveLayer(null, null)
        canvas.drawCircle(c, c, discRadius, shadow)
        // Keep the shadow outside the disc only: the disc is 90 % opaque at rest.
        canvas.drawCircle(c, c, discRadius, punch)
        canvas.restoreToCount(layer)
        if (listening) {
            // Outer edge 8 dp from the disc; 3 dp thick in silence, up to 8 dp loud.
            val thickness = dp(3f) + dp(5f) * voiceLevel
            halo.strokeWidth = thickness
            halo.color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, BubbleState.Listening.background), 128)
            canvas.drawCircle(c, c, discRadius + dp(8f) - thickness / 2f, halo)
        }
    }
}

/** The « Masquer » target at the bottom while dragging: a disc with a cross. */
class HideZoneView(context: Context) : View(context) {

    var active = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.5f)
    }

    init {
        contentDescription = context.getString(R.string.bubble_hide_zone)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val radius = dp(if (active) 32f else 28f)
        fill.color = ContextCompat.getColor(
            context,
            if (active) R.color.bubble_hide_zone_active
            else R.color.bubble_hide_zone,
        )
        canvas.drawCircle(c, c, radius, fill)
        cross.color = ContextCompat.getColor(context, R.color.bubble_on_hide_zone)
        val arm = dp(7f)
        canvas.drawLine(c - arm, c - arm, c + arm, c + arm, cross)
        canvas.drawLine(c - arm, c + arm, c + arm, c - arm, cross)
    }
}
