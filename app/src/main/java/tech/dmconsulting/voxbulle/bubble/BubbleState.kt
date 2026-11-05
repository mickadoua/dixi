package tech.dmconsulting.voxbulle.bubble

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R

/**
 * Bubble states from the neon charter, § Bulle (DMC-213). States also differ by
 * shape, not by color only: low wave, filled disc with high wave and halo, ring, check.
 * [glow]: the two glow layers (blur in dp, alpha 0 to 1), drawn behind the disc.
 */
enum class BubbleState(
    @ColorRes val fill: Int,
    @ColorRes val outline: Int,
    @ColorRes val content: Int,
    val glow: List<Glow>,
    @StringRes val description: Int,
) {
    Idle(
        R.color.bubble_disc, R.color.bubble_orange, R.color.bubble_orange,
        listOf(Glow(10f, 0.7f), Glow(26f, 0.4f)), R.string.bubble_idle,
    ),
    Listening(
        R.color.bubble_orange, R.color.bubble_orange, R.color.bubble_on_orange,
        listOf(Glow(14f, 0.9f), Glow(30f, 0.55f)), R.string.bubble_listening,
    ),
    Processing(
        R.color.bubble_disc, R.color.bubble_processing_outline, R.color.bubble_cyan,
        listOf(Glow(10f, 0.35f)), R.string.bubble_processing,
    ),
    Ready(
        R.color.bubble_disc, R.color.bubble_cyan, R.color.bubble_cyan,
        listOf(Glow(10f, 0.8f), Glow(28f, 0.45f)), R.string.bubble_ready,
    ),
    ;

    /** 92 % at rest, 100 % in use. */
    val opacity: Float get() = if (this == Idle) 0.92f else 1f

    /** The glow color: the outline's neon, or the fill's when the disc is filled. */
    @get:ColorRes
    val glowColor: Int get() = if (this == Processing) R.color.bubble_cyan else outline

    data class Glow(val blurDp: Float, val alpha: Float)

    companion object {
        /** Room around the disc in the backdrop window, for the glow and the halo. */
        const val GLOW_MARGIN_DP = 32f
    }
}
