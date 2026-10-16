package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexIndexTest {

    // Six chapters; Mira and Tobin share chapters 1 to 3, Wren keeps to
    // chapter 4, Fenna appears only in chapter 6, which a reader in
    // chapter 4 or 5 must not see.
    private val units = mapOf(
        0 to "At dawn, Mira waited by the door, and Tobin arrived with the ledger. Later, Mira smiled at Tobin. The mill stood quiet.",
        1 to "By noon, Mira read the ledger, and Tobin repaired the sluice. Again, Mira thanked Tobin. The valley lay still.",
        2 to "At dusk, Mira closed the mill, and Tobin barred the door. Once more, Mira waved at Tobin. The night fell.",
        3 to "On the cliffs, Wren said nothing. All day, Wren watched the gulls. Wren waited.",
        5 to "Beyond the gate, Fenna opened her satchel. Fenna called out twice. Fenna listened.",
    )

    private val passages = units.map { (unit, text) -> passage(unit, text) }

    private val index = units.entries.sortedBy { it.key }.fold(CodexIndex.EMPTY) { scan, (unit, text) -> scan.scan(unit, text) }

    @Test
    fun `scanning merges each unit's aggregate`() {
        val scanned = CodexIndex.EMPTY
            .scan(0, "At dawn, Mira waited by the door. At noon, Mira read. At dusk, Mira slept.")
            .scan(2, "Late, Mira arrived.")
        assertEquals(CodexEntry("Mira", 4, 2, 0, 2, 0), scanned.entry("Mira"))
    }

    @Test
    fun `a unit already scanned is merged once`() {
        val once = CodexIndex.EMPTY.scan(0, "At dawn, Mira waited.").scan(1, "At noon, Mira slept.")
        val twice = once.scan(0, "At dusk, Mira waited again.")
        assertEquals(once.entry("Mira"), twice.entry("Mira"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `new units must be scanned in increasing order`() {
        CodexIndex.EMPTY.scan(2, "At dawn, Mira waited.").scan(1, "At noon, Mira slept.")
    }

    @Test
    fun `the display floor is read, never written`() {
        val scanned = CodexIndex.EMPTY.scan(0, "At dusk, Bram lit the lamp. Bram said nothing more.")
        assertEquals(2, scanned.entry("Bram")?.occurrences)
        assertTrue(scanned.visible(10).isEmpty())
        assertEquals(listOf("Bram"), scanned.visible(10, floor = 2).map { it.name })
    }

    @Test
    fun `stale sub-floor candidates are pruned, floor entries are not`() {
        var scanned = CodexIndex.EMPTY
            .scan(0, "At dusk, Bram lit the lamp. Bram said nothing more. Ash came by, Ash stayed, and Ash left.")
        for (unit in 1..CodexIndex.PRUNE_WINDOW + 1) {
            scanned = scanned.scan(unit, "The road went on and on.")
        }
        assertNull(scanned.entry("Bram"))
        assertNotNull(scanned.entry("Ash"))
    }

    @Test
    fun `visible hides entries first named at or after the reader's chapter`() {
        assertEquals(listOf("Mira", "Tobin", "Wren"), index.visible(4).map { it.name })
        assertEquals(listOf("Mira", "Tobin", "Wren", "Fenna"), index.visible(6).map { it.name })
        assertTrue(index.visible(0).isEmpty())
    }

    @Test
    fun `visible keeps only what the floor allows`() {
        assertTrue(index.visible(10, floor = 4).map { it.name }.none { it == "Wren" })
        assertTrue(index.visible(10, floor = 3).map { it.name }.any { it == "Wren" })
    }

    @Test
    fun `future scans cannot change earlier entries or graph nodes`() {
        val text = "At dawn, Mira waited. At noon, Tobin waited. ".repeat(5) + "At dusk, Bram waited."
        val early = CodexIndex.EMPTY.scan(0, text)
        val later = early.scan(5, "At dawn, Tobin said nothing. At noon, Bram replied. ".repeat(5))
        val scope = AskingScope(1, 6)

        assertEquals(early.visible(1), later.visible(1))
        assertEquals(CodexKind.UNKNOWN, later.visible(1).first { it.name == "Tobin" }.kind)
        assertEquals(early.graph(listOf(passage(0, text)), scope), later.graph(listOf(passage(0, text)), scope))
    }

    @Test
    fun `later pruning does not remove earlier evidence`() {
        val early = CodexIndex.EMPTY.scan(0, "At dawn, Bram waited. Bram slept.")
        val later = early.scan(CodexIndex.PRUNE_WINDOW + 1, "The rain fell.")

        assertNull(later.entry("Bram"))
        assertEquals(early.visible(1, floor = 2), later.visible(1, floor = 2))
    }

    @Test
    fun `sparse and intervening-empty scans expire stale candidates consistently while visible keeps recurring names`() {
        val base = CodexIndex.EMPTY.scan(0, "At dusk, Bram waited. Bram slept.")
        val sparse = base.scan(CodexIndex.PRUNE_WINDOW + 2, "At dusk, Bram returned.")
        val withEmpty = base
            .scan(CodexIndex.PRUNE_WINDOW + 1, "The rain fell.")
            .scan(CodexIndex.PRUNE_WINDOW + 2, "At dusk, Bram returned.")

        assertEquals(withEmpty.entry("Bram"), sparse.entry("Bram"))
        assertEquals(1, sparse.entry("Bram")?.occurrences)
        assertEquals(listOf("Bram"), sparse.visible(CodexIndex.PRUNE_WINDOW + 3).map { it.name })
        assertEquals(3, sparse.visible(CodexIndex.PRUNE_WINDOW + 3).single().occurrences)
    }

    @Test
    fun `an edge is the number of chapters naming both, within the reader's scope`() {
        val graph = index.graph(passages, AskingScope(4, 6), minNodeOccurrences = 2)
        assertEquals(listOf("Mira", "Tobin", "Wren"), graph.nodes.map { it.name })
        assertEquals(setOf(0, 1, 2), graph.nodes[0].units)
        assertEquals(listOf(CodexEdge(0, 1, 3)), graph.edges)
        assertEquals(3, graph.maxWeight)
        assertEquals(4, graph.ceiling)
    }

    @Test
    fun `the graph grows with the reader`() {
        val graph = index.graph(passages, AskingScope(6, 6), minNodeOccurrences = 2)
        assertEquals(listOf("Mira", "Tobin", "Wren", "Fenna"), graph.nodes.map { it.name })
        assertEquals(listOf(CodexEdge(0, 1, 3)), graph.edges)
    }

    @Test
    fun `the default node floor keeps only the frequent`() {
        val graph = index.graph(passages, AskingScope(6, 6))
        assertEquals(listOf("Mira", "Tobin"), graph.nodes.map { it.name })
    }

    @Test
    fun `the graph honors a node floor below three occurrences`() {
        val text = "At dawn, Mira waited. At noon, Tobin waited."
        val scanned = CodexIndex.EMPTY.scan(0, "$text At dusk, Bram waited.").scan(1, text)
        val graph = scanned.graph(listOf(passage(0, text), passage(1, text)), AskingScope(2, 3), minNodeOccurrences = 2)

        assertEquals(listOf("Mira", "Tobin"), graph.nodes.map { it.name })
        assertEquals(listOf(2, 2), graph.nodes.map { it.occurrences })
        assertEquals(listOf(CodexEdge(0, 1, 2)), graph.edges)
    }

    @Test
    fun `compound names retain their relationships`() {
        for ((canonical, variant) in listOf(
            "King's Landing" to "King’s Landing",
            "Gabriele D'Annunzio" to "Gabriele D’Annunzio",
            "Élisabeth" to "E\u0301lisabeth",
            "O'Brien" to "O’Brien",
        )) {
            val text0 = "They reached $canonical. At dawn, Tobin waited. ".repeat(5)
            val text1 = "They reached $variant. At dawn, Tobin waited. ".repeat(5)
            val scanned = CodexIndex.EMPTY.scan(0, text0).scan(1, text1)
            val passages = listOf(passage(0, text0), passage(1, text1))
            val scope = AskingScope(2, 3)
            val graph = scanned.graph(passages, scope)

            assertEquals(listOf(canonical, "Tobin").sorted(), graph.nodes.map { it.name })
            assertEquals(listOf(10, 10), graph.nodes.map { it.occurrences })
            assertEquals(listOf(setOf(0, 1), setOf(0, 1)), graph.nodes.map { it.units })
            assertEquals(listOf(CodexEdge(0, 1, 2)), graph.edges)
            assertEquals(listOf(0, 1), sharedUnits(canonical, "Tobin", passages, scope))
        }
    }

    @Test
    fun `passage cuts within an element keep compound names in the graph`() {
        val text = "x ".repeat(596) + "Elder Chen waited, and Tobin arrived."
        val scanned = CodexIndex.EMPTY.scan(0, text).scan(1, text)
        val cuts = PassageText.split(text)
        assertEquals(2, cuts.size)
        val passages = (0..1).flatMap { unit ->
            cuts.mapIndexed { part, piece ->
                Passage(
                    ref = PassageText.ref(unit, 0, part),
                    hash = PassageText.hash(piece),
                    resource = unit,
                    text = piece,
                    range = ChapterRange(unit, unit),
                    place = unit,
                )
            }
        }
        val scope = AskingScope(2, 3)
        val graph = scanned.graph(passages, scope, minNodeOccurrences = 2)

        assertEquals(listOf("Elder Chen", "Tobin"), graph.nodes.map { it.name })
        assertEquals(listOf(setOf(0, 1), setOf(0, 1)), graph.nodes.map { it.units })
        assertEquals(listOf(CodexEdge(0, 1, 2)), graph.edges)
        assertEquals(listOf(0, 1), sharedUnits("Elder Chen", "Tobin", passages, scope))
    }

    @Test
    fun `a cut passage is credited only with names starting in its own text`() {
        val parts = listOf("Mira waited by the door. Elder", "Chen came. Tobin smiled.").mapIndexed { part, piece ->
            passage(0, piece).copy(ref = PassageText.ref(0, 0, part))
        }
        val runs = CodexIndex.withSplitContinuity(parts)
        assertEquals(listOf(true, false), runs.map { it.mentions("Elder Chen") })
        assertEquals(listOf(false, true), runs.map { it.mentions("Tobin") })
        assertEquals(listOf(false, true), runs.map { it.mentions("Chen") })
    }

    @Test
    fun `shared units say where a bond can be read`() {
        assertEquals(listOf(0, 1, 2), sharedUnits("Mira", "Tobin", passages, AskingScope(4, 6)))
        assertEquals(emptyList<Int>(), sharedUnits("Mira", "Wren", passages, AskingScope(4, 6)))
    }

    @Test
    fun `uncertain chapter ranges cannot add shared units or strengthen an edge`() {
        val text = "At dawn, Mira waited. At noon, Tobin waited. ".repeat(5)
        val scanned = CodexIndex.EMPTY.scan(0, text)
        val uncertain = listOf(
            passage(0, text),
            passage(1, "Mira waited.").copy(range = ChapterRange(1, 3)),
            passage(2, "Tobin waited.").copy(range = ChapterRange(2, 3)),
        )
        val scope = AskingScope(4, 6)
        val graph = scanned.graph(uncertain, scope)

        assertEquals(listOf(setOf(0), setOf(0)), graph.nodes.map { it.units })
        assertTrue(graph.edges.isEmpty())
        assertEquals(listOf(0), sharedUnits("Mira", "Tobin", uncertain, scope))
    }

    private companion object {
        fun passage(chapter: Int, text: String) = Passage(
            ref = PassageText.ref(chapter, 0, null),
            hash = PassageText.hash(text),
            resource = chapter,
            text = text,
            range = ChapterRange(chapter, chapter),
            place = chapter,
        )
    }
}
