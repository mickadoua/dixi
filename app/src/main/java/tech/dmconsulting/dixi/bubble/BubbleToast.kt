package tech.dmconsulting.dixi.bubble

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import tech.dmconsulting.dixi.R

/** Room around the toast in its window, for the glow. */
const val TOAST_GLOW_DP = 16f

/** « 0:12 »: minutes and seconds. */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The short message next to the bubble (mockup 01): « Texte copié » with a cyan border,
 * or the cause of an error with a magenta one (« Clé refusée », DMC-768). 40 dp high,
 * 1.5 dp border and its glow.
 */
class BubbleToastView(context: Context) : TextView(context) {

    private val g = dp(TOAST_GLOW_DP)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.toast_surface) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur(14f), BlurMaskFilter.Blur.OUTER) }
    private val rect = RectF()

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
        setTextColor(ContextCompat.getColor(context, R.color.panel_text))
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 2
        maxWidth = (dp(240f) + 2 * g).toInt()
        val pad = (g + dp(16f)).toInt()
        setPadding(pad, g.toInt(), pad, g.toInt())
        minHeight = (dp(40f) + 2 * g).toInt()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        allowBlur()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        show(R.string.toast_copied, error = false)
    }

    fun show(@StringRes message: Int, error: Boolean) {
        setText(message)
        val neon = ContextCompat.getColor(context, if (error) R.color.bubble_magenta else R.color.bubble_cyan)
        border.color = neon
        glow.color = ColorUtils.setAlphaComponent(neon, 115)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(g, g, width - g, height - g)
        val r = minOf(rect.height() / 2f, dp(20f))
        canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawRoundRect(rect, r, r, fill)
        rect.inset(dp(0.75f), dp(0.75f))
        canvas.drawRoundRect(rect, r, r, border)
        super.onDraw(canvas)
    }
}
