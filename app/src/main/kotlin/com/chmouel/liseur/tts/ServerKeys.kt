package com.chmouel.liseur.tts

import android.content.Context
import com.chmouel.liseur.data.security.SecretCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The speech servers' API keys, one per server, each kept like any
 * [ApiKeyStore]. A key is read for the server a request is about to
 * reach, so it is never sent to another one, however the address
 * changes. The file name is a hash of the server, which is not written
 * out in clear.
 */
class ServerKeys(
    private val dir: File,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    internal constructor(context: Context) : this(context.noBackupFilesDir, SecretCipher(KEY_ALIAS))

    private val stores = ConcurrentHashMap<String, ApiKeyStore>()

    @Volatile
    private var legacyGone = false

    fun store(origin: String): ApiKeyStore =
        stores.getOrPut(origin) { ApiKeyStore(File(dir, fileName(origin)), cipher, io) }

    fun configured(origin: String): StateFlow<Boolean> = store(origin).configured

    suspend fun get(origin: String): String? {
        dropLegacy()
        return store(origin).get()
    }

    suspend fun set(origin: String, key: String) {
        dropLegacy()
        store(origin).set(key)
    }

    suspend fun clear(origin: String) {
        dropLegacy()
        store(origin).clear()
    }

    /**
     * The one key saved before keys were kept per server. Which server it
     * was for is unknown, so it is never sent anywhere.
     */
    private suspend fun dropLegacy() {
        if (legacyGone) return
        withContext(io) { File(dir, LEGACY_FILE).delete() }
        legacyGone = true
    }

    companion object {
        private const val KEY_ALIAS = "liseur.openai.key"
        private const val LEGACY_FILE = "openai-key"

        /** The server [base] is on: its scheme, host and port, whatever its path. */
        fun origin(base: HttpUrl): String = "${base.scheme}://${base.host.lowercase()}:${base.port}"

        internal fun fileName(origin: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
            return "openai-key-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
