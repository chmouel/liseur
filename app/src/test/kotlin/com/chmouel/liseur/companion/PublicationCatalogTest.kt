package com.chmouel.liseur.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The catalog over a real EPUB opened by Readium: chapters from the
 * contents, a file holding two chapters, a chapter spread over two files,
 * and a contents entry whose anchor is missing.
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class PublicationCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun open(missingAnchor: Boolean = false): PublicationCatalog = runBlocking {
        val epub = File(folder.root, "book-$missingAnchor.epub").also { writeEpub(it, missingAnchor) }
        PublicationCatalog(openPublication(epub))
    }

    private fun texts(passages: List<Passage<*>>) = passages.joinToString("\n") { it.text }

    @Test
    fun `asking in chapter 5 reaches the book up to the selection and nothing from it on`() = runBlocking {
        val catalog = open()
        assertEquals(6, catalog.map.chapterCount)

        val all = catalog.passagesBefore(AskingScope(6, 6))
        val section2 = all.first { "FIVEB" in it.text }
        val scope = catalog.scopeOf(section2.place, translated = false)!!
        assertEquals(4, scope.lo)
        assertEquals(4, scope.until!!.resource)

        val sent = texts(catalog.passagesBefore(scope))
        for (token in listOf("ONE", "TWO", "THREE", "FOUR", "FIVEA")) assertTrue("$token missing", "Mira $token" in sent)
        for (token in listOf("FIVEB", "SIX")) assertFalse("$token leaked", token in sent)

        // Chapters alone, as recaps ask, still stop before chapter 5.
        assertFalse("FIVEA" in texts(catalog.passagesBefore(AskingScope(4, 6))))
    }

    @Test
    fun `a selection reaches the paragraphs before it in its own file and not its own`() = runBlocking {
        val catalog = open()
        val ledger = catalog.passagesBefore(AskingScope(6, 6)).first { "ledger" in it.text }
        val scope = catalog.scopeOf(ledger.place, translated = false)!!
        assertEquals(0, scope.lo)
        assertTrue(scope.hasEarlier)
        assertEquals("Mira ONE walks to the mill.", texts(catalog.passagesBefore(scope)))

        // A translated page, or a selector that cannot be found, starts at the file.
        assertTrue(catalog.passagesBefore(catalog.scopeOf(ledger.place, translated = true)!!).isEmpty())
        val unknown = ledger.place.copy(
            locations = ledger.place.locations.copy(
                otherLocations = ledger.place.locations.otherLocations + ("cssSelector" to "#nowhere"),
            ),
        )
        val lost = catalog.scopeOf(unknown, translated = false)!!
        assertEquals(ReadPoint(0, 0), lost.until)
        assertFalse(lost.hasEarlier)
    }

    @Test
    fun `two chapters in one file are told apart at the contents anchor`() = runBlocking {
        val catalog = open()
        val all = catalog.passagesBefore(AskingScope(6, 6))
        assertEquals(ChapterRange(1, 1), all.first { "TWO" in it.text }.range)
        assertEquals(ChapterRange(2, 2), all.first { "THREE" in it.text }.range)

        // Going back to chapter 3 narrows what can be reached, even with
        // later chapters already walked.
        val three = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = false)!!
        assertEquals(2, three.lo)
        val sent = texts(catalog.passagesBefore(three))
        assertTrue("Mira TWO" in sent)
        assertFalse("THREE" in sent)

        // A translated page cannot place the selection inside its file.
        val translated = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = true)!!
        assertEquals(1, translated.lo)
        val withheld = texts(catalog.passagesBefore(translated))
        assertTrue("Mira ONE" in withheld)
        assertFalse("TWO" in withheld)
    }

    @Test
    fun `the first paragraph of the book has nothing before it`() = runBlocking {
        val catalog = open()
        val first = catalog.passagesBefore(AskingScope(6, 6)).first { "ONE" in it.text }
        val scope = catalog.scopeOf(first.place, translated = false)!!
        assertEquals(0, scope.lo)
        assertTrue(catalog.passagesBefore(scope).isEmpty())
    }

    @Test
    fun `a missing anchor gives the whole file both chapters and withholds it from either`() = runBlocking {
        val catalog = open(missingAnchor = true)
        val all = catalog.passagesBefore(AskingScope(6, 6))
        assertEquals(ChapterRange(1, 2), all.first { "TWO" in it.text }.range)
        assertEquals(ChapterRange(1, 2), all.first { "THREE" in it.text }.range)

        val scope = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = false)!!
        assertEquals(1, scope.lo)
        assertFalse("TWO" in texts(catalog.passagesBefore(AskingScope(scope.lo, scope.count))))
        // The text before the selection is reachable whatever its chapter.
        val sent = texts(catalog.passagesBefore(scope))
        assertTrue("Mira ONE" in sent)
        assertTrue("Mira TWO" in sent)
        assertFalse("THREE" in sent)

        // From chapter 4 on, the whole file lies before and is reachable.
        val four = catalog.scopeOf(all.first { "FOUR" in it.text }.place, translated = false)!!
        assertTrue("Mira THREE" in texts(catalog.passagesBefore(four)))
    }

    @Test
    fun `the story so far lists chapters before the reader and opens them at their anchor`() = runBlocking {
        for (missing in listOf(false, true)) {
            val catalog = open(missingAnchor = missing)
            val all = catalog.passagesBefore(AskingScope(6, 6))
            val six = catalog.chaptersBefore(catalog.scopeOf(all.first { "SIX" in it.text }.place, translated = false)!!)
            assertEquals(listOf(0, 1, 2, 3, 4), six.map { it.unit })
            val three = six.single { it.unit == 2 }.start
            if (missing) {
                // An anchor that is not in the file cannot be opened; the top of the file is not offered instead.
                assertEquals(null, three)
            } else {
                assertEquals("two.xhtml", three!!.href.toString().substringAfterLast('/'))
                assertEquals(listOf("c3"), three.locations.fragments)
            }
            assertEquals(emptyList<String>(), six.single { it.unit == 0 }.start!!.locations.fragments)
        }

        val catalog = open()
        val place = catalog.passagesBefore(AskingScope(6, 6)).first { "THREE" in it.text }.place
        assertEquals(listOf(0, 1), catalog.chaptersBefore(catalog.scopeOf(place, translated = false)!!).map { it.unit })
        assertEquals(listOf(0), catalog.chaptersBefore(catalog.scopeOf(place, translated = true)!!).map { it.unit })
    }

    @Test
    fun `the story so far leaves out a chapter the contents list before the reader but the book puts after`() = runBlocking {
        val epub = File(folder.root, "out-of-order.epub")
        writeBook(
            epub,
            spine = listOf("one", "two", "three", "four", "five"),
            contents = """
                  <li><a href="one.xhtml">One</a></li>
                  <li><a href="five.xhtml">Five</a></li>
                  <li><a href="three.xhtml">Three</a></li>""",
            pages = listOf("one", "two", "three", "four", "five").associateWith { page("<p>Mira ${it.uppercase()} waits.</p>") },
        )
        val publication = openPublication(epub)
        try {
            val catalog = PublicationCatalog(publication)
            val four = catalog.passagesBefore(AskingScope(3, 3)).first { "FOUR" in it.text }
            val scope = catalog.scopeOf(four.place, translated = false)!!
            assertEquals(2, scope.lo)
            assertEquals(listOf(0), catalog.chaptersBefore(scope).map { it.unit })
        } finally {
            publication.close()
        }
    }

    @Test
    fun `refs stay the same when the book is opened again`() = runBlocking {
        val first = open().passagesBefore(AskingScope(6, 6)).map { it.ref to it.hash }
        val again = open().passagesBefore(AskingScope(6, 6)).map { it.ref to it.hash }
        assertEquals(first, again)
        assertEquals(first.size, first.map { it.first }.distinct().size)
    }

    @Test
    fun `codex streams the same passages and resumes within shared files`() = runBlocking {
        for (missing in listOf(false, true)) {
            val epub = File(folder.root, "codex-$missing.epub").also { writeEpub(it, missing) }
            val publication = openPublication(epub)
            try {
                val catalog = PublicationCatalog(publication)
                val expected = catalog.passagesBefore(AskingScope(6, 6))
                for (from in listOf(0, 2, 4)) {
                    val units = linkedMapOf<Int, List<Passage<org.readium.r2.shared.publication.Locator>>>()
                    PublicationCodexSource(publication, catalog.map).scan(from) { unit, passages -> units[unit] = passages }
                    assertEquals((from until 6).toList(), units.keys.toList())
                    for ((unit, passages) in units) {
                        assertEquals(expected.filter { it.range.hi == unit }.map { it.ref to it.hash }, passages.map { it.ref to it.hash })
                        assertEquals(expected.filter { it.range.hi == unit }.map { it.range }, passages.map { it.range })
                    }
                }
            } finally {
                publication.close()
            }
        }
    }

    @Test
    fun `codex does not mark unreadable chapters as empty and complete`() = runBlocking {
        val epub = File(folder.root, "unreadable.epub").also { writeEpub(it, false, missingFile = true) }
        val publication = openPublication(epub)
        try {
            val completed = mutableListOf<Int>()
            try {
                PublicationCodexSource(publication, PublicationCatalog(publication).map).scan(0) { unit, _ -> completed += unit }
                org.junit.Assert.fail("Unreadable resources must fail the scan")
            } catch (_: IllegalStateException) {
                assertTrue(completed.isNotEmpty())
                assertFalse(4 in completed)
                assertFalse(5 in completed)
            }
        } finally {
            publication.close()
        }
    }

    @Test
    fun `codex commits chapters sharing one file as the walk leaves them`() = runBlocking {
        val epub = File(folder.root, "one-file.epub")
        writeBook(
            epub,
            spine = listOf("all", "last"),
            contents = """
                  <li><a href="all.xhtml">One</a></li>
                  <li><a href="all.xhtml#c2">Two</a></li>
                  <li><a href="all.xhtml#c3">Three</a></li>
                  <li><a href="last.xhtml">Four</a></li>""",
            pages = mapOf(
                "all" to page(
                    "<p>Mira ONE walks to the mill.</p>",
                    "<h1 id=\"c2\">Two</h1>",
                    "<p>Mira TWO meets Tobin.</p>",
                    "<h1 id=\"c3\">Three</h1>",
                    "<p>Mira THREE crosses the river.</p>",
                ),
                "last" to null,
            ),
        )
        val publication = openPublication(epub)
        try {
            val completed = mutableListOf<Int>()
            try {
                PublicationCodexSource(publication, PublicationCatalog(publication).map).scan(0) { unit, _ -> completed += unit }
                org.junit.Assert.fail("Unreadable resources must fail the scan")
            } catch (_: IllegalStateException) {
                // The scan fails at the missing file, after the shared file's first chapters were kept.
                assertEquals(listOf(0, 1), completed)
            }
        } finally {
            publication.close()
        }
    }

    @Test
    fun `codex's stored passages give lookups and recaps exactly what the walk gives`() = runBlocking {
        for (missing in listOf(false, true)) {
            assertStoredMatchesWalk(File(folder.root, "seed-$missing.epub").also { writeEpub(it, missing) })
        }
    }

    @Test
    fun `codex's stored passages match the walk across a name cut in two and past ten refs`() = runBlocking {
        // The name straddles the cut, so the stored first part carries a run-on the walk does not have.
        val long = "lorem ".repeat(198) + "ipsum Mira Hale " + "dolor ".repeat(60)
        assertTrue(long.length > PassageText.MAX_LENGTH)
        val names = (1..11).map { "c$it" }
        val epub = File(folder.root, "long.epub").also { target ->
            writeBook(
                target,
                spine = names,
                contents = names.joinToString("\n") { """<li><a href="$it.xhtml">${it.uppercase()}</a></li>""" },
                pages = names.associateWith { name ->
                    if (name == "c1") {
                        page(*((0 until 11).map { "<p>Mira walks to mill $it.</p>" } + "<p>$long</p>").toTypedArray())
                    } else {
                        page("<p>Mira ${name.uppercase()} waits.</p>")
                    }
                },
            )
        }
        assertStoredMatchesWalk(epub) { all ->
            val first = all.filter { it.resource == 0 }
            assertTrue("a part ends on the name's first word", first.any { it.text.endsWith("Mira") })
            assertTrue("the next part starts on its second", first.any { it.text.startsWith("Hale") })
            assertFalse("refs sorted as text are not in reading order", all.map { it.ref } == all.map { it.ref }.sorted())
        }
    }

    /** Scans [epub] into Codex and checks every scope and chapter reads the same from it as from the walk, without walking. */
    private suspend fun assertStoredMatchesWalk(epub: File, inspect: (List<Passage<Locator>>) -> Unit = {}) {
        val publication = openPublication(epub)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val live = PublicationCatalog(publication)
            val count = live.map.chapterCount
            val store = CodexStore(database) { it }
            val identity = CodexStore.identity("sha", live.map)
            val book = CodexBookRow("book", identity, count)
            store.prepare(book)
            PublicationCodexSource(publication, live.map).scan(0) { unit, passages -> store.save(book, unit, emptyMap(), passages) }
            // Rewritten last first, rows unchanged, so storage order runs against reading order and only the store's sort restores it.
            database.codex().let { it.putPassages(it.passagesUpTo("book", count, count).asReversed()) }
            val walks = Walks(PublicationCatalog(publication))
            val seeded = seeded(walks, store, identity)

            val all = live.passagesBefore(AskingScope(count, count))
            inspect(all)
            val scopes = all.mapNotNull { live.scopeOf(it.place, translated = false) } +
                all.mapNotNull { live.scopeOf(it.place, translated = true) } +
                (0..count).map { AskingScope(it, count) }
            for (scope in scopes) assertEquals("$scope", shape(live.passagesBefore(scope)), shape(seeded.passagesBefore(scope)))
            for (unit in 0 until count) assertEquals("chapter $unit", shape(live.passagesOf(unit)), shape(seeded.passagesOf(unit)))
            assertEquals("the book is not walked again", 0, walks.count)
        } finally {
            database.close()
            publication.close()
        }
    }

    @Test
    fun `lookups walk the book when codex lacks a chapter they need or scanned another version`() = runBlocking {
        val publication = openPublication(File(folder.root, "partial.epub").also { writeEpub(it, false) })
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val live = PublicationCatalog(publication)
            val store = CodexStore(database) { it }
            val identity = CodexStore.identity("sha", live.map)
            val book = CodexBookRow("book", identity, live.map.chapterCount)
            store.prepare(book)
            PublicationCodexSource(publication, live.map).scan(0) { unit, passages ->
                if (unit != 3) store.save(book, unit, emptyMap(), passages)
            }
            val fiveB = live.passagesBefore(AskingScope(6, 6)).first { "FIVEB" in it.text }
            val scope = live.scopeOf(fiveB.place, translated = false)!!

            val walks = Walks(PublicationCatalog(publication))
            assertEquals(shape(live.passagesBefore(scope)), shape(seeded(walks, store, identity).passagesBefore(scope)))
            assertEquals("chapter 4 is missing, so the book is walked", 1, walks.count)
            seeded(walks, store, identity).passagesBefore(AskingScope(3, 6))
            assertEquals("chapters 1 to 3 are all stored", 1, walks.count)

            seeded(walks, store, CodexStore.identity("other", live.map)).passagesBefore(AskingScope(3, 6))
            assertEquals("another version's scan is not used", 2, walks.count)

            assertEquals(shape(live.passagesOf(3)), shape(seeded(walks, store, identity).passagesOf(3)))
            assertEquals("chapter 4 alone is walked for", 3, walks.count)
        } finally {
            database.close()
            publication.close()
        }
    }

    private class Walks(val live: PublicationCatalog) {
        var count = 0
        val catalog = object : BookCatalog<Locator> by live {
            override suspend fun passagesBefore(scope: AskingScope) = live.passagesBefore(scope).also { count++ }
            override suspend fun passagesOf(unit: Int) = live.passagesOf(unit).also { count++ }
        }
    }

    private fun seeded(walks: Walks, store: CodexStore, identity: String) = CodexBackedCatalog(
        live = walks.catalog,
        stored = { through, ceiling, resource -> store.stored("book", identity, through, ceiling, resource) },
        storedUnit = { store.storedUnit("book", identity, it) },
        log = { throw it },
    )

    private fun shape(passages: List<Passage<Locator>>) = passages.map {
        listOf(it.ref, it.hash, it.resource, it.text, it.range, it.end, it.folded, it.place.toJSON().toString())
    }

    private suspend fun openPublication(epub: File): Publication {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val http = DefaultHttpClient()
        val assets = AssetRetriever(context.contentResolver, http)
        val asset = assets.retrieve(epub.toUrl(isDirectory = false)).getOrNull()!!
        return PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
            .open(asset, allowUserInteraction = false).getOrNull()!!
    }

    private fun page(vararg paragraphs: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>
        ${paragraphs.joinToString("\n")}
        </body></html>"""

    private fun writeEpub(target: File, missingAnchor: Boolean, missingFile: Boolean = false) {
        val anchor = if (missingAnchor) "nowhere" else "c3"
        writeBook(
            target,
            spine = listOf("one", "two", "four", "five-a", "five-b", "six"),
            contents = """
                  <li><a href="one.xhtml">One</a></li>
                  <li><a href="two.xhtml">Two</a></li>
                  <li><a href="two.xhtml#$anchor">Three</a></li>
                  <li><a href="four.xhtml">Four</a></li>
                  <li><a href="five-a.xhtml">Five</a><ol><li><a href="five-b.xhtml">Section 2</a></li></ol></li>
                  <li><a href="six.xhtml">Six</a></li>""",
            pages = mapOf(
                "one" to page("<p>Mira ONE walks to the mill.</p>", "<p>Mira ONE keeps the ledger.</p>"),
                "two" to page(
                    "<p>Mira TWO meets Tobin.</p>",
                    "<h1 id=\"c3\">Three</h1>",
                    "<p>Mira THREE crosses the river.</p>",
                ),
                "four" to page("<p>Mira FOUR reads a letter.</p>"),
                "five-a" to page("<p>Mira FIVEA learns the secret.</p>"),
                "five-b" to page("<p>Mira FIVEB keeps it.</p>").takeUnless { missingFile },
                "six" to page("<p>Mira SIX leaves.</p>"),
            ),
        )
    }

    /** An EPUB of [spine] files; a null page is listed but left out of the archive. */
    private fun writeBook(target: File, spine: List<String>, contents: String, pages: Map<String, String?>) {
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556667</dc:identifier>
                    <dc:title>Chapters</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    ${spine.joinToString("\n") { """<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml"/>""" }}
                  </manifest>
                  <spine>
                    ${spine.joinToString("\n") { """<itemref idref="$it"/>""" }}
                  </spine>
                </package>""",
            )
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
                <nav epub:type="toc"><ol>$contents
                </ol></nav></body></html>""",
            )
            for ((name, body) in pages) if (body != null) put("OEBPS/$name.xhtml", body)
        }
    }
}
