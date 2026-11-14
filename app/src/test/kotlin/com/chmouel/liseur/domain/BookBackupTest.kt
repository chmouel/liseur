package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookBackupTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @Test
    fun `book index rejects bad digests, negative sizes and duplicate book ids`() {
        val valid = """{"format":1,"application":"liseur","books":[
            {"book_id":"book:1","title":"Dune","author":"Herbert","sha256":"$shaA","size":1234}
        ]}"""
        assertEquals(
            listOf(BackedUpBookFile("book:1", "Dune", "Herbert", shaA, 1234L)),
            decodeBookBackup(valid),
        )

        val badCases = listOf(
            // Bad digest
            """{"format":1,"application":"liseur","books":[{"book_id":"book:1","title":"Dune","author":null,"sha256":"not-a-sha","size":10}]}""",
            // Uppercase digest
            """{"format":1,"application":"liseur","books":[{"book_id":"book:1","title":"Dune","author":null,"sha256":"${"A".repeat(64)}","size":10}]}""",
            // Negative size
            """{"format":1,"application":"liseur","books":[{"book_id":"book:1","title":"Dune","author":null,"sha256":"$shaA","size":-1}]}""",
            // Duplicate book_id
            """{"format":1,"application":"liseur","books":[
                {"book_id":"book:1","title":"Dune","author":null,"sha256":"$shaA","size":10},
                {"book_id":"book:1","title":"Other","author":null,"sha256":"$shaB","size":20}
            ]}""",
        )
        for (bad in badCases) {
            var rejected = false
            try {
                decodeBookBackup(bad)
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Expected failure for: $bad", rejected)
        }
    }

    @Test
    fun `plan matches by URL, by an existing content copy, then by unambiguous title and author`() {
        val known = listOf(
            KnownBook("file:///library/exact.epub", "Exact", "Author A"),
            KnownBook("file:///data/user/0/pkg/files/books/$shaA.epub", "Different Title", "Author B"),
            KnownBook("file:///library/by-title.epub", "Poems", "Blake"),
        )
        val copies = mapOf(shaA to "file:///data/user/0/pkg/files/books/$shaA.epub")
        val files = listOf(
            BackedUpBookFile("file:///library/exact.epub", "Renamed", "Someone", shaB, 100),
            BackedUpBookFile("calibre:uuid-1", "Original Title", "Author B", shaA, 200),
            BackedUpBookFile("calibre:uuid-2", " poems ", "BLAKE", "c".repeat(64), 300),
            BackedUpBookFile("calibre:uuid-3", "Missing Book", "Unknown", "d".repeat(64), 400),
        )

        val plan = planBookRestore(files, known, copies)
        assertEquals(
            mapOf(
                "file:///library/exact.epub" to "file:///library/exact.epub",
                "calibre:uuid-1" to "file:///data/user/0/pkg/files/books/$shaA.epub",
                "calibre:uuid-2" to "file:///library/by-title.epub",
            ),
            plan.matched,
        )
        assertEquals(listOf(files[3]), plan.missing)
    }

    @Test
    fun `two archived books claiming one library book by title are both treated as missing`() {
        val known = listOf(
            KnownBook("file:///library/poems.epub", "Poems", "Blake"),
            KnownBook("file:///library/exact.epub", "Exact", "Author"),
        )
        val firstEdition = BackedUpBookFile("calibre:1", "Poems", "Blake", shaA, 100)
        val secondEdition = BackedUpBookFile("calibre:2", "Poems", "Blake", shaB, 200)
        val plan1 = planBookRestore(listOf(firstEdition, secondEdition), known, emptyMap())
        assertTrue(plan1.matched.isEmpty())
        assertEquals(listOf(firstEdition, secondEdition), plan1.missing)

        // Also if a library book is already another record's exact match, a title/author match cannot claim it
        val exactRecord = BackedUpBookFile("file:///library/exact.epub", "Other", "Other", shaA, 100)
        val titleClaimingExact = BackedUpBookFile("calibre:3", "Exact", "Author", shaB, 200)
        val plan2 = planBookRestore(listOf(exactRecord, titleClaimingExact), known, emptyMap())
        assertEquals(mapOf("file:///library/exact.epub" to "file:///library/exact.epub"), plan2.matched)
        assertEquals(listOf(titleClaimingExact), plan2.missing)
    }
}
