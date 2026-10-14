package com.chmouel.liseur.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.use

/**
 * The open book's passages and chapters, walked from the start as far as
 * a lookup needs and kept for the rest of the reading session.
 */
@OptIn(ExperimentalReadiumApi::class)
class PublicationCatalog(private val publication: Publication) : BookCatalog<Locator> {
    private val readingOrder = publication.readingOrder.map { it.url().path() }
    override val map: ChapterMap = ChapterMap.of(readingOrder, publication.tableOfContents.map(::node))
    private val builder = PassageCatalogBuilder<Locator>(map, readingOrder, ::html)
    private val walked = mutableListOf<Passage<Locator>>()
    private val lock = Mutex()
    private var elements: Content.Iterator? = null
    private var finished = false

    override suspend fun scopeOf(place: Locator, translated: Boolean): AskingScope? = withContext(Dispatchers.IO) {
        val path = place.href.toString().path()
        val listed = readingOrder.indices.filter { readingOrder[it] == path }
        val resource = listed.firstOrNull() ?: return@withContext null
        val selector = place.locations.otherLocations[CSS_SELECTOR] as? String
        val range = lock.withLock { builder.rangeOf(resource, selector.takeUnless { translated }) }
        AskingScope(range.lo, map.chapterCount)
    }

    override suspend fun passagesBefore(scope: AskingScope): List<Passage<Locator>> = withContext(Dispatchers.IO) {
        lock.withLock {
            while (!finished && (builder.current < 0 || map.floorFrom(builder.current) < scope.lo)) {
                val iterator = elements ?: checkNotNull(publication.content()) { "No content service" }.iterator()
                    .also { elements = it }
                val element = iterator.nextOrNull()
                if (element == null) {
                    finished = true
                    break
                }
                source(element)?.let { walked += builder.add(it) }
            }
            walked.filter { Eligibility.allows(scope, it.range) }
        }
    }

    override suspend fun passagesOf(unit: Int): List<Passage<Locator>> =
        passagesBefore(AskingScope(unit + 1, map.chapterCount)).filter { it.range.hi == unit }

    private fun source(element: Content.Element): SourceElement<Locator>? {
        val text = when (element) {
            is Content.TextElement -> element.segments.joinToString("") { it.text }
            is Content.TextualElement -> element.text
            else -> null
        }?.takeIf { it.any(Char::isLetterOrDigit) } ?: return null
        val locator = element.locator
        return SourceElement(
            href = locator.href.toString().path(),
            selector = locator.locations.otherLocations[CSS_SELECTOR] as? String,
            text = text,
            place = locator,
        )
    }

    private suspend fun html(resource: Int): String? = withContext(Dispatchers.IO) {
        val link = publication.readingOrder.getOrNull(resource) ?: return@withContext null
        publication.get(link)?.use { it.read().getOrNull()?.decodeToString() }
    }

    private fun node(link: Link): TocNode {
        val url = link.url().toString()
        val fragment = url.substringAfter('#', "")
        return TocNode(
            href = url.path() + if (fragment.isNotBlank()) "#$fragment" else "",
            children = link.children.map(::node),
            title = link.title,
        )
    }

    private companion object {
        const val CSS_SELECTOR = "cssSelector"

        fun Url.path(): String = toString().path()

        fun String.path(): String = substringBefore('#').substringBefore('?')
    }
}

/** A book's passages and chapters, as the lookup and the recaps see them. */
interface BookCatalog<P> {
    val map: ChapterMap

    /** The chapters a lookup asked at [place] may draw on; [translated] when the page shows a translation. */
    suspend fun scopeOf(place: P, translated: Boolean): AskingScope?

    /** Every passage eligible for [scope], in reading order. */
    suspend fun passagesBefore(scope: AskingScope): List<Passage<P>>

    /**
     * Every passage whose highest possible chapter is [unit]: the text a
     * recap of that chapter covers, including any stretch that may also
     * belong to the chapter before it.
     */
    suspend fun passagesOf(unit: Int): List<Passage<P>>
}
