package tech.dmconsulting.dixi.bubble

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
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import tech.dmconsulting.dixi.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

internal fun View.dp(value: Float) = value * resources.displayMetrics.density

/** CSS blur radius (2 sigma) to the Android BlurMaskFilter radius (sigma = 0.57735 r + 0.5). */
internal fun View.blur(cssDp: Float) = (dp(cssDp) / 2f - 0.5f).coerceAtLeast(0.5f) / 0.57735f

/** BlurMaskFilter needs a software layer before Android 9. */
internal fun View.allowBlur() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
}

/** Wave bar heights in dp, 4 dp wide every 7 dp (neon charter § Bulle), drawn for a 56 dp disc. */
private val LOW_WAVE = floatArrayOf(6f, 12f, 18f, 12f, 6f)
private const val CHECK_PATH = "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"

/** Material « priority_high »: an exclamation mark. */
private const val ERROR_PATH = "M10 3h4v12h-4zM12 17a2 2 0 1 1 0 4a2 2 0 1 1 0-4z"

/** Material « mic », as res/drawable/ic_mic. */
private const val MIC_PATH = "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zM17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-0.49 6-3.39 6-6.92h-2z"

/** The content is drawn for the charter's 56 dp disc, then scaled to the 44 dp one. */
private const val CONTENT_SCALE = BubbleState.DISC_DP / 56f

/** Voice wave, mockups DMC-764 § 2: rings up to 14 dp past the disc, smoothed over 80 ms. */
const val WAVE_MAX_DP = 14f
const val WAVE_SMOOTH_MS = 80f
const val WAVE_DELAY_MS = 120L
const val WAVE_PULSE_MS = 1_200L

/** Gap between the two rings, so both show even when the voice holds steady. */
const val WAVE_RING_GAP_DP = 4f

/** Slow breath of the wave in silence: listening goes on. */
const val WAVE_PULSE_DP = 3f

/**
 * Ring distance past the disc edge, in dp, for a voice [level] from 0 to 1. Square root:
 * speech is quiet in linear amplitude, a normal voice must move the wave.
 */
fun waveOffsetDp(level: Float): Float = WAVE_MAX_DP * sqrt(level.coerceIn(0f, 1f))

/** One step of the exponential smoothing from [current] toward [target], [dtMs] later. */
fun smoothWave(current: Float, target: Float, dtMs: Long): Float = current + (target - current) * (1f - exp(-dtMs.coerceAtLeast(0) / WAVE_SMOOTH_MS))

/** Extra ring distance in silence, [sinceMs] after the listening started: 0 to [WAVE_PULSE_DP] and back, fading as the voice rises. */
fun silencePulseDp(sinceMs: Long, offsetDp: Float): Float {
    val breath = 0.5f - 0.5f * cos(2.0 * PI * (sinceMs % WAVE_PULSE_MS) / WAVE_PULSE_MS).toFloat()
    return WAVE_PULSE_DP * breath * (1f - offsetDp / WAVE_PULSE_DP).coerceIn(0f, 1f)
}

/**
 * The 44 dp disc and its content for each state, in a 48 dp window that takes the touches.
 * Tucked at the edge ([dockedSide]), the disc is centered on that window edge: half of it
 * shows against the screen edge, at 60 %, while the whole window still takes the touches.
 */
class BubbleView(context: Context) : View(context) {

    var state: BubbleState = BubbleState.Idle
        set(value) {
            field = value
            if (value != BubbleState.Listening) listeningElapsed = null
            updateLook()
        }

    /** Side the bubble is tucked at, or null when it shows whole. */
    var dockedSide: Side? = null
        set(value) {
            field = value
            updateLook()
        }

    /** « 0:12 » while listening: TalkBack says it, nothing on screen (mockups DMC-764 § 2). */
    var listeningElapsed: String? = null
        set(value) {
            if (field == value) return
            field = value
            updateLook()
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
        maskFilter = BlurMaskFilter(blur(8f), BlurMaskFilter.Blur.NORMAL)
    }
    private val bar = RectF()
    private val arc = RectF()
    private val disc = Path()
    private val check = PathParser.createPathFromPathData(CHECK_PATH)
    private val exclamation = PathParser.createPathFromPathData(ERROR_PATH)
    private val mic = PathParser.createPathFromPathData(MIC_PATH)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        allowBlur()
        updateLook()
    }

    private fun updateLook() {
        contentDescription = when {
            dockedSide != null -> context.getString(R.string.bubble_docked)
            listeningElapsed != null -> context.getString(R.string.bubble_listening_elapsed, listeningElapsed)
            else -> context.getString(state.description)
        }
        alpha = if (dockedSide != null) BubbleState.DOCKED_OPACITY else state.opacity
        invalidate()
    }

    // Taps come from BubbleOverlay's touch listener, TalkBack from here.
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val r = dp(BubbleState.DISC_DP / 2f)
        val cx = when (dockedSide) {
            Side.Left -> 0f
            Side.Right -> width.toFloat()
            null -> width / 2f
        }
        val cy = height / 2f
        fill.color = color(state.fill)
        canvas.drawCircle(cx, cy, r, fill)
        val outline = color(state.outline)
        if (state.fill != state.outline) {
            // Inner glow (inset 0 0 8 dp at 25 %), kept inside the disc.
            disc.rewind()
            disc.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.withClip(disc) {
                innerGlow.strokeWidth = dp(3f)
                innerGlow.color = ColorUtils.setAlphaComponent(color(state.glowColor), 64)
                drawCircle(cx, cy, r, innerGlow)
            }
        }
        // 2 dp outline on the disc edge.
        stroke.strokeWidth = dp(2f)
        stroke.color = outline
        canvas.drawCircle(cx, cy, r - dp(1f), stroke)
        val content = color(state.content)
        canvas.withScale(CONTENT_SCALE, CONTENT_SCALE, cx, cy) {
            when (state) {
                BubbleState.Idle -> drawWave(this, cx, cy, content)
                BubbleState.Listening -> drawIcon(this, cx, cy, mic, content, glow = false)
                BubbleState.Processing -> drawSpinner(this, cx, cy, content)
                BubbleState.Ready -> drawIcon(this, cx, cy, check, content, glow = true)
                BubbleState.Error -> drawIcon(this, cx, cy, exclamation, content, glow = true)
            }
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    /** 5 bars, 4 dp wide every 7 dp, centered. Neon bars glow (0 0 6 dp at 90 %). */
    private fun drawWave(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val left = cx - dp(16f)
        ink.color = color
        glowInk.color = ColorUtils.setAlphaComponent(color, 230)
        listOf(glowInk, ink).forEach { paint ->
            LOW_WAVE.forEachIndexed { i, h ->
                val x = left + dp(7f * i)
                val y = cy - dp(h) / 2f
                bar.set(x, y, x + dp(4f), y + dp(h))
                canvas.drawRoundRect(bar, dp(2f), dp(2f), paint)
            }
        }
    }

    /** 30 dp ring, 4 dp stroke: a faint track and a turning half, still if animations are off. */
    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val radius = dp(13f)
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
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

    /** 28 dp icon from a 24-unit Material path: the microphone, the check or the error mark. */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, icon: Path, color: Int, glow: Boolean) {
        val size = dp(28f)
        val scale = size / 24f
        ink.color = color
        glowInk.color = ColorUtils.setAlphaComponent(color, 230)
        canvas.withTranslation(cx - size / 2f, cy - size / 2f) {
            scale(scale, scale)
            if (glow) drawPath(icon, glowInk)
            drawPath(icon, ink)
        }
    }

    private companion object {
        const val SPIN_MS = 1_000L
    }
}

/**
 * Drawn behind the disc in a window that takes no touch: the neon glow of the state and,
 * when listening, the voice wave (mockups DMC-764 § 2): two rings whose distance follows
 * the voice, the second 120 ms behind at 40 %, a slow breath in silence, magenta near
 * the time limit. With animations off, one still ring instead.
 */
class BubbleBackdropView(context: Context) : View(context) {

    private val discRadius = dp(BubbleState.DISC_DP / 2f)

    var state: BubbleState = BubbleState.Idle
        set(value) {
            if (value == BubbleState.Listening && field != BubbleState.Listening) {
                listeningSinceMs = SystemClock.uptimeMillis()
                smoothed = 0f
                lastFrameMs = 0L
                trail.clear()
            }
            if (value != BubbleState.Listening) nearLimit = false
            field = value
            invalidate()
        }

    /** Voice level, 0 to 1: the target of the wave. */
    var voiceLevel = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Less than 10 s before the time limit: the wave turns magenta. */
    var nearLimit = false
        set(value) {
            field = value
            invalidate()
        }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val glowFilters = HashMap<Float, BlurMaskFilter>()
    private var listeningSinceMs = 0L
    private var lastFrameMs = 0L
    private var smoothed = 0f

    /** Recent ring distances (time, dp): the second ring reads the one from 120 ms ago. */
    private val trail = ArrayDeque<Pair<Long, Float>>()

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
        if (state == BubbleState.Listening) drawWave(canvas, c)
    }

    private fun drawWave(canvas: Canvas, c: Float) {
        val neon = ContextCompat.getColor(context, if (nearLimit) R.color.bubble_magenta else R.color.bubble_orange)
        val edge = discRadius + dp(1f)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            ring.color = ColorUtils.setAlphaComponent(neon, 217)
            canvas.drawCircle(c, c, edge + dp(6f), ring)
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceAtMost(100L)
        lastFrameMs = now
        smoothed = smoothWave(smoothed, waveOffsetDp(voiceLevel), dt)
        val offset = smoothed + silencePulseDp(now - listeningSinceMs, smoothed)
        trail.addLast(now to offset)
        while (trail.size > 1 && trail[1].first <= now - WAVE_DELAY_MS) trail.removeFirst()
        val behind = trail.first().second
        ring.color = ColorUtils.setAlphaComponent(neon, 217)
        canvas.drawCircle(c, c, edge + dp(offset), ring)
        ring.color = ColorUtils.setAlphaComponent(neon, 102)
        canvas.drawCircle(c, c, edge + dp(behind + WAVE_RING_GAP_DP), ring)
        postInvalidateOnAnimation()
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
