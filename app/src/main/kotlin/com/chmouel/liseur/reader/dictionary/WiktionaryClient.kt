package com.chmouel.liseur.reader.dictionary

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the definition card is showing at any moment. */
sealed interface DictionaryState {
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
 * calibre-web server, and it only happens when someone taps Define on a word.
 */
class WiktionaryClient(
    private val client: OkHttpClient = default(),
    private val baseUrl: String = "https://en.wiktionary.org/api/rest_v1/page/definition/",
) {

    suspend fun define(word: String, languages: List<String>): DictionaryState =
        withContext(Dispatchers.IO) {
            val term = normaliseLookupTerm(word)
            if (term.isBlank()) return@withContext DictionaryState.NotFound
            val url = baseUrl + java.net.URLEncoder.encode(term, "UTF-8").replace("+", "%20")
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // Wikimedia answers 403 to requests without a descriptive
                // agent, so say who we are and where to complain.
                .header("User-Agent", USER_AGENT)
                .build()
            runCatching {
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
        private const val USER_AGENT =
            "Liseur/1.0 (https://github.com/chmouel/liseur; ebook reader)"

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
