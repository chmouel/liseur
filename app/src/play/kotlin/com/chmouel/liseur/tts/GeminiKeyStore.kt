package com.chmouel.liseur.tts

import android.content.Context
import com.chmouel.liseur.data.security.SecretCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The reader's Gemini API key, encrypted with a Keystore key in a file
 * under no_backup: outside Auto Backup, device transfer and the settings
 * export, so it never leaves the device. A key that can no longer be
 * decrypted (the Keystore was reset) counts as no key.
 */
class GeminiKeyStore(
    private val file: File,
    private val cipher: SecretCipher = SecretCipher(KEY_ALIAS),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    private val mutableConfigured = MutableStateFlow(file.exists())

    /** Whether a key is saved; it is never read back for display. */
    val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    suspend fun get(): String? = withContext(io) {
        val key = file.takeIf { it.exists() }?.readText()?.let(cipher::decrypt)?.takeIf { it.isNotBlank() }
        if (key == null && file.exists()) {
            file.delete()
            mutableConfigured.value = false
        }
        key
    }

    suspend fun set(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return clear()
        withContext(io) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(cipher.encrypt(trimmed))
            if (!temp.renameTo(file)) {
                temp.delete()
                error("Could not save the Gemini key")
            }
            mutableConfigured.value = true
        }
    }

    suspend fun clear() = withContext(io) {
        file.delete()
        mutableConfigured.value = false
    }

    private companion object {
        const val FILE_NAME = "gemini-key"
        const val KEY_ALIAS = "liseur.gemini.key"
    }
}
