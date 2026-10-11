package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What the definition card is showing at any moment. */
sealed interface DictionaryState {
    /**
     * Definitions are switched off, so nothing has been asked of anyone
     * yet and the card is offering to turn them on.
     */
    data object Disabled : DictionaryState

    data object Loading : DictionaryState

    /** [term] is the entry the senses came from, lowercased when the word as written had none. */
    data class Found(val senses: List<DictionarySense>, val term: String = "") : DictionaryState

    /** The word is not in Wiktionary — a normal outcome, not an error. */
    data object NotFound : DictionaryState

    /**
     * The lookup failed, and [host] is who did not answer, so the card
     * can point at the dictionary setting rather than leave the reader
     * staring at a bare HTTP code.
     */
    data class Failed(val message: String?, val host: String? = null) : DictionaryState
}

/** What the translations sheet is showing. */
sealed interface TranslationsState {
    data object Loading : TranslationsState

    data class Found(val sections: List<TranslationSection>) : TranslationsState {
        /** The first word Wiktionary gives, for the most common sense. */
        val first: String? get() = sections.firstNotNullOfOrNull { s -> s.groups.firstNotNullOfOrNull { it.terms.firstOrNull() } }
    }

    /** The entry has no translations into the language, or does not tag them. */
    data object Empty : TranslationsState

    data class Failed(val message: String?, val host: String? = null) : TranslationsState
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
    private val expansionClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val expansion = checkNotNull(chain.request().tag(Expansion::class.java))
            if (expansion.requests++ >= expansion.limit) throw ExpansionLimitReached()
            chain.proceed(chain.request())
        }
        .build()

    /** The pages the last lookup fetched, so [translations] of its word can reuse the entry page. */
    @Volatile
    private var pages: MutableMap<String, DictionaryResponse> = Collections.synchronizedMap(mutableMapOf())

    suspend fun define(
        word: String,
        languages: List<String>,
        baseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
        strict: Boolean = false,
        onInitialResult: suspend (DictionaryState.Found) -> Unit = {},
    ): DictionaryState =
        withContext(Dispatchers.IO) {
            val term = normaliseLookupTerm(word)
            if (term.isBlank()) return@withContext DictionaryState.NotFound
            val cache = Collections.synchronizedMap(mutableMapOf<String, DictionaryResponse>())
            pages = cache
            val (foundTerm, lookedUp) = lookupWithLowercase(term, languages, baseUrl, cache = cache, strict = strict)
            val result = (lookedUp as? DictionaryState.Found)?.copy(term = foundTerm) ?: return@withContext lookedUp
            onInitialResult(result)
            expand(term, result, baseUrl)
        }

    private suspend fun lookupWithLowercase(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion? = null,
        cache: MutableMap<String, DictionaryResponse>? = expansion?.responses,
        strict: Boolean = false,
    ): Pair<String, DictionaryState> {
        val result = lookup(term, languages, baseUrl, expansion, cache, strict)
        val lowercaseTerm = term.lowercase()
        return if (result == DictionaryState.NotFound && lowercaseTerm != term) {
            lowercaseTerm to lookup(lowercaseTerm, languages, baseUrl, expansion, cache, strict)
        } else {
            term to result
        }
    }

    /**
     * Wiktionary's translations of [term] into [target], from its entry
     * on [baseUrl]. Only words in the edition's own language have
     * translation tables, and English moves long ones to a subpage, so
     * this is at most two requests: none for the entry page when the
     * lookup already fetched it, as other editions do.
     */
    suspend fun translations(term: String, baseUrl: String, target: String): TranslationsState =
        withContext(Dispatchers.IO) {
            val language = WiktionaryEditions.codeOf(baseUrl) ?: "en"
            val host = DictionaryUrl.hostOf(baseUrl)
            val budget = Expansion(MAX_TRANSLATION_REQUESTS, pages)
            val entry = try {
                respond(DictionaryUrl.pageHtmlApi(baseUrl, term), "text/html", budget, budget.responses)
            } catch (error: IOException) {
                return@withContext TranslationsState.Failed(error.message, host)
            }
            when {
                entry.code == 404 -> return@withContext TranslationsState.Empty
                entry.code !in 200..299 -> return@withContext TranslationsState.Failed("HTTP ${entry.code}", host)
            }
            var translations = parseWiktionaryTranslations(entry.body, language, target)
            var failure: TranslationsState.Failed? = null
            // Only the subpage of this very entry: a pointer elsewhere is not followed.
            val subpage = translations.subpage?.takeIf { it == term + SUBPAGE_SUFFIX }
            if (subpage != null) {
                try {
                    val response = respond(DictionaryUrl.pageHtmlApi(baseUrl, subpage), "text/html", budget, budget.responses)
                    if (response.code in 200..299) {
                        translations = translations.plus(parseWiktionaryTranslations(response.body, language, target))
                    } else {
                        failure = TranslationsState.Failed("HTTP ${response.code}", host)
                    }
                } catch (error: IOException) {
                    failure = TranslationsState.Failed(error.message, host)
                }
            }
            val sections = translations.sections.filter { it.groups.isNotEmpty() }
            when {
                sections.isNotEmpty() -> TranslationsState.Found(sections)
                failure != null -> failure
                else -> TranslationsState.Empty
            }
        }

    private suspend fun expand(
        term: String,
        original: DictionaryState.Found,
        baseUrl: String,
    ): DictionaryState.Found {
        val expansion = Expansion()
        val entries = mutableMapOf<DictionaryReference, DictionaryState>()
        return original.copy(senses = original.senses.map { sense ->
            val resolved = sense.references.filterKeys { it < MAX_CARD_DEFINITIONS }.mapValues { (_, reference) ->
                if (reference.term.equals(term, ignoreCase = true)) {
                    return@mapValues ResolvedDefinition(reference.term, problem = DefinitionProblem.UNRESOLVED)
                }
                val entry = try {
                    entries.getOrPut(reference) {
                        lookupWithLowercase(reference.term, listOf(reference.language), baseUrl, expansion).second
                    }
                } catch (_: ExpansionLimitReached) {
                    return@mapValues ResolvedDefinition(reference.term, problem = DefinitionProblem.LIMIT)
                }
                when (entry) {
                    is DictionaryState.Found -> {
                        val matching = entry.senses.filter {
                            it.language == reference.language &&
                                grammaticalPart(it.partOfSpeech) == grammaticalPart(sense.partOfSpeech)
                        }
                        val meanings = matching.mapNotNull { target ->
                            val definitions = target.definitions.filterIndexed { index, _ ->
                                index !in target.references
                            }
                            target.copy(definitions = definitions, references = emptyMap())
                                .takeIf { definitions.isNotEmpty() }
                        }
                        ResolvedDefinition(
                            reference.term, meanings,
                            if (meanings.isEmpty() || matching.any { it.references.isNotEmpty() }) {
                                DefinitionProblem.UNRESOLVED
                            } else null,
                        )
                    }
                    DictionaryState.NotFound ->
                        ResolvedDefinition(reference.term, problem = DefinitionProblem.MISSING)
                    else -> ResolvedDefinition(reference.term, problem = DefinitionProblem.FAILED)
                }
            }
            sense.copy(resolved = resolved)
        })
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

    private suspend fun lookup(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion? = null,
        cache: MutableMap<String, DictionaryResponse>? = expansion?.responses,
        strict: Boolean = false,
    ): DictionaryState {
        val edition = WiktionaryEditions.editionOf(baseUrl)
        val nonEnglishEdition = WiktionaryEditions.isWiktionaryHost(baseUrl) &&
            (edition == null || edition.code != "en")
        if (nonEnglishEdition) return lookupEntryPage(term, languages, baseUrl, expansion, cache, strict)

        val definitions = lookupDefinitions(term, languages, baseUrl, expansion, cache)
        // A mirror may only serve pages: on the 501 that marks a wiki
        // without the definition endpoint, try the page instead.
        return if (definitions is DictionaryState.Failed && definitions.message == "HTTP 501") {
            lookupEntryPage(term, languages, baseUrl, expansion, cache, strict)
        } else {
            definitions
        }
    }

    private suspend fun lookupDefinitions(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion?,
        cache: MutableMap<String, DictionaryResponse>?,
    ): DictionaryState =
        fetch(
            url = DictionaryUrl.definitionApi(baseUrl, term),
            baseUrl = baseUrl,
            accept = "application/json",
            expansion = expansion,
            cache = cache,
        ) { body ->
            parseWiktionaryDefinitions(body, languages)
        }

    private suspend fun lookupEntryPage(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion?,
        cache: MutableMap<String, DictionaryResponse>?,
        strict: Boolean = false,
    ): DictionaryState =
        fetch(
            url = DictionaryUrl.pageHtmlApi(baseUrl, term),
            baseUrl = baseUrl,
            accept = "text/html",
            expansion = expansion,
            cache = cache,
        ) { body ->
            parseWiktionaryEntryHtml(body, languages, WiktionaryEditions.editionOf(baseUrl)?.code, strict)
        }

    private suspend fun fetch(
        url: String,
        baseUrl: String,
        accept: String,
        expansion: Expansion?,
        cache: MutableMap<String, DictionaryResponse>?,
        parse: (String) -> List<DictionarySense>,
    ): DictionaryState {
        val host = DictionaryUrl.hostOf(baseUrl)
        return try {
            val response = respond(url, accept, expansion, cache)
            when {
                response.code == 404 -> DictionaryState.NotFound
                response.code !in 200..299 -> DictionaryState.Failed("HTTP ${response.code}", host)
                else -> {
                    val senses = parse(response.body)
                    if (senses.isEmpty()) DictionaryState.NotFound else DictionaryState.Found(senses)
                }
            }
        } catch (error: ExpansionLimitReached) {
            throw error
        } catch (error: IOException) {
            DictionaryState.Failed(error.message, host)
        }
    }

    private suspend fun respond(
        url: String,
        accept: String,
        expansion: Expansion?,
        cache: MutableMap<String, DictionaryResponse>?,
    ): DictionaryResponse {
        cache?.get(url)?.let { return it }
        if (expansion != null && expansion.requests >= expansion.limit) throw ExpansionLimitReached()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .tag(Expansion::class.java, expansion)
            // Wikimedia answers 403 to requests without a descriptive
            // agent, so say who we are and where to complain.
            .header("User-Agent", USER_AGENT)
            .build()
        return request((if (expansion == null) client else expansionClient).newCall(request))
            .also { cache?.put(url, it) }
    }

    private suspend fun request(call: Call): DictionaryResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    Result.success(response.use { DictionaryResponse(it.code, it.body.string()) })
                } catch (error: IOException) {
                    Result.failure(error)
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private data class DictionaryResponse(val code: Int, val body: String)
    private class Expansion(
        val limit: Int = MAX_EXPANSION_REQUESTS,
        val responses: MutableMap<String, DictionaryResponse> = mutableMapOf(),
    ) {
        var requests = 0
    }
    private class ExpansionLimitReached : IOException()

    companion object {
        private const val MAX_EXPANSION_REQUESTS = 4
        private const val MAX_TRANSLATION_REQUESTS = 2
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
