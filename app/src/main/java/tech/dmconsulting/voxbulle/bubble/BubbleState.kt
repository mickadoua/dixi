package tech.dmconsulting.voxbulle.bubble

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R

/**
 * Bubble states from the Voxbulle charter, section 6. Colors are charter roles,
 * light and dark (values-night). Listening also differs by shape: high wave and halo.
 */
enum class BubbleState(
    @ColorRes val background: Int,
    @ColorRes val content: Int,
    @StringRes val description: Int,
) {
    Idle(R.color.bubble_primary, R.color.bubble_on_primary, R.string.bubble_idle),
    Listening(R.color.bubble_tertiary, R.color.bubble_on_tertiary, R.string.bubble_listening),
    Processing(R.color.bubble_primary, R.color.bubble_on_primary, R.string.bubble_processing),
    Ready(R.color.bubble_secondary, R.color.bubble_on_secondary, R.string.bubble_ready),
    ;

    /** 90 % at rest, 100 % in use. */
    val opacity: Float get() = if (this == Idle) 0.9f else 1f
}
