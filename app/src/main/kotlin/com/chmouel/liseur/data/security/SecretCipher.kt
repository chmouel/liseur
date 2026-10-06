package com.chmouel.liseur.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts a secret with an AES/GCM key held in the Android Keystore under
 * [alias], so it is not stored in the clear.
 *
 * This protects the secret at rest only: the app needs it back in the
 * clear to use it, so it can always decrypt it while it runs. It is not a
 * substitute for a user-supplied passphrase.
 */
class SecretCipher(private val alias: String) {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        return encode(cipher.iv) + ":" + encode(encrypted)
    }

    /** Returns null when the ciphertext cannot be read, e.g. after the key was lost. */
    fun decrypt(stored: String): String? = runCatching {
        val (iv, encrypted) = stored.split(":", limit = 2).let { decode(it[0]) to decode(it[1]) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(encrypted))
    }.getOrNull()

    /**
     * The key this encrypts with.
     *
     * Kept in a field so a JVM test can hand over an ordinary AES key:
     * Robolectric has no Android Keystore. Nothing on a device ever
     * assigns to it.
     */
    @VisibleForTesting
    var keyForTesting: SecretKey? = null

    private fun key(): SecretKey {
        keyForTesting?.let { return it }
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
