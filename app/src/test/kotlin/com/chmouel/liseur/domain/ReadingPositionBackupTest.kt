package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionBackupTest {
    private val position = BackedUpReadingPosition("old", "Poems", "Blake", "", 0.3, 10)

    @Test
    fun `position replacement does not match a same-title book by another author`() {
        assertEquals("old", matchBackedUpReadingPosition(position, listOf(KnownBook("new", "Poems", "Keats"))))
        assertEquals("old", matchBackedUpReadingPosition(position, listOf(KnownBook("new", "Poems", null))))
    }

    @Test
    fun `position replacement requires one matching title and author when the URL changes`() {
        assertEquals("new", matchBackedUpReadingPosition(position, listOf(KnownBook("new", " poems ", "BLAKE"))))
        assertEquals("old", matchBackedUpReadingPosition(position, listOf(
            KnownBook("one", "Poems", "Blake"), KnownBook("two", "Poems", "Blake"),
        )))
    }

    @Test
    fun `an unchanged URL keeps its position even when display metadata changed`() {
        assertEquals("old", matchBackedUpReadingPosition(position, listOf(KnownBook("old", "Edited title", "Edited author"))))
    }

    @Test
    fun `backed-up book URL matching returns null when no single book matches`() {
        assertNull(matchBackedUpBookUrl("old", "Poems", "Blake", emptyList()))
        assertNull(matchBackedUpBookUrl("old", " ", "Blake", listOf(KnownBook("new", " ", "Blake"))))
        assertNull(matchBackedUpBookUrl("old", "Poems", "Blake", listOf(
            KnownBook("one", "Poems", "Blake"), KnownBook("two", "Poems", "Blake"),
        )))
        assertEquals("new", matchBackedUpBookUrl("old", "Poems", " ", listOf(KnownBook("new", "poems", null))))
    }
}
