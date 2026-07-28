package com.chmouel.liseur.reader.progress

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder

/** A chapter of the book and the range of positions it covers. */
data class BookChapter(
    val title: String?,
    val firstPosition: Int,
    val lastPosition: Int,
)

/**
 * The page-like "positions" Readium computes for a book, grouped by
 * chapter. Positions are numbered from 1 and are stable for a given
 * book, whatever the font size, which makes them a good basis for
 * page numbers, the scrubber and time-left estimates.
 */
class BookPositions(
    private val locators: List<Locator>,
    val chapters: List<BookChapter>,
    private val chapterIndexByResource: Map<Int, Int>,
) {
    val totalPositions: Int get() = locators.size

    val isUsable: Boolean get() = locators.isNotEmpty()

    /** The chapter containing [position], or null when out of range. */
    fun chapterAt(position: Int): BookChapter? =
        chapters.firstOrNull { position in it.firstPosition..it.lastPosition }

    /** The chapter of the resource at [resourceIndex] in the reading order. */
    fun chapterOfResource(resourceIndex: Int): BookChapter? =
        chapterIndexByResource[resourceIndex]?.let(chapters::getOrNull)

    /** The locator to jump to for [position], numbered from 1. */
    fun locatorAt(position: Int): Locator? =
        locators.getOrNull(position.coerceIn(1, totalPositions) - 1)

    /** The position matching a whole-book progression between 0 and 1. */
    fun positionAtProgression(progression: Float): Int {
        if (!isUsable) return 1
        val index = (progression.coerceIn(0f, 1f) * (totalPositions - 1)).toInt()
        return index + 1
    }

    companion object {
        suspend fun of(publication: Publication): BookPositions {
            val byResource = publication.positionsByReadingOrder()
            val titles = publication.chapterTitles()
            val chapters = mutableListOf<BookChapter>()
            val chapterIndexByResource = mutableMapOf<Int, Int>()
            byResource.forEachIndexed { resourceIndex, positions ->
                val first = positions.firstOrNull()?.locations?.position
                val last = positions.lastOrNull()?.locations?.position
                if (first == null || last == null) return@forEachIndexed
                val title = titles[resourceIndex]
                    ?: publication.readingOrder.getOrNull(resourceIndex)?.title
                // Resources without a heading continue the previous
                // chapter, as split chapters are common in EPUBs.
                val previous = chapters.lastOrNull()
                if (title == null && previous != null) {
                    chapters[chapters.lastIndex] = previous.copy(lastPosition = last)
                } else {
                    chapters += BookChapter(title, first, last)
                }
                chapterIndexByResource[resourceIndex] = chapters.lastIndex
            }
            return BookPositions(byResource.flatten(), chapters, chapterIndexByResource)
        }

        /** Titles from the table of contents, keyed by reading order index. */
        private fun Publication.chapterTitles(): Map<Int, String> {
            // Contents entries often point inside a resource with a
            // #fragment, so resources are matched on their path alone.
            val indexByHref = readingOrder
                .mapIndexed { index, link -> link.resourcePath() to index }
                .toMap()
            return tableOfContents.flattenLinks()
                .mapNotNull { link ->
                    val title = link.title?.trim()?.takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val index = indexByHref[link.resourcePath()] ?: return@mapNotNull null
                    index to title
                }
                // Several entries may point at the same resource; the
                // first one names the chapter.
                .reversed()
                .toMap()
        }

        private fun Link.resourcePath(): String =
            url().toString().substringBefore('#').substringBefore('?')

        private fun List<Link>.flattenLinks(): List<Link> =
            flatMap { listOf(it) + it.children.flattenLinks() }
    }
}

/** Where the reader is in the book, and how much is left to read. */
data class ReaderProgress(
    val position: Int,
    val totalPositions: Int,
    val totalProgression: Float,
    val chapterTitle: String?,
    val minutesLeftInChapter: Int,
    val minutesLeftInBook: Int,
    val isSpeedMeasured: Boolean,
) {
    val percent: Int get() = (totalProgression * 100).toInt().coerceIn(0, 100)
}
