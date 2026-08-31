package com.chmouel.liseur.reader.progress

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** Which page number the go-to-page dialog asks the reader for. */
enum class PageNumbering {
    PRINTED,
    POSITION,
}

/** The wording and accepted endpoints shown by the go-to-page dialog. */
data class GoToPagePrompt(
    val numbering: PageNumbering,
    val firstLabel: String,
    val lastLabel: String,
)

/** A valid page together with the chapter the dialog can preview. */
data class GoToPageDestination(
    val locator: Locator,
    val chapterTitle: String?,
)

/**
 * Resolves the page labels a reader can type.
 *
 * An EPUB page list is deliberately kept as labels: roman numerals,
 * missing numbers and repeated labels are all valid. When labels repeat,
 * the first page with that label wins, matching the order of the book.
 */
class GoToPageResolver(
    pageList: List<Link>,
    private val positions: BookPositions,
    private val locatorFromLink: (Link) -> Locator?,
) {
    private val orderedPrintedPages: List<Pair<String, Link>> = pageList.mapNotNull { link ->
        link.title?.trim()?.takeIf(String::isNotEmpty)?.let { it to link }
    }
    private val printedPages: Map<String, Link> = buildMap {
        orderedPrintedPages.forEach { (label, link) -> putIfAbsent(label, link) }
    }

    val prompt: GoToPagePrompt = if (orderedPrintedPages.isNotEmpty()) {
        GoToPagePrompt(
            numbering = PageNumbering.PRINTED,
            firstLabel = orderedPrintedPages.first().first,
            lastLabel = orderedPrintedPages.last().first,
        )
    } else {
        GoToPagePrompt(
            numbering = PageNumbering.POSITION,
            firstLabel = "1",
            lastLabel = positions.totalPositions.toString(),
        )
    }

    /** Returns null when [answer] is not a page this book declares. */
    fun resolve(answer: String): GoToPageDestination? {
        val label = answer.trim()
        val locator = when (prompt.numbering) {
            PageNumbering.PRINTED -> printedPages[label]?.let(locatorFromLink)
            PageNumbering.POSITION -> {
                val position = label.toIntOrNull()
                    ?.takeIf { it in 1..positions.totalPositions }
                    ?: return null
                positions.locatorAt(position)
            }
        } ?: return null
        val chapter = positions.resolve(locator)
            ?.position
            ?.let(positions::chapterAt)
            ?.title
        return GoToPageDestination(locator, chapter)
    }
}
