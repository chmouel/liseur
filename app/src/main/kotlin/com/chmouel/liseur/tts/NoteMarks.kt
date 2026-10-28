@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package com.chmouel.liseur.tts

import com.chmouel.liseur.reader.footnotes.NoteVocabulary
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.content.DefaultContentService
import org.readium.r2.shared.publication.services.content.iterators.HtmlResourceContentIterator
import org.readium.r2.shared.publication.services.content.iterators.ResourceContentIteratorFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.resource.map

/**
 * The numbers and signs a book's text points at its notes with, which
 * read aloud skips: heard in the middle of a sentence, they are noise.
 * The notes themselves are still read where they stand.
 */
internal object NoteMarks {

    // A bare note mark: a number, a footnote sign, a roman numeral or one letter, maybe bracketed.
    private val MARK = Regex("""^[\[(]?(\d+|[*\u2020\u2021\u00a7]+|[ivxlc]+|[a-z])[\])]?$""", RegexOption.IGNORE_CASE)

    /** Whether link [a] is a reference to a note: labelled as one, or a mark set in superscript. */
    fun isMark(a: Element): Boolean {
        if (NoteVocabulary.isNoteRef(a.attr("epub:type"), a.attr("role"))) return true
        val raised = a.closest("sup") != null || a.selectFirst("sup") != null
        return raised && MARK.matches(a.text().trim())
    }

    /**
     * [html] with the text of every note mark taken out, and of the
     * superscript holding it when that holds nothing else. Elements stay
     * where they are, so the selectors of what is read still find it on
     * the page. [html] itself when it has no mark.
     */
    fun strip(html: String): String {
        val document = Jsoup.parse(html)
        val marks = document.select("a").filter(::isMark)
        if (marks.isEmpty()) return html
        for (a in marks) {
            val target = a.closest("sup")?.takeIf { MARK.matches(it.text().trim()) } ?: a
            for (element in target.select("*")) element.textNodes().forEach { it.remove() }
        }
        document.outputSettings().prettyPrint(false)
        return document.outerHtml()
    }

    /**
     * [publication] as read aloud sees it, without note marks: the same
     * book, positions and resources, with its text cut from a copy of each
     * chapter whose marks are taken out. Shares [publication]'s resources,
     * so it is never closed itself.
     */
    @OptIn(ExperimentalReadiumApi::class)
    fun withoutMarks(publication: Publication): Publication {
        val html = HtmlResourceContentIterator.Factory()
        val factory = ResourceContentIteratorFactory { manifest, _, index, resource, mediaType, locator ->
            val stripped = resource.map { bytes -> Try.success(strip(bytes.decodeToString()).encodeToByteArray()) }
            html.create(manifest, publication, index, stripped, mediaType, locator)
        }
        return Publication(
            manifest = publication.manifest,
            container = publication.container,
            servicesBuilder = Publication.ServicesBuilder(
                content = DefaultContentService.createFactory(listOf(factory)),
                positions = {
                    object : PositionsService {
                        override suspend fun positionsByReadingOrder() = publication.positionsByReadingOrder()
                    }
                },
            ),
        )
    }
}
