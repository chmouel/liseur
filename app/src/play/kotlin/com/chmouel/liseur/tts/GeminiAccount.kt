package com.chmouel.liseur.tts

import com.chmouel.liseur.providers.ServerConnections
import kotlinx.coroutines.flow.StateFlow

/**
 * The reader's Gemini key, set on the Services page and used by every
 * feature that asks Gemini. One instance per app, as whether a key is
 * saved is known per store.
 */
internal class GeminiAccount(private val keys: ApiKeyStore, private val connections: ServerConnections) {
    val configured: StateFlow<Boolean> = keys.configured

    /** [OWNER] when the last key save or removal failed. */
    val keyFailure: StateFlow<String?> = connections.keyFailure

    /** Moves on with every key change. */
    val generation: StateFlow<Int> = connections.generation(OWNER)

    suspend fun key(): String? = keys.get()

    /** Saves [key]; features using the old one hear of it first. */
    fun commitKey(key: String, done: (Boolean) -> Unit) = connections.commitKey(OWNER, { keys.set(key) }, done)

    fun commitKeyRemoval() = connections.commitKey(OWNER, { keys.clear() })

    companion object {
        /** Gemini has one key, whatever the server: the owner of every key typed for it. */
        const val OWNER = "gemini"
    }
}
