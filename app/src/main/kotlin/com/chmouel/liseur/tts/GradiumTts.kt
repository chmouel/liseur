package com.chmouel.liseur.tts

import okhttp3.HttpUrl

/**
 * What sets Gradium's speech API apart from an OpenAI-compatible one, so
 * a Gradium server listed by its address is spoken to its own way: its
 * key goes in an `x-api-key` header, it has one model, its voices come
 * from `voices/` with a name and a language each, and speech is asked of
 * `post/speech/tts` as 48 kHz WAV. Its API root is `/api`, with no
 * version in the path.
 */
object GradiumTts {
    /** The one model Gradium serves, as its API names it. */
    const val MODEL = "default"

    /** Gradium's hosts: the default, and the two a data residency choice pins to. */
    val HOSTS = listOf("api.gradium.ai", "eu.api.gradium.ai", "us.api.gradium.ai")

    fun isGradium(base: HttpUrl): Boolean = base.scheme == "https" && isGradiumHost(base.host)

    fun isGradiumHost(host: String): Boolean = HOSTS.any { host.equals(it, ignoreCase = true) }

    /**
     * Whether Gradium's plain-text error says the key was refused: an
     * upstream failure reads `error from server <code>: <reason>`, and
     * code 1008 is a key that is invalid, revoked or over its plan.
     */
    fun refusedKey(text: String): Boolean = REFUSED.containsMatchIn(text)

    private val REFUSED = Regex("error from server 1008\\b", RegexOption.IGNORE_CASE)

    /** One page of the voice list, at most [limit] voices from [skip]. */
    fun voicesUrl(base: HttpUrl, skip: Int, limit: Int): HttpUrl = base.newBuilder()
        .addPathSegments("voices/")
        .addQueryParameter("include_catalog", "true")
        .addQueryParameter("skip", skip.toString())
        .addQueryParameter("limit", limit.toString())
        .build()

    const val VOICE_PAGE = 100
}
