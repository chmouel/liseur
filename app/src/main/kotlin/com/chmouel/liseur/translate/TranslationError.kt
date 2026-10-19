package com.chmouel.liseur.translate

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Why a passage could not be translated. Messages never carry the key, the passage or its translation. */
sealed class TranslationError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * What the server said about a refused request, for showing the
     * reader on demand; kept out of [message] so logs never carry it.
     */
    open val serverMessage: String? get() = null

    /** [code] is null when the key could not even be sent. */
    class InvalidKey(val code: Int? = null, override val serverMessage: String? = null) : TranslationError(
        code?.let { "The service rejected the API key (HTTP $it)" } ?: "The API key cannot be sent",
    )
    class RateLimited(val code: Int, override val serverMessage: String? = null) :
        TranslationError("Quota or rate limit reached (HTTP $code)")
    class LocalNetworkBlocked : TranslationError("Local network access is not allowed")
    class Network(cause: IOException) :
        TranslationError("The service could not be reached (${cause.javaClass.simpleName})", cause)
    class Service(val code: Int, override val serverMessage: String? = null) :
        TranslationError("The service answered HTTP $code")

    /** The server has no [model] by that name: misspelled, or not downloaded yet. */
    class ModelNotFound(val model: String, override val serverMessage: String? = null) :
        TranslationError("The server has no such model")

    /** Nothing answers chat completions at the server's address: the address is likely wrong. */
    class NoEndpoint(override val serverMessage: String? = null) :
        TranslationError("The server has no chat endpoint at this address")

    /** The model declined, or a safety filter held the answer back. */
    class Refused : TranslationError("The service declined to translate")
    class Empty : TranslationError("The service sent an empty translation")

    /** The model stopped at its length limit, so the translation is missing its end. */
    class Truncated : TranslationError("The service stopped before the end of the translation")
    open class Malformed(reason: String) : TranslationError("The service sent no usable translation: $reason")

    /** An incompatible response envelope is an endpoint failure, not a sentence refusal. */
    class Protocol(reason: String) : Malformed(reason)

    /** The chosen service is missing its server, model or key. */
    class NotSetUp : TranslationError("The translation service is not set up")

    /** The service's address or key kept changing while it was asked. */
    class Changed : TranslationError("The service changed while translating")

    /** The device's translator has this pair but has not downloaded it. */
    class NotDownloaded : TranslationError("The language pair is not downloaded")

    /** The device's translator has no such pair. */
    class Unsupported : TranslationError("The language pair is not supported")

    /** The device's translator did not answer, or answered without a translation. */
    class DeviceFailed(reason: String) : TranslationError("The device's translator failed: $reason")

    /** A translator on the phone other than the system's did not answer; named by its service. */
    class Failed(reason: String) : TranslationError("The translator failed: $reason")

    companion object {
        const val MAX_SERVER_MESSAGE = 4_000

        /**
         * The readable part of an error reply: `error.message` (or a bare
         * `message` or `detail`) when the body is JSON with one, else the
         * body itself, trimmed and bounded; null when there is nothing.
         */
        fun serverMessage(body: String): String? {
            val trimmed = body.trim()
            val parsed = try {
                val root = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0)
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    else -> null
                }
                root?.let { r ->
                    val error = r.opt("error")
                    (error as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: (error as? String)?.takeIf { it.isNotBlank() }
                        ?: r.optString("message").takeIf { it.isNotBlank() }
                        ?: (r.opt("detail") as? String)?.takeIf { it.isNotBlank() }
                }
            } catch (_: JSONException) {
                null
            }
            return (parsed ?: trimmed).trim().take(MAX_SERVER_MESSAGE).ifEmpty { null }
        }
    }
}
