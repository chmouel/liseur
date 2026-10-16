package com.chmouel.liseur.companion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CodexLayoutTest {
    @Test fun `dense and disconnected graphs have finite deterministic positions inside the view`() {
        val nodes = (0 until 25).map { CodexNode("Name $it", 10, setOf(0, 1)) }
        val dense = codexGraph(nodes, 3)
        for (graph in listOf(dense, dense.copy(edges = emptyList()))) {
            val positions = codexLayout(graph)
            assertEquals(25, positions.size)
            assertEquals(positions, codexLayout(graph))
            assertTrue(positions.all { (x, y) -> x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f })
        }
    }

    @Test fun `dots stay far enough apart to tap`() {
        val nodes = (0 until 25).map { CodexNode("Name $it", 10, setOf(0, 1)) }
        val dense = codexGraph(nodes, 3)
        for (graph in listOf(dense, dense.copy(edges = emptyList()))) {
            val positions = codexLayout(graph)
            val closest = positions.indices.flatMap { a -> (a + 1 until positions.size).map { b -> a to b } }
                .minOf { (a, b) -> hypot(positions[a].first - positions[b].first, positions[a].second - positions[b].second) }
            assertTrue("closest pair $closest", closest >= MIN_NODE_GAP * 0.9)
        }
    }

    @Test fun `labels never overlap and the selected name and its connections come first`() {
        val nodes = (0 until 9).map { CodexNode("Name $it", 10, setOf(0, 1)) }
        val graph = CodexGraph(nodes, listOf(CodexEdge(3, 2, 2), CodexEdge(3, 0, 5)), 5, 2)
        assertEquals(listOf(3, 0, 2, 1, 4, 5, 6, 7, 8), codexLabelOrder(graph, 3))
        val centers = (0 until 9).map { Offset(100f + 30f * (it % 3), 100f + 30f * (it / 3)) }
        val bounds = Size(400f, 400f)
        fun place(selected: Int?) =
            codexLabels(centers, List(9) { 80f }, 14f, 10f, 9f, bounds, codexLabelOrder(graph, selected), selected)
        fun assertReadable(labels: Map<Int, androidx.compose.ui.geometry.Rect>) {
            for (box in labels.values) {
                assertTrue(box.left >= 0f && box.right <= bounds.width && box.top >= 0f && box.bottom <= bounds.height)
                assertTrue(labels.values.none { it !== box && it.overlaps(box) })
            }
        }

        val idle = place(null)
        assertReadable(idle)
        assertTrue(0 in idle)
        val hidden = (0 until 9).first { it !in idle }
        val focused = place(hidden)
        assertReadable(focused)
        assertTrue(hidden in focused)
    }

    @Test fun `excerpt highlights follow passage lookup across apostrophes and accents`() {
        val text = "Then O’Brien met Élisabeth and E\u0301lisabeth again."
        val found = highlightRanges(text, listOf("O'Brien", "Élisabeth")).map { text.substring(it) }
        assertEquals(listOf("O’Brien", "Élisabeth", "E\u0301lisabeth"), found)
        assertTrue(highlightRanges(text, listOf("", "'")).isEmpty())
    }

    @Test fun `excerpt highlights whole names only`() {
        val text = "Then Anna told Ann about Annette and Ann."
        assertEquals(listOf(15 until 18, 37 until 40), highlightRanges(text, listOf("Ann")))
        assertEquals(listOf(2 until 4), highlightRanges("他说李明来了", listOf("李明")))
    }
}
