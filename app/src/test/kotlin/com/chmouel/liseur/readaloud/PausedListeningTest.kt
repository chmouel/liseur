package com.chmouel.liseur.readaloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PausedListeningTest {
    private var stored: PausedReadAloud? = null
    private val places = mutableMapOf<String, SavedPlace>()
    private val store = object : PausedReadAloudStore {
        override suspend fun read() = stored
        override suspend fun write(record: PausedReadAloud?) {
            stored = record
        }
    }
    private val listening = PausedListening(
        store,
        queue = { action -> runBlocking { action() }; true },
        saved = { places[it] },
    )

    private fun pauseAt(revision: Long, locator: String) {
        places["book"] = SavedPlace(revision, locator)
        listening.paused("book", "heard", "voice", "en")
    }

    @Test
    fun `restores the pause while the saved place is unchanged`() = runBlocking {
        pauseAt(3, "page")
        assertEquals(PausedReadAloud("book", "heard", "voice", "en", 3, "page"), listening.toRestore("book"))
    }

    @Test
    fun `a place saved after the pause drops it`() = runBlocking {
        pauseAt(3, "page")
        places["book"] = SavedPlace(4, "next page")
        assertNull(listening.toRestore("book"))
        assertNull(stored)
    }

    @Test
    fun `the page captured after listening becomes the baseline`() = runBlocking {
        pauseAt(3, "sentence")
        places["book"] = SavedPlace(4, "page top")
        listening.captured("book", "page top")
        assertEquals(4L, listening.toRestore("book")?.revision)
    }

    @Test
    fun `a capture of another place drops the pause`() = runBlocking {
        pauseAt(3, "sentence")
        places["book"] = SavedPlace(4, "elsewhere")
        listening.captured("book", "page top")
        assertNull(stored)
    }

    @Test
    fun `forgetting another book keeps the pause`() = runBlocking {
        pauseAt(3, "page")
        listening.forget("other")
        assertEquals("book", stored?.bookId)
        listening.forget()
        assertNull(stored)
    }

    @Test
    fun `nothing is restored when the queue no longer runs`() = runBlocking {
        pauseAt(3, "page")
        val closed = PausedListening(store, queue = { false }, saved = { places[it] })
        assertNull(closed.toRestore("book"))
    }
}
