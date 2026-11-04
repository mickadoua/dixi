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
