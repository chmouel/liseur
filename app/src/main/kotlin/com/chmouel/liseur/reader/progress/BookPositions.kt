package com.chmouel.liseur.reader.progress

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import kotlin.math.floor
import kotlin.math.roundToInt

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
    positionsByResource: List<List<Locator>> = listOf(locators),
) {
    private val resources = positionsByResource.mapIndexedNotNull { index, positions ->
        positions.takeIf { it.isNotEmpty() }?.let {
            val nextPosition = positionsByResource
                .drop(index + 1)
                .firstNotNullOfOrNull { resource ->
                    resource.firstNotNullOfOrNull { locator -> locator.locations.position }
                }
                ?: locators.size
            ResourcePositions(index, it, nextPosition)
        }
    }

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

    /**
     * A layout-independent fractional Readium position for [locator].
     *
     * Navigator pages are finer than Readium's integer positions, so the
     * resource progression is interpolated between the stable synthetic
     * locators. The integer position is only a fallback and a hint when an
     * EPUB contains the same href more than once in its reading order.
     */
    fun resolve(locator: Locator): StableBookProgress? {
        if (!isUsable) return null
        val href = locator.href.toString()
        val candidates = resources.filter { it.href == href }
        val resource = candidates.withAnchor(locator.locations.position)
            ?: return locator.locations.position
                ?.coerceIn(1, totalPositions)
                ?.toDouble()
                ?.let(::progressAtCoordinate)

        val progression = locator.locations.progression
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0)
        val coordinate = progression
            ?.let(resource::coordinateAt)
            ?: locator.locations.position?.toDouble()
            ?: resource.firstPosition.toDouble()
        return progressAtCoordinate(coordinate)
    }

    /** The position matching a whole-book progression between 0 and 1. */
    fun positionAtProgression(progression: Float): Int {
        if (!isUsable) return 1
        if (totalPositions == 1) return 1
        return (1 + progression.coerceIn(0f, 1f) * (totalPositions - 1))
            .roundToInt()
            .coerceIn(1, totalPositions)
    }

    /**
     * A conservative synthetic locator which never starts after [progression].
     * Used when an exact text quote is unavailable or belongs to another edition.
     */
    fun locatorAtOrBeforeProgression(progression: Double): Locator? {
        if (!isUsable) return null
        if (totalPositions == 1) return locators.first()
        val coordinate = 1.0 + progression.coerceIn(0.0, 1.0) * (totalPositions - 1)
        val position = floor(coordinate).toInt().coerceIn(1, totalPositions)
        return locatorAt(position)
    }

    private fun progressAtCoordinate(coordinate: Double): StableBookProgress {
        val bounded = coordinate
            .takeIf { it.isFinite() }
            ?.coerceIn(1.0, totalPositions.toDouble())
            ?: 1.0
        val progression = if (totalPositions <= 1) {
            0.0
        } else {
            (bounded - 1) / (totalPositions - 1)
        }
        return StableBookProgress(
            coordinate = bounded,
            position = bounded.roundToInt().coerceIn(1, totalPositions),
            totalPositions = totalPositions,
            progression = progression,
        )
    }

    private fun List<ResourcePositions>.withAnchor(position: Int?): ResourcePositions? {
        if (isEmpty()) return null
        if (size == 1 || position == null) return first()
        return firstOrNull { position in it.firstPosition..it.lastPosition } ?: first()
    }

    private inner class ResourcePositions(
        val index: Int,
        val positions: List<Locator>,
        nextPosition: Int,
    ) {
        val href: String = positions.first().href.toString()
        val firstPosition: Int =
            positions.firstNotNullOfOrNull { it.locations.position } ?: 1
        val lastPosition: Int =
            positions.asReversed().firstNotNullOfOrNull { it.locations.position }
                ?: firstPosition

        private val anchors: List<Pair<Double, Double>> = buildList {
            add(0.0 to firstPosition.toDouble())
            positions.forEach { locator ->
                val progression = locator.locations.progression
                    ?.takeIf { it.isFinite() }
                    ?.coerceIn(0.0, 1.0)
                    ?: return@forEach
                val position = locator.locations.position
                    ?.coerceIn(1, totalPositions)
                    ?.toDouble()
                    ?: return@forEach
                add(progression to position)
            }
            add(1.0 to nextPosition.coerceIn(1, totalPositions).toDouble())
        }.sortedBy { it.first }
            .fold(mutableListOf()) { distinct, anchor ->
                if (distinct.lastOrNull()?.first == anchor.first) {
                    distinct[distinct.lastIndex] = anchor
                } else {
                    distinct += anchor
                }
                distinct
            }

        fun coordinateAt(progression: Double): Double {
            val upperIndex = anchors.indexOfFirst { it.first >= progression }
            if (upperIndex <= 0) return anchors.first().second
            if (upperIndex < 0) return anchors.last().second
            val lower = anchors[upperIndex - 1]
            val upper = anchors[upperIndex]
            val width = upper.first - lower.first
            if (width <= 0.0) return upper.second
            val fraction = (progression - lower.first) / width
            return lower.second + (upper.second - lower.second) * fraction
        }
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
            return BookPositions(
                locators = byResource.flatten(),
                chapters = chapters,
                chapterIndexByResource = chapterIndexByResource,
                positionsByResource = byResource,
            )
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

/** A stable local coordinate derived from Readium's synthetic positions. */
data class StableBookProgress(
    val coordinate: Double,
    val position: Int,
    val totalPositions: Int,
    val progression: Double,
)

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
