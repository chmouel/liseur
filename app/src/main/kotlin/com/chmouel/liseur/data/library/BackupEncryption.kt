package com.chmouel.liseur.data.library

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Password encryption for the portable connection payload; never uses the device Keystore. */
internal object BackupEncryption {
    private const val ITERATIONS = 600_000
    const val MAX_BYTES = 1024 * 1024L
    private val aad = "liseur-connections-v1".toByteArray(Charsets.UTF_8)

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty() && plaintext.size <= MAX_BYTES / 2)
        val random = SecureRandom()
        val salt = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, plaintext, password, salt, nonce)
        return JSONObject().put("version", 1).put("cipher", "AES-256-GCM")
            .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
            .put("salt", encode(salt)).put("nonce", encode(nonce))
            .put("ciphertext", encode(encrypted)).toString().toByteArray(Charsets.UTF_8)
    }

    fun validate(encrypted: ByteArray) { parameters(encrypted) }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty())
        val (salt, nonce, ciphertext) = parameters(encrypted)
        return crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, nonce)
    }

    private fun parameters(bytes: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(bytes.size <= MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.opt("version") == 1 && json.opt("iterations") == ITERATIONS)
        require(json.opt("cipher") == "AES-256-GCM" && json.opt("kdf") == "PBKDF2-HMAC-SHA256")
        val salt = Base64.getDecoder().decode(json.getString("salt"))
        val nonce = Base64.getDecoder().decode(json.getString("nonce"))
        val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
        require(salt.size == 32 && nonce.size == 12 && ciphertext.size >= 16)
        return Triple(salt, nonce, ciphertext)
    }

    private fun crypt(mode: Int, bytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(bytes)
            }
        } finally { key.fill(0) }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
