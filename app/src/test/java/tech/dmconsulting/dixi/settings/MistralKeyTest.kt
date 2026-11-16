package tech.dmconsulting.dixi.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MistralKeyTest {
    @Test
    fun pastedKeyIsTrimmed() {
        assertEquals("abcdefghijklmnop0123", MistralKey.normalize("  abcdefghijklmnop0123\n"))
    }

    @Test
    fun refusesWhatCannotBeAKey() {
        listOf(
            "",
            "   ",
            "short-key",
            "x".repeat(MistralKey.MAX_LENGTH + 1),
            "abcdefgh ijklmnop0123",
            "abcdefgh\nijklmnop0123",
            "abcdéfghijklmnop0123",
        ).forEach { assertNull(it, MistralKey.normalize(it)) }
    }

    @Test
    fun inMemoryStoreFollowsTheKeyLife() = runTest {
        val store = InMemoryMistralKeyStore()
        assertEquals(KeyStatus.None, store.status.value)
        store.markRejected()
        assertEquals(KeyStatus.None, store.status.value)

        store.save("test-key-not-real-1")
        assertEquals(KeyStatus.Saved, store.status.value)
        assertEquals("test-key-not-real-1", store.key())
        store.markRejected()
        assertEquals(KeyStatus.Rejected, store.status.value)
        store.markAccepted()
        assertEquals(KeyStatus.Saved, store.status.value)

        store.delete()
        assertEquals(KeyStatus.None, store.status.value)
        assertNull(store.key())
    }
}
