package com.chmouel.liseur.tts

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.TextTokenizer

/**
 * The book's sentences from a place onwards, cut and filtered exactly as
 * Readium's own (internal) TtsUtteranceIterator does for the navigator, so
 * what is prefetched is what will be spoken. Both must be given the same
 * [tokenizerFactory] and language settings.
 */
@OptIn(ExperimentalReadiumApi::class)
class PublicationUtteranceCursor(
    publication: Publication,
    locator: Locator,
    private val tokenizerFactory: (Language?) -> TextTokenizer,
    private val language: Language?,
    private val overrideContentLanguage: Boolean,
) : UtteranceCursor {
    private val elements = checkNotNull(publication.content(locator)) { "No content service" }.iterator()
    private val pending = ArrayDeque<Pair<UtteranceText, Locator>>()

    override suspend fun next(): UtteranceText? = nextLocated()?.first

    /** The next sentence with the locator Readium gave it, which names its element and the text before it. */
    suspend fun nextLocated(): Pair<UtteranceText, Locator>? {
        while (pending.isEmpty()) {
            val element = elements.nextOrNull() ?: return null
            TextContentTokenizer(
                language = language,
                textTokenizerFactory = tokenizerFactory,
                overrideContentLanguage = overrideContentLanguage,
            ).tokenize(element).flatMapTo(pending) { it.utterances() }
        }
        return pending.removeFirst()
    }

    private fun Content.Element.utterances(): List<Pair<UtteranceText, Locator>> = when (this) {
        is Content.TextElement -> segments.mapNotNull { utterance(it.text, it.locator) }
        is Content.TextualElement -> listOfNotNull(text?.takeIf { it.isNotBlank() }?.let { utterance(it, locator) })
        else -> emptyList()
    }

    private fun utterance(text: String, locator: Locator): Pair<UtteranceText, Locator>? =
        if (text.any { it.isLetterOrDigit() }) UtteranceText(text, locator.text.before) to locator else null
}
