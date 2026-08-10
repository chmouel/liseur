package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.BookAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file that carries marks between devices.
 *
 * It has to come back exactly as it went out — a highlight that returns
 * without its colour, or a note without the passage it was about, is
 * worse than one that never came back at all, because nobody checks.
 */
// Robolectric only for a real org.json; nothing here touches Android.
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AnnotationBackupTest {

    private val full = BookAnnotation(
        id = "a1",
        bookId = "calibre:uuid-1",
        kind = "HIGHLIGHT",
        locatorJson = """{"href":"/ch1.xhtml","locations":{"progression":0.25}}""",
        text = "a passage worth keeping",
        note = "and what I thought of it",
        tint = "YELLOW",
        chapter = "Chapter One",
        position = 42,
        totalProgression = 0.25,
        createdAt = 1_700_000_000_000,
    )

    private val bare = BookAnnotation(
        id = "a2",
        bookId = "calibre:uuid-1",
        kind = "BOOKMARK",
        locatorJson = """{"href":"/ch2.xhtml"}""",
        createdAt = 1_700_000_001_000,
    )

    private fun roundTrip(books: List<BackedUpBook>): List<BackedUpBook> {
        val decoded = decodeAnnotationBackup(encodeAnnotationBackup(books))
        assertTrue("$decoded", decoded is BackupContents.Readable)
        return (decoded as BackupContents.Readable).books
    }

    @Test
    fun `a highlight comes back with everything it had`() {
        val back = roundTrip(
            listOf(BackedUpBook("calibre:uuid-1", "A Book", "An Author", listOf(full))),
        )
        assertEquals(full, back.single().annotations.single())
    }

    @Test
    fun `a bookmark with nothing written on it comes back empty, not blank`() {
        val back = roundTrip(listOf(BackedUpBook("calibre:uuid-1", null, null, listOf(bare))))
        assertEquals(bare, back.single().annotations.single())
    }

    @Test
    fun `what the book is called travels with its marks`() {
        val back = roundTrip(
            listOf(BackedUpBook("calibre:uuid-1", "A Book", "An Author", listOf(full, bare))),
        )
        assertEquals("A Book", back.single().title)
        assertEquals("An Author", back.single().author)
        assertEquals(2, back.single().annotations.size)
    }

    @Test
    fun `a file that is not ours says so rather than importing nothing`() {
        val result = decodeAnnotationBackup("this is not json at all")
        assertTrue("$result", result is BackupContents.Unreadable)
    }

    @Test
    fun `a file from a newer Liseur is refused rather than half read`() {
        val result = decodeAnnotationBackup("""{"format":99,"books":[]}""")
        assertTrue("$result", result is BackupContents.Unreadable)
    }

    @Test
    fun `valid json that is not a backup says so`() {
        val result = decodeAnnotationBackup("""{"something":"else"}""")
        assertTrue("$result", result is BackupContents.Unreadable)
    }

    @Test
    fun `an empty backup reads as empty rather than broken`() {
        val result = decodeAnnotationBackup(encodeAnnotationBackup(emptyList()))
        assertEquals(emptyList<BackedUpBook>(), (result as BackupContents.Readable).books)
    }

    @Test
    fun `a book from calibre-web is the same book everywhere`() {
        val backedUp = BackedUpBook("calibre:uuid-1", "A Book", "An Author", emptyList())
        val known = listOf(KnownBook("calibre:uuid-1", "Something Else Entirely", null))
        assertEquals("calibre:uuid-1", matchBackedUpBook(backedUp, known))
    }

    @Test
    fun `the same file added by hand on two phones is matched by name`() {
        val backedUp = BackedUpBook("file:///phone-one/a.epub", "A Book", "An Author", emptyList())
        val known = listOf(KnownBook("file:///phone-two/b.epub", "a book", "an author"))
        assertEquals("file:///phone-two/b.epub", matchBackedUpBook(backedUp, known))
    }

    @Test
    fun `two books of the same name are told apart by their author`() {
        val backedUp = BackedUpBook("file:///elsewhere.epub", "Poems", "Blake", emptyList())
        val known = listOf(
            KnownBook("file:///poems-keats.epub", "Poems", "Keats"),
            KnownBook("file:///poems-blake.epub", "Poems", "Blake"),
        )
        assertEquals("file:///poems-blake.epub", matchBackedUpBook(backedUp, known))
    }

    @Test
    fun `two books of the same name and no way to choose are left alone`() {
        val backedUp = BackedUpBook("file:///elsewhere.epub", "Poems", null, emptyList())
        val known = listOf(
            KnownBook("file:///poems-keats.epub", "Poems", "Keats"),
            KnownBook("file:///poems-blake.epub", "Poems", "Blake"),
        )
        assertEquals("file:///elsewhere.epub", matchBackedUpBook(backedUp, known))
    }

    @Test
    fun `marks for a book that is not here keep waiting for it`() {
        val backedUp = BackedUpBook("file:///elsewhere.epub", "A Book", "An Author", emptyList())
        assertEquals("file:///elsewhere.epub", matchBackedUpBook(backedUp, emptyList()))
    }

    @Test
    fun `a preview counts what would land here`() {
        val backedUp = listOf(
            BackedUpBook("calibre:uuid-1", "A Book", "An Author", listOf(full, bare)),
            BackedUpBook("file:///phone-one/b.epub", "Another", "Someone", listOf(full)),
            BackedUpBook("file:///gone.epub", "Not Here", "Nobody", listOf(bare)),
        )
        val known = listOf(
            KnownBook("calibre:uuid-1", "A Book", "An Author"),
            KnownBook("file:///phone-two/b.epub", "another", "someone"),
        )
        val match = previewBackupMatch(BackupContents.Readable(backedUp), known)
        assertEquals(3, match.books)
        assertEquals(4, match.marks)
        assertEquals(2, match.matchedBooks)
        assertEquals(3, match.matchedMarks)
    }

    @Test
    fun `a preview of nothing matching says so`() {
        val backedUp = listOf(BackedUpBook("file:///gone.epub", "Not Here", "Nobody", listOf(bare)))
        val match = previewBackupMatch(BackupContents.Readable(backedUp), emptyList())
        assertEquals(1, match.books)
        assertEquals(0, match.matchedBooks)
        assertEquals(0, match.matchedMarks)
    }
}
