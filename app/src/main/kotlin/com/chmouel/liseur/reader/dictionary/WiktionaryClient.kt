package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
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

    /**
     * The lookup failed, and [host] is who did not answer, so the card
     * can point at the dictionary setting rather than leave the reader
     * staring at a bare HTTP code.
     */
    data class Failed(val message: String?, val host: String? = null) : DictionaryState
}

/**
 * Looks words up on Wiktionary.
 *
 * This is the only request the app makes that is not to the reader's own
 * book server, and it happens only after the reader has turned definitions
 * on, and only when they tap Define on a word. The site is theirs to
 * choose — any Wiktionary edition, or a mirror.
 *
 * The tidy JSON endpoint (`page/definition`) is implemented only on
 * en.wiktionary.org; every other edition answers it with a 501. So the
 * English edition is asked for JSON, the others for their entry page as
 * Parsoid HTML, and an unknown host gets the JSON endpoint first with the
 * page as the fallback.
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

    /**
     * Whether [baseUrl] answers as a dictionary at all, checked with a
     * word every edition has an entry for. Null when it does; what went
     * wrong when it does not — so a typo in the settings field fails
     * right there, under the field, not later in the reader as a bare
     * HTTP code (a misspelling of fr.wiktionary.org earned this check).
     */
    suspend fun probe(baseUrl: String): String? =
        withContext(Dispatchers.IO) {
            when (val state = lookup(PROBE_WORD, emptyList(), baseUrl)) {
                is DictionaryState.Failed -> state.message ?: "error"
                // Even NotFound means a dictionary answered the endpoint.
                else -> null
            }
        }

    private fun lookup(
        term: String,
        languages: List<String>,
        baseUrl: String,
    ): DictionaryState {
        val edition = WiktionaryEditions.editionOf(baseUrl)
        val nonEnglishEdition = WiktionaryEditions.isWiktionaryHost(baseUrl) &&
            (edition == null || edition.code != "en")
        if (nonEnglishEdition) return lookupEntryPage(term, languages, baseUrl)

        val definitions = lookupDefinitions(term, languages, baseUrl)
        // A mirror may only serve pages: on the 501 that marks a wiki
        // without the definition endpoint, try the page instead.
        return if (definitions is DictionaryState.Failed && definitions.message == "HTTP 501") {
            lookupEntryPage(term, languages, baseUrl)
        } else {
            definitions
        }
    }

    private fun lookupDefinitions(
        term: String,
        languages: List<String>,
        baseUrl: String,
    ): DictionaryState =
        fetch(DictionaryUrl.definitionApi(baseUrl, term), baseUrl) { body ->
            parseWiktionaryDefinitions(body, languages)
        }

    private fun lookupEntryPage(
        term: String,
        languages: List<String>,
        baseUrl: String,
    ): DictionaryState =
        fetch(DictionaryUrl.pageHtmlApi(baseUrl, term), baseUrl) { body ->
            parseWiktionaryEntryHtml(body, languages)
        }

    private fun fetch(
        url: String,
        baseUrl: String,
        parse: (String) -> List<DictionarySense>,
    ): DictionaryState {
        val host = DictionaryUrl.hostOf(baseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // Wikimedia answers 403 to requests without a descriptive
            // agent, so say who we are and where to complain.
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> DictionaryState.NotFound
                    !response.isSuccessful ->
                        DictionaryState.Failed("HTTP ${response.code}", host)
                    else -> {
                        val senses = parse(response.body?.string().orEmpty())
                        if (senses.isEmpty()) {
                            DictionaryState.NotFound
                        } else {
                            DictionaryState.Found(senses)
                        }
                    }
                }
            }
        }.getOrElse { DictionaryState.Failed(it.message, host) }
    }

    companion object {
        private val USER_AGENT =
            "Liseur/${BuildConfig.VERSION_NAME} " +
                "(https://github.com/chmouel/liseur; ebook reader)"

        /** In every edition, and short: what [probe] asks for. */
        private const val PROBE_WORD = "book"

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
