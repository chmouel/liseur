package com.chmouel.liseur.data.calibre

import androidx.annotation.VisibleForTesting
import com.chmouel.liseur.data.security.SecretCipher
import javax.crypto.SecretKey

/**
 * Encrypts the calibre-web password with an AES/GCM key held in the
 * Android Keystore, so it is not sitting in the database in the clear.
 *
 * This protects the password at rest only: HTTP Basic auth needs it back
 * in the clear on every request, so the app can always decrypt it while
 * it runs. It is not a substitute for a user-supplied passphrase.
 */
object CredentialCipher {
    private val cipher = SecretCipher("liseur.calibre.credentials")

    fun encrypt(plaintext: String): String = cipher.encrypt(plaintext)

    /** Returns null when the ciphertext cannot be read, e.g. after the key was lost. */
    fun decrypt(stored: String): String? = cipher.decrypt(stored)

    /**
     * Lets a JVM test hand over an ordinary AES key: Robolectric has no
     * Android Keystore, and the reconciliation tests that need to read a
     * stored secret back are not about how it was stored. Nothing on a
     * device ever assigns to it.
     */
    @VisibleForTesting
    internal var keyForTesting: SecretKey?
        get() = cipher.keyForTesting
        set(value) {
            cipher.keyForTesting = value
        }
}
