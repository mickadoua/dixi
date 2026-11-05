package tech.dmconsulting.voxbulle.settings

import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Keystore does not exist on the JVM: [XorCipher] stands in; the emulator proof covers [KeystoreKeyCipher]. */
class FileMistralKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(folder.root, "no_backup/mistral_key.bin")
    }

    private fun store(cipher: KeyCipher = XorCipher()) = FileMistralKeyStore(file, cipher)

    @Test
    fun noFileMeansNoKey() = runTest {
        val store = store()
        assertEquals(KeyStatus.None, store.status.value)
        assertNull(store.key())
    }

    @Test
    fun savedKeyIsReadBackAndOnlyItsCiphertextIsOnDisk() = runTest {
        store().save(KEY)

        assertTrue(file.isFile)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(KEY))
        assertFalse(File(file.parentFile, "mistral_key.bin.tmp").exists())
        // A new process reads the same file.
        val next = store()
        assertEquals(KeyStatus.Saved, next.status.value)
        assertEquals(KEY, next.key())
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val store = store()
        store.save(KEY)
        store.markRejected()
        store.delete()

        assertEquals(KeyStatus.None, store.status.value)
        assertNull(store.key())
        assertTrue(file.parentFile!!.listFiles()!!.isEmpty())
        assertEquals(KeyStatus.None, store().status.value)
    }

    @Test
    fun refusalSurvivesARestartUntilAcceptedOrReplaced() = runTest {
        val store = store()
        store.save(KEY)
        store.markRejected()
        assertEquals(KeyStatus.Rejected, store.status.value)
        assertEquals(KeyStatus.Rejected, store().status.value)

        store.markAccepted()
        assertEquals(KeyStatus.Saved, store().status.value)

        store.markRejected()
        store.save("test-key-not-real-2")
        assertEquals(KeyStatus.Saved, store.status.value)
        assertEquals(KeyStatus.Saved, store().status.value)
    }

    @Test
    fun refusalWithoutKeyIsIgnored() = runTest {
        val store = store()
        store.markRejected()
        assertEquals(KeyStatus.None, store.status.value)
        assertFalse(file.parentFile?.exists() == true && file.parentFile!!.listFiles()!!.isNotEmpty())
    }

    @Test
    fun undecryptableFileIsForgotten() = runTest {
        store().save(KEY)
        val store = store(FailingCipher())
        assertEquals(KeyStatus.Saved, store.status.value)

        assertNull(store.key())
        assertEquals(KeyStatus.None, store.status.value)
        assertFalse(file.exists())
    }

    @Test
    fun aCipherFailureOnSaveNeverCarriesTheKey() = runTest {
        try {
            store(FailingCipher()).save(KEY)
            fail("save should fail")
        } catch (e: GeneralSecurityException) {
            assertFalse(e.toString().contains(KEY))
            assertFalse(e.stackTraceToString().contains(KEY))
        }
        assertFalse(file.exists())
    }

    @Test
    fun storeNeverPrintsTheKey() = runTest {
        val store = store()
        store.save(KEY)
        store.key()
        assertFalse(store.toString().contains(KEY))
        assertFalse(store.status.value.toString().contains(KEY))
    }

    /** Reversible, not secure: enough to check that the plain key never reaches the disk. */
    private class XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray) = encrypt(sealed)
    }

    private class FailingCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = throw GeneralSecurityException("Keystore unavailable")

        override fun decrypt(sealed: ByteArray): ByteArray = throw GeneralSecurityException("Keystore key gone")
    }

    private companion object {
        const val KEY = "test-key-not-real-1"
    }
}
