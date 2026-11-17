package tech.dmconsulting.dixi.bubble

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import tech.dmconsulting.dixi.R

/**
 * Bubble states from the neon charter, § Bulle (DMC-213). States also differ by
 * shape, not by color only: low wave, filled disc with high wave and halo, ring, check.
 * [glow]: the two glow layers (blur in dp, alpha 0 to 1), drawn behind the disc, scaled
 * to the 44 dp disc (DMC-768: blurs 8 and 20 dp instead of 10 and 26).
 * [Error] is not in the charter table: magenta outline and an exclamation
 * mark, so it differs by shape too.
 */
enum class BubbleState(
    @ColorRes val fill: Int,
    @ColorRes val outline: Int,
    @ColorRes val content: Int,
    val glow: List<Glow>,
    @StringRes val description: Int,
) {
    Idle(
        R.color.bubble_disc,
        R.color.bubble_orange,
        R.color.bubble_orange,
        listOf(Glow(8f, 0.7f), Glow(20f, 0.4f)),
        R.string.bubble_idle,
    ),
    Listening(
        R.color.bubble_orange,
        R.color.bubble_orange,
        R.color.bubble_on_orange,
        listOf(Glow(11f, 0.9f), Glow(24f, 0.55f)),
        R.string.bubble_listening,
    ),
    Processing(
        R.color.bubble_disc,
        R.color.bubble_processing_outline,
        R.color.bubble_cyan,
        listOf(Glow(8f, 0.35f)),
        R.string.bubble_processing,
    ),
    Ready(
        R.color.bubble_disc,
        R.color.bubble_cyan,
        R.color.bubble_cyan,
        listOf(Glow(8f, 0.8f), Glow(22f, 0.45f)),
        R.string.bubble_ready,
    ),
    Error(
        R.color.bubble_disc,
        R.color.bubble_magenta,
        R.color.bubble_magenta,
        listOf(Glow(8f, 0.7f), Glow(20f, 0.4f)),
        R.string.bubble_error,
    ),
    ;

    /** 92 % at rest, 100 % in use. */
    val opacity: Float get() = if (this == Idle) 0.92f else 1f

    /** The glow color: the outline's neon, or the fill's when the disc is filled. */
    @get:ColorRes
    val glowColor: Int get() = if (this == Processing) R.color.bubble_cyan else outline

    data class Glow(val blurDp: Float, val alpha: Float)

    companion object {
        /** Room around the disc in the backdrop window, for the glow and the voice wave (+14 dp). */
        const val GLOW_MARGIN_DP = 32f

        /** Disc diameter (DMC-768: 56 → 44 dp). */
        const val DISC_DP = 44f

        /** Bubble window: the touch target, 48 dp at least (Android accessibility). */
        const val TOUCH_DP = 48f

        /** Opacity of the half-bubble tucked at the screen edge. */
        const val DOCKED_OPACITY = 0.6f
    }
}
