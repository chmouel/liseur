package com.chmouel.liseur.data.kosync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.BookFingerprints
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Turning a stored exact anchor into the `progress` string of a push.
 *
 * The book to convert is the book being pushed: the same partial MD5, the
 * same file, the same row. Anything short of that, or any place that
 * cannot be named exactly, answers null and the push keeps its
 * percentage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KosyncProgressPointerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun shelve(file: File, url: String = BOOK): Book {
        val book = Book(
            url = url,
            title = "A Memory Called Empire",
            author = "Arkady Martine",
            coverPath = null,
            source = null,
            addedAt = NOW,
            lastOpenedAt = NOW,
            localUri = file.toURI().toString(),
            fileModifiedAt = NOW,
            remoteUuid = "uuid-1",
        )
        db.bookDao().upsert(book)
        return requireNotNull(db.bookDao().getByUrl(url))
    }

    private fun pointer(stamp: (File) -> FileStamp? = ::stampOf) =
        KosyncProgressPointer(db.bookDao(), stamp)

    private suspend fun document(file: File): String = BookFingerprints.partialMd5(file)

    private suspend fun convert(
        book: Book,
        file: File,
        locator: String? = locator(),
        source: KosyncProgressPointer = pointer(),
    ): String? = source.pointer(book, document(file), locator)

    // -- The shapes a book can be shelved in -------------------------------

    @Test
    fun `a linear=no itemref still takes its DocFragment index`() = runTest {
        val file = chapterBook()

        assertEquals(
            "/body/DocFragment[2]/body/p[1]/text()[1].8",
            convert(shelve(file), file),
        )
    }

    @Test
    fun `an OPF in a subdirectory and an escaped href are resolved`() = runTest {
        val file = epub(
            opfPath = "OPS/book.opf",
            entries = mapOf(
                "OPS/Text/chapter 1.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Call me Ishmael.</p></body></html>""",
            ),
            manifest = listOf(Triple("c1", "Text/chapter%201.xhtml", true)),
        )

        assertEquals(
            "/body/DocFragment[1]/body/p[1]/text()[1].8",
            convert(shelve(file), file, locator(href = "/OPS/Text/chapter%201.xhtml")),
        )
    }

    // -- Everything that declines ------------------------------------------

    @Test
    fun `a locator with only a progression declines`() = runTest {
        val file = chapterBook()

        assertNull(
            convert(
                shelve(file),
                file,
                """{"href":"OPS/Text/chapter%201.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.4},"text":{"highlight":"Ishmael."}}""",
            ),
        )
    }

    @Test
    fun `a book opened through a content provider declines`() = runTest {
        val file = chapterBook()
        val remote = shelve(file).copy(localUri = "content://com.example.documents/12")
        db.bookDao().upsert(remote)

        assertNull(convert(remote, file))
    }

    @Test
    fun `a resource the book does not hold declines`() = runTest {
        val file = chapterBook()

        assertNull(convert(shelve(file), file, locator(href = "OPS/missing.xhtml")))
    }

    @Test
    fun `a chapter past the size the parser reads declines`() = runTest {
        val huge = "<html><body><p>" + "a".repeat(5 * 1024 * 1024) + "</p></body></html>"
        val file = epub(
            entries = mapOf("OPS/huge.xhtml" to huge),
            manifest = listOf(Triple("huge", "huge.xhtml", true)),
        )

        assertNull(convert(shelve(file), file, locator(href = "OPS/huge.xhtml")))
    }

    @Test
    fun `a file that is not the document being pushed declines`() = runTest {
        val file = chapterBook()
        val book = shelve(file)

        assertNull(pointer().pointer(book, "0".repeat(32), locator()))
    }

    @Test
    fun `a file that changes under the read declines`() = runTest {
        val file = chapterBook()
        val book = shelve(file)
        var reads = 0

        // The second stamp is the one taken after the conversion: a length the
        // first reading did not see is a file replaced in the meantime, even
        // though the sampled bytes are the ones that were asked about.
        val moved = pointer {
            reads++
            FileStamp(it.length() + if (reads == 1) 0 else 1, it.lastModified())
        }

        assertNull(convert(book, file, source = moved))
        assertEquals(2, reads)
    }

    @Test
    fun `the file is stat'ed on both sides of the conversion`() = runTest {
        val file = chapterBook()
        var reads = 0

        assertEquals(
            "/body/DocFragment[2]/body/p[1]/text()[1].8",
            convert(
                shelve(file),
                file,
                source = pointer { candidate -> reads++; stampOf(candidate) },
            ),
        )
        assertEquals(2, reads)
    }

    @Test
    fun `a book row that changed under the read declines`() = runTest {
        val file = chapterBook()
        val stale = shelve(file)
        db.bookDao().upsert(stale.copy(fileModifiedAt = NOW + 1))

        assertNull(convert(stale, file))
    }

    @Test
    fun `a book row moved to another file under the read declines`() = runTest {
        val file = chapterBook()
        val stale = shelve(file)
        val moved = file.copyTo(folder.root.resolve("moved.epub"))
        db.bookDao().upsert(stale.copy(localUri = moved.toURI().toString()))

        assertNull(convert(stale, file))
    }

    @Test
    fun `cancellation is let through`() = runTest {
        val file = chapterBook()
        val book = shelve(file)

        var cancelled = false
        try {
            convert(book, file, source = pointer { throw CancellationException("called off") })
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
    }

    @Test
    fun `a book whose file is gone declines`() = runTest {
        val file = chapterBook()
        val book = shelve(file)
        val document = document(file)
        file.delete()

        assertNull(pointer().pointer(book, document, locator()))
    }

    // -- Fixtures ----------------------------------------------------------

    private fun chapterBook(): File = epub(
        entries = mapOf(
            "OPS/cover.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Cover</p></body></html>""",
            "OPS/Text/chapter 1.xhtml" to
                """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Call me Ishmael.</p></body></html>""",
        ),
        manifest = listOf(
            Triple("cover", "cover.xhtml", false),
            Triple("c1", "Text/chapter%201.xhtml", true),
        ),
    )

    private fun epub(
        opfPath: String = "OPS/book.opf",
        entries: Map<String, String>,
        manifest: List<Triple<String, String, Boolean>>,
    ): File {
        val file = folder.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val items = manifest.joinToString("") { (id, href, _) ->
                """<item id="$id" href="$href" media-type="application/xhtml+xml"/>"""
            }
            val refs = manifest.joinToString("") { (id, _, linear) ->
                if (linear) """<itemref idref="$id"/>""" else """<itemref idref="$id" linear="no"/>"""
            }
            put(
                opfPath,
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier><dc:title>Book</dc:title><dc:language>en</dc:language></metadata>
<manifest>$items</manifest><spine>$refs</spine></package>""",
            )
            entries.forEach { (name, body) -> put(name, body) }
        }
        return file
    }

    private fun locator(href: String = "OPS/Text/chapter%201.xhtml") =
        """{"href":"$href","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"body > p:nth-of-type(1)","totalProgression":0.4},"text":{"before":"Call me ","highlight":"Ishmael."}}"""

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOK = "calibre://books.example/12"
    }
}
