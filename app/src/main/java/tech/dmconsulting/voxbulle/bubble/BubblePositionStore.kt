package tech.dmconsulting.voxbulle.bubble

import android.content.Context

/** Bubble place and hidden flag, kept across service restarts and process death. */
class BubblePositionStore(context: Context) {

    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    var position: BubblePosition
        get() = BubblePosition(
            side = if (prefs.getString(KEY_SIDE, null) == Side.Left.name) Side.Left else BubblePosition.Default.side,
            yFraction = prefs.getFloat(KEY_Y, BubblePosition.Default.yFraction),
        )
        set(value) = prefs.edit().putString(KEY_SIDE, value.side.name).putFloat(KEY_Y, value.yFraction).apply()

    var hidden: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDDEN, value).apply()

    private companion object {
        const val KEY_SIDE = "side"
        const val KEY_Y = "y_fraction"
        const val KEY_HIDDEN = "hidden"
    }
}
