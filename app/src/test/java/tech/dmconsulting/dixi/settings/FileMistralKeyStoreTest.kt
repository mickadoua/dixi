package tech.dmconsulting.dixi.settings

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
import java.io.File
import java.security.GeneralSecurityException

/** The Keystore does not exist on the JVM: [XorCipher] stands in; the emulator proof covers [KeystoreKeyCipher]. */
class FileMistralKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var legacy: File

    @Before
    fun setUp() {
        dir = File(folder.root, "no_backup/mistral_keys")
        legacy = File(folder.root, "no_backup/mistral_key.bin")
    }

    private fun store(cipher: KeyCipher = XorCipher()) = FileMistralKeyStore(dir, cipher, legacy, legacyName = "Key 1")

    /** Everything written under no_backup/, as text. */
    private fun disk(): String = folder.root.walk().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Test
    fun noFileMeansNoKey() = runTest {
        val store = store()
        assertEquals(KeyStatus.None, store.status.value)
        assertEquals(emptyList<KeyEntry>(), store.entries.value)
        assertNull(store.key())
    }

    @Test
    fun keysAreReadBackByNameAndOnlyTheirCiphertextIsOnDisk() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.add("Pro", KEY_2)

        assertFalse(disk().contains(KEY))
        assertFalse(disk().contains(KEY_2))
        assertTrue(disk().contains("Perso"))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        // A new process reads the same files.
        val next = store()
        assertEquals(listOf("Perso" to false, "Pro" to true), next.entries.value.map { it.name to it.active })
        assertEquals(KeyStatus.Saved, next.status.value)
        assertEquals(KEY_2, next.key())
    }

    @Test
    fun theActiveKeySurvivesARestart() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.add("Pro", KEY_2)
        store.activate(store.entries.value.first { it.name == "Perso" }.id)
        assertEquals(KEY, store.key())

        assertEquals(KEY, store().key())
    }

    @Test
    fun deleteRemovesTheKeyFileAndHandsOver() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.add("Pro", KEY_2)
        store.markRejected()
        store.delete(store.entries.value.first { it.active }.id)

        assertEquals(KEY, store.key())
        assertEquals(KeyStatus.Saved, store.status.value)
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".bin") })

        store.delete(store.entries.value.single().id)
        assertEquals(KeyStatus.None, store.status.value)
        assertNull(store.key())
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".bin") })
        assertEquals(KeyStatus.None, store().status.value)
    }

    @Test
    fun refusalSurvivesARestartUntilAccepted() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.markRejected()
        assertEquals(KeyStatus.Rejected, store.status.value)
        assertEquals(KeyStatus.Rejected, store().status.value)

        store.markAccepted()
        assertEquals(KeyStatus.Saved, store().status.value)

        // A new key is active and was just accepted by « Test key ».
        store.markRejected()
        store.add("Pro", KEY_2)
        assertEquals(KeyStatus.Saved, store.status.value)
        assertEquals(listOf(true, false), store().entries.value.map { it.rejected })
    }

    @Test
    fun refusalWithoutKeyIsIgnored() = runTest {
        val store = store()
        store.markRejected()
        assertEquals(KeyStatus.None, store.status.value)
        assertFalse(dir.exists())
    }

    @Test
    fun theSingleKeyOfOlderVersionsIsMovedIn() = runTest {
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(XorCipher().encrypt(KEY.toByteArray()))
        File(legacy.parentFile, "mistral_key.bin.rejected").createNewFile()

        val store = store()

        assertEquals(listOf(KeyEntry(store.entries.value.single().id, "Key 1", active = true, rejected = true)), store.entries.value)
        assertEquals(KeyStatus.Rejected, store.status.value)
        assertEquals(KEY, store.key())
        assertFalse(legacy.exists())
        assertFalse(File(legacy.parentFile, "mistral_key.bin.rejected").exists())
        assertEquals(KEY, store().key())
    }

    @Test
    fun anUndecryptableKeyIsForgottenAndTheNextTakesOver() = runTest {
        store().add("Perso", KEY)
        val store = store(FailingCipher())
        assertEquals(KeyStatus.Saved, store.status.value)

        assertNull(store.key())
        assertEquals(KeyStatus.None, store.status.value)
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".bin") })
    }

    @Test
    fun anUndecryptableKeyOnAFullDiskIsForgottenWithoutThrowing() = runTest {
        store().add("Perso", KEY)
        val store = store(FailingCipher())
        // keys.json cannot be rewritten: its temporary file is a directory.
        File(dir, "keys.json.tmp").mkdirs()

        assertNull(store.key())
        assertEquals(KeyStatus.None, store.status.value)
    }

    @Test
    fun filesWithoutAnEntryAreDeletedAndEntriesWithoutAFileDropped() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.add("Pro", KEY_2)
        File(dir, "orphan.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, store.entries.value.first { it.name == "Pro" }.id + ".bin").delete()

        val next = store()

        assertEquals(listOf("Perso"), next.entries.value.map { it.name })
        assertEquals(KEY, next.key())
        assertFalse(File(dir, "orphan.bin").exists())
    }

    @Test
    fun aDamagedIndexMeansNoKeyAndNoFileLeft() = runTest {
        store().add("Perso", KEY)
        File(dir, "keys.json").writeText("{not json")

        val store = store()

        assertEquals(KeyStatus.None, store.status.value)
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".bin") })
    }

    @Test
    fun aCipherFailureOnAddNeverCarriesTheKey() = runTest {
        try {
            store(FailingCipher()).add("Perso", KEY)
            fail("add should fail")
        } catch (e: GeneralSecurityException) {
            assertFalse(e.toString().contains(KEY))
            assertFalse(e.stackTraceToString().contains(KEY))
        }
        assertEquals(KeyStatus.None, store().status.value)
        assertFalse(disk().contains(KEY))
    }

    @Test
    fun storeNeverPrintsTheKey() = runTest {
        val store = store()
        store.add("Perso", KEY)
        store.key()
        assertFalse(store.toString().contains(KEY))
        assertFalse(store.status.value.toString().contains(KEY))
        assertFalse(store.entries.value.toString().contains(KEY))
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
        const val KEY_2 = "test-key-not-real-2"
    }
}
