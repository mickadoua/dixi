package tech.dmconsulting.dixi.bubble

import android.content.Context
import androidx.core.content.edit

/** Bubble place, tucked and hidden flags, kept across service restarts and process death. */
class BubblePositionStore(context: Context) {

    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    var position: BubblePosition
        get() = BubblePosition(
            side = if (prefs.getString(KEY_SIDE, null) == Side.Left.name) Side.Left else BubblePosition.Default.side,
            yFraction = prefs.getFloat(KEY_Y, BubblePosition.Default.yFraction),
        )
        set(value) = prefs.edit { putString(KEY_SIDE, value.side.name).putFloat(KEY_Y, value.yFraction) }

    var hidden: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDDEN, value) }

    /** Tucked at the [position] side, half hidden (DMC-768). Not the same as [hidden]. */
    var docked: Boolean
        get() = prefs.getBoolean(KEY_DOCKED, false)
        set(value) = prefs.edit { putBoolean(KEY_DOCKED, value) }

    private companion object {
        const val KEY_SIDE = "side"
        const val KEY_Y = "y_fraction"
        const val KEY_HIDDEN = "hidden"
        const val KEY_DOCKED = "docked"
    }
}
