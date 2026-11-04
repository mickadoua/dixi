package tech.dmconsulting.voxbulle.dictation

/** How much the user may still dictate. Room kept for a subscription; v1 is unlimited. */
interface UsageQuota {
    suspend fun canDictate(): Boolean

    /** Counts a finished dictation of [durationMs] against the quota. */
    suspend fun consume(durationMs: Long)
}

object UnlimitedQuota : UsageQuota {
    override suspend fun canDictate() = true

    override suspend fun consume(durationMs: Long) = Unit
}
