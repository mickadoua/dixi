package tech.dmconsulting.voxbulle.dictation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlimitedQuotaTest {
    @Test
    fun alwaysAllowsDictation() = runBlocking {
        UnlimitedQuota.consume(durationMs = 300_000)
        assertTrue(UnlimitedQuota.canDictate())
    }
}
