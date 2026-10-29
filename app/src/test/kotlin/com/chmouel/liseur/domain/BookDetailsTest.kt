package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookDetailsTest {
    @Test
    fun `the raw cap never splits a surrogate pair`() {
        val text = "a".repeat(9) + "😀"
        assertEquals("a".repeat(9), capUtf16(text, 10))
        assertEquals(text, capUtf16(text, 11))
    }

    @Test
    fun `descriptions are cut at the code point bound with an ellipsis`() {
        val long = "😀".repeat(BookDetailsBounds.DESCRIPTION_CODE_POINTS + 5)
        val cut = cleanDescription(long)!!
        assertEquals(BookDetailsBounds.DESCRIPTION_CODE_POINTS, cut.codePointCount(0, cut.length))
        assertEquals("…", cut.takeLast(1))
        val exact = "x".repeat(BookDetailsBounds.DESCRIPTION_CODE_POINTS)
        assertEquals(exact, cleanDescription(exact))
    }

    @Test
    fun `descriptions lose image placeholders and runs of blank lines`() {
        assertEquals("One\n\nTwo", cleanDescription("\n\uFFFC One \n\n\n \n Two\n\n"))
        assertNull(cleanDescription(" \uFFFC \n "))
    }

    @Test
    fun `publication dates keep the precision they were given`() {
        assertEquals("1962", normalizePublished("1962"))
        assertEquals("1962-04", normalizePublished("1962-04"))
        assertEquals("1962-04-12", normalizePublished("1962-04-12T23:00:00+00:00"))
        assertNull(normalizePublished("0101-01-01T00:00:00+00:00"))
        assertNull(normalizePublished("1962-02-30"))
        assertNull(normalizePublished("someday"))
    }

    @Test
    fun `identifiers read their scheme from a urn`() {
        assertEquals(Identifier("isbn", "978-0-14"), identifierOf("urn:isbn:978-0-14"))
        assertEquals(Identifier("uuid", "abc"), identifierOf("urn:uuid:abc"))
        assertEquals(Identifier("isbn", "9780"), identifierOf("9780", "ISBN"))
        assertEquals(Identifier("", "plain"), identifierOf("plain"))
        assertNull(identifierOf("  "))
    }

    @Test
    fun `single values come from the catalog and lists merge without duplicates`() {
        val catalog = MetadataSnapshot(
            subtitle = "Catalog subtitle",
            languages = listOf("EN-us"),
            subjects = listOf("Science  Fiction"),
            contributors = listOf(Contributor("aut", "Ursula K. Le Guin")),
            identifiers = listOf(Identifier("isbn", "978-0-441-47812-5")),
            pageCount = 0,
        )
        val file = MetadataSnapshot(
            subtitle = "File subtitle",
            description = "From the file",
            languages = listOf("en-US", "fr"),
            subjects = listOf("science fiction", "Anarchism"),
            contributors = listOf(Contributor("author", "ursula k. le guin"), Contributor("trl", "Someone")),
            identifiers = listOf(Identifier("isbn", "9780441478125")),
            pageCount = 300,
            fileSizeBytes = 1234,
        )
        val merged = resolveMetadata(mapOf(MetadataSource.CATALOG to catalog, MetadataSource.FILE to file))!!
        assertEquals("Catalog subtitle", merged.subtitle)
        assertEquals("From the file", merged.description)
        assertEquals(listOf("en-US", "fr"), merged.languages)
        assertEquals(listOf("Science Fiction", "Anarchism"), merged.subjects)
        assertEquals(
            listOf(Contributor("author", "Ursula K. Le Guin"), Contributor("translator", "Someone")),
            merged.contributors,
        )
        assertEquals(1, merged.identifiers.size)
        assertEquals(300, merged.pageCount)
        assertEquals(1234L, merged.fileSizeBytes)
    }

    @Test
    fun `bounds apply after the merge and the file keeps half the room`() {
        val catalog = MetadataSnapshot(subjects = (1..40).map { "c$it" })
        val file = MetadataSnapshot(subjects = (1..40).map { "f$it" })
        val merged = resolveMetadata(mapOf(MetadataSource.CATALOG to catalog, MetadataSource.FILE to file))!!
        assertEquals(BookDetailsBounds.SUBJECTS, merged.subjects.size)
        assertEquals(16, merged.subjects.count { it.startsWith("f") })

        val few = resolveMetadata(
            mapOf(MetadataSource.CATALOG to catalog, MetadataSource.FILE to MetadataSnapshot(subjects = listOf("f"))),
        )!!
        assertEquals(31, few.subjects.count { it.startsWith("c") })
        assertEquals("f", few.subjects.last())
    }

    @Test
    fun `out of range numbers and unknown sources are dropped`() {
        assertNull(validPageCount(0))
        assertEquals(100_000, validPageCount(100_000))
        assertNull(validPageCount(100_001))
        assertNull(validByteSize(-1))
        assertNull(validByteSize((1L shl 53) + 1))
        assertNull(validSeriesPosition(Double.NaN))
        assertNull(MetadataSource.of("cloud"))
        assertNull(resolveMetadata(emptyMap()))
    }
}
