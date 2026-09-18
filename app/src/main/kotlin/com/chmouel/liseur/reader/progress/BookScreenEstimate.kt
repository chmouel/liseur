package com.chmouel.liseur.reader.progress

import kotlin.math.roundToInt

/**
 * A running estimate of how many screenfuls the whole book holds, and
 * which one is on the screen.
 *
 * Only the resource being drawn can be measured. Its screen count and
 * its share of the book give a density, and the book's length in
 * screens follows from it. Every resource the reader passes through
 * adds a sample, so the estimate is a guess made from one chapter at
 * the start of the book and a better one by the middle.
 *
 * Two things are held still on purpose.
 *
 * The total is not recomputed per turn. Reading through a resource adds
 * nothing new, so it stands still while the current page walks up it one
 * screen at a time, which is what a page number is supposed to do.
 *
 * And a resource's page origin — the screens lying ahead of it in the
 * book — is settled when it is first measured and afterwards only ever
 * moves forward. The density is revised as the book is read, and
 * re-deriving an origin from the current one lets a forward turn print
 * a smaller number than the turn before it, which is the bug this whole
 * file exists to fix. So origins are stored, and a sample joining
 * behind an existing one pushes it along rather than pulling it back.
 *
 * Everything is thrown away when the page is rebuilt: [layout] names
 * the shape the samples were taken at, and a reading taken at another
 * shape is refused rather than mixed in. Samples are keyed by
 * reading-order index rather than by href, because a reading order may
 * list one file twice and the two occurrences are different stretches
 * of the book.
 */
data class BookScreenEstimate(
    val layout: Int = 0,
    val samples: Map<Int, Sample> = emptyMap(),
) {
    /**
     * One resource: where it begins in the book, how much of the book
     * it is, the screens it laid out into, and how many screens of the
     * book lie ahead of it.
     */
    data class Sample(
        val start: Double,
        val span: Double,
        val screens: Int,
        val origin: Int,
    ) {
        /** Whether this is the same measurement, page origin aside. */
        fun matches(start: Double, span: Double, screens: Int): Boolean =
            this.start == start && this.span == span && this.screens == screens
    }

    private val ordered: List<Sample> by lazy { samples.values.sortedBy { it.start } }

    private val measuredSpan: Double by lazy { ordered.sumOf { it.span }.coerceAtMost(1.0) }

    private val measuredScreens: Int by lazy { ordered.sumOf { it.screens } }

    /** Screens per unit of the book, from everything measured so far. */
    private val density: Double? by lazy {
        if (measuredSpan <= 0.0 || measuredScreens <= 0) null else measuredScreens / measuredSpan
    }

    /**
     * The book's length in screenfuls, or null before anything has
     * been measured and for an answer too long to be a book.
     *
     * What has been measured is counted; the rest of the book is
     * guessed at the density of what has. The book is never shorter
     * than the last page it can already print, so a total that moves
     * cannot leave a page number hanging past the end.
     *
     * A total past [MAX_BOOK_SCREENS] is refused outright rather than
     * clamped. Clamping keeps the figure in range by making it stop
     * moving, and a page number that stands still while the reader
     * turns pages is the thing this whole file is here to prevent.
     */
    val totalScreens: Int? by lazy {
        val perUnit = density ?: return@lazy null
        val unmeasured = (1.0 - measuredSpan).coerceAtLeast(0.0)
        val guessed = (measuredScreens + unmeasured * perUnit).roundToInt()
        val printable = ordered.maxOf { it.origin + it.screens }
        maxOf(guessed, printable).takeIf { it in measuredScreens..MAX_BOOK_SCREENS }
    }

    /**
     * The estimate as it stands for layout [generation], which is
     * empty when the page has been rebuilt since these samples were
     * taken.
     */
    fun forLayout(generation: Int): BookScreenEstimate =
        if (generation == layout) this else BookScreenEstimate(layout = generation)

    /**
     * Takes [screens] as the measurement, at layout [generation], of
     * the resource at reading-order [index] covering [start] to [end]
     * of the book.
     *
     * A reading from another layout generation is refused: it is about
     * a page that no longer exists, and averaging it in would describe
     * a book that was never laid out. A resource that measures
     * differently from last time at the *same* generation says the page
     * was rebuilt without anything noticing, so the older samples go and
     * this one starts the estimate over.
     */
    fun recording(
        generation: Int,
        index: Int,
        start: Double,
        end: Double,
        screens: Int,
    ): BookScreenEstimate {
        if (generation != layout) return this
        val span = end - start
        if (!start.isFinite() || start < 0.0 || start > 1.0) return this
        if (!span.isFinite() || span <= 0.0 || span > 1.0 || screens <= 0) return this
        val seen = samples[index]
        if (seen != null && seen.matches(start, span, screens)) return this
        val kept = if (seen != null) emptyMap() else samples
        return copy(samples = kept.placing(index, start, span, screens))
    }

    /**
     * Which screenful of the book screen [screen] of the resource at
     * reading-order [index] is, or null while that resource has not
     * been measured at this layout.
     */
    fun pageAt(index: Int, screen: Int): Int? {
        val total = totalScreens ?: return null
        val here = samples[index] ?: return null
        if (screen < 1 || screen > here.screens) return null
        return (here.origin + screen).coerceIn(1, total)
    }

    /**
     * The samples with this one placed among them, every origin left
     * where it was or pushed further along, never pulled back.
     */
    private fun Map<Int, Sample>.placing(
        index: Int,
        start: Double,
        span: Double,
        screens: Int,
    ): Map<Int, Sample> {
        val others = this - index
        val perUnit = (others.values.sumOf { it.screens } + screens) /
            (others.values.sumOf { it.span } + span)
        val ahead = others.values.filter { it.start < start }
        // The screens behind the reader are counted. Only the stretches
        // never visited are guessed, and only for as long as they are
        // still ahead of where this resource begins.
        val guessed = (start - ahead.sumOf { it.span }).coerceAtLeast(0.0) * perUnit
        val settled = ahead.maxOfOrNull { it.origin + it.screens } ?: 0
        val origin = maxOf((ahead.sumOf { it.screens } + guessed).roundToInt(), settled)
        val placed = (others + (index to Sample(start, span, screens, origin))).toMutableMap()
        // Anything beginning later must begin after this one ends, or
        // crossing into it would count a screen twice.
        var reached = origin + screens
        placed.entries
            .filter { it.value.start > start }
            .map { it.key to it.value }
            .sortedBy { (_, sample) -> sample.start }
            .forEach { (key, sample) ->
                val pushed = maxOf(sample.origin, reached)
                placed[key] = sample.copy(origin = pushed)
                reached = pushed + sample.screens
            }
        return placed
    }
}

/** A book longer than this is a measurement gone wrong, not a book. */
private const val MAX_BOOK_SCREENS = 200_000
