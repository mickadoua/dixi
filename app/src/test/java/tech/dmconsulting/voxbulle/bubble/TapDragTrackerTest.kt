package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDragTrackerTest {

    private val tracker = TapDragTracker(touchSlop = 20f)

    @Test
    fun noMoveIsATap() {
        tracker.down(100f, 100f)
        assertTrue(tracker.up())
    }

    @Test
    fun smallMoveUnderTheSlopIsStillATap() {
        tracker.down(100f, 100f)
        assertFalse(tracker.move(112f, 112f)) // 17 px
        assertFalse(tracker.isDragging)
        assertTrue(tracker.up())
    }

    @Test
    fun moveOverTheSlopStartsADrag() {
        tracker.down(100f, 100f)
        assertTrue(tracker.move(100f, 121f))
        assertTrue(tracker.isDragging)
        assertFalse(tracker.up())
    }

    @Test
    fun dragBackToTheStartIsNeverATap() {
        tracker.down(100f, 100f)
        tracker.move(200f, 100f)
        assertFalse(tracker.move(100f, 100f)) // the drag started already
        assertTrue(tracker.isDragging)
        assertFalse(tracker.up())
    }

    @Test
    fun newGestureStartsClean() {
        tracker.down(0f, 0f)
        tracker.move(50f, 0f)
        tracker.up()
        tracker.down(0f, 0f)
        assertFalse(tracker.isDragging)
        assertTrue(tracker.up())
    }
}
