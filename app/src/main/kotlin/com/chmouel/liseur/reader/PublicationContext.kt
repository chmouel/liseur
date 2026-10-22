package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.BookFacts
import com.chmouel.liseur.translate.PageSentences
import com.chmouel.liseur.translate.PrecedingSentences
import com.chmouel.liseur.translate.PrecedingText
import com.chmouel.liseur.translate.TranslationLanguages
import com.chmouel.liseur.tts.BoundedSentenceTokenizer
import com.chmouel.liseur.tts.PublicationUtteranceCursor
import com.chmouel.liseur.tts.SpeechKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Language

/**
 * Where a passage sits in its book, shared by translation and read aloud:
 * the book's sentences cut as read aloud cuts them, the sentences before
 * one in its chapter file, and the facts known of each chapter file.
 */
internal object PublicationContext {
    /** The sentences from [place] onwards, cut as read aloud cuts them. */
    fun sentences(publication: Publication, place: Locator, source: String?): PageSentences {
        val cursor = PublicationUtteranceCursor(
            publication,
            place,
            BoundedSentenceTokenizer.factory(1),
            source?.let(::Language),
            overrideContentLanguage = false,
        )
        return PageSentences {
            val (text, locator) = cursor.nextLocated() ?: return@PageSentences null
            // Named as read aloud names it, so the two share each sentence's place.
            SpeechKey.sentenceAt(text.text, locator)
        }
    }

    /**
     * The book's sentences before one, read from the start of its chapter
     * file the way [sentences] reads them, for the text a translation or
     * speech request takes with it.
     */
    fun preceding(publication: Publication, source: String?): PrecedingText = PrecedingSentences(open = { href ->
        publication.readingOrder
            .firstOrNull { ResourceAddress.canonicalPath(it.url().toString()) == href }
            ?.let { publication.locatorFromLink(it) }
            ?.let { sentences(publication, it, source) }
    })

    /**
     * What an AI translator or speech service may be told about each
     * chapter file of [publication], by the path a [PageSentence] names
     * it with: worked out once, the first time it is asked.
     */
    fun bookFacts(publication: Publication): suspend (href: String) -> BookFacts? {
        val lock = Mutex()
        var known: Map<String, BookFacts>? = null
        return { href -> lock.withLock { known ?: factsOf(publication).also { known = it } }[href] }
    }

    private suspend fun factsOf(publication: Publication): Map<String, BookFacts> {
        val hrefs = publication.readingOrder.map { pathOf(it) }
        val indexes = HashMap<String, Int>()
        hrefs.forEachIndexed { index, href -> indexes.putIfAbsent(href, index) }
        val titles = HashMap<Int, String>()
        // The first contents entry pointing into a file names it, even at a #fragment.
        fun walk(links: List<Link>) {
            for (link in links) {
                val title = link.title?.trim()?.takeIf(String::isNotEmpty)
                val index = indexes[pathOf(link)]
                if (title != null && index != null) titles.putIfAbsent(index, title)
                walk(link.children)
            }
        }
        walk(publication.tableOfContents)
        publication.readingOrder.forEachIndexed { index, link ->
            link.title?.trim()?.takeIf(String::isNotEmpty)?.let { titles.putIfAbsent(index, it) }
        }
        // Without positions the model is only not told how far in the book it is.
        val starts = try {
            publication.positionsByReadingOrder().map { it.firstOrNull()?.locations?.totalProgression }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val metadata = publication.metadata
        val language = TranslationLanguages.source(metadata.languages)
            ?.let { "${TranslationLanguages.englishName(it)} ($it)" }
        return BookFacts.byResource(
            title = metadata.title,
            author = metadata.authors.joinToString(", ") { it.name }.ifBlank { null },
            hrefs = hrefs,
            titles = titles,
            starts = starts,
            language = language,
        )
    }

    private fun pathOf(link: Link): String = link.url().toString().let { ResourceAddress.canonicalPath(it) ?: it }
}
