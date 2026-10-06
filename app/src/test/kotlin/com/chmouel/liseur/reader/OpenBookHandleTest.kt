package com.chmouel.liseur.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource

class OpenBookHandleTest {

    private class CountingContainer : Container<Resource> {
        var closed = 0
        override val entries: Set<Url> = emptySet()
        override fun get(url: Url): Resource? = null
        override fun close() {
            closed++
        }
    }

    private val containers = mutableListOf<CountingContainer>()
    private val handles = OpenBookHandles()

    private fun publication(): Publication {
        val container = CountingContainer().also(containers::add)
        return Publication(Manifest(metadata = Metadata(localizedTitle = LocalizedString("Book"))), container)
    }

    private suspend fun open(bookId: String = "book", keep: (OpenBookHandle) -> Boolean = { true }) =
        handles.open(bookId, keep) { publication() }

    @Test
    fun `closing work waits for the last holder and the book closes once`() = runTest {
        val reader = open()!!
        val session = handles.acquire("book")!!
        assertSame(reader, session)
        val closed = mutableListOf<String>()

        reader.release { closed += "reader" }
        assertEquals(emptyList<String>(), closed)
        assertEquals(0, containers.single().closed)

        session.release { closed += "session" }
        assertEquals(listOf("reader", "session"), closed)
        assertEquals(1, containers.single().closed)
    }

    @Test
    fun `reopening while held shares the publication`() = runTest {
        val session = open()!!
        val reader = open()!!
        assertSame(session.publication, reader.publication)
        assertEquals(1, containers.size)
    }

    @Test
    fun `a closed book is opened afresh`() = runTest {
        val first = open()!!
        first.release()
        assertNull(handles.acquire("book"))
        val second = open()!!
        assertNotSame(first, second)
        assertEquals(2, containers.size)
        assertEquals(1, containers[0].closed)
        assertEquals(0, containers[1].closed)
    }

    @Test
    fun `different books stay apart`() = runTest {
        val one = open("one")!!
        val two = open("two")!!
        assertNotSame(one.publication, two.publication)
        one.release()
        assertEquals(listOf(1, 0), containers.map { it.closed })
    }

    @Test
    fun `a declined hold is released at once`() = runTest {
        assertNull(open(keep = { false }))
        assertEquals(1, containers.single().closed)
        assertNull(handles.acquire("book"))
    }

    @Test
    fun `a failed open leaves nothing held`() = runTest {
        assertNull(handles.open("book", { true }) { null })
        assertNull(handles.acquire("book"))
    }

    @Test
    fun `listening is remembered for whoever closes last`() = runTest {
        val reader = open()!!
        val session = handles.acquire("book")!!
        var adopted: Boolean? = null
        reader.release { adopted = !reader.listened }
        assertFalse(reader.listened)
        session.noteListened()
        session.release()
        assertEquals(false, adopted)
        assertTrue(session.listened)
    }
}
