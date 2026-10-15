package com.chmouel.liseur.data.library

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BackupEncryptionTest {
    @Test
    fun `round trip uses fresh salt and nonce and rejects wrong passwords and tampering`() {
        val password = "a backup passphrase 🔑".toCharArray()
        val plaintext = "credentials and connection settings".toByteArray()
        val encrypted = BackupEncryption.encrypt(plaintext, password)
        assertArrayEquals(plaintext, BackupEncryption.decrypt(encrypted, password))
        assertFalse(encrypted.contentEquals(BackupEncryption.encrypt(plaintext, password)))
        assertThrows(Exception::class.java) { BackupEncryption.decrypt(encrypted, "wrong".toCharArray()) }
        val json = JSONObject(encrypted.toString(Charsets.UTF_8))
        val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        json.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        assertThrows(Exception::class.java) { BackupEncryption.decrypt(json.toString().toByteArray(), password) }
    }

    @Test
    fun `unsupported and unsafe encryption parameters are rejected before key derivation`() {
        val encrypted = BackupEncryption.encrypt("{}".toByteArray(), "password".toCharArray())
        listOf("version" to 2, "iterations" to 1, "iterations" to Int.MAX_VALUE,
            "iterations" to 600000.5, "cipher" to "AES-CBC", "kdf" to "other",
            "salt" to "AA==", "nonce" to "AA==", "ciphertext" to "AA==",
        ).forEach { (field, value) ->
            val json = JSONObject(encrypted.toString(Charsets.UTF_8)).put(field, value)
            assertThrows("$field=$value", Exception::class.java) {
                BackupEncryption.decrypt(json.toString().toByteArray(), "password".toCharArray())
            }
        }
    }
}
