package com.chmouel.liseur.translate

import java.io.IOException

/** Why a passage could not be translated. Messages never carry the key, the passage or its translation. */
sealed class TranslationError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [code] is null when the key could not even be sent. */
    class InvalidKey(code: Int? = null) : TranslationError(
        code?.let { "The service rejected the API key (HTTP $it)" } ?: "The API key cannot be sent",
    )
    class RateLimited(code: Int) : TranslationError("Quota or rate limit reached (HTTP $code)")
    class LocalNetworkBlocked : TranslationError("Local network access is not allowed")
    class Network(cause: IOException) :
        TranslationError("The service could not be reached (${cause.javaClass.simpleName})", cause)
    class Service(val code: Int) : TranslationError("The service answered HTTP $code")

    /** The model declined, or a safety filter held the answer back. */
    class Refused : TranslationError("The service declined to translate")
    class Empty : TranslationError("The service sent an empty translation")

    /** The model stopped at its length limit, so the translation is missing its end. */
    class Truncated : TranslationError("The service stopped before the end of the translation")
    class Malformed(reason: String) : TranslationError("The service sent no usable translation: $reason")

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
}
