package tech.dmconsulting.dixi.bubble

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** A haptic tap on the bubble (mockups DMC-764 § 2). No VIBRATE permission: the view plays it. */
enum class Haptic { Confirm, Reject, Click, Warn }

/**
 * What the user feels when the bubble goes from [from] to [to]: one tap when the
 * dictation starts, two when it stops, a refusal when it is cancelled or fails.
 * « Texte prêt » has none.
 */
fun hapticsFor(from: BubbleState, to: BubbleState): List<Haptic> = when {
    from == to -> emptyList()
    to == BubbleState.Listening -> listOf(Haptic.Confirm)
    from == BubbleState.Listening && to == BubbleState.Processing -> listOf(Haptic.Confirm, Haptic.Confirm)
    from == BubbleState.Listening && to == BubbleState.Idle -> listOf(Haptic.Reject)
    to == BubbleState.Error -> listOf(Haptic.Reject)
    else -> emptyList()
}

/** Plays [haptics] on [view], [HAPTIC_GAP_MS] apart. CONFIRM and REJECT exist from Android 11. */
fun play(view: View, haptics: List<Haptic>) {
    haptics.forEachIndexed { i, haptic ->
        val constant = when (haptic) {
            Haptic.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Haptic.Click -> HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.Warn -> HapticFeedbackConstants.LONG_PRESS
        }
        if (i == 0) view.performHapticFeedback(constant) else view.postDelayed({ view.performHapticFeedback(constant) }, i * HAPTIC_GAP_MS)
    }
}

/** Gap between the two taps of « stop »: felt as two, not as one long buzz. */
const val HAPTIC_GAP_MS = 120L
