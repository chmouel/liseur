package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming a book to a sync server.
 *
 * The title-and-author identifier is compared as a plain string on the
 * server, so these tests are the interoperability contract: anything
 * that changes them changes which books two devices agree about, silently
 * and after the fact.
 */
class WorkIdentifiersTest {

    private val fingerprint = BookFingerprint(sha256 = "AABB", partialMd5 = "CCDD", size = 10)

    @Test
    fun `every identifier is offered, strongest first`() {
        val identifiers = WorkIdentifiers.of(
            fingerprint = fingerprint,
            sourceId = "komga:0K1Q",
            dcIdentifier = "urn:isbn:9780765387561",
            title = "A Memory Called Empire",
            author = "Arkady Martine",
        )

        assertEquals(
            listOf("sha256", "partial-md5", "source", "dc", "ta"),
            identifiers.map { it.kind },
        )
        // Hashes go up as hex and are compared lowercased everywhere.
        assertEquals("aabb", identifiers[0].value)
        assertEquals("ccdd", identifiers[1].value)
    }

    @Test
    fun `a book with no file still has something to say for itself`() {
        // Catalog entries that have not been downloaded are exactly the
        // ones a reader most wants their place kept for, so they resolve
        // on what the catalog knows rather than not at all.
        val identifiers = WorkIdentifiers.of(
            fingerprint = null,
            sourceId = "komga:2f9b",
            dcIdentifier = "urn:uuid:2f9b",
            title = "Piranesi",
            author = "Susanna Clarke",
        )

        assertEquals(listOf("source", "dc", "ta"), identifiers.map { it.kind })
    }

    @Test
    fun `a book with nothing to say for itself offers nothing`() {
        assertTrue(
            WorkIdentifiers.of(
                fingerprint = null,
                dcIdentifier = null,
                title = null,
                author = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `placeholder identifiers are not sent`() {
        // Several tools stamp the same dc:identifier into every file
        // they produce; taking one at face value would merge a reader's
        // whole library into one book.
        val identifiers = WorkIdentifiers.of(
            fingerprint = null,
            dcIdentifier = "urn:uuid:00000000-0000-0000-0000-000000000000",
            title = "Piranesi",
            author = null,
        )

        assertEquals(listOf("ta"), identifiers.map { it.kind })
    }

    @Test
    fun `case, accents and punctuation do not make two books`() {
        assertEquals(
            WorkIdentifiers.titleAuthor("L'Étranger", "Albert Camus"),
            WorkIdentifiers.titleAuthor("  l'etranger ", "albert   camus"),
        )
    }

    @Test
    fun `an author alone is not a book`() {
        // Otherwise every unnamed file by the same writer collapses into
        // a single identity and they trade each other's positions.
        assertNull(WorkIdentifiers.titleAuthor(null, "Arkady Martine"))
        assertNull(WorkIdentifiers.titleAuthor("   ", "Arkady Martine"))
    }

    @Test
    fun `a title without an author is still a title`() {
        assertEquals("piranesi|", WorkIdentifiers.titleAuthor("Piranesi", null))
    }

    @Test
    fun `two authors are not the same book by one of them`() {
        assertEquals("dune|frank herbert", WorkIdentifiers.titleAuthor("Dune", "Frank Herbert"))
        assertEquals(
            "dune|frank herbert brian herbert",
            WorkIdentifiers.titleAuthor("Dune", "Frank Herbert, Brian Herbert"),
        )
    }

    @Test
    fun `the stored work id is only a dc identifier when it came from the file`() {
        // The column holds the file's own identifier when it has one and
        // its title and author when it does not; only the first is
        // something the book actually claims about itself.
        assertEquals(
            "urn:isbn:9780765387561",
            WorkIdentifiers.dcFrom("urn:isbn:9780765387561", "A Memory Called Empire", "Arkady Martine"),
        )
        assertNull(
            WorkIdentifiers.dcFrom(
                workIdOf(null, "A Memory Called Empire", "Arkady Martine"),
                "A Memory Called Empire",
                "Arkady Martine",
            ),
        )
    }
}
