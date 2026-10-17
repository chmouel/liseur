package com.chmouel.liseur.companion

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.settings.AppSettingsRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class CodexStoreTest {
    private lateinit var database: CompanionDatabase
    private lateinit var store: CodexStore
    private val library = mutableSetOf("book")
    private val book = CodexBookRow("book", "sha:format:chapters", 5)
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CompanionDatabase::class.java)
    @get:Rule val folder = TemporaryFolder()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        store = CodexStore(database) { urls -> urls.filter { it in library } }
    }
    @After fun close() = database.close()

    private fun passage(unit: Int, text: String): Passage<Locator> {
        val locator = checkNotNull(Locator.fromJSON(JSONObject().put("href", "chapter-$unit.xhtml").put("type", "application/xhtml+xml")))
        return Passage("r$unit.0", PassageText.hash(text), unit, text, ChapterRange(unit, unit), locator)
    }
    private suspend fun save(unit: Int, text: String) {
        store.save(book, unit, CodexExtractor.extract(text), listOf(passage(unit, text)))
    }

    @Test fun `chapters resume without duplicating counts and empty chapters stay complete`() = runTest {
        assertTrue(store.prepare(book).isEmpty())
        val text = "At dawn, Mira waited. ".repeat(3)
        save(0, text)
        store.save(book, 1, emptyMap(), emptyList())
        save(0, text)
        assertEquals(listOf(0, 1), store.prepare(book))
        assertEquals(3, store.entries(book, 2).single().occurrences)
        val restored = store.mentions(book, 2, "Mira", null, 0).single()
        assertEquals(text, restored.text)
        assertEquals(passage(0, text).place.toJSON().toString(), restored.place.toJSON().toString())
        assertTrue(store.mentions(book, 2, "Mir", null, 0).isEmpty())
    }

    @Test fun `names at the edges of a passage are found in details and graph edges`() = runTest {
        store.prepare(book)
        val text = "Mira waited for Tobin"
        for (unit in 0..1) store.save(book, unit, CodexExtractor.extract("At dawn, Mira waited. At noon, Tobin waited. ".repeat(5)), listOf(passage(unit, text)))
        assertEquals(2, store.mentions(book, 2, "Mira", null, 0).size)
        assertEquals(2, store.mentions(book, 2, "Tobin", null, 0).size)
        assertEquals(listOf(0, 1), store.mentions(book, 2, "Tobin", "Mira", 0).map { it.range.hi })
        assertEquals(listOf(CodexEdge(0, 1, 2)), store.graph(book, 2, store.entries(book, 2)).edges)
    }

    @Test fun `compound and accented names match their variants in details and graph edges`() = runTest {
        for ((canonical, variant) in listOf(
            "King's Landing" to "King’s Landing",
            "Gabriele D'Annunzio" to "Gabriele D’Annunzio",
            "Élisabeth" to "E\u0301lisabeth",
            "O'Brien" to "O’Brien",
        )) {
            // A new identity each time: completed chapters are never written again.
            val book = CodexBookRow("book", "sha:$canonical", 5)
            store.prepare(book)
            for ((unit, name) in listOf(canonical, variant).withIndex()) {
                val text = "They reached $name. At dawn, Tobin waited."
                store.save(book, unit, CodexExtractor.extract("$text ".repeat(5)), listOf(passage(unit, text)))
            }
            assertEquals(canonical, listOf(0, 1), store.mentions(book, 2, canonical, "Tobin", 0).map { it.range.hi })
            assertEquals(canonical, listOf(CodexEdge(0, 1, 2)), store.graph(book, 2, store.entries(book, 2)).edges)
        }
    }

    @Test fun `a cut passage shows only names starting in its own text, and unspaced scripts match inside words`() = runTest {
        store.prepare(book)
        val parts = listOf("Mira waited by the door. Elder", "Chen came. Tobin smiled.")
            .mapIndexed { part, text -> passage(0, text).copy(ref = PassageText.ref(0, 0, part)) }
        store.save(book, 0, emptyMap(), parts)
        store.save(book, 1, emptyMap(), listOf(passage(1, "李雷去了市场。")))
        assertEquals(listOf("r0.0.0"), store.mentions(book, 2, "Elder Chen", null, 0).map { it.ref })
        assertEquals(listOf("r0.0.1"), store.mentions(book, 2, "Tobin", null, 0).map { it.ref })
        assertEquals(listOf("r0.0.1"), store.mentions(book, 2, "Chen", null, 0).map { it.ref })
        assertEquals(listOf("r0.0.1"), store.mentions(book, 2, "Tobin", "Chen", 0).map { it.ref })
        assertEquals(listOf(1), store.mentions(book, 2, "李雷", null, 0).map { it.range.hi })
    }

    @Test fun `future evidence cannot change entries graphs or excerpts at an earlier scope`() = runTest {
        store.prepare(book)
        val text = "At dawn, Mira waited. At noon, Tobin waited. ".repeat(5)
        save(0, text)
        save(1, text)
        val entries = store.entries(book, 2)
        val graph = store.graph(book, 2, entries)
        assertEquals(listOf(CodexEdge(0, 1, 2)), graph.edges)
        save(3, "At dawn, Fenna arrived. At noon, Tobin said nothing. ".repeat(10))
        assertEquals(entries, store.entries(book, 2))
        assertEquals(graph, store.graph(book, 2, store.entries(book, 2)))
        assertTrue(store.mentions(book, 2, "Fenna", null, 0).isEmpty())
        assertEquals(listOf(0, 1), store.mentions(book, 2, "Mira", "Tobin", 0).map { it.range.hi })
    }

    @Test fun `uncertain chapter membership cannot make a graph edge`() = runTest {
        store.prepare(book)
        val text = "At dawn, Mira waited. At noon, Tobin waited. ".repeat(5)
        store.save(book, 2, CodexExtractor.extract(text), listOf(passage(2, text).copy(range = ChapterRange(0, 2))))
        val entries = store.entries(book, 3)
        assertEquals(2, entries.size)
        assertTrue(store.graph(book, 3, entries).edges.isEmpty())
        assertTrue(store.mentions(book, 2, "Mira", null, 0).isEmpty())
        assertEquals(1, store.mentions(book, 3, "Mira", null, 0).size)
        assertTrue(store.mentions(book, 3, "Mira", "Tobin", 0).isEmpty())
    }

    @Test fun `a connection lists each shared chapter once and prefers a co-occurring passage in reading order`() = runTest {
        store.prepare(book)
        val text = "At dawn, Mira waited. At noon, Tobin waited."
        for (unit in 0..1) {
            val first = passage(unit, text)
            store.save(book, unit, CodexExtractor.extract("$text $text"), listOf(first, first.copy(ref = "r$unit.1")))
        }
        assertEquals(listOf(0, 1), store.mentions(book, 2, "Mira", "Tobin", 0).map { it.range.hi })
        assertEquals(4, store.mentions(book, 2, "Mira", null, 0).size)

        val passages = (0..12).map { ordinal ->
            val line = when (ordinal) {
                2 -> "At dawn, Mira waited alone."
                5 -> "At noon, Mira greeted Tobin."
                10 -> "At dusk, Mira left alone."
                else -> "The wind blew."
            }
            passage(2, line).copy(ref = "r2.$ordinal")
        }
        store.save(book, 2, CodexExtractor.extract(passages.joinToString("\n\n") { it.text }), passages)
        assertEquals(listOf("r2.2", "r2.5", "r2.10"), store.mentions(book, 3, "Mira", null, 0).filter { it.range.hi == 2 }.map { it.ref })
        assertEquals("r2.5", store.mentions(book, 3, "Mira", "Tobin", 0).single { it.range.hi == 2 }.ref)
    }

    @Test fun `split passages and long chapter gaps retain compound names and recurring counts`() = runTest {
        val longBook = CodexBookRow("book", "sha:format:chapters", 200)
        store.prepare(longBook)
        val splitText = "x ".repeat(596) + "Elder Chen waited, and Tobin arrived."
        val cuts = PassageText.split(splitText)
        for (unit in 0..1) {
            val parts = cuts.mapIndexed { part, piece -> passage(unit, piece).copy(ref = PassageText.ref(unit, 0, part)) }
            store.save(longBook, unit, CodexExtractor.extract(CodexViewModel.unitText(parts)), parts)
        }
        store.save(longBook, 0 + 2, CodexExtractor.extract("At dusk, Bram waited. Bram slept."), listOf(passage(2, "At dusk, Bram waited. Bram slept.")))
        for (unit in 3..155) store.save(longBook, unit, emptyMap(), emptyList())
        store.save(longBook, 156, CodexExtractor.extract("At dawn, Bram returned."), listOf(passage(156, "At dawn, Bram returned.")))

        val entries = store.entries(longBook, 157)
        assertTrue(entries.any { it.name == "Bram" && it.occurrences == 3 })
        assertEquals(2, store.mentions(longBook, 157, "Elder Chen", null, 0).size)
        assertEquals(listOf(0, 1), store.mentions(longBook, 157, "Elder Chen", "Tobin", 0).map { it.range.hi })
    }

    @Test fun `a changed identity clears old results and rejects stale scan writes`() = runTest {
        store.prepare(book)
        save(0, "At dawn, Mira waited. ".repeat(3))
        val changed = book.copy(identity = "new-sha:new-format:new-chapters")
        assertTrue(store.prepare(changed).isEmpty())
        assertTrue(store.entries(book, 5).isEmpty())
        assertTrue(store.entries(changed, 5).isEmpty())
        try { save(1, "At dawn, Mira waited."); fail("stale writes must fail") } catch (_: IllegalStateException) { }
        assertTrue(store.prepare(changed).isEmpty())
    }

    @Test fun `removed books are swept and an in-flight scan cannot recreate them`() = runTest {
        val owner = CompanionStore(database, { urls -> urls.filter { it in library } })
        owner.codex.prepare(book)
        owner.codex.save(book, 0, emptyMap(), emptyList())
        library.clear()
        owner.sweep()
        assertNull(database.codex().book(book.bookUrl))
        assertTrue(database.codex().completed(book.bookUrl).isEmpty())
        try { owner.codex.save(book, 1, emptyMap(), emptyList()); fail("removed book must stay removed") } catch (_: IllegalStateException) { }
    }

    @Test fun `version two migration preserves recaps and settings`() {
        migrations.createDatabase("codex-migration", 2).apply {
            execSQL("INSERT INTO recap_choice (book_url, enabled) VALUES ('book', 1)")
            execSQL("INSERT INTO chapter_recap (book_url, unit_key, unit, content_sha, server_id, model, format, json, saved_at, used_at) VALUES ('book', 'u0', 0, 'sha', 'server', 'model', 1, '{}', 1, 1)")
            close()
        }
        migrations.runMigrationsAndValidate("codex-migration", 3, true, *CompanionDatabase.MIGRATIONS).use { db ->
            db.query("SELECT enabled FROM recap_choice WHERE book_url = 'book'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT json FROM chapter_recap WHERE book_url = 'book'").use { assertTrue(it.moveToFirst()); assertEquals("{}", it.getString(0)) }
        }
    }

    private fun withViewModel(
        chapters: List<String>,
        clock: () -> Long = { 1_000L },
        profiles: MutableStateFlow<Boolean> = MutableStateFlow(false),
        block: suspend (CodexViewModel, AppSettingsRepository) -> Unit,
    ) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).allowMainThreadQueries().build()
        val epub = File(folder.root, "codex-vm.epub").also { writeVmEpub(it, chapters) }
        val publication = openPublication(context, epub)
        try {
            val url = epub.toURI().toString()
            library += url
            liseurDb.bookDao().upsert(Book(url = url, title = "Book", author = null, coverPath = null, source = null, addedAt = 1L, lastOpenedAt = null, fileModifiedAt = epub.lastModified()))
            liseurDb.workIdentityDao().upsert(BookFingerprintRow(url, "sha256", "md5", epub.length(), epub.lastModified(), 1L))
            val settings = AppSettingsRepository(PreferenceDataStoreFactory.create { File(folder.root, "vm.preferences_pb") })
            val vm = CodexViewModel(
                bookUrl = url,
                store = store,
                settings = settings,
                books = liseurDb.bookDao(),
                fingerprints = BookFingerprintStore(context, liseurDb.workIdentityDao()),
                profilesFlow = profiles,
                foregroundFlow = MutableStateFlow(true),
                clock = clock,
            )
            vm.attach(publication)
            block(vm, settings)
        } finally {
            publication.close()
            liseurDb.close()
        }
    }

    private fun chapter(name: String) = checkNotNull(Locator.fromJSON(JSONObject().put("href", "OEBPS/$name.xhtml").put("type", "application/xhtml+xml")))

    @Test fun `a relationship opened mid-scan gains the chapters committed after it`() {
        val pair = "<p>At dawn, Mira greeted Tobin.</p><p>Mira left alone.</p>"
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticks = AtomicInteger()
        val clock = {
            // The first tick follows chapter one's commit; holding it pauses the scan there.
            if (ticks.getAndIncrement() == 0) { reached.countDown(); release.await(10, TimeUnit.SECONDS) }
            1_000L
        }
        withViewModel(listOf(pair, pair, "<p>At dusk, Fenna waited.</p>"), clock) { vm, _ ->
            vm.open(chapter("three"), translated = false)
            awaitCondition { reached.count == 0L && vm.panel.value?.ceiling == 2 }
            vm.detail("Mira", "Tobin")
            awaitCondition { vm.panel.value?.detail?.loading == false }
            assertEquals(listOf(0), vm.panel.value?.detail?.passages?.map { it.range.hi })
            assertFalse(vm.panel.value?.detail?.more == true)

            release.countDown()
            awaitCondition { vm.progress.value.stage == CodexStage.COMPLETE && vm.panel.value?.detail?.passages?.size == 2 }
            assertEquals(listOf(0, 1), vm.panel.value?.detail?.passages?.map { it.range.hi })
            assertFalse(vm.panel.value?.detail?.loading == true)
        }
    }

    @Test fun `chapters past the ceiling do not refresh and an unchanged refresh keeps the relationships`() {
        val ticks = AtomicInteger()
        val pairs = (0 until 6).joinToString("\n") { "<p>At dawn, Mira greeted Tobin ($it).</p>" }
        withViewModel(listOf(pairs, pairs, "<p>At dusk, Fenna waited.</p>"), clock = { ticks.incrementAndGet(); 1_000L }) { vm, _ ->
            vm.open(chapter("two"), translated = false)
            awaitCondition { vm.progress.value.stage == CodexStage.COMPLETE && vm.panel.value?.entries?.isNotEmpty() == true }
            // Only chapter one lies below the ceiling; chapters two and three cannot change the panel.
            assertEquals(1, ticks.get())

            vm.showGraph()
            awaitCondition { vm.panel.value?.graph != null }
            val graph = vm.panel.value?.graph
            vm.resume()
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); delay(20) }
            assertSame(graph, vm.panel.value?.graph)
            assertFalse(vm.panel.value?.graphFailed == true)
        }
    }

    @Test fun `a chapter that adds no names still updates relationships before the scan stops`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticks = AtomicInteger()
        val clock = {
            when (val tick = ticks.getAndIncrement()) {
                0 -> 1_000L
                1 -> { reached.countDown(); release.await(10, TimeUnit.SECONDS); 2_000L }
                2 -> 3_000L
                else -> error("scan stopped at tick $tick")
            }
        }
        val pairs = (0 until 6).joinToString("\n") { "<p>At dawn, Mira greeted Tobin ($it).</p>" }
        val chapters = listOf(pairs, "<p>Rain.</p>", "<p>Mira waited. Tobin slept.</p>", "<p>Dusk.</p>", "<p>End.</p>")
        withViewModel(chapters, clock) { vm, _ ->
            vm.open(chapter("five"), translated = false)
            awaitCondition { reached.count == 0L && vm.panel.value?.entries?.isNotEmpty() == true }
            vm.showGraph()
            // One shared chapter is not yet a relationship.
            awaitCondition { vm.panel.value?.graph?.edges?.isEmpty() == true }
            val names = vm.panel.value?.entries

            // Chapter three names nobody new but puts both in it; the scan then stops before the last chapter.
            release.countDown()
            awaitCondition { vm.progress.value.stage == CodexStage.FAILED }
            assertEquals(names, vm.panel.value?.entries)
            awaitCondition { vm.panel.value?.graph?.edges?.singleOrNull()?.weight == 2 }
        }
    }

    @Test fun `the names tab stops relationship rebuilds and returning rebuilds without clearing the map`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticks = AtomicInteger()
        val clock = {
            if (ticks.getAndIncrement() == 0) { reached.countDown(); release.await(10, TimeUnit.SECONDS) }
            1_000L
        }
        val one = (0 until 6).joinToString("\n") { "<p>At dawn, Mira greeted Tobin ($it).</p>" }
        val two = (0 until 6).joinToString("\n") { "<p>At dusk, Mira greeted Fenna ($it).</p>" }
        withViewModel(listOf(one, two, "<p>Rain.</p>"), clock) { vm, _ ->
            vm.open(chapter("three"), translated = false)
            awaitCondition { reached.count == 0L && vm.panel.value?.ceiling == 2 }
            vm.showGraph()
            awaitCondition { vm.panel.value?.graph != null }
            val early = vm.panel.value?.graph
            vm.showNames()

            release.countDown()
            awaitCondition { vm.progress.value.stage == CodexStage.COMPLETE && vm.panel.value?.entries?.any { it.name == "Fenna" } == true }
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); delay(20) }
            assertSame(early, vm.panel.value?.graph)

            vm.showGraph()
            // The rebuild can finish before showGraph returns; either way the map is never cleared.
            val returning = vm.panel.value?.graph
            assertTrue(returning === early || returning?.nodes?.any { it.name == "Fenna" } == true)
            awaitCondition { vm.panel.value?.graph?.nodes?.any { it.name == "Fenna" } == true }
        }
    }

    @Test fun `view model preserves completed stage across reopen paginates details and closes when disabled`() {
        val paragraphs = (0 until 55).joinToString("\n") { "<p>At dawn, Mira greeted Tobin ($it).</p>" }
        withViewModel(listOf(paragraphs, "<p>At dusk, Fenna waited.</p>")) { vm, settings ->
            val unplaced = checkNotNull(Locator.fromJSON(JSONObject().put("href", "missing.xhtml").put("type", "application/xhtml+xml")))
            vm.open(unplaced, translated = false)
            awaitCondition { vm.panel.value?.placing == false }
            assertNull(vm.panel.value?.ceiling)
            assertEquals(CodexStage.PREPARING, vm.progress.value.stage)

            val chapterTwo = chapter("two")
            vm.open(chapterTwo, translated = false)
            awaitCondition { vm.progress.value.stage == CodexStage.COMPLETE && vm.panel.value?.entries?.isNotEmpty() == true }
            assertEquals(CodexProgress(CodexStage.COMPLETE, 2, 2), vm.progress.value)
            assertEquals(listOf("Mira", "Tobin"), vm.panel.value?.entries?.map { it.name })

            vm.close()
            vm.open(chapterTwo, translated = false)
            awaitCondition { vm.panel.value?.placing == false && vm.panel.value?.entries?.isNotEmpty() == true }
            assertEquals(CodexStage.COMPLETE, vm.progress.value.stage)

            vm.detail("Mira")
            assertEquals(CharacterPane.MENTIONS, vm.panel.value?.detail?.pane)
            awaitCondition { vm.panel.value?.detail?.loading == false }
            assertEquals(50, vm.panel.value?.detail?.passages?.size)
            assertTrue(vm.panel.value?.detail?.more == true)
            vm.loadMore()
            awaitCondition { vm.panel.value?.detail?.loading == false && vm.panel.value?.detail?.passages?.size == 55 }
            assertFalse(vm.panel.value?.detail?.more == true)

            vm.close()
            vm.open(chapterTwo, translated = false, selection = "“Tobin’s,”")
            awaitCondition { vm.panel.value?.detail?.loading == false && vm.panel.value?.detail?.passages?.isNotEmpty() == true }
            assertEquals("Tobin", vm.panel.value?.query)
            assertEquals("Tobin", vm.panel.value?.detail?.name)
            assertEquals(50, vm.panel.value?.detail?.passages?.size)
            assertTrue(vm.panel.value?.detail?.more == true)

            settings.setCodexEnabled(false)
            awaitCondition { vm.panel.value == null }
        }
    }

    @Test fun `with a companion service the scan can be off, and an alias lists its own mentions`() {
        val profiles = MutableStateFlow(true)
        val text = (0 until 3).joinToString("\n") { "<p>At dawn, Mira greeted Tobin ($it).</p>" } + "<p>The Captain waved.</p>"
        withViewModel(listOf(text, "<p>At dusk, Fenna waited.</p>"), profiles = profiles) { vm, settings ->
            vm.openCharacter(chapter("two"), translated = false, selection = "Mira’s", pane = CharacterPane.MENTIONS)
            awaitCondition { vm.progress.value.stage == CodexStage.COMPLETE && vm.panel.value?.detail?.loading == false }
            assertEquals(CodexTab.NAMES, vm.panel.value?.tab)
            assertEquals("Mira", vm.panel.value?.detail?.name)
            assertEquals(3, vm.panel.value?.detail?.passages?.size)

            vm.focus("Captain")
            awaitCondition { vm.panel.value?.detail?.loading == false }
            assertEquals("Captain", vm.panel.value?.detail?.term)
            assertEquals(1, vm.panel.value?.detail?.passages?.size)
            vm.focus(null)
            awaitCondition { vm.panel.value?.detail?.loading == false }
            assertEquals(3, vm.panel.value?.detail?.passages?.size)

            // Story and the profiles stay without the scan; the service going too closes Codex.
            settings.setCodexEnabled(false)
            awaitCondition { vm.panel.value?.tab == CodexTab.STORY }
            assertEquals("Mira", vm.panel.value?.detail?.name)
            vm.close()

            vm.openCharacter(chapter("two"), translated = false, selection = "a long phrase that is no name at all, spoken by nobody in particular here", pane = CharacterPane.PROFILE)
            awaitCondition { vm.panel.value?.placing == false }
            assertEquals(CodexTab.STORY, vm.panel.value?.tab)
            assertEquals(CharacterPane.PROFILE, vm.panel.value?.detail?.pane)
            // Story stays paused under the page, so nothing it would send starts before Ask.
            assertFalse(vm.panel.value!!.storyInFront)
            vm.back()
            assertTrue(vm.panel.value!!.storyInFront)
            vm.detail("Mira")
            assertEquals(CharacterPane.PROFILE, vm.panel.value?.detail?.pane)
            assertFalse(vm.panel.value!!.storyInFront)
            assertEquals(1, vm.panel.value?.ceiling)
            // Nothing is read from the index while the scan is off.
            assertTrue(vm.panel.value?.entries?.isEmpty() == true)
            assertTrue(vm.panel.value?.detail?.passages?.isEmpty() == true)

            profiles.value = false
            awaitCondition { vm.panel.value == null }
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(predicate())
    }

    private suspend fun openPublication(context: Application, epub: File): Publication {
        val http = DefaultHttpClient()
        val assets = AssetRetriever(context.contentResolver, http)
        val asset = assets.retrieve(epub.toUrl(isDirectory = false)).getOrNull()!!
        return PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
            .open(asset, allowUserInteraction = false).getOrNull()!!
    }

    private fun writeVmEpub(target: File, chapters: List<String>) {
        val names = chapters.indices.map { listOf("one", "two", "three", "four", "five")[it] }
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            })
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
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556668</dc:identifier>
                    <dc:title>Codex</dc:title><dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    ${names.joinToString("") { "<item id=\"$it\" href=\"$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}
                  </manifest>
                  <spine>${names.joinToString("") { "<itemref idref=\"$it\"/>" }}</spine>
                </package>""",
            )
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <nav epub:type="toc"><ol>${names.joinToString("") { "<li><a href=\"$it.xhtml\">$it</a></li>" }}</ol></nav>
                </body></html>""",
            )
            names.zip(chapters).forEach { (name, body) ->
                put("OEBPS/$name.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""")
            }
        }
    }
}
