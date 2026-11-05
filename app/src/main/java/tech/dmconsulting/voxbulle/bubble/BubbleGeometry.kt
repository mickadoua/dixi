package tech.dmconsulting.voxbulle.bubble

import kotlin.math.roundToInt

enum class Side { Left, Right }

/** Saved bubble place: screen side and vertical place, 0 = top, 1 = bottom. */
data class BubblePosition(val side: Side, val yFraction: Float) {
    companion object {
        val Default = BubblePosition(Side.Right, 0.4f)
    }
}

/**
 * Bubble placement in screen pixels. [insets] are the system bars and cutout
 * (left, top, right, bottom): the bubble never goes under them.
 */
class BubbleGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    private val bubbleSize: Int,
    private val margin: Int,
    val insets: Insets = Insets(0, 0, 0, 0),
) {
    data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    val minX: Int get() = insets.left + margin
    val maxX: Int get() = maxOf(minX, screenWidth - insets.right - margin - bubbleSize)
    val minY: Int get() = insets.top + margin
    val maxY: Int get() = maxOf(minY, screenHeight - insets.bottom - margin - bubbleSize)

    /** Nearest side for a bubble whose left edge is at [x]. */
    fun nearestSide(x: Int): Side =
        if (x + bubbleSize / 2f <= screenWidth / 2f) Side.Left else Side.Right

    fun xFor(side: Side): Int = if (side == Side.Left) minX else maxX

    fun clampY(y: Int): Int = y.coerceIn(minY, maxY)

    /** Where the bubble lands when dropped at ([x], [y]). */
    fun snap(x: Int, y: Int): BubblePosition {
        val range = maxY - minY
        val fraction = if (range == 0) 0f else (clampY(y) - minY).toFloat() / range
        return BubblePosition(nearestSide(x), fraction)
    }

    /** Top-left pixel of a saved [position] on this screen. */
    fun place(position: BubblePosition): Pair<Int, Int> {
        val y = minY + (position.yFraction.coerceIn(0f, 1f) * (maxY - minY)).roundToInt()
        return xFor(position.side) to y
    }

    /** True when the bubble center at ([centerX], [centerY]) is over the hide target. */
    fun isOverHideTarget(centerX: Float, centerY: Float, targetX: Float, targetY: Float, radius: Float): Boolean {
        val dx = centerX - targetX
        val dy = centerY - targetY
        return dx * dx + dy * dy <= radius * radius
    }

    /**
     * Top of the panel card for a bubble at [bubbleY]: just above the bubble
     * (mockup 09), or just below when there is no room above. [gap] between them.
     */
    fun panelTop(bubbleY: Int, panelHeight: Int, gap: Int): Int {
        val above = bubbleY - gap - panelHeight
        if (above >= minY) return above
        val below = bubbleY + bubbleSize + gap
        return below.coerceAtMost(maxOf(minY, screenHeight - insets.bottom - margin - panelHeight))
    }
}
