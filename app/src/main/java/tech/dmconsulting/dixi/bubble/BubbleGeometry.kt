package tech.dmconsulting.dixi.bubble

import kotlin.math.abs
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
    fun nearestSide(x: Int): Side = if (x + bubbleSize / 2f <= screenWidth / 2f) Side.Left else Side.Right

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

    /** Screen edges the bubble can tuck behind: inside the bars and the cutout. */
    val leftEdge: Int get() = insets.left
    val rightEdge: Int get() = screenWidth - insets.right

    /** Dragging lets the bubble go half past an edge, to tuck it away. */
    fun clampDragX(x: Int): Int = x.coerceIn(leftEdge - bubbleSize / 2, maxOf(leftEdge, rightEdge - bubbleSize / 2))

    /**
     * Side to tuck the bubble at when dropped with its left edge at [x]: its center within
     * [dockDistance] of an edge (mockups DMC-764 § 1, « lâchée à moins de 16 dp du bord »).
     * At rest the center is margin + radius from the edge (30 dp): moving it up or down the
     * side never tucks it.
     */
    fun dockSide(x: Int, dockDistance: Int): Side? {
        val center = x + bubbleSize / 2
        return when {
            center - leftEdge <= dockDistance -> Side.Left
            rightEdge - center <= dockDistance -> Side.Right
            else -> null
        }
    }

    /** Left edge of the bubble window whose center sits on the [side] edge: half of it hidden. */
    fun halfHiddenX(side: Side): Int = if (side == Side.Left) leftEdge - bubbleSize / 2 else rightEdge - bubbleSize / 2

    /** Left edge of the tucked bubble window, fully on screen against the [side] edge: it takes the touches. */
    fun dockedX(side: Side): Int = if (side == Side.Left) leftEdge else rightEdge - bubbleSize
}

/**
 * Side of a fling that tucks the bubble away: fast enough ([minVelocity], px/s) and
 * mostly sideways. Null for a slow or vertical gesture.
 */
fun flingSide(velocityX: Float, velocityY: Float, minVelocity: Float): Side? = when {
    abs(velocityX) < minVelocity || abs(velocityX) < abs(velocityY) * 1.5f -> null
    velocityX < 0 -> Side.Left
    else -> Side.Right
}
