package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.domain.DictionaryUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the definition card is showing at any moment. */
sealed interface DictionaryState {
    /**
     * Definitions are switched off, so nothing has been asked of anyone
     * yet and the card is offering to turn them on.
     */
    data object Disabled : DictionaryState

    data object Loading : DictionaryState

    data class Found(val senses: List<DictionarySense>) : DictionaryState

    /** The word is not in Wiktionary — a normal outcome, not an error. */
    data object NotFound : DictionaryState

    data class Failed(val message: String?) : DictionaryState
}

/**
 * Looks words up on Wiktionary.
 *
 * This is the only request the app makes that is not to the reader's own
 * book server, and it happens only after the reader has turned definitions
 * on, and only when they tap Define on a word. The site is theirs to
 * choose: any Wiktionary edition or mirror answers the same endpoint.
 */
class WiktionaryClient(
    private val client: OkHttpClient = default(),
) {

    suspend fun define(
        word: String,
        languages: List<String>,
        baseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
    ): DictionaryState =
        withContext(Dispatchers.IO) {
            val term = normaliseLookupTerm(word)
            if (term.isBlank()) return@withContext DictionaryState.NotFound
            val result = lookup(term, languages, baseUrl)
            if (result !is DictionaryState.NotFound) return@withContext result
            val lowercaseTerm = term.lowercase()
            if (lowercaseTerm == term) return@withContext result
            lookup(lowercaseTerm, languages, baseUrl)
        }

    private fun lookup(
        term: String,
        languages: List<String>,
        baseUrl: String,
    ): DictionaryState {
        val request = Request.Builder()
            .url(DictionaryUrl.definitionApi(baseUrl, term))
            .header("Accept", "application/json")
            // Wikimedia answers 403 to requests without a descriptive
            // agent, so say who we are and where to complain.
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> DictionaryState.NotFound
                    !response.isSuccessful -> DictionaryState.Failed("HTTP ${response.code}")
                    else -> {
                        val senses = parseWiktionaryDefinitions(
                            response.body?.string().orEmpty(),
                            languages,
                        )
                        if (senses.isEmpty()) {
                            DictionaryState.NotFound
                        } else {
                            DictionaryState.Found(senses)
                        }
                    }
                }
            }
        }.getOrElse { DictionaryState.Failed(it.message) }
    }

    companion object {
        private val USER_AGENT =
            "Liseur/${BuildConfig.VERSION_NAME} " +
                "(https://github.com/chmouel/liseur; ebook reader)"

        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * Wiktionary keys definitions by language, so a French book should get
         * French senses first while still falling back to English.
         */
        fun languagesFor(publicationLanguage: String?): List<String> {
            val primary = publicationLanguage?.substringBefore('-')?.lowercase()
            return listOfNotNull(primary?.takeIf { it.isNotBlank() }, "en").distinct()
        }
    }
}
