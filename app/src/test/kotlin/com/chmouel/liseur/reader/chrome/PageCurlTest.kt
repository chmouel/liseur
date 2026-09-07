package com.chmouel.liseur.reader.chrome

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlTest {

    private val width = 1000f
    private val height = 1800f
    private val radius = 100f
    private val paper = 0xFFFAF3E0.toInt()

    @Test
    fun `no travel leaves the page flat and all ink side`() {
        val mesh = mesh(travel = 0f)
        assertFalse(mesh.curled)
        assertEquals(0f, mesh.lift, 0f)
        forEachVertex(mesh) { i, col, row ->
            assertEquals(width * col / mesh.cols, mesh.vertices[i * 2], 1e-3f)
            assertEquals(height * row / mesh.rows, mesh.vertices[i * 2 + 1], 1e-3f)
            assertEquals(1f, PageCurl.alphaOf(mesh.front[i]), 0f)
            assertEquals(0f, PageCurl.alphaOf(mesh.back[i]), 0f)
            assertEquals(0f, PageCurl.alphaOf(mesh.paper[i]), 0f)
        }
    }

    @Test
    fun `the leading edge lands under the finger`() {
        for (travel in listOf(40f, 200f, 500f, 900f)) {
            val mesh = mesh(travel = travel, grabY = height / 2)
            val edge = cornerX(mesh, col = mesh.cols, row = mesh.rows / 2)
            assertEquals("travel $travel", width - travel, edge, 1f)
        }
    }

    @Test
    fun `the edge lands under the finger in a backward turn too`() {
        val mesh = mesh(travel = 300f, movesLeft = false)
        val edge = cornerX(mesh, col = 0, row = mesh.rows / 2)
        assertEquals(300f, edge, 1f)
    }

    @Test
    fun `nothing before the fold moves`() {
        val mesh = mesh(travel = 300f)
        forEachVertex(mesh) { i, col, row ->
            val x = width * col / mesh.cols
            if (x < mesh.foldX - 1f) {
                assertEquals(x, mesh.vertices[i * 2], 1e-2f)
                assertEquals(height * row / mesh.rows, mesh.vertices[i * 2 + 1], 1e-2f)
            }
        }
    }

    @Test
    fun `the lifted sheet bows in perspective but flat text stays anchored`() {
        val mesh = mesh(travel = 400f)
        var bent = false
        forEachVertex(mesh) { i, col, row ->
            val x = width * col / mesh.cols
            val y = height * row / mesh.rows
            if (x <= mesh.foldX) {
                assertEquals(x, mesh.vertices[i * 2], 1e-2f)
                assertEquals(y, mesh.vertices[i * 2 + 1], 1e-2f)
                assertEquals(0f, PageCurl.alphaOf(mesh.highlight[i]), 0f)
            } else if (abs(y - height / 2) > 1f) {
                bent = bent || abs(mesh.vertices[i * 2 + 1] - y) > 1f
            }
        }
        assertTrue(bent)
        assertTrue(mesh.highlight.any { PageCurl.alphaOf(it) > 0f })
    }

    @Test
    fun `the bend broadens mid turn and tightens before leaving`() {
        val start = PageCurl.bendRadius(0f, width, radius)
        val middle = PageCurl.bendRadius(width / 2, width, radius)
        val end = PageCurl.bendRadius(width, width, radius)
        assertTrue(middle > start * 2f)
        assertEquals(start, end, 1e-3f)
    }

    @Test
    fun `the back of the page shows where it has come over the top`() {
        val mesh = mesh(travel = 600f)
        var sawFront = false
        var sawBack = false
        forEachVertex(mesh) { i, _, _ ->
            val front = PageCurl.alphaOf(mesh.front[i])
            val back = PageCurl.alphaOf(mesh.back[i])
            val paper = PageCurl.alphaOf(mesh.paper[i])
            // A vertex is one side or the other, never both and never neither.
            assertEquals(1f, front + back, 1e-6f)
            if (front == 1f) sawFront = true else sawBack = true
            if (back == 1f) assertTrue(paper > 0f) else assertEquals(0f, paper, 0f)
        }
        assertTrue(sawFront)
        assertTrue(sawBack)
    }

    @Test
    fun `the ink darkens into the crease and nowhere else`() {
        val mesh = mesh(travel = 300f)
        forEachVertex(mesh) { i, col, _ ->
            val x = width * col / mesh.cols
            val front = mesh.front[i]
            if (PageCurl.alphaOf(front) == 0f) return@forEachVertex
            val brightness = (front and 0xFF) / 255f
            if (x < mesh.foldX - 1f) {
                assertEquals(1f, brightness, 1e-2f)
            } else {
                assertTrue(brightness <= 1f && brightness > 0.5f)
            }
        }
    }

    @Test
    fun `the roll flattens once it is well over`() {
        val mesh = mesh(travel = 900f)
        // A vertex near the leading edge, past the roll: on the back,
        // lying flat, so it has moved by exactly the fold's reflection.
        val i = index(mesh, col = mesh.cols, row = mesh.rows / 2)
        assertEquals(width - 900f, mesh.vertices[i * 2], 1f)
        assertEquals(height / 2, mesh.vertices[i * 2 + 1], 1e-2f)
        assertEquals(1f, mesh.lift, 0f)
    }

    @Test
    fun `a page pulled clear has nothing left on screen`() {
        for (tilt in listOf(0f, 0.3f, -0.3f)) {
            val travel = PageCurl.travelToClear(width, height, height / 2, tilt, radius)
            val mesh = mesh(travel = travel, tilt = tilt)
            forEachVertex(mesh) { i, _, _ ->
                if (PageCurl.alphaOf(mesh.front[i]) == 1f) {
                    assertTrue("tilt $tilt", mesh.vertices[i * 2] < 0f)
                }
            }
        }
    }

    @Test
    fun `the fold leans towards the corner the finger holds`() {
        assertTrue(PageCurl.tilt(grabY = 100f, dy = 0f, height = height) < 0f)
        assertTrue(PageCurl.tilt(grabY = height - 100f, dy = 0f, height = height) > 0f)
        assertEquals(0f, PageCurl.tilt(grabY = height / 2, dy = 0f, height = height), 1e-6f)
        assertTrue(PageCurl.tilt(grabY = height / 2, dy = 400f, height = height) > 0f)
    }

    @Test
    fun `the tilt is capped`() {
        val cap = (28.0 * PI / 180.0).toFloat()
        assertEquals(cap, PageCurl.tilt(grabY = height, dy = height * 3, height = height), 1e-6f)
        assertEquals(-cap, PageCurl.tilt(grabY = 0f, dy = -height * 3, height = height), 1e-6f)
    }

    @Test
    fun `the fold does not lean before the page has moved`() {
        val mesh = mesh(travel = 0f, grabY = 0f, tilt = 0.4f)
        assertEquals(1f, abs(mesh.normalX), 1e-6f)
        assertEquals(0f, mesh.normalY, 1e-6f)
    }

    @Test
    fun `edge distance inverts the roll`() {
        val halfTurn = (PI * radius).toFloat()
        assertEquals(0f, PageCurl.edgeDistance(0f, radius), 0f)
        // On the roll: d - R sin(d/R) == travel.
        for (travel in listOf(10f, 100f, 300f)) {
            val d = PageCurl.edgeDistance(travel, radius)
            assertTrue(d < halfTurn)
            assertEquals(travel, d - radius * kotlin.math.sin(d / radius), 0.05f)
        }
        // Over the top: linear, and continuous with the roll.
        assertEquals(halfTurn, PageCurl.edgeDistance(halfTurn, radius), 0.05f)
        assertEquals(halfTurn + 50f, PageCurl.edgeDistance(halfTurn + 100f, radius), 1e-3f)
    }

    @Test
    fun `a page pulled past the middle turns on a slow release`() {
        assertTrue(PageCurl.commits(travel = 450f, velocity = 0f, width = width, density = 2f))
        assertFalse(PageCurl.commits(travel = 350f, velocity = 0f, width = width, density = 2f))
    }

    @Test
    fun `a short pull turns only when flung`() {
        assertTrue(PageCurl.commits(travel = 120f, velocity = 800f, width = width, density = 2f))
        assertFalse(PageCurl.commits(travel = 120f, velocity = 200f, width = width, density = 2f))
        // Under the swipe distance even a fling is a twitch.
        assertFalse(PageCurl.commits(travel = 60f, velocity = 800f, width = width, density = 2f))
    }

    @Test
    fun `a fling back puts the page back however far it came`() {
        assertFalse(PageCurl.commits(travel = 900f, velocity = -800f, width = width, density = 2f))
    }

    private fun mesh(
        travel: Float,
        grabY: Float = height / 2,
        tilt: Float = 0f,
        movesLeft: Boolean = true,
    ): PageCurl.Mesh {
        val mesh = PageCurl.Mesh(cols = 20, rows = 12)
        PageCurl.mesh(width, height, travel, grabY, tilt, movesLeft, radius, paper, mesh)
        return mesh
    }

    private fun index(mesh: PageCurl.Mesh, col: Int, row: Int) = row * (mesh.cols + 1) + col

    private fun cornerX(mesh: PageCurl.Mesh, col: Int, row: Int) =
        mesh.vertices[index(mesh, col, row) * 2]

    private fun forEachVertex(mesh: PageCurl.Mesh, block: (i: Int, col: Int, row: Int) -> Unit) {
        for (row in 0..mesh.rows) for (col in 0..mesh.cols) block(index(mesh, col, row), col, row)
    }
}
