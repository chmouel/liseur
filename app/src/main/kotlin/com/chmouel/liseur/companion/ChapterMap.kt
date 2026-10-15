package com.chmouel.liseur.companion

/** A contents entry: an href with its fragment, already normalised, its title, and the entries under it. */
data class TocNode(val href: String, val children: List<TocNode> = emptyList(), val title: String? = null)

/**
 * The chapters a stretch of text may belong to, numbered from 0 in the
 * book's contents. A stretch whose place is certain has [lo] == [hi].
 */
data class ChapterRange(val lo: Int, val hi: Int) {
    init {
        require(lo in 0..hi) { "Bad chapter range" }
    }

    fun union(other: ChapterRange) = ChapterRange(minOf(lo, other.lo), maxOf(hi, other.hi))
}

/**
 * Where the selection a lookup was asked about starts: the reading-order
 * [resource] holding it, and the first element in document order
 * ([ResourceOutline.span]) of what holds it there. 0 when it cannot be
 * told within the resource, so nothing of that resource lies before it.
 */
data class ReadPoint(val resource: Int, val element: Int)

/**
 * Whether everything eligible up to this point is eligible up to [other]
 * too, no read point meaning none of the asking chapter.
 */
fun ReadPoint?.notAfter(other: ReadPoint?): Boolean = when {
    this == null -> true
    other == null -> false
    else -> resource < other.resource || (resource == other.resource && element <= other.element)
}

/**
 * Where a lookup was asked from: chapters numbered below [lo] may be
 * used, and [count] is the book's chapter count. With [until], so may
 * the text before that point in reading order, the asking chapter's
 * own up to the selection.
 */
data class AskingScope(val lo: Int, val count: Int, val until: ReadPoint? = null) {
    /** Whether any text at all may lie before the place asked from. */
    val hasEarlier: Boolean get() = lo > 0 || (until != null && (until.resource > 0 || until.element > 0))
}

/**
 * The single gate between the book and anything sent away: text is
 * eligible only when every chapter it may belong to lies strictly before
 * the chapter asked from, or when it ends, in reading order, before the
 * scope's [AskingScope.until]. Text whose place in the selection's own
 * resource is unknown never passes the second test.
 */
object Eligibility {
    fun allows(scope: AskingScope, range: ChapterRange): Boolean = range.hi < scope.lo

    fun allows(scope: AskingScope, passage: Passage<*>): Boolean {
        if (allows(scope, passage.range)) return true
        val until = scope.until ?: return false
        if (passage.resource < until.resource) return true
        val end = passage.end ?: return false
        return passage.resource == until.resource && end < until.element
    }
}

/**
 * The book's chapters as its contents declare them, mapped onto the
 * reading order.
 *
 * Top-level entries are chapters; a lone top-level wrapper (the book's
 * title, say) is looked through. A top-level entry whose entries open two
 * or more files is a part: it keeps its own start, for its heading, and
 * its entries are chapters too. Deeper entries never split a chapter, so
 * a chapter whose sections share its file, or that has a single section
 * in another file, stays whole. Text before the first entry joins the
 * first chapter, and a book whose contents give nothing usable is a
 * single chapter.
 *
 * A chapter starting partway into a resource starts at its anchor. Where
 * that anchor or a passage cannot be found, or the contents list chapters
 * out of reading order, the text takes every chapter it might belong to,
 * which only ever makes less of the book eligible: whatever lies at or
 * after a place always reaches at least that place's lowest chapter.
 */
class ChapterMap private constructor(
    private val readingOrder: List<String>,
    private val starts: List<Start>,
    private val titles: List<String?>,
) {
    /** A chapter's first place: a resource in the reading order, and an anchor in it when not its top. */
    private data class Start(val resource: Int, val fragment: String?)

    val chapterCount: Int get() = starts.size

    /** Chapter [unit]'s title in the contents, for the reader's eyes only: it is never sent away. */
    fun titleOf(unit: Int): String? = titles.getOrNull(unit)

    private val occurrences: Map<String, List<Int>> =
        readingOrder.withIndex().groupBy({ it.value }, { it.index })

    /** The anchors [resource] needs located in its document to place its text. */
    fun anchorsIn(resource: Int): Set<String> =
        starts.drop(1).filter { it.resource in occurrencesOf(resource) }.mapNotNullTo(mutableSetOf()) { it.fragment }

    /** Every reading-order index holding the same file as [resource], itself included. */
    fun occurrencesOf(resource: Int): List<Int> =
        readingOrder.getOrNull(resource)?.let { occurrences[it] } ?: listOf(resource)

    /**
     * The chapters a stretch of [resource] may belong to.
     *
     * [start] and [end] are the stretch's first and last element in
     * document order, and [anchors] where each of [anchorsIn] sits in that
     * same order. A null bound or a missing anchor is a place unknown
     * within the resource, and a file listed more than once in the reading
     * order takes every place it is listed.
     */
    fun range(resource: Int, start: Int?, end: Int?, anchors: Map<String, Int>): ChapterRange {
        val listed = occurrencesOf(resource)
        val known = start != null && end != null && start in 0..end && listed.size == 1
        return listed
            .map { at -> if (known) rangeAt(at, start!!, end!!, anchors) else rangeAt(at, BEFORE, AFTER, anchors) }
            .reduce(ChapterRange::union)
    }

    /**
     * A name for chapter [unit]'s bounds, so a recap saved for it is not
     * read against a chapter that starts or ends elsewhere.
     */
    fun unitKey(unit: Int): String {
        fun bound(k: Int) = starts.getOrNull(k)?.let { "${it.resource}:${readingOrder[it.resource]}#${it.fragment.orEmpty()}" } ?: "end"
        return "$unit:" + PassageText.hash("${bound(unit)}|${bound(unit + 1)}|$chapterCount")
    }

    /** The widest chapters anything in [resource] may belong to, for text whose place is unknown. */
    fun resourceRange(resource: Int): ChapterRange = range(resource, null, null, emptyMap())

    /**
     * A chapter every text in [resource] and after it may belong to: once
     * it reaches a lookup's chapter, nothing from there on can be eligible.
     */
    fun floorFrom(resource: Int): Int =
        starts.indices.filter { k -> k == 0 || starts[k].resource < resource || (starts[k].resource == resource && starts[k].fragment == null) }.max()

    private fun rangeAt(resource: Int, start: Int, end: Int, anchors: Map<String, Int>): ChapterRange {
        // Each chapter start as the earliest and the latest it could be: a located anchor is one
        // point, an anchor that is not could be anywhere in its file.
        val early = starts.mapIndexed { k, it -> point(k, it, anchors, unknown = BEFORE) }
        val late = starts.mapIndexed { k, it -> point(k, it, anchors, unknown = AFTER) }
        val first = Point(resource, start)
        val last = Point(resource, end)
        // The highest chapter that may have begun by the stretch's end.
        val hi = early.indices.filter { early[it] <= last }.max()
        // The lowest chapter the stretch may be in: the one surely begun at its start, or at any
        // later start falling inside it.
        val lo = (listOf(first) + late.filter { it > first && it <= last }).minOf { begunAt(late, it) }
        return ChapterRange(minOf(lo, hi), hi)
    }

    /** The chapter whose latest possible start is the last one at or before [at]; the lowest on a tie. */
    private fun begunAt(late: List<Point>, at: Point): Int {
        var best = 0
        late.indices.forEach { k -> if (late[k] <= at && late[k] > late[best]) best = k }
        return best
    }

    private fun point(index: Int, start: Start, anchors: Map<String, Int>, unknown: Int): Point {
        if (index == 0) return Point(0, BEFORE)
        val fragment = start.fragment ?: return Point(start.resource, BEFORE)
        return Point(start.resource, anchors[fragment] ?: unknown)
    }

    private data class Point(val resource: Int, val offset: Int) : Comparable<Point> {
        override fun compareTo(other: Point): Int = compareValuesBy(this, other, { it.resource }, { it.offset })
    }

    companion object {
        private const val BEFORE = -1
        private const val AFTER = Int.MAX_VALUE

        /**
         * The chapters of a book whose reading order lists [readingOrder]
         * (hrefs without fragment or query) and whose contents are [toc]
         * (hrefs without query, fragments kept).
         */
        fun of(readingOrder: List<String>, toc: List<TocNode>): ChapterMap {
            var level = toc
            while (level.size == 1 && level.single().children.isNotEmpty()) level = level.single().children
            val entries = level.flatMap { entry ->
                val files = entry.children.mapTo(mutableSetOf()) { it.href.substringBefore('#') }.filter { it in readingOrder }
                if (files.size >= 2) listOf(entry) + entry.children else listOf(entry)
            }
            val starts = mutableListOf<Start>()
            val titles = mutableListOf<String?>()
            var after = 0
            entries.forEach { entry ->
                val path = entry.href.substringBefore('#')
                val fragment = entry.href.substringAfter('#', "").takeIf { it.isNotBlank() }
                val listed = readingOrder.indices.filter { readingOrder[it] == path }
                if (listed.isEmpty()) return@forEach
                // A file listed twice is taken where reading has got to, so a later entry for it goes on.
                val resource = listed.firstOrNull { it >= after } ?: listed.first()
                val start = Start(resource, fragment)
                if (start in starts) return@forEach
                starts += start
                titles += entry.title?.trim()?.takeIf { it.isNotEmpty() }
                after = maxOf(after, resource)
            }
            if (starts.isEmpty()) {
                starts += Start(0, null)
                titles += null
            }
            return ChapterMap(readingOrder, starts, titles)
        }
    }
}
