package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcoverDetailsUpdateTest {
    private val baseLocal = LocalDetails(
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        description = "Local description",
        seriesName = "Middle-earth",
        seriesIndex = 1.0,
    )

    private val baseRemote = HardcoverEditionDetails(
        editionId = 5,
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        description = "Local description",
        coverUrl = null,
        seriesName = "Middle-earth",
        seriesPosition = 1.0,
    )

    @Test
    fun `identical fields produce no proposals`() {
        val proposals = proposeUpdates(
            baseLocal.copy(title = "  The Hobbit "),
            baseRemote,
        )
        assertTrue(proposals.isEmpty())
    }

    @Test
    fun `cover is ticked by default even when a local cover exists`() {
        val proposals = proposeUpdates(
            baseLocal,
            baseRemote.copy(coverUrl = "https://assets.hardcover.app/books/1/cover.jpg"),
        )
        val cover = proposals.single()
        assertEquals(DetailField.COVER, cover.field)
        assertEquals("https://assets.hardcover.app/books/1/cover.jpg", cover.newCoverUrl)
        assertTrue(cover.defaultChecked)
    }

    @Test
    fun `a cover already imported from the same URL is not offered again`() {
        val url = "https://assets.hardcover.app/books/1/cover.jpg"
        assertTrue(proposeUpdates(baseLocal.copy(importedCoverUrl = url), baseRemote.copy(coverUrl = url)).isEmpty())

        val newer = proposeUpdates(
            baseLocal.copy(importedCoverUrl = url),
            baseRemote.copy(coverUrl = "https://assets.hardcover.app/books/1/other.jpg"),
        )
        assertEquals(DetailField.COVER, newer.single().field)
    }

    @Test
    fun `blank local fields are ticked by default while overwriting non-blank fields are unticked`() {
        val local = LocalDetails(
            title = "Old Title",
            author = "   ",
            description = null,
            seriesName = null,
            seriesIndex = null,
        )
        val remote = HardcoverEditionDetails(
            editionId = 5,
            title = "New Title",
            author = "New Author",
            description = "New Description",
            coverUrl = null,
            seriesName = "New Series",
            seriesPosition = 2.0,
        )

        val byField = proposeUpdates(local, remote).associateBy { it.field }

        assertFalse(byField.getValue(DetailField.TITLE).defaultChecked)
        assertTrue(byField.getValue(DetailField.AUTHOR).defaultChecked)
        assertTrue(byField.getValue(DetailField.DESCRIPTION).defaultChecked)
        assertTrue(byField.getValue(DetailField.SERIES).defaultChecked)
    }

    @Test
    fun `series differs when either name or index differs and stays unticked if local has a series`() {
        val indexDiff = proposeUpdates(
            baseLocal,
            baseRemote.copy(seriesPosition = 2.0),
        ).single()
        assertEquals(DetailField.SERIES, indexDiff.field)
        assertEquals(1.0, indexDiff.currentSeriesIndex)
        assertEquals(2.0, indexDiff.newSeriesIndex)
        assertFalse(indexDiff.defaultChecked)

        val nameDiff = proposeUpdates(
            baseLocal,
            baseRemote.copy(seriesName = "The Lord of the Rings"),
        ).single()
        assertEquals(DetailField.SERIES, nameDiff.field)
        assertEquals("Middle-earth", nameDiff.currentText)
        assertEquals("The Lord of the Rings", nameDiff.newText)
        assertFalse(nameDiff.defaultChecked)
    }

    @Test
    fun `values exceeding wire byte limits are omitted`() {
        val overTitle = "a".repeat(BookMetadataOverrideRepository.MAX_TITLE_BYTES + 1)
        val overAuthor = "b".repeat(BookMetadataOverrideRepository.MAX_AUTHOR_BYTES + 1)
        val overDesc = "c".repeat(BookMetadataOverrideRepository.wireLimit(MetadataOverrideFields.DESCRIPTION) + 1)

        val proposals = proposeUpdates(
            baseLocal,
            baseRemote.copy(
                title = overTitle,
                author = overAuthor,
                description = overDesc,
            ),
        )

        assertTrue(proposals.isEmpty())
    }
}
