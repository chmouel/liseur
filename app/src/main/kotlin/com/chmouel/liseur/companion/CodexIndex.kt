package com.chmouel.liseur.companion

/**
 * One name's aggregate over the units scanned so far. [unitCount] is how
 * many distinct units named it, and [firstUnit] and [lastUnit] the span
 * of its appearances.
 */
data class CodexEntry(
    val name: String,
    val occurrences: Int,
    val unitCount: Int,
    val firstUnit: Int,
    val lastUnit: Int,
    val speechHits: Int,
) {
    /** What kind of thing the name is, worked out at read time; better word lists cost no rescan. */
    val kind: CodexKind get() = CodexExtractor.classify(name, speechHits)
}

/** One node of the relationship graph. [units] are the chapters naming it within the reader's scope. */
data class CodexNode(val name: String, val occurrences: Int, val units: Set<Int>)

/** A bond between two nodes of [CodexGraph.nodes]: the number of chapters naming both. */
data class CodexEdge(val a: Int, val b: Int, val weight: Int)

/** Who appears with whom, up to the reader's scope; the reader's chapter is [ceiling]. */
data class CodexGraph(
    val nodes: List<CodexNode>,
    val edges: List<CodexEdge>,
    val maxWeight: Int,
    val ceiling: Int,
)

/**
 * The names a book's scanned units have yielded, merged into aggregates.
 * Immutable: [scan] returns a new index, so an interrupted pass leaves
 * the old one whole.
 *
 * Complete units are scanned in increasing order, each exactly once; a
 * unit already scanned is left alone. Callers fence by the book's content
 * hash, as recaps do, when a unit's text may have changed underneath.
 *
 * The display floor is applied when entries are read, never when they are
 * written: filtering at write time made the result depend on how the scan
 * was chunked, so a name mentioned once in each of six chapters totalled
 * six when they were scanned together and zero one at a time. Sub-floor
 * names are still kept while they might accumulate, and dropped once they
 * have gone [PRUNE_WINDOW] units without recurring. Per-unit extraction
 * results are retained so earlier reading scopes can reconstruct their
 * own aggregates, including candidates pruned by a later scan.
 */
class CodexIndex private constructor(
    private val entries: Map<String, CodexEntry>,
    val scanned: Set<Int>,
    private val unitStats: Map<Int, Map<String, CodexStats>>,
) {
    /** The entry stored for [name], sub-floor candidates included. */
    fun entry(name: String): CodexEntry? = entries[name]

    /** How many names the index holds, sub-floor candidates included. */
    val names: Int get() = entries.size

    /**
     * The entries a reader in chapter [ceiling] may see: at or above
     * [floor] occurrences, using only units strictly before that chapter.
     * Counts, classification and ordering never depend on later scans.
     */
    fun visible(ceiling: Int, floor: Int = MIN_OCCURRENCES): List<CodexEntry> {
        val scoped = unitStats.asSequence().takeWhile { it.key < ceiling }
            .fold(emptyMap<String, CodexEntry>()) { merged, (unit, stats) -> merge(merged, unit, stats, prune = false) }
        return scoped.values.asSequence()
            .filter { it.occurrences >= floor }
            .sortedWith(compareByDescending<CodexEntry> { it.occurrences }.thenBy { it.firstUnit }.thenBy { it.name })
            .toList()
    }

    /**
     * One unit's text merged in, then the candidates that have clearly
     * gone nowhere dropped: still under the display floor, and last seen
     * a whole [PRUNE_WINDOW] ago. A name at the floor is safe however
     * old it is. New units must arrive in increasing order; repeated
     * scans of an already indexed unit remain a no-op.
     */
    fun scan(unit: Int, text: String): CodexIndex {
        if (unit in scanned) return this
        require(unit > (scanned.maxOrNull() ?: -1)) { "New units must be scanned in increasing order" }
        val stats = CodexExtractor.extract(text)
        return CodexIndex(merge(entries, unit, stats), scanned + unit, unitStats + (unit to stats))
    }

    companion object {
        /** The empty index, before any unit is scanned. */
        val EMPTY = CodexIndex(emptyMap(), emptySet(), emptyMap())

        /** Below this many occurrences an entry is not shown; read-time, so it never depends on scan chunking. */
        const val MIN_OCCURRENCES = 3

        /** How many units a sub-floor candidate may go unseen before it is dropped. */
        const val PRUNE_WINDOW = 150

        internal fun merge(
            entries: Map<String, CodexEntry>,
            unit: Int,
            stats: Map<String, CodexStats>,
            prune: Boolean = true,
        ): Map<String, CodexEntry> {
            val merged = LinkedHashMap<String, CodexEntry>(
                if (prune) entries.filterValues { it.occurrences >= MIN_OCCURRENCES || it.lastUnit >= unit - PRUNE_WINDOW } else entries,
            )
            for ((name, hits) in stats) {
                val prev = merged[name]
                merged[name] = CodexEntry(
                    name = name,
                    occurrences = (prev?.occurrences ?: 0) + hits.occurrences,
                    unitCount = (prev?.unitCount ?: 0) + 1,
                    firstUnit = minOf(prev?.firstUnit ?: unit, unit),
                    lastUnit = maxOf(prev?.lastUnit ?: unit, unit),
                    speechHits = (prev?.speechHits ?: 0) + hits.speechHits,
                )
            }
            return if (prune) merged.filterValues { it.occurrences >= MIN_OCCURRENCES || it.lastUnit >= unit - PRUNE_WINDOW } else merged
        }

        internal fun <P> withSplitContinuity(passages: List<Passage<P>>): List<Continued<P>> = passages.mapIndexed { i, passage ->
            val next = passages.getOrNull(i + 1)
            val tail = if (next != null && sameElement(passage, next)) {
                PassageText.fold(next.text).split(' ').filter { it.isNotEmpty() }.take(PassageText.MAX_NAME_WORDS - 1).joinToString(" ")
            } else ""
            Continued(passage, if (tail.isEmpty()) passage.folded else "${passage.folded}$tail ")
        }

        internal fun <P> sameElement(a: Passage<P>, b: Passage<P>): Boolean =
            a.resource == b.resource &&
                a.range == b.range &&
                a.ref.substringBefore('.') == b.ref.substringBefore('.') &&
                a.ref.substringAfter('.').substringBefore('.') == b.ref.substringAfter('.').substringBefore('.')
    }
}

/**
 * The co-occurrence graph of the names this index holds, over the
 * passages [scope] allows: nodes are the most frequent names first met
 * before the scope's chapter, and an edge between two names is the number
 * of chapters naming both. The app reads the graph from [CodexStore]'s
 * queries; this in-memory version states the same rules for the tests.
 * Passages with uncertain chapter membership cannot establish a shared
 * chapter and are excluded from mention sets.
 */
fun <P> CodexIndex.graph(
    passages: List<Passage<P>>,
    scope: AskingScope,
    maxNodes: Int = 25,
    minNodeOccurrences: Int = 5,
    minEdgeWeight: Int = 2,
    maxEdges: Int = 60,
): CodexGraph {
    val top = visible(scope.lo, floor = minNodeOccurrences).take(maxNodes)
    if (top.size < 2) return CodexGraph(emptyList(), emptyList(), 1, scope.lo)
    val eligible = CodexIndex.withSplitContinuity(passages.filter { Eligibility.allows(scope, it) && it.range.lo == it.range.hi })
    val unitSets = top.map { entry ->
        eligible.filter { it.mentions(entry.name) }
            .mapTo(mutableSetOf()) { it.passage.range.lo }
    }
    val nodes = top.mapIndexed { i, entry -> CodexNode(entry.name, entry.occurrences, unitSets[i]) }
    return codexGraph(nodes, scope.lo, minEdgeWeight, maxEdges)
}

internal fun codexGraph(nodes: List<CodexNode>, ceiling: Int, minEdgeWeight: Int = 2, maxEdges: Int = 60): CodexGraph {
    val edges = buildList {
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val weight = nodes[i].units.count { it in nodes[j].units }
            if (weight >= minEdgeWeight) add(CodexEdge(i, j, weight))
        }
    }.sortedByDescending { it.weight }.take(maxEdges)
    return CodexGraph(nodes, edges, edges.maxOfOrNull { it.weight } ?: 1, ceiling)
}

/** The chapters certainly naming both [nameA] and [nameB], in order, within [scope]: where a bond can be read. */
fun <P> sharedUnits(nameA: String, nameB: String, passages: List<Passage<P>>, scope: AskingScope): List<Int> {
    val eligible = CodexIndex.withSplitContinuity(passages.filter { Eligibility.allows(scope, it) && it.range.lo == it.range.hi })
    val unitsOf: (String) -> Set<Int> = { name ->
        eligible.filter { it.mentions(name) }
            .mapTo(mutableSetOf()) { it.passage.range.lo }
    }
    return (unitsOf(nameA) intersect unitsOf(nameB)).sorted()
}

/**
 * A passage whose [folded] text runs on into the first words of the next
 * part of the same element, so a name cut in two still matches. Only a
 * match starting in the passage's own text counts, or a name wholly in the
 * next part would be credited to an excerpt that does not show it.
 */
internal class Continued<P>(val passage: Passage<P>, val folded: String) {
    /** The last place, counted as SQLite's 1-based `instr` counts code points, where a match may start. */
    val ownEnd: Int get() = passage.folded.codePointCount(0, passage.folded.length) - 1

    fun mentions(name: String): Boolean {
        val needle = PassageText.fold(name).takeIf { it.isNotEmpty() } ?: return false
        return folded.indexOf(PassageText.pattern(needle)) in 0 until passage.folded.length - 1
    }
}
