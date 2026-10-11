package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import java.io.IOException
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
    private val expansionClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val expansion = checkNotNull(chain.request().tag(Expansion::class.java))
            if (expansion.requests++ >= MAX_EXPANSION_REQUESTS) throw ExpansionLimitReached()
            chain.proceed(chain.request())
        }
        .build()

    suspend fun define(
        word: String,
        languages: List<String>,
        baseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
        onInitialResult: suspend (DictionaryState.Found) -> Unit = {},
    ): DictionaryState =
        withContext(Dispatchers.IO) {
            val term = normaliseLookupTerm(word)
            if (term.isBlank()) return@withContext DictionaryState.NotFound
            val result = lookupWithLowercase(term, languages, baseUrl)
            if (result !is DictionaryState.Found) return@withContext result
            onInitialResult(result)
            expand(term, result, baseUrl)
        }

    private suspend fun lookupWithLowercase(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion? = null,
    ): DictionaryState {
        val result = lookup(term, languages, baseUrl, expansion)
        val lowercaseTerm = term.lowercase()
        return if (result == DictionaryState.NotFound && lowercaseTerm != term) {
            lookup(lowercaseTerm, languages, baseUrl, expansion)
        } else {
            result
        }
    }

    private suspend fun expand(
        term: String,
        original: DictionaryState.Found,
        baseUrl: String,
    ): DictionaryState.Found {
        val expansion = Expansion()
        val entries = mutableMapOf<DictionaryReference, DictionaryState>()
        return DictionaryState.Found(original.senses.map { sense ->
            val resolved = sense.references.filterKeys { it < MAX_CARD_DEFINITIONS }.mapValues { (_, reference) ->
                if (reference.term.equals(term, ignoreCase = true)) {
                    return@mapValues ResolvedDefinition(reference.term, problem = DefinitionProblem.UNRESOLVED)
                }
                val entry = try {
                    entries.getOrPut(reference) {
                        lookupWithLowercase(reference.term, listOf(reference.language), baseUrl, expansion)
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
    ): DictionaryState {
        val edition = WiktionaryEditions.editionOf(baseUrl)
        val nonEnglishEdition = WiktionaryEditions.isWiktionaryHost(baseUrl) &&
            (edition == null || edition.code != "en")
        if (nonEnglishEdition) return lookupEntryPage(term, languages, baseUrl, expansion)

        val definitions = lookupDefinitions(term, languages, baseUrl, expansion)
        // A mirror may only serve pages: on the 501 that marks a wiki
        // without the definition endpoint, try the page instead.
        return if (definitions is DictionaryState.Failed && definitions.message == "HTTP 501") {
            lookupEntryPage(term, languages, baseUrl, expansion)
        } else {
            definitions
        }
    }

    private suspend fun lookupDefinitions(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion?,
    ): DictionaryState =
        fetch(
            url = DictionaryUrl.definitionApi(baseUrl, term),
            baseUrl = baseUrl,
            accept = "application/json",
            expansion = expansion,
        ) { body ->
            parseWiktionaryDefinitions(body, languages)
        }

    private suspend fun lookupEntryPage(
        term: String,
        languages: List<String>,
        baseUrl: String,
        expansion: Expansion?,
    ): DictionaryState =
        fetch(
            url = DictionaryUrl.pageHtmlApi(baseUrl, term),
            baseUrl = baseUrl,
            accept = "text/html",
            expansion = expansion,
        ) { body ->
            parseWiktionaryEntryHtml(body, languages, WiktionaryEditions.editionOf(baseUrl)?.code)
        }

    private suspend fun fetch(
        url: String,
        baseUrl: String,
        accept: String,
        expansion: Expansion?,
        parse: (String) -> List<DictionarySense>,
    ): DictionaryState {
        val host = DictionaryUrl.hostOf(baseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .tag(Expansion::class.java, expansion)
            // Wikimedia answers 403 to requests without a descriptive
            // agent, so say who we are and where to complain.
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            val response = expansion?.responses?.get(url) ?: run {
                if (expansion != null && expansion.requests >= MAX_EXPANSION_REQUESTS) throw ExpansionLimitReached()
                request((if (expansion == null) client else expansionClient).newCall(request))
                    .also { expansion?.responses?.put(url, it) }
            }
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
    private class Expansion {
        var requests = 0
        val responses = mutableMapOf<String, DictionaryResponse>()
    }
    private class ExpansionLimitReached : IOException()

    companion object {
        private const val MAX_EXPANSION_REQUESTS = 4
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
