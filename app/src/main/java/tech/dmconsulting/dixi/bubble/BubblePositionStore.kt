package tech.dmconsulting.dixi.bubble

import android.content.Context
import androidx.core.content.edit

/** Bubble place, hidden flag and hide mode, kept across service restarts and process death. */
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

    /** Hidden bubble: a 20 dp edge tab (true, default) or only a notification. */
    var hideToEdge: Boolean
        get() = prefs.getBoolean(KEY_HIDE_TO_EDGE, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_TO_EDGE, value) }

    private companion object {
        const val KEY_SIDE = "side"
        const val KEY_Y = "y_fraction"
        const val KEY_HIDDEN = "hidden"
        const val KEY_HIDE_TO_EDGE = "hide_to_edge"
    }
}
