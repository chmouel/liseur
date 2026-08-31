package com.chmouel.liseur.reader.progress

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import kotlin.math.ceil

/** Which page number the go-to-page dialog asks the reader for. */
enum class PageNumbering {
    PRINTED,
    POSITION,
}

/** The wording, accepted endpoints and starting point of a go-to dialog. */
data class GoToPagePrompt(
    val numbering: PageNumbering,
    val firstLabel: String,
    val lastLabel: String,
    val currentLabel: String,
)

/** A valid destination together with the chapter the dialog can preview. */
data class GoToDestination(
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

    private val numbering: PageNumbering =
        if (orderedPrintedPages.isEmpty()) PageNumbering.POSITION else PageNumbering.PRINTED
    private val firstLabel: String =
        orderedPrintedPages.firstOrNull()?.first ?: "1"
    private val lastLabel: String =
        orderedPrintedPages.lastOrNull()?.first ?: positions.totalPositions.toString()

    /**
     * Where each printed page falls in the book, in reading order.
     *
     * Built on first use: a page list runs to a mark per printed page and
     * every one of them costs a locator resolution, which is worth paying
     * once the reader opens the dialog and not on every book that is
     * merely opened. Marks sharing a position keep the earliest label, so
     * a chapter whose marks all land on its first position reads as the
     * page it starts on rather than the page it ends on.
     */
    private val printedPagesByPosition: List<Pair<Int, String>> by lazy {
        orderedPrintedPages
            .mapNotNull { (label, link) ->
                locatorFromLink(link)
                    ?.let(positions::resolve)
                    ?.position
                    ?.let { it to label }
            }
            .sortedBy { it.first }
            .distinctBy { it.first }
    }

    /** The dialog to show for a reader currently on [position]. */
    fun promptAt(position: Int): GoToPagePrompt = GoToPagePrompt(
        numbering = numbering,
        firstLabel = firstLabel,
        lastLabel = lastLabel,
        currentLabel = labelAt(position),
    )

    /**
     * The page the reader is on, in the numbering the dialog asks for.
     *
     * A printed page list rarely starts at the first position — a cover
     * and a title page are before page one is printed — so a reader ahead
     * of no mark at all is told the first page the book prints.
     */
    private fun labelAt(position: Int): String = when (numbering) {
        PageNumbering.POSITION ->
            position.coerceIn(1, positions.totalPositions.coerceAtLeast(1)).toString()

        PageNumbering.PRINTED ->
            printedPagesByPosition.lastOrNull { it.first <= position }?.second ?: firstLabel
    }

    /** Returns null when [answer] is not a page this book declares. */
    fun resolve(answer: String): GoToDestination? {
        val label = answer.trim()
        val locator = when (numbering) {
            PageNumbering.PRINTED -> printedPages[label]?.let(locatorFromLink)
            PageNumbering.POSITION -> {
                val position = label.toIntOrNull()
                    ?.takeIf { it in 1..positions.totalPositions }
                    ?: return null
                positions.locatorAt(position)
            }
        } ?: return null
        return positions.destinationAt(locator)
    }
}

/**
 * Resolves a whole percentage of the book a reader can type.
 *
 * The answer is the *first* position the footer would call that
 * percentage, which is the one promise worth keeping here: type the
 * number the footer is showing and the footer still shows it. Rounding
 * the other way lands a page short of the mark, and since the footer
 * truncates, typing 70 would leave 69% on screen — an answer a reader
 * can only read as a refusal, and retype forever.
 *
 * It is also the conservative direction for the prefilled answer.
 * Confirming it untouched goes to where that percentage begins, which is
 * at or a little before where the reader already is, never past it.
 *
 * Decimals are refused: on a five hundred page book a tenth of a percent
 * is half a page, and that precision is what the page dialog is for.
 */
fun goToPercent(answer: String, positions: BookPositions): GoToDestination? {
    val percent = answer.trim().toIntOrNull()?.takeIf { it in 0..100 } ?: return null
    val total = positions.totalPositions
    if (total < 1) return null
    val coordinate = 1.0 + percent / 100.0 * (total - 1)
    val locator = positions.locatorAt(ceil(coordinate).toInt()) ?: return null
    return positions.destinationAt(locator)
}

private fun BookPositions.destinationAt(locator: Locator): GoToDestination = GoToDestination(
    locator = locator,
    chapterTitle = resolve(locator)?.position?.let { chapterAt(it) }?.title,
)
