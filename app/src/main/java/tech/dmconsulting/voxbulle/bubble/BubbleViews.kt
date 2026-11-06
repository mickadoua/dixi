package tech.dmconsulting.voxbulle.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import tech.dmconsulting.voxbulle.R

internal fun View.dp(value: Float) = value * resources.displayMetrics.density

/** CSS blur radius (2 sigma) to the Android BlurMaskFilter radius (sigma = 0.57735 r + 0.5). */
internal fun View.blur(cssDp: Float) = (dp(cssDp) / 2f - 0.5f).coerceAtLeast(0.5f) / 0.57735f

/** BlurMaskFilter needs a software layer before Android 9. */
internal fun View.allowBlur() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
}

/** Wave bar heights in dp, 4 dp wide every 7 dp (neon charter § Bulle). */
private val LOW_WAVE = floatArrayOf(6f, 12f, 18f, 12f, 6f)
private val HIGH_WAVE = floatArrayOf(14f, 26f, 34f, 26f, 14f)
private const val CHECK_PATH = "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"

/** Material « priority_high »: an exclamation mark. */
private const val ERROR_PATH = "M10 3h4v12h-4zM12 17a2 2 0 1 1 0 4a2 2 0 1 1 0-4z"

/** The 56 dp disc and its content for each state. Takes the touches. */
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
    private val glowInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur(6f), BlurMaskFilter.Blur.NORMAL) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(blur(10f), BlurMaskFilter.Blur.NORMAL)
    }
    private val bar = RectF()
    private val arc = RectF()
    private val disc = Path()
    private val check = PathParser.createPathFromPathData(CHECK_PATH)
    private val exclamation = PathParser.createPathFromPathData(ERROR_PATH)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        allowBlur()
        state = BubbleState.Idle
    }

    // Taps come from BubbleOverlay's touch listener, TalkBack from here.
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        fill.color = color(state.fill)
        canvas.drawCircle(r, r, r, fill)
        val outline = color(state.outline)
        if (state.fill != state.outline) {
            // Inner glow (inset 0 0 10 dp at 25 %), kept inside the disc.
            disc.rewind()
            disc.addCircle(r, r, r, Path.Direction.CW)
            canvas.withClip(disc) {
                innerGlow.strokeWidth = dp(4f)
                innerGlow.color = ColorUtils.setAlphaComponent(color(state.glowColor), 64)
                drawCircle(r, r, r, innerGlow)
            }
        }
        // 2 dp outline on the disc edge.
        stroke.strokeWidth = dp(2f)
        stroke.color = outline
        canvas.drawCircle(r, r, r - dp(1f), stroke)
        val content = color(state.content)
        when (state) {
            BubbleState.Idle -> drawWave(canvas, LOW_WAVE, content, glow = true)
            BubbleState.Listening -> drawWave(canvas, HIGH_WAVE, content, glow = false)
            BubbleState.Processing -> drawSpinner(canvas, content)
            BubbleState.Ready -> drawIcon(canvas, check, content)
            BubbleState.Error -> drawIcon(canvas, exclamation, content)
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    /** 5 bars, 4 dp wide every 7 dp, centered. Neon bars glow (0 0 6 dp at 90 %). */
    private fun drawWave(canvas: Canvas, heights: FloatArray, color: Int, glow: Boolean) {
        val left = (width - dp(32f)) / 2f
        ink.color = color
        glowInk.color = ColorUtils.setAlphaComponent(color, 230)
        listOfNotNull(glowInk.takeIf { glow }, ink).forEach { paint ->
            heights.forEachIndexed { i, h ->
                val x = left + dp(7f * i)
                val y = (height - dp(h)) / 2f
                bar.set(x, y, x + dp(4f), y + dp(h))
                canvas.drawRoundRect(bar, dp(2f), dp(2f), paint)
            }
        }
    }

    /** 30 dp ring, 4 dp stroke: a faint track and a turning half, still if animations are off. */
    private fun drawSpinner(canvas: Canvas, color: Int) {
        val inset = (width - dp(30f)) / 2f + dp(2f)
        arc.set(inset, inset, width - inset, height - inset)
        stroke.strokeWidth = dp(4f)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = ColorUtils.setAlphaComponent(color, 46)
        canvas.drawOval(arc, stroke)
        val spins = ValueAnimator.areAnimatorsEnabled()
        val angle = if (spins) (SystemClock.uptimeMillis() % SPIN_MS) * 360f / SPIN_MS else 0f
        stroke.color = color
        canvas.drawArc(arc, angle - 90f, 180f, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        if (spins) postInvalidateOnAnimation()
    }

    /** 28 dp icon from a 24-unit Material path: the check, or the error mark. */
    private fun drawIcon(canvas: Canvas, icon: Path, color: Int) {
        val size = dp(28f)
        val scale = size / 24f
        ink.color = color
        glowInk.color = ColorUtils.setAlphaComponent(color, 230)
        canvas.withTranslation((width - size) / 2f, (height - size) / 2f) {
            scale(scale, scale)
            drawPath(icon, glowInk)
            drawPath(icon, ink)
        }
    }

    private companion object {
        const val SPIN_MS = 1_000L
    }
}

/**
 * Drawn behind the disc in a window that takes no touch: the neon glow of the
 * state and, when listening, the two halo rings (8 and 18 dp) that follow the voice.
 */
class BubbleBackdropView(context: Context) : View(context) {

    private val discRadius = dp(28f)

    var state: BubbleState = BubbleState.Idle
        set(value) {
            field = value
            invalidate()
        }

    /** Voice level, 0 to 1: the halo rings thicken with it. */
    var voiceLevel = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (state == BubbleState.Listening) invalidate()
        }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowFilters = HashMap<Float, BlurMaskFilter>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        allowBlur()
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val color = ContextCompat.getColor(context, state.glowColor)
        // OUTER: the glow stays outside the disc, which is 92 % opaque at rest.
        state.glow.forEach { layer ->
            glow.maskFilter = glowFilters.getOrPut(layer.blurDp) { BlurMaskFilter(blur(layer.blurDp), BlurMaskFilter.Blur.OUTER) }
            glow.color = ColorUtils.setAlphaComponent(color, (layer.alpha * 255).toInt())
            canvas.drawCircle(c, c, discRadius, glow)
        }
        if (state == BubbleState.Listening) {
            // Rings 8 and 18 dp from the disc, 70 % and 30 %; 2 dp thick in silence, up to 4 dp loud.
            halo.strokeWidth = dp(2f) + dp(2f) * voiceLevel
            listOf(8f to 179, 18f to 77).forEach { (gap, alpha) ->
                halo.color = ColorUtils.setAlphaComponent(color, alpha)
                canvas.drawCircle(c, c, discRadius + dp(gap), halo)
            }
        }
    }
}

/**
 * The « Masquer » target at the bottom while dragging: « Lâchez ici pour masquer »
 * above a 72 dp disc with a magenta outline and cross. Over it, the disc fills magenta.
 */
class HideZoneView(context: Context) : View(context) {

    var active = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Disc center from the bottom of the view: room for the glow below. */
    val discCenterFromBottom = dp(GLOW_DP + 36f)

    private val label = context.getString(R.string.bubble_hide_zone_label)
    private val magenta = ContextCompat.getColor(context, R.color.bubble_magenta)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowNear = BlurMaskFilter(blur(18f), BlurMaskFilter.Blur.OUTER)
    private val glowFar = BlurMaskFilter(blur(44f), BlurMaskFilter.Blur.OUTER)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = magenta
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
        color = ContextCompat.getColor(context, R.color.bubble_hide_zone_label)
    }

    // Dark pill behind the label: readable on a light or a dark app behind.
    private val labelPill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.bubble_hide_zone), 235)
    }
    private val labelBounds = Rect()

    init {
        contentDescription = context.getString(R.string.bubble_hide_zone)
        allowBlur()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height - discCenterFromBottom
        val radius = dp(36f)
        listOf(glowNear to 0.6f, glowFar to 0.3f).forEach { (filter, alpha) ->
            glow.maskFilter = filter
            glow.color = ColorUtils.setAlphaComponent(magenta, ((if (active) 1f else alpha) * 255).toInt())
            canvas.drawCircle(cx, cy, radius, glow)
        }
        fill.color = if (active) magenta else ContextCompat.getColor(context, R.color.bubble_hide_zone)
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius - dp(1f), outline)
        cross.color = if (active) ContextCompat.getColor(context, R.color.bubble_on_orange) else magenta
        val arm = dp(9f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross)
        val baseline = cy - radius - dp(18f)
        text.getTextBounds(label, 0, label.length, labelBounds)
        val padX = dp(12f)
        val padY = dp(6f)
        canvas.drawRoundRect(
            cx - labelBounds.width() / 2f - padX,
            baseline + labelBounds.top - padY,
            cx + labelBounds.width() / 2f + padX,
            baseline + labelBounds.bottom + padY,
            dp(16f),
            dp(16f),
            labelPill,
        )
        canvas.drawText(label, cx, baseline, text)
    }

    companion object {
        /** Room under the disc for its glow. */
        const val GLOW_DP = 32f
    }
}

/**
 * The hidden bubble in edge mode: a 20 × 56 dp half-pill against the screen
 * [side], orange outline and glow, 85 %. Its window is 48 dp wide: the touch
 * target. A tap shows the bubble again.
 */
class EdgeTabView(context: Context) : View(context) {

    var side: Side = Side.Right
        set(value) {
            field = value
            invalidate()
        }

    private val orange = ContextCompat.getColor(context, R.color.bubble_orange)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.bubble_disc) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = orange
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val near = BlurMaskFilter(blur(10f), BlurMaskFilter.Blur.OUTER)
    private val far = BlurMaskFilter(blur(22f), BlurMaskFilter.Blur.OUTER)
    private val tab = Path()

    init {
        isClickable = true
        alpha = 0.85f
        contentDescription = context.getString(R.string.bubble_edge)
        allowBlur()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val w = dp(20f)
        val h = dp(56f)
        val top = (height - h) / 2f
        val r = h / 2f
        // Rounded on the screen side only: wider than the view, the flat end stays off screen.
        val left = if (side == Side.Right) width - w else -r
        val right = if (side == Side.Right) width + r else w
        tab.rewind()
        tab.addRoundRect(RectF(left, top, right, top + h), r, r, Path.Direction.CW)
        listOf(near to 0.7f, far to 0.35f).forEach { (filter, a) ->
            glow.maskFilter = filter
            glow.color = ColorUtils.setAlphaComponent(orange, (a * 255).toInt())
            canvas.drawPath(tab, glow)
        }
        canvas.drawPath(tab, fill)
        canvas.drawPath(tab, outline)
    }
}
