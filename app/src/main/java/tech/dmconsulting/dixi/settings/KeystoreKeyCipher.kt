package tech.dmconsulting.dixi.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore and cannot be exported:
 * the file alone, copied off the phone, cannot be read. The Keystore draws a fresh
 * IV for each encryption; output is `IV length (1 byte) | IV | ciphertext + tag`.
 * Chosen over EncryptedSharedPreferences (deprecated) and Tink (≈ 1 MB for one string): DMC-227.
 */
class KeystoreKeyCipher(private val alias: String = ALIAS) : KeyCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val ivLength = sealed.firstOrNull()?.toInt() ?: throw GeneralSecurityException("Empty key file")
        if (ivLength !in 12..16 || sealed.size <= 1 + ivLength) throw GeneralSecurityException("Bad key file")
        val key = existingKey() ?: throw GeneralSecurityException("No Keystore key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, ivLength))
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun secretKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"

        // Old app name kept on purpose: a new alias would lose the key already saved (DMC-653).
        const val ALIAS = "voxbulle_mistral_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
