package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.TranslationContextSize
import com.chmouel.liseur.translate.BookFacts
import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PrecedingText
import com.chmouel.liseur.translate.TranslationBookContext
import com.chmouel.liseur.translate.TranslationContext
import com.chmouel.liseur.translate.TranslationLanguages
import java.security.MessageDigest

/**
 * What a speech service may be told about where an utterance sits in its
 * book, in its non-spoken instructions field: the rendered `<book>` block
 * from the shared template and the bounded `<context>` window of preceding
 * source-language sentences in the same chapter file.
 *
 * The spoken `input` always stays the exact utterance text.
 */
internal class SpeechContext(
    private val preceding: PrecedingText,
    private val bookFacts: suspend (href: String) -> BookFacts?,
    private val sourceLanguage: String? = null,
    private val spokenLanguage: () -> String? = { null },
    private val configuration: suspend () -> Configuration = { Configuration.OFF },
) {
    /**
     * What the current settings and active speech service allow for
     * reference context: off when the reader has not enabled it or the
     * selected speech model cannot take non-spoken context.
     */
    data class Configuration(
        val enabled: Boolean,
        val size: TranslationContextSize = TranslationContextSize.OFF,
        val template: String? = null,
    ) {
        val active: Boolean
            get() = enabled && (size != TranslationContextSize.OFF || template?.trim()?.isEmpty() != true)

        val identity: String?
            get() {
                if (!active) return null
                val window = size.takeIf { it != TranslationContextSize.OFF }?.let { "context=${it.id}" }.orEmpty()
                val resolvedTemplate = TranslationBookContext.template(template)
                val about = resolvedTemplate?.let { "book=${TranslationBookContext.identity(it)}" }.orEmpty()
                return "$VERSION\u0000$window\u0000$about"
            }

        companion object {
            val OFF = Configuration(enabled = false)
            private const val VERSION = "speech-context-1"
        }
    }

    /** The active context identity for [SpeechKey], or null when speech context is inactive. */
    suspend fun identity(): String? = configuration().identity

    /**
     * Resolves the non-spoken instructions for [key], using [previous] only
     * when the sentence cannot be located in its chapter file. Returns null
     * when speech context is off, unsupported, or resolves to nothing.
     */
    suspend fun resolve(key: SpeechKey, previous: String? = null): String? {
        val config = configuration()
        if (!config.active) return null
        val place = key.place
        val facts = place?.href?.let { bookFacts(it) }
        val resolvedTemplate = TranslationBookContext.template(config.template)
        val book = TranslationBookContext.render(resolvedTemplate, facts)
        val window = when {
            config.size == TranslationContextSize.OFF -> null
            place != null -> when (val found = preceding.before(place, config.size.sentences)) {
                // An empty lookup is a chapter's start: [previous] may be the chapter before's.
                null -> previous?.let { TranslationContext.window(listOf(PageSentence(it, place.href, null, null)), config.size) }
                else -> TranslationContext.window(found, config.size)
            }
            previous != null -> TranslationContext.window(listOf(PageSentence(previous, "", null, null)), config.size)
            else -> null
        }
        return instructions(
            book = book,
            context = window,
            sourceLanguage = sourceLanguage ?: facts?.language,
            spokenLanguage = spokenLanguage(),
        )
    }

    companion object {
        private const val BOOK_OPEN = "<book>"
        private const val BOOK_CLOSE = "</book>"
        private const val CONTEXT_OPEN = "<context>"
        private const val CONTEXT_CLOSE = "</context>"
        private val MARKER = Regex("<(/?(?:context|book))", RegexOption.IGNORE_CASE)

        /**
         * Formats non-spoken TTS instructions from [book] details and
         * earlier [context], escaping any marker tags inside book data.
         * Null when both [book] and [context] are null or blank.
         */
        fun instructions(
            book: String?,
            context: String?,
            sourceLanguage: String? = null,
            spokenLanguage: String? = null,
        ): String? {
            val cleanBook = book?.trim()?.takeIf { it.isNotEmpty() }
            val cleanContext = context?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanBook == null && cleanContext == null) return null
            val sourceLabel = sourceLanguage?.let(TranslationLanguages::of)?.let {
                "${TranslationLanguages.englishName(it)} ($it)"
            }
            val spokenLabel = spokenLanguage?.let(TranslationLanguages::of)?.let {
                "${TranslationLanguages.englishName(it)} ($it)"
            }
            val languageNote = when {
                sourceLabel != null && spokenLabel != null && sourceLanguage != spokenLanguage ->
                    "The book's original language is $sourceLabel; the spoken input is translated into $spokenLabel. "
                sourceLabel != null ->
                    "The book's language is $sourceLabel. "
                else -> ""
            }
            val about = cleanBook?.let { "$BOOK_OPEN\n${safe(it)}\n$BOOK_CLOSE\n" }.orEmpty()
            val before = cleanContext?.let { "$CONTEXT_OPEN\n${safe(it)}\n$CONTEXT_CLOSE\n" }.orEmpty()
            return "Read the input text aloud verbatim. Do not add, omit, translate, or paraphrase any words, " +
                "and never speak the reference blocks below. " +
                languageNote +
                "Use the reference blocks only to infer tone, character voice, and pronunciation of names and ambiguous words.\n" +
                about +
                before.trimEnd()
        }

        /** Hash of rendered instructions for in-memory audio cache keys. */
        fun digest(instructions: String?): String? {
            val text = instructions?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun safe(text: String): String = text.replace(MARKER, "<\u200B$1")
    }
}
