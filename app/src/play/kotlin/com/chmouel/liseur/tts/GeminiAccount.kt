package com.chmouel.liseur.tts

import com.chmouel.liseur.companion.GeminiCompanionService
import com.chmouel.liseur.providers.ServerConnections
import kotlinx.coroutines.flow.StateFlow

/**
 * The reader's Gemini key, set on the Services page and used by every
 * feature that asks Gemini. One instance per app, as whether a key is
 * saved is known per store.
 */
internal class GeminiAccount(
    private val keys: ApiKeyStore,
    private val connections: ServerConnections,
    private val validate: suspend (String) -> Unit = { GeminiTtsClient().models(it) },
) {
    val configured: StateFlow<Boolean> = keys.configured

    /** [OWNER] when the last key save or removal failed. */
    val keyFailure: StateFlow<String?> = connections.keyFailure

    /** How the last saved key fared when checked with Gemini. */
    val keyCheck: StateFlow<KeyCheck?> = connections.keyCheck(OWNER)

    /** Moves on with every key change. */
    val generation: StateFlow<Int> = connections.generation(OWNER)

    suspend fun key(): String? = keys.get()

    suspend fun restoreKey(key: String?) {
        if (key == null) keys.clear() else keys.set(key)
    }

    /**
     * Saves [key] and checks it with Gemini; features using the old one
     * hear of it first, and unset features adopt Gemini once the key is
     * accepted (or when the network cannot be reached to check it).
     */
    fun commitKey(key: String, done: (Boolean) -> Unit) = connections.commitKey(
        OWNER,
        op = { keys.set(key) },
        afterSave = { generation ->
            connections.checkKey(
                owner = OWNER,
                generation = generation,
                validate = { validate(key) },
                onAccepted = { connections.adoptGeminiDefaults(OWNER, GeminiCompanionService.RECOMMENDED_MODEL) },
            )
        },
        done = done,
    )

    fun commitKeyRemoval() = connections.commitKey(OWNER, { keys.clear() })

    companion object {
        /** Gemini has one key, whatever the server: the owner of every key typed for it. */
        const val OWNER = "gemini"
    }
}
