package com.chmouel.liseur.data.quotes

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.quotes.DailyQuote
import com.chmouel.liseur.domain.quotes.MemorableLines
import com.chmouel.liseur.domain.quotes.QuoteCandidate
import com.chmouel.liseur.domain.quotes.QuoteParagraph
import com.chmouel.liseur.domain.quotes.QuoteRules
import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.translate.TranslationPublicationOpener
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.toAbsoluteUrl

private val Context.homeQuoteStore: DataStore<Preferences> by preferencesDataStore(name = "home_quote")

/** The quote Home shows, with the book it comes from. */
data class HomeQuote(
    val book: Book,
    val text: String,
    val chapter: String?,
    /** Where to open the book; null opens it at the reader's own place. */
    val locatorJson: String?,
    val highlight: Boolean,
)

/**
 * Quotes from what the reader has already read.
 *
 * A book's own highlights come first. Without any, its text is walked as
 * far as the furthest point reached and the lines most likely to stand
 * on their own are kept ([MemorableLines]), in a cache of their own that
 * is walked again only once the reader has read on or the scorer has
 * changed. Nothing leaves the device.
 */
class HomeQuotes internal constructor(
    private val context: Context,
    private val database: LiseurDatabase,
    private val store: () -> QuoteDao,
    private val opener: () -> TranslationPublicationOpener,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val scanning = AtomicBoolean(false)

    /** A change that arrived while a scan was running, so the loop runs again. */
    private val scanAgain = AtomicBoolean(false)

    /**
     * Today's number, and again when the date turns over.
     *
     * Home can stay subscribed across midnight, so a day read only when
     * a preference was written would go on being yesterday's: the same
     * quote, and the same nonce choosing it, until Home was rebuilt or
     * Another quote was tapped.
     */
    private val dayRollover: Flow<Long> = flow {
        while (true) {
            val day = today()
            emit(day.toEpochDay())
            delay(QuoteRules.millisUntilTomorrow(day, System.currentTimeMillis(), ZoneId.systemDefault()))
        }
    }.distinctUntilChanged()

    private val daily: Flow<Daily> =
        combine(context.homeQuoteStore.data, dayRollover) { prefs, day ->
            // The line already chosen for this day, when it is this
            // day's: another day or a shuffle makes it null, so the
            // pick is made again.
            val nonce = if (prefs[DAY] == day) prefs[NONCE] ?: 0 else 0
            Daily(day, nonce, prefs[CHOSEN]?.takeIf { prefs[DAY] == day && nonce == prefs[NONCE] })
        }.distinctUntilChanged()

    /** The day, the shuffle count, and the line those two chose. */
    private data class Daily(val day: Long, val nonce: Int, val chosen: String?)

    @OptIn(ExperimentalCoroutinesApi::class)
    val quote: Flow<HomeQuote?> =
        combine(
            database.invalidationTracker.createFlow("books", "annotations", "reading_progress"),
            store().observeAll(),
            daily,
        ) { _, rows, day -> rows to day }
            .conflate()
            .mapLatest { (rows, day) -> pick(rows, day) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /** Shows another quote, and keeps showing it for the rest of the day. */
    suspend fun another() {
        context.homeQuoteStore.edit { prefs ->
            val day = today().toEpochDay()
            val nonce = if (prefs[DAY] == day) prefs[NONCE] ?: 0 else 0
            prefs[DAY] = day
            prefs[NONCE] = nonce + 1
            prefs.remove(CHOSEN)
        }
    }

    private suspend fun pick(rows: List<QuoteRow>, day: Daily): HomeQuote? {
        // Whether anything has moved since the lines were last looked
        // at is exactly what Home asks when it recomputes, so the scan
        // is scheduled here rather than only when Home is first shown:
        // a book marked read from the shelf, or a highlight arriving
        // from another device, has to be able to bring a quote with it
        // without the reader going away and coming back.
        scanSoon()
        return pick(rows, day.day, day.nonce, day.chosen)
    }

    /** One book the reader has got somewhere in, and where that is. */
    private class ReadBook(val point: Double, val place: QuoteRules.PassagePlace?)

    /** The books that have been read, by URL. */
    private suspend fun readBooks(books: Map<String, Book>): Map<String, ReadBook> =
        database.readingProgressDao().getAll()
            .filter { it.bookUrl in books }
            .map { it.bookUrl to ReadBook(readPoint(it), placeIn(it.locatorJson)) }
            .filter { (_, read) -> read.point > 0.0 }
            .toMap()

    /**
     * The marks Home may quote, grouped by the book they are in.
     *
     * The ceiling the found lines obey applies here too. A highlight can
     * be imported, synced in from another device or copied from a copy
     * that was read further, so being on a book with some progress does
     * not put it inside what was read here. Both the pick and the scan
     * ask this one question, so a book cannot be skipped for being
     * highlighted and then have none of its highlights eligible.
     */
    private suspend fun marksWithin(read: Map<String, ReadBook>): Map<String, List<BookAnnotation>> =
        database.annotationDao().all()
            .filter {
                // A note on a passage is still that passage: writing
                // about a highlight turns it into one, and the words the
                // reader chose do not stop being theirs.
                it.kind in MARK_KINDS && !it.text.isNullOrBlank() && it.bookId in read
            }
            .filter { mark ->
                val book = read.getValue(mark.bookId)
                QuoteRules.markWithinCeiling(
                    mark = placeIn(mark.locatorJson),
                    markTotalProgression = mark.totalProgression ?: locatorProgression(mark.locatorJson),
                    place = book.place,
                    readPoint = book.point,
                )
            }
            .groupBy { it.bookId }

    private suspend fun pick(
        rows: List<QuoteRow>,
        day: Long,
        nonce: Int,
        chosen: String?,
    ): HomeQuote? {
        val books = database.bookDao().allOnce().filter { it.openableUrl != null }.associateBy { it.url }
        val read = readBooks(books)
        val scans = store().scans().associateBy { it.bookUrl }
        val highlights = marksWithin(read).flatMap { (bookUrl, marks) ->
            marks.map { mark ->
                QuoteCandidate(
                    bookUrl,
                    mark.text.orEmpty(),
                    mark.locatorJson.takeIf(String::isNotBlank),
                    mark.chapter,
                    highlight = true,
                )
            }
        }
        // Lines kept under a work the URL no longer holds are the old
        // book's words. The sweep drops them a moment later; until then
        // they are not this book's.
        val lines = rows
            .filter { row -> QuoteRules.stillSameWork(scans[row.bookUrl]?.workId, books[row.bookUrl]?.workId) }
            .filter { row -> read[row.bookUrl]?.let { QuoteRules.withinCeiling(row.totalProgression, it.point) } == true }
            .map { QuoteCandidate(it.bookUrl, it.text, it.locatorJson, it.chapter, it.score) }
        val picked = DailyQuote.pick(highlights + lines, day, nonce, keep = chosen) ?: return null
        remember(picked, day, nonce)
        val book = books[picked.bookUrl] ?: return null
        return HomeQuote(book, DailyQuote.shorten(picked.text), picked.chapter, picked.locatorJson, picked.highlight)
    }

    /** Keeps today's line the same while anything else on the screen moves. */
    private suspend fun remember(picked: QuoteCandidate, day: Long, nonce: Int) {
        val key = DailyQuote.key(picked)
        context.homeQuoteStore.edit { prefs ->
            if (prefs[DAY] != day || prefs[NONCE] != nonce || prefs[CHOSEN] != key) {
                prefs[DAY] = day
                prefs[NONCE] = nonce
                prefs[CHOSEN] = key
            }
        }
    }

    /**
     * Where a locator says it is, in the one scale two clients measure
     * the same way.
     */
    private fun placeIn(locatorJson: String?): QuoteRules.PassagePlace? {
        val locator = locatorJson
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: return null
        val resource = ResourceAddress.canonicalPath(locator.href.toString()) ?: return null
        val progression = locator.locations.progression?.takeIf { it.isFinite() } ?: return null
        return QuoteRules.PassagePlace(resource, progression)
    }

    /**
     * The whole-book fraction a mark's locator carries.
     *
     * The column is what this device writes when it marks and what sync
     * stores when a peer sends one, but a peer is free to send neither,
     * and the locator is the copy that survives when the column did not
     * make the trip.
     */
    private fun locatorProgression(locatorJson: String?): Double? =
        locatorJson
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?.locations
            ?.totalProgression

    /** Looks through the books read on since they were last looked at, one at a time, in the background. */
    fun scanSoon() {
        if (!scanning.compareAndSet(false, true)) {
            // A walk takes seconds. Whatever asked for one while it ran
            // is answered by another pass rather than dropped.
            scanAgain.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    scanAgain.set(false)
                    scanDue()
                } while (scanAgain.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not look for quotes: ${e.javaClass.simpleName}")
            } finally {
                scanning.set(false)
                if (scanAgain.getAndSet(false)) scanSoon()
            }
        }
    }

    internal suspend fun scanDue() {
        val dao = store()
        val scans = dao.scans().associateBy { it.bookUrl }
        val all = database.bookDao().allOnce().filter { it.openableUrl != null }
        val read = readBooks(all.associateBy { it.url })
        // A book the reader marked on is quoted from its marks, which
        // the pick prefers, so walking its text would be work whose
        // result cannot be shown, repeated on every advance.
        val quotedFromMarks = marksWithin(read).keys
        val due = all
            .filter { it.url !in quotedFromMarks }
            .mapNotNull { book -> read[book.url]?.takeIf { it.point >= MIN_READ }?.let { book to it.point } }
            // A recorded work that is not the one there now is due
            // whatever the watermark says: the lines and the watermark
            // belong to a book that has been replaced.
            .filter { (book, point) ->
                isDue(scans[book.url], point) ||
                    !QuoteRules.stillSameWork(scans[book.url]?.workId, book.workId)
            }
            .sortedByDescending { (book, _) -> book.lastOpenedAt ?: 0L }
        for ((book, point) in due) {
            currentCoroutineContext().ensureActive()
            scan(dao, book, point)
        }
    }

    private suspend fun scan(dao: QuoteDao, book: Book, point: Double) {
        val url = book.openableUri()?.toUri()?.toAbsoluteUrl() ?: return
        val found = try {
            opener().withPublication(url) { publication -> QuoteScanner.walk(publication, point) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not an EPUB, fixed layout or unreadable: not opened again until read on.
            Log.i(TAG, "No quotes from a book: ${e.javaClass.simpleName}")
            null
        }
        // The walk takes seconds, and the book can be removed or
        // replaced under it meanwhile. Writing then would put the old
        // book's lines and watermark back after the sweep cleared them.
        val now = database.bookDao().getByUrl(book.url) ?: return
        if (now.workId != book.workId || now.localUri != book.localUri) return
        val rows = found.orEmpty().mapIndexed { ord, line ->
            QuoteRow(book.url, ord, line.text, line.locatorJson, line.totalProgression, line.chapter, line.score)
        }
        dao.replace(
            QuoteScanRow(book.url, point, MemorableLines.VERSION, found != null, System.currentTimeMillis(), book.workId),
            rows,
        )
    }

    /**
     * Forgets the lines of books no longer in the library, and of books
     * whose file now holds a different work.
     *
     * A replaced file keeps its URL ([contentReplaced]), so being in the
     * library is not enough to make a URL's cached lines about the book
     * there now: the old book's lines would be shown under the new
     * title, and the recorded watermark would keep the new text from
     * being walked until the reader passed it.
     *
     * [present] answers, for each URL asked about, the work the book
     * holds — and omits the URL when the library no longer has it.
     */
    internal suspend fun sweep(present: suspend (List<String>) -> Map<String, String?>) {
        val dao = store()
        val books = dao.books()
        if (books.isEmpty()) return
        val scans = dao.scans().associateBy { it.bookUrl }
        val gone = books.chunked(BATCH).flatMap { batch ->
            val now = present(batch)
            batch.filterNot { url ->
                url in now && QuoteRules.stillSameWork(scans[url]?.workId, now[url])
            }
        }
        gone.chunked(BATCH).forEach { dao.forget(it) }
    }

    internal companion object {
        private const val TAG = "home-quote"
        private val DAY = longPreferencesKey("day")
        private val NONCE = intPreferencesKey("nonce")

        /** The line the day and nonce chose, kept so a recompute does not move it. */
        private val CHOSEN = stringPreferencesKey("chosen")
        private const val MIN_READ = 0.01
        private const val STEP = 0.02
        private const val BATCH = 500

        /** What the reader marked on a passage; a book note has no passage to quote. */
        private val MARK_KINDS = setOf(AnnotationKind.HIGHLIGHT.name, AnnotationKind.NOTE.name)

        /** How far the reader has got: the furthest point reached, or all of it once marked read. */
        fun readPoint(progress: ReadingProgress): Double {
            if (progress.finishedOverride == FinishedOverride.FINISHED.ordinal) return 1.0
            val total = progress.totalProgression ?: 0.0
            val peak = progress.peakProgression ?: 0.0
            // An unread mark starts the reading over. The furthest point
            // recorded before it describes a reading the reader has
            // since put back on the pile, so it does not count until
            // reading past it again gives the peak a newer revision.
            val peakStands = progress.unreadResetRevision?.let { reset ->
                (progress.peakRevision ?: 0) > reset
            } ?: true
            val furthest = if (peakStands) maxOf(total, peak) else total
            return if (furthest.isFinite()) furthest.coerceIn(0.0, 1.0) else 0.0
        }

        fun isDue(scan: QuoteScanRow?, point: Double): Boolean =
            scan == null || scan.version != MemorableLines.VERSION ||
                point - scan.scannedThrough >= STEP ||
                (point >= 1.0 && scan.scannedThrough < 1.0)
    }
}

/** A line found by [QuoteScanner], with where it is. */
internal data class FoundLine(
    val text: String,
    val locatorJson: String,
    val totalProgression: Double,
    val chapter: String?,
    val score: Double,
)

/** Walks a book's text up to a point and keeps its most quotable lines. */
@OptIn(ExperimentalReadiumApi::class)
internal object QuoteScanner {
    private const val CONTEXT = 40

    private class Block(val text: String, val locator: Locator, val path: String?, val end: Double)

    suspend fun walk(publication: Publication, readPoint: Double): List<FoundLine> {
        val iterator = publication.content()?.iterator() ?: return emptyList()
        val whole = readPoint >= 1.0
        val blocks = ArrayList<Block>()
        var held: Pair<String, Locator>? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val element = iterator.nextOrNull() ?: break
            val start = element.locator.locations.totalProgression
            // Each block ends where the next begins: one that runs past the read point is left out.
            if (!whole && (start == null || start > readPoint)) break
            held?.let { (text, locator) -> blocks += Block(text, locator, pathOf(locator), start ?: 1.0) }
            held = (element as? Content.TextElement)
                ?.takeUnless { it.role is Content.TextElement.Role.Heading || it.role == Content.TextElement.Role.Footnote }
                ?.let { text -> text.segments.joinToString("") { it.text }.trim() to text.locator }
                ?.takeIf { it.first.isNotEmpty() }
        }
        if (whole) held?.let { (text, locator) -> blocks += Block(text, locator, pathOf(locator), 1.0) }
        val paragraphs = blocks.mapIndexed { index, block ->
            val edge = index == 0 || index == blocks.lastIndex ||
                blocks[index - 1].path != block.path || blocks[index + 1].path != block.path
            QuoteParagraph(block.text, chapterEdge = edge)
        }
        val language = publication.metadata.languages.firstOrNull()
        val titles = chapterTitles(publication)
        return MemorableLines.find(paragraphs, language).map { line ->
            val block = blocks[line.paragraph]
            val locator = block.locator.copy(
                text = Locator.Text(
                    before = block.text.substring(0, line.start).takeLast(CONTEXT).takeIf(String::isNotBlank),
                    highlight = line.text,
                    after = block.text.substring(line.end).take(CONTEXT).takeIf(String::isNotBlank),
                ),
            )
            FoundLine(
                text = line.text,
                locatorJson = locator.toJSON().toString(),
                totalProgression = block.end,
                chapter = block.path?.let(titles::get) ?: block.locator.title?.takeIf(String::isNotBlank),
                score = line.score,
            )
        }
    }

    private fun pathOf(locator: Locator): String? = ResourceAddress.canonicalPath(locator.href.toString())

    /** The first contents entry naming each file. */
    private fun chapterTitles(publication: Publication): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        fun visit(links: List<Link>) {
            for (link in links) {
                val title = link.title?.trim()?.takeIf(String::isNotEmpty)
                val path = ResourceAddress.canonicalPath(link.url().toString())
                if (title != null && path != null) titles.putIfAbsent(path, title)
                visit(link.children)
            }
        }
        visit(publication.tableOfContents)
        return titles
    }
}
