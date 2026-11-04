package tech.dmconsulting.voxbulle.bubble

import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R

/** Bubble states and colors from the Voxbulle charter, section 6. */
enum class BubbleState(val color: Int, @StringRes val description: Int) {
    Idle(0xFF3A47C8.toInt(), R.string.bubble_idle),
    Listening(0xFFB3402A.toInt(), R.string.bubble_listening),
    Processing(0xFF3A47C8.toInt(), R.string.bubble_processing),
    Ready(0xFF00696B.toInt(), R.string.bubble_ready),
}
