package tech.dmconsulting.voxbulle.bubble

/**
 * Tells a tap from a drag. Once the finger moves more than [touchSlop] px from
 * where it went down, the gesture is a drag until it lifts, and it is never a tap.
 */
class TapDragTracker(private val touchSlop: Float) {

    private var downX = 0f
    private var downY = 0f

    var isDragging = false
        private set

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        isDragging = false
    }

    /** Returns true when this move starts the drag. */
    fun move(x: Float, y: Float): Boolean {
        if (isDragging) return false
        val dx = x - downX
        val dy = y - downY
        isDragging = dx * dx + dy * dy > touchSlop * touchSlop
        return isDragging
    }

    /** Returns true when the gesture was a tap. */
    fun up(): Boolean = !isDragging
}

/** What the bubble does when the finger lifts, or when the system cancels the gesture. */
enum class Release { Click, Hide, Snap, None }

/**
 * A cancelled gesture (for example a system navigation gesture) never clicks
 * and never hides the bubble: a cancelled drag snaps to the side.
 */
fun releaseAction(wasTap: Boolean, cancelled: Boolean, overHideZone: Boolean): Release = when {
    wasTap -> if (cancelled) Release.None else Release.Click
    cancelled -> Release.Snap
    overHideZone -> Release.Hide
    else -> Release.Snap
}
