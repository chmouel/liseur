package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi.Component.Indirection
import com.chmouel.liseur.data.bookorbit.BookOrbitCfi.Component.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookOrbitCfiTest {

    @Test
    fun `parses a point cfi with a text assertion and side bias inside the brackets`() {
        val raw = "epubcfi(/6/4[chap01ref]!/4[body01]/10/1:18[before,after;s=b])"

        val cfi = BookOrbitCfi.parse(raw) as BookOrbitCfi.Point

        assertEquals(raw, cfi.serialize())
        assertEquals(
            listOf(Step(6), Step(4, "chap01ref"), Indirection, Step(4, "body01"), Step(10), Step(1)),
            cfi.path.components,
        )
        assertEquals(
            BookOrbitCfi.Offset(18, "before", "after", BookOrbitCfi.SideBias.BEFORE),
            cfi.path.offset,
        )
    }

    @Test
    fun `reads a side bias without text assertion as foliate writes it`() {
        val cfi = BookOrbitCfi.parse("epubcfi(/6/4!/4/2/1:18[;s=a])") as BookOrbitCfi.Point

        assertEquals(BookOrbitCfi.Offset(18, sideBias = BookOrbitCfi.SideBias.AFTER), cfi.path.offset)
    }

    @Test
    fun `keeps both endpoints of a range cfi`() {
        val raw = "epubcfi(/6/4!/4/2,/1:0,/3:5)"

        val cfi = BookOrbitCfi.parse(raw) as BookOrbitCfi.Range

        assertEquals(raw, cfi.raw)
        assertNull(cfi.parent.offset)
        assertEquals(listOf(Step(1)), cfi.start.components)
        assertEquals(0, cfi.start.offset!!.character)
        assertEquals(5, cfi.end.offset!!.character)
    }

    @Test
    fun `accepts an indirection at the end of the parent or opening each endpoint`() {
        val trailing = BookOrbitCfi.parse("epubcfi(/6/4!,/4/2/1:0,/4/6/1:5)") as BookOrbitCfi.Range
        val leading = BookOrbitCfi.parse("epubcfi(/6/4,!/4/2/1:0,!/4/2/1:5)") as BookOrbitCfi.Range

        assertEquals(listOf(Step(6), Step(4), Indirection), trailing.parent.components)
        assertEquals(Indirection, leading.start.components.first())
        assertEquals(5, leading.end.offset!!.character)
    }

    @Test
    fun `accepts range endpoints that are only offsets`() {
        val cfi = BookOrbitCfi.parse("epubcfi(/6/4!/4/10/1,:1,:4)") as BookOrbitCfi.Range

        assertTrue(cfi.start.components.isEmpty())
        assertEquals(1, cfi.start.offset!!.character)
        assertEquals(4, cfi.end.offset!!.character)
    }

    @Test
    fun `accepts an empty range start at its parent location`() {
        val raw = "epubcfi(/6/4!/4/2,,/1:5)"
        val cfi = BookOrbitCfi.parse(raw) as BookOrbitCfi.Range

        assertEquals(raw, cfi.serialize())
        assertEquals(BookOrbitCfi.Path(emptyList(), null), cfi.start)
        assertEquals(listOf(Step(1)), cfi.end.components)
        assertEquals(5, cfi.end.offset!!.character)
    }

    @Test
    fun `unescapes unicode assertions after splitting on real delimiters`() {
        val cfi = BookOrbitCfi.parse("epubcfi(/6/4[été^]^;x]!/4/1:0[a^,b,c^;d])") as BookOrbitCfi.Point

        assertEquals("été];x", (cfi.path.components[1] as Step).id)
        assertEquals("a,b", cfi.path.offset!!.textBefore)
        assertEquals("c;d", cfi.path.offset!!.textAfter)
    }

    @Test
    fun `an escaped semicolon in an id is not a parameter`() {
        val literal = BookOrbitCfi.parse("epubcfi(/6/4[chap^;s=b])") as BookOrbitCfi.Point
        val parameter = BookOrbitCfi.parse("epubcfi(/6/4[chap;s=b])") as BookOrbitCfi.Point

        assertEquals(Step(4, "chap;s=b"), literal.path.components[1])
        assertEquals(Step(4, "chap", mapOf("s" to listOf("b"))), parameter.path.components[1])
    }

    @Test
    fun `keeps unknown parameters instead of refusing them`() {
        val cfi = BookOrbitCfi.parse("epubcfi(/6/4!/4/1:2[;x=y,z])") as BookOrbitCfi.Point

        assertEquals(mapOf("x" to listOf("y", "z")), cfi.path.offset!!.parameters)
    }

    @Test
    fun `rejects duplicate assertion parameters rather than choosing one side bias`() {
        listOf(
            "epubcfi(/6/4!/4/1:1[;s=b;s=a])",
            "epubcfi(/6/4[;x^=y=one;x^=y=two])",
        ).forEach { raw ->
            val error = assertThrows(BookOrbitCfi.ParseException::class.java) {
                BookOrbitCfi.parse(raw)
            }
            assertTrue(error.message!!.contains("duplicate assertion parameter"))
            assertEquals(raw, BookOrbitForeignCfi.capture("account", 1, 2, 0, raw).raw)
        }
    }

    @Test
    fun `distinguishes escaped equals in a parameter name from its separator`() {
        val raw = "epubcfi(/6/4[;x^=y=z^=v])"
        val cfi = BookOrbitCfi.parse(raw) as BookOrbitCfi.Point

        assertEquals(raw, cfi.serialize())
        assertEquals(mapOf("x=y" to listOf("z=v")), (cfi.path.components[1] as Step).parameters)
    }

    @Test
    fun `refuses temporal and spatial offsets as unsupported`() {
        listOf("epubcfi(/6/4~3)", "epubcfi(/6/4@10:20)").forEach { raw ->
            assertThrows(raw, BookOrbitCfi.UnsupportedException::class.java) { BookOrbitCfi.parse(raw) }
        }
    }

    @Test
    fun `rejects malformed paths rather than repairing them`() {
        listOf(
            "epubcfi(/6//4)",
            "epubcfi(/6/4:3!/4/2)",
            "epubcfi(/6/4!)",
            "epubcfi(/6!!/4)",
            "epubcfi(!/4)",
            "epubcfi(/6/4:2,/1:0,/3:5)",
            "epubcfi(/6/4:2;s=b)",
            "epubcfi(/6/4:2[;s=q])",
            "epubcfi(/6/4[a[b])",
            "epubcfi(/6/4[a)",
            "epubcfi(/6/4,/1)",
            "epubcfi(/6/4!,!/4/1:0,/4/1:2)",
            "epubcfi(/6/4!,:1,:2)",
            "epubcfi(/6/4,!,/4/1:2)",
            "epubcfi(/6/4!)",
        ).forEach { raw ->
            assertThrows(raw, BookOrbitCfi.ParseException::class.java) { BookOrbitCfi.parse(raw) }
        }
    }

    @Test
    fun `retains an unsupported foreign cfi with its file binding`() {
        val foreign = BookOrbitForeignCfi.capture(
            accountKey = "account",
            bookId = 12,
            fileId = 34,
            bindingRevision = 2,
            raw = "epubcfi(/6/4~3)",
        )

        assertEquals(34, foreign.fileId)
        assertEquals(2, foreign.bindingRevision)
        assertEquals("epubcfi(/6/4~3)", foreign.raw)
        assertNull(foreign.parsed)
        assertTrue(foreign.unresolvedReason!!.contains("temporal"))
    }
}
