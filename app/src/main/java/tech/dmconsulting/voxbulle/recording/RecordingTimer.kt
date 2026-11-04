package tech.dmconsulting.voxbulle.recording

/** Monotonic time in ms. SystemClock.elapsedRealtime in the app, a fake in tests. */
fun interface Clock {
    fun nowMs(): Long
}

enum class TimerEvent {
    /** 30 s left (at 4 min 30). Sent once. */
    Warning,

    /** 5 min reached: stop now and send the file anyway. */
    LimitReached,
}

/** Tells when a recording must warn and stop. Polled a few times per second. */
class RecordingTimer(private val clock: Clock, private val limits: RecordingLimits) {

    private var startedAt = 0L
    private var warned = false

    fun start() {
        startedAt = clock.nowMs()
        warned = false
    }

    fun elapsedMs(): Long = clock.nowMs() - startedAt

    fun poll(): TimerEvent? {
        val elapsed = elapsedMs()
        return when {
            elapsed >= limits.maxMs -> TimerEvent.LimitReached
            !warned && elapsed >= limits.warnAtMs -> TimerEvent.Warning.also { warned = true }
            else -> null
        }
    }
}
