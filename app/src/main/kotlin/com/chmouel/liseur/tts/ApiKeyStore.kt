package com.chmouel.liseur.tts

import android.content.Context
import com.chmouel.liseur.data.security.SecretCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One API key a speech service is used with, encrypted with a Keystore key in a file under no_backup:
 * outside Auto Backup, device transfer and the settings export, so it
 * never leaves the device. A key that can no longer be decrypted (the
 * Keystore was reset) counts as no key. Reads, saves and removals take
 * turns, so a removal cannot be undone by a save that was still being
 * written.
 */
class ApiKeyStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    internal constructor(context: Context, fileName: String, keyAlias: String) :
        this(File(context.noBackupFilesDir, fileName), SecretCipher(keyAlias))

    private val mutableConfigured = MutableStateFlow(file.exists())

    /** Whether a key is saved; it is never read back for display. */
    val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    private val lock = Mutex()

    suspend fun get(): String? = locked {
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
        locked {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(cipher.encrypt(trimmed))
            if (!temp.renameTo(file)) {
                temp.delete()
                error("Could not save the API key")
            }
            mutableConfigured.value = true
        }
    }

    suspend fun clear() = locked {
        file.delete()
        mutableConfigured.value = false
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    companion object {
        fun openAi(context: Context) = ApiKeyStore(context, "openai-key", "liseur.openai.key")
    }
}
