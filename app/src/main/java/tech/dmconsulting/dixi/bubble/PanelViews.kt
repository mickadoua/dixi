package tech.dmconsulting.dixi.bubble

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.widget.TextViewCompat
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.dictation.DictationPanel

/** Room around the panel card and the toast in their windows, for the glow. */
const val PANEL_GLOW_DP = 24f
const val TOAST_GLOW_DP = 16f

/** The word around [offset] in [text] (letters, digits, apostrophes, hyphens), or null on a space. */
fun wordAt(text: CharSequence, offset: Int): IntRange? {
    fun inWord(i: Int) = i in text.indices && (text[i].isLetterOrDigit() || text[i] == '\'' || text[i] == '’' || text[i] == '-')
    // A tap just after the last letter still means that word.
    val at = when {
        inWord(offset) -> offset
        inWord(offset - 1) -> offset - 1
        else -> return null
    }
    var start = at
    while (inWord(start - 1)) start--
    var end = at
    while (inWord(end + 1)) end++
    return start..end
}

/** « 0:12 »: minutes and seconds. */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun Context.font(id: Int) = ResourcesCompat.getFont(this, id)

private fun TextView.style(sizeSp: Float, fontId: Int, colorId: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    typeface = context.font(fontId)
    setTextColor(ContextCompat.getColor(context, colorId))
    includeFontPadding = false
}

/**
 * The panel card (mockups 09 and 10): #0A0A0E, 24 dp corners, 1.5 dp neon
 * border and its glow, in a window [PANEL_GLOW_DP] larger on each side.
 */
@SuppressLint("ViewConstructor")
class PanelView(
    context: Context,
    private val onCancel: () -> Unit,
    private val onFinish: () -> Unit,
    private val onRedo: () -> Unit,
    private val onValidate: (String) -> Unit,
) : LinearLayout(context) {

    private val g = dp(PANEL_GLOW_DP)
    private val card = RectF()
    private val cardPath = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.bubble_disc) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val near = BlurMaskFilter(blur(14f), BlurMaskFilter.Blur.OUTER)
    private val far = BlurMaskFilter(blur(40f), BlurMaskFilter.Blur.OUTER)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(blur(14f), BlurMaskFilter.Blur.NORMAL)
    }

    @ColorInt private var accent = ContextCompat.getColor(context, R.color.bubble_orange)

    private val label = TextView(context).apply {
        style(16f, R.font.inter_semibold, R.color.panel_orange_text)
        isAllCaps = true
        letterSpacing = 0.094f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val wave = LiveWaveView(context)
    private val elapsed = TextView(context).apply {
        style(14f, R.font.inter_regular, R.color.panel_muted)
        gravity = Gravity.CENTER
    }

    /** Live text goes here once a live source exists (DMC-233); empty in v1, same height. */
    private val live = TextView(context).apply {
        style(18f, R.font.inter_regular, R.color.panel_text)
        TextViewCompat.setLineHeight(this, dp(28f).toInt())
        minLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val suggestion = CorrectableText(context)
    private val correctHint = TextView(context).apply {
        style(14f, R.font.inter_regular, R.color.panel_muted)
        TextViewCompat.setLineHeight(this, dp(20f).toInt())
        setText(R.string.panel_correct_hint)
    }
    private val secondary = PillButton(context, filled = false)
    private val primary = PillButton(context, filled = true)
    private val buttons = LinearLayout(context).apply {
        clipChildren = false
        addView(secondary, LayoutParams(0, dp(56f).toInt(), 1f))
        addView(primary, LayoutParams(0, dp(56f).toInt(), 2f).apply { marginStart = dp(12f).toInt() })
    }
    private val validateHint = TextView(context).apply {
        style(14f, R.font.inter_regular, R.color.panel_muted)
        TextViewCompat.setLineHeight(this, dp(20f).toInt())
        gravity = Gravity.CENTER
        setText(R.string.panel_validate_hint)
    }

    var panel: DictationPanel = DictationPanel.None
        private set

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        allowBlur()
        val pad = (g + dp(16f)).toInt()
        setPadding(pad, pad, pad, pad)
        addView(label)
        addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(56f).toInt()).apply { topMargin = dp(8f).toInt() })
        addView(elapsed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(live, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        addView(suggestion, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        addView(correctHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f).toInt() })
        addView(validateHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f).toInt() })
        secondary.setOnClickListener { if (panel is DictationPanel.Suggestion) onRedo() else onCancel() }
        primary.setOnClickListener {
            if (panel is DictationPanel.Suggestion) onValidate(suggestion.text.toString()) else onFinish()
        }
    }

    fun show(newPanel: DictationPanel, elapsedMs: Long = 0) {
        val previous = panel
        panel = newPanel
        val isSuggestion = newPanel is DictationPanel.Suggestion
        accent = ContextCompat.getColor(context, if (isSuggestion) R.color.bubble_cyan else R.color.bubble_orange)
        label.setTextColor(ContextCompat.getColor(context, if (isSuggestion) R.color.bubble_cyan else R.color.panel_orange_text))
        // Listening: 16 sp title (DMC-234). Suggestion: 12 sp label (mockup 10).
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isSuggestion) 12f else 16f)
        label.letterSpacing = if (isSuggestion) 0.125f else 0.094f
        label.setText(
            when (newPanel) {
                is DictationPanel.Suggestion -> R.string.panel_suggestion
                DictationPanel.Processing -> R.string.panel_processing
                else -> R.string.panel_listening
            },
        )
        val listening = newPanel is DictationPanel.Listening
        listOf(wave, live).forEach { it.visibility = if (isSuggestion) GONE else VISIBLE }
        elapsed.visibility = if (listening) {
            VISIBLE
        } else if (isSuggestion) {
            GONE
        } else {
            INVISIBLE
        }
        wave.spinning = newPanel == DictationPanel.Processing
        if (listening && previous !is DictationPanel.Listening) {
            wave.reset()
            setElapsed(elapsedMs)
        }
        listOf(suggestion, correctHint, validateHint).forEach { it.visibility = if (isSuggestion) VISIBLE else GONE }
        // While the text is processed there is nothing to choose.
        buttons.visibility = if (newPanel == DictationPanel.Processing) INVISIBLE else VISIBLE
        secondary.setText(if (isSuggestion) R.string.panel_redo else R.string.panel_cancel)
        primary.setText(if (isSuggestion) R.string.panel_validate else R.string.panel_finish)
        primary.check = isSuggestion
        if (newPanel is DictationPanel.Suggestion && previous != newPanel) suggestion.setSuggestion(newPanel.text)
        invalidate()
    }

    /** Screen centers of the buttons and the text, for the emulator proof log. */
    fun centers(): String {
        fun View.center(): String {
            val xy = IntArray(2)
            getLocationOnScreen(xy)
            return "${xy[0] + width / 2} ${xy[1] + height / 2}"
        }
        return "secondary=${secondary.center()} primary=${primary.center()} text=${suggestion.center()}"
    }

    fun setElapsed(ms: Long) {
        val text = formatElapsed(ms)
        if (elapsed.text == text) return
        elapsed.text = text
        elapsed.contentDescription = context.getString(R.string.panel_elapsed_description, text)
    }

    override fun onDraw(canvas: Canvas) {
        card.set(g, g, width - g, height - g)
        val r = dp(24f)
        cardPath.rewind()
        cardPath.addRoundRect(card, r, r, Path.Direction.CW)
        listOf(far to 0.25f, near to (if (panel is DictationPanel.Suggestion) 0.5f else 0.55f)).forEach { (filter, a) ->
            glow.maskFilter = filter
            glow.color = ColorUtils.setAlphaComponent(accent, (a * 255).toInt())
            canvas.drawPath(cardPath, glow)
        }
        canvas.drawPath(cardPath, fill)
        if (panel !is DictationPanel.Suggestion) {
            // Inset glow (inset 0 0 14 px at 12 %), kept inside the card.
            canvas.withClip(cardPath) {
                inner.strokeWidth = dp(6f)
                inner.color = ColorUtils.setAlphaComponent(accent, 31)
                drawPath(cardPath, inner)
            }
        }
        border.color = accent
        canvas.drawRoundRect(
            card.left + dp(0.75f),
            card.top + dp(0.75f),
            card.right - dp(0.75f),
            card.bottom - dp(0.75f),
            r,
            r,
            border,
        )
    }
}

/** 56 dp pill: filled orange with its glow, or a 1.5 dp grey outline. */
@SuppressLint("ViewConstructor")
class PillButton(context: Context, private val filled: Boolean) : TextView(context) {

    /** « Valider ✓ »: the 22 dp check after the text. */
    var check = false
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val orange = ContextCompat.getColor(context, R.color.bubble_orange)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(orange, 140)
        maskFilter = BlurMaskFilter(blur(14f), BlurMaskFilter.Blur.OUTER)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.bubble_on_orange)
    }
    private val rect = RectF()
    private val tickPath = Path()

    init {
        style(16f, R.font.inter_semibold, if (filled) R.color.bubble_on_orange else R.color.panel_text)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(48f).toInt()
        allowBlur()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room for the check on the right, so the text and the check stay centered together.
        val extra = if (check) dp(30f).toInt() else 0
        setPadding(0, 0, extra, 0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        if (filled) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, glow)
            shape.style = Paint.Style.FILL
            shape.color = orange
            canvas.drawRoundRect(rect, r, r, shape)
        } else {
            val half = dp(0.75f)
            rect.set(half, half, width - half, height - half)
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = dp(1.5f)
            shape.color = ContextCompat.getColor(context, R.color.panel_line)
            canvas.drawRoundRect(rect, r, r, shape)
        }
        super.onDraw(canvas)
        if (check) {
            // Mockup path « M6 15l5.5 5.5L22 9 » in a 28-unit box drawn at 22 dp.
            val textWidth = layout?.getLineWidth(0) ?: 0f
            val s = dp(22f) / 28f
            val left = (width - dp(30f) - textWidth) / 2f + textWidth + dp(8f)
            val top = (height - dp(22f)) / 2f
            tickPath.rewind()
            tickPath.moveTo(left + 6 * s, top + 15 * s)
            tickPath.lineTo(left + 11.5f * s, top + 20.5f * s)
            tickPath.lineTo(left + 22 * s, top + 9 * s)
            tick.strokeWidth = 3.5f * s
            canvas.drawPath(tickPath, tick)
        }
    }
}

/**
 * The final text, editable. A tap on a word selects it and marks it with the
 * 2 dp cyan line of mockup 10; typing replaces it.
 */
class CorrectableText(context: Context) : EditText(context) {

    private val cyan = ContextCompat.getColor(context, R.color.bubble_cyan)
    private val mark = Paint().apply { color = cyan }
    private var marked: IntRange? = null

    init {
        style(18f, R.font.inter_regular, R.color.panel_text)
        TextViewCompat.setLineHeight(this, dp(28f).toInt())
        background = null
        setPadding(0, 0, 0, dp(4f).toInt())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = false
        highlightColor = ColorUtils.setAlphaComponent(cyan, 64)
    }

    fun setSuggestion(text: String) {
        marked = null
        setText(text)
    }

    /** Debug proof only: marks the word at [offset] without the keyboard. */
    fun markWordAt(offset: Int) {
        marked = wordAt(text, offset)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val range = wordAt(text, getOffsetForPosition(event.x, event.y))
            marked = range
            if (range != null) setSelection(range.first, range.last + 1)
            requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
            invalidate()
        }
        return handled
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // Once the user types, the marked word is gone.
        if (lengthBefore != 0 || lengthAfter != 0) marked = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = marked ?: return
        val layout = layout ?: return
        if (range.last >= text.length) return
        val end = range.last + 1
        for (line in layout.getLineForOffset(range.first)..layout.getLineForOffset(end)) {
            val from = maxOf(range.first, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            if (from >= to) continue
            val x1 = layout.getPrimaryHorizontal(from) + totalPaddingLeft
            val x2 = (if (to == layout.getLineEnd(line) && to != end) layout.getLineRight(line) else layout.getPrimaryHorizontal(to)) + totalPaddingLeft
            val y = layout.getLineBaseline(line) + totalPaddingTop + dp(5f)
            canvas.drawRect(minOf(x1, x2), y, maxOf(x1, x2), y + dp(2f), mark)
        }
    }
}

/**
 * The panel wave: 21 bars 4 dp wide every 8 dp, orange with a glow. The newest
 * level enters on the right; in silence the bars stay flat and thin (DMC-234).
 * [spinning]: the 30 dp loading ring instead, still if animations are off.
 */
class LiveWaveView(context: Context) : View(context) {

    var spinning = false
        set(value) {
            field = value
            invalidate()
        }

    private val levels = FloatArray(BARS)
    private val orange = ContextCompat.getColor(context, R.color.bubble_orange)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(orange, 230)
        maskFilter = BlurMaskFilter(blur(6f), BlurMaskFilter.Blur.NORMAL)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    init {
        contentDescription = context.getString(R.string.panel_wave_description)
        allowBlur()
    }

    fun reset() {
        levels.fill(0f)
        invalidate()
    }

    /** [level]: 0 to 1. */
    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (spinning) {
            drawSpinner(canvas)
            return
        }
        val w = dp(4f)
        val step = dp(8f)
        val left = (width - (BARS - 1) * step - w) / 2f
        val cy = height / 2f
        val min = dp(4f)
        val max = dp(48f)
        for (i in 0 until BARS) {
            // Speech rarely reaches full scale: the square root lifts the quiet half.
            val h = min + (max - min) * kotlin.math.sqrt(levels[i])
            val x = left + i * step
            rect.set(x, cy - h / 2f, x + w, cy + h / 2f)
            canvas.drawRoundRect(rect, w / 2f, w / 2f, glow)
            canvas.drawRoundRect(rect, w / 2f, w / 2f, bar)
        }
    }

    private fun drawSpinner(canvas: Canvas) {
        val size = dp(30f)
        val stroke = dp(4f)
        rect.set((width - size) / 2f + stroke / 2f, (height - size) / 2f + stroke / 2f, (width + size) / 2f - stroke / 2f, (height + size) / 2f - stroke / 2f)
        ring.strokeWidth = stroke
        ring.color = ColorUtils.setAlphaComponent(orange, 46)
        canvas.drawOval(rect, ring)
        val spins = ValueAnimator.areAnimatorsEnabled()
        val angle = if (spins) (SystemClock.uptimeMillis() % SPIN_MS) * 360f / SPIN_MS else 0f
        ring.color = orange
        canvas.drawArc(rect, angle - 90f, 180f, false, ring)
        if (spins) postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 21
        const val SPIN_MS = 1_000L
    }
}

/** « Texte copié » (mockup 01): 40 dp, 1.5 dp cyan border and glow, near the bubble. */
class CopiedToastView(context: Context) : TextView(context) {

    private val g = dp(TOAST_GLOW_DP)
    private val cyan = ContextCompat.getColor(context, R.color.bubble_cyan)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.toast_surface) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = cyan
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(cyan, 115)
        maskFilter = BlurMaskFilter(blur(14f), BlurMaskFilter.Blur.OUTER)
    }
    private val rect = RectF()

    init {
        style(16f, R.font.inter_medium, R.color.panel_text)
        setText(R.string.toast_copied)
        gravity = Gravity.CENTER
        val pad = (g + dp(16f)).toInt()
        setPadding(pad, g.toInt(), pad, g.toInt())
        height = (dp(40f) + 2 * g).toInt()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        allowBlur()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(g, g, width - g, height - g)
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, glow)
        canvas.drawRoundRect(rect, r, r, fill)
        rect.inset(dp(0.75f), dp(0.75f))
        canvas.drawRoundRect(rect, r, r, border)
        super.onDraw(canvas)
    }
}
