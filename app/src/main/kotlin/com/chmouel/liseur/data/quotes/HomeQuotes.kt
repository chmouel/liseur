package com.chmouel.liseur.data.quotes

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
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

    private val daily: Flow<Pair<Long, Int>> =
        combine(context.homeQuoteStore.data, dayRollover) { prefs, day ->
            day to if (prefs[DAY] == day) prefs[NONCE] ?: 0 else 0
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val quote: Flow<HomeQuote?> =
        combine(
            database.invalidationTracker.createFlow("books", "annotations", "reading_progress"),
            store().observeAll(),
            daily,
        ) { _, rows, day -> rows to day }
            .conflate()
            .mapLatest { (rows, day) -> pick(rows, day.first, day.second) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /** Shows another quote, and keeps showing it for the rest of the day. */
    suspend fun another() {
        context.homeQuoteStore.edit { prefs ->
            val day = today().toEpochDay()
            val nonce = if (prefs[DAY] == day) prefs[NONCE] ?: 0 else 0
            prefs[DAY] = day
            prefs[NONCE] = nonce + 1
        }
    }

    private suspend fun pick(rows: List<QuoteRow>, day: Long, nonce: Int): HomeQuote? {
        val books = database.bookDao().allOnce().filter { it.openableUrl != null }.associateBy { it.url }
        val points = database.readingProgressDao().getAll()
            .filter { it.bookUrl in books }
            .associate { it.bookUrl to readPoint(it) }
            .filterValues { it > 0.0 }
        // The same ceiling the found lines obey. A highlight can be
        // imported, synced in from another device or copied from a copy
        // that was read further, so being on a book with some progress
        // does not put it inside what was read here.
        val highlights = database.annotationDao().all()
            .filter {
                it.kind == AnnotationKind.HIGHLIGHT.name &&
                    !it.text.isNullOrBlank() &&
                    it.bookId in points
            }
            .mapNotNull { mark ->
                val point = points.getValue(mark.bookId)
                val at = mark.totalProgression ?: locatorProgression(mark.locatorJson)
                // A mark nobody placed cannot be shown to be behind the
                // reader, and this is the one screen that would show it
                // to them out of the book's own order.
                if (!QuoteRules.withinCeiling(at, point)) return@mapNotNull null
                QuoteCandidate(mark.bookId, mark.text.orEmpty(), mark.locatorJson.takeIf(String::isNotBlank), mark.chapter, highlight = true)
            }
        val lines = rows
            .filter { row -> points[row.bookUrl]?.let { QuoteRules.withinCeiling(row.totalProgression, it) } == true }
            .map { QuoteCandidate(it.bookUrl, it.text, it.locatorJson, it.chapter, it.score) }
        val picked = DailyQuote.pick(highlights + lines, day, nonce) ?: return null
        val book = books[picked.bookUrl] ?: return null
        return HomeQuote(book, DailyQuote.shorten(picked.text), picked.chapter, picked.locatorJson, picked.highlight)
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
        if (!scanning.compareAndSet(false, true)) return
        scope.launch {
            try {
                scanDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not look for quotes: ${e.javaClass.simpleName}")
            } finally {
                scanning.set(false)
            }
        }
    }

    internal suspend fun scanDue() {
        val dao = store()
        val scans = dao.scans().associateBy { it.bookUrl }
        val progress = database.readingProgressDao().getAll().associateBy { it.bookUrl }
        val points = progress.mapValues { (_, row) -> readPoint(row) }
        // A book the reader highlighted is quoted from its highlights,
        // which the pick prefers, so walking its text would be work
        // whose result cannot be shown — and it would be repeated on
        // every advance. Only highlights inside the read point count:
        // one from further on leaves the text worth walking.
        val quotedFromHighlights = database.annotationDao().all()
            .filter { it.kind == AnnotationKind.HIGHLIGHT.name && !it.text.isNullOrBlank() }
            .groupBy({ it.bookId }, { it.totalProgression ?: locatorProgression(it.locatorJson) })
            .mapNotNull { (url, marks) ->
                val point = points[url] ?: return@mapNotNull null
                url.takeIf { marks.any { at -> QuoteRules.withinCeiling(at, point) } }
            }
            .toSet()
        val due = database.bookDao().allOnce()
            .filter { it.openableUrl != null && it.url !in quotedFromHighlights }
            .mapNotNull { book -> progress[book.url]?.let(::readPoint)?.takeIf { it >= MIN_READ }?.let { book to it } }
            .filter { (book, point) -> isDue(scans[book.url], point) }
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
        private const val MIN_READ = 0.01
        private const val STEP = 0.02
        private const val BATCH = 500

        /** How far the reader has got: the furthest point reached, or all of it once marked read. */
        fun readPoint(progress: ReadingProgress): Double {
            if (progress.finishedOverride == FinishedOverride.FINISHED.ordinal) return 1.0
            val furthest = maxOf(progress.totalProgression ?: 0.0, progress.peakProgression ?: 0.0)
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
