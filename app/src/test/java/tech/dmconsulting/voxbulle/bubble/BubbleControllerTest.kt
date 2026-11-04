package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleControllerTest {

    /** Runs scheduled blocks only when the test advances the clock. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val task = now + delayMs to block
            tasks += task
            return { tasks.remove(task) }
        }

        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() }
        }

        val pending get() = tasks.size
    }

    private val scheduler = FakeScheduler()
    private val controller = BubbleController(scheduler)
    private val seen = mutableListOf<BubbleState>()

    init {
        controller.onStateChanged = { seen += it }
    }

    @Test
    fun startsAtRest() {
        assertEquals(BubbleState.Idle, controller.state)
    }

    @Test
    fun setStateNotifiesTheView() {
        controller.setState(BubbleState.Listening)
        controller.setState(BubbleState.Processing)
        assertEquals(listOf(BubbleState.Listening, BubbleState.Processing), seen)
        assertEquals(BubbleState.Processing, controller.state)
    }

    @Test
    fun readyGoesBackToRestAfterOneAndAHalfSeconds() {
        controller.setState(BubbleState.Ready)
        scheduler.advance(1_499)
        assertEquals(BubbleState.Ready, controller.state)
        scheduler.advance(1)
        assertEquals(BubbleState.Idle, controller.state)
        assertEquals(listOf(BubbleState.Ready, BubbleState.Idle), seen)
    }

    @Test
    fun aNewStateCancelsTheReadyTimeout() {
        controller.setState(BubbleState.Ready)
        scheduler.advance(1_000)
        controller.setState(BubbleState.Listening)
        assertEquals(0, scheduler.pending)
        scheduler.advance(10_000)
        assertEquals(BubbleState.Listening, controller.state)
    }

    @Test
    fun readyAgainRestartsTheTimeout() {
        controller.setState(BubbleState.Ready)
        scheduler.advance(1_000)
        controller.setState(BubbleState.Ready)
        scheduler.advance(1_000)
        assertEquals(BubbleState.Ready, controller.state)
        scheduler.advance(500)
        assertEquals(BubbleState.Idle, controller.state)
    }

    @Test
    fun readyDelayCanBeChanged() {
        controller.readyToIdleMs = 8_000
        controller.setState(BubbleState.Ready)
        scheduler.advance(7_999)
        assertEquals(BubbleState.Ready, controller.state)
        scheduler.advance(1)
        assertEquals(BubbleState.Idle, controller.state)
    }

    @Test
    fun tapStartsStopsOrDoesNothing() {
        assertEquals(TapAction.Start, controller.tapAction())
        controller.setState(BubbleState.Listening)
        assertEquals(TapAction.Stop, controller.tapAction())
        controller.setState(BubbleState.Processing)
        assertEquals(TapAction.None, controller.tapAction())
        controller.setState(BubbleState.Ready)
        assertEquals(TapAction.Start, controller.tapAction())
    }

    @Test
    fun hideOnlyWhenNoDictationRuns() {
        assertTrue(controller.canHide)
        controller.setState(BubbleState.Listening)
        assertFalse(controller.canHide)
        controller.setState(BubbleState.Processing)
        assertFalse(controller.canHide)
        controller.setState(BubbleState.Ready)
        assertTrue(controller.canHide)
    }

    @Test
    fun hideIsRefusedWhileADictationRuns() {
        var hides = 0
        controller.setState(BubbleState.Listening)
        assertFalse(controller.hideIfAllowed { hides++ })
        controller.setState(BubbleState.Processing)
        assertFalse(controller.hideIfAllowed { hides++ })
        assertEquals(0, hides)
        controller.setState(BubbleState.Idle)
        assertTrue(controller.hideIfAllowed { hides++ })
        controller.setState(BubbleState.Ready)
        assertTrue(controller.hideIfAllowed { hides++ })
        assertEquals(2, hides)
    }

    @Test
    fun voiceLevelIsKeptBetweenZeroAndOne() {
        val levels = mutableListOf<Float>()
        controller.onVoiceLevel = { levels += it }
        controller.setVoiceLevel(-1f)
        controller.setVoiceLevel(0.4f)
        controller.setVoiceLevel(3f)
        assertEquals(listOf(0f, 0.4f, 1f), levels)
    }
}
