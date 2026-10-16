package com.chmouel.liseur.companion

/** One text element as the content iterator gives it: its file, the selector naming it, its text, where it is. */
data class SourceElement<P>(val href: String, val selector: String?, val text: String, val place: P)

/**
 * Turns the book's text elements, walked in reading order, into passages
 * each carrying the chapters it may belong to.
 *
 * An element whose file is not in the reading order is left out: with no
 * place in the book it has no chapter, and text whose chapter is unknown
 * is never offered.
 */
class PassageCatalogBuilder<P>(
    private val map: ChapterMap,
    private val readingOrder: List<String>,
    firstResource: Int = 0,
    private val html: suspend (resource: Int) -> String?,
) {
    private var resource = firstResource - 1
    private var entered = false
    private var ordinal = 0
    private var outline: ResourceOutline? = null
    private var anchors: Map<String, Int> = emptyMap()
    // The last outline parsed for an ask rather than for the walk, kept so
    // repeated asks about the resource the reader is in stay cheap.
    private var askedResource = -1
    private var askedOutline: ResourceOutline? = null

    /** The reading-order index of the last element added, or `firstResource - 1` before any. */
    val current: Int get() = resource

    /** The passages [element] makes, possibly none. */
    suspend fun add(element: SourceElement<P>): List<Passage<P>> {
        if (!entered || readingOrder.getOrNull(resource) != element.href) {
            val next = readingOrder.indices.firstOrNull { it > resource && readingOrder[it] == element.href }
                ?: return emptyList()
            enter(next)
        }
        val span = element.selector?.let { outline?.span(it) }
        val range = map.range(resource, span?.first, span?.last, anchors)
        val parts = PassageText.split(element.text)
        val index = ordinal++
        return parts.mapIndexed { part, text ->
            Passage(
                ref = PassageText.ref(resource, index, part.takeIf { parts.size > 1 }),
                hash = PassageText.hash(text),
                resource = resource,
                text = text,
                range = range,
                place = element.place,
                end = span?.last,
            )
        }
    }

    /**
     * Where [selector] sits in [at], as chapters, for the place a lookup is
     * asked from. Null [selector], or one that cannot be found, gives the
     * whole file's chapters.
     */
    suspend fun rangeOf(at: Int, selector: String?): ChapterRange {
        val located = selector?.let { outlineAt(at) }
        val span = selector?.let { located?.span(it) }
        return map.range(at, span?.first, span?.last, located?.anchors(map.anchorsIn(at)) ?: emptyMap())
    }

    /**
     * Where [selector] starts in [at], in document order, for the point a
     * lookup may read up to; 0, before everything in the file, when it
     * cannot be found.
     */
    suspend fun startOf(at: Int, selector: String?): Int =
        selector?.let { outlineAt(at)?.span(it)?.first } ?: 0

    /** The outline of a resource asked about, parsed once and kept for the next ask about it. */
    private suspend fun outlineAt(at: Int): ResourceOutline? {
        if (at == askedResource) return askedOutline
        if (at == resource) return outline
        val parsed = html(at)?.let(::ResourceOutline)
        askedResource = at
        askedOutline = parsed
        return parsed
    }

    private suspend fun enter(next: Int) {
        entered = true
        resource = next
        ordinal = 0
        outline = html(next)?.let(::ResourceOutline)
        anchors = outline?.anchors(map.anchorsIn(next)) ?: emptyMap()
    }
}
