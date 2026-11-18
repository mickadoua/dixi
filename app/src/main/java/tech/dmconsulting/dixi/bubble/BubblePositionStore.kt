package tech.dmconsulting.dixi.bubble

import android.content.Context
import androidx.core.content.edit

/** Bubble place, tucked and hidden flags, kept across service restarts and process death. */
class BubblePositionStore(context: Context) {

    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    init {
        // Before DMC-768, a hidden bubble left a 20 dp tab at the edge (on by default). That tab
        // is gone: such a bubble comes back tucked, half shown, not hidden with no way back but the app.
        // Runs once: the tucked flag is written here, before this version can hide the bubble.
        edgeTabMigration(prefs.contains(KEY_DOCKED), hidden, prefs.getBoolean(KEY_HIDE_TO_EDGE, true))?.let { flags ->
            prefs.edit {
                putBoolean(KEY_HIDDEN, flags.hidden)
                putBoolean(KEY_DOCKED, flags.docked)
                remove(KEY_HIDE_TO_EDGE)
            }
        }
    }

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

        /** Before DMC-768: hidden to the edge tab (true, default) or only to a notification. */
        const val KEY_HIDE_TO_EDGE = "hide_to_edge"
    }
}

data class BubbleFlags(val hidden: Boolean, val docked: Boolean)

/**
 * Flags after the move away from the edge tab (DMC-785); null once done, the tucked flag saved.
 * Hidden to the tab ([hideToEdge]): tucked at the edge. Hidden to the notification: still hidden.
 */
fun edgeTabMigration(dockedSaved: Boolean, hidden: Boolean, hideToEdge: Boolean): BubbleFlags? {
    if (dockedSaved) return null
    val tucked = hidden && hideToEdge
    return BubbleFlags(hidden = hidden && !tucked, docked = tucked)
}
