package com.chmouel.liseur.data.library

import android.util.JsonWriter
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.domain.BackedUpBookSessions
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.MAX_SESSION_BOOKS
import com.chmouel.liseur.domain.MAX_SESSION_DURATION_MS
import com.chmouel.liseur.domain.MAX_SESSION_IDLE_MS
import com.chmouel.liseur.domain.MAX_SESSIONS
import com.chmouel.liseur.domain.matchBackedUpBookUrl
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ReadingSessionBackupTooLarge : IOException("Reading session backup exceeds limit")

private const val MAX_TEXT_LENGTH = 64 * 1024

data class SessionRestoreResult(
    val added: Int = 0,
    val alreadyPresent: Int = 0,
)

class ReadingSessionBackupRepository(
    private val sessionDao: ReadingSessionDao,
    private val bookDao: BookDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun writeContents(file: File, maxBytes: Long): Int {
        val sessions = sessionDao.closedForBackup()
        var written = 0L
        var count = 0
        var groups = 0
        var lastBook: String? = null
        file.outputStream().use { output ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    if (++written > maxBytes) throw ReadingSessionBackupTooLarge()
                    output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length
                    if (written > maxBytes) throw ReadingSessionBackupTooLarge()
                    output.write(bytes, offset, length)
                }
            }
            JsonWriter(bounded.writer(Charsets.UTF_8)).use { writer ->
                writer.beginObject()
                    .name("format").value(1)
                    .name("application").value("liseur")
                    .name("books").beginArray()
                for (session in sessions) {
                    currentCoroutineContext().ensureActive()
                    val endedAt = session.endedAt ?: continue
                    // The decoder rejects these anyway, so they stay out of a valid archive.
                    if (session.bookUrl.isBlank() || session.bookUrl.length > MAX_TEXT_LENGTH) continue
                    val startedAt = session.startedAt.coerceAtLeast(0)
                    val safeEndedAt = endedAt.coerceAtLeast(startedAt)
                    val durationMs = session.durationMs.coerceAtLeast(0)
                    // The decoder refuses what could overflow the summed totals; no real sitting
                    // comes near its bound, so this only guards against corrupt stored rows.
                    if (durationMs > MAX_SESSION_DURATION_MS) continue
                    val idleMs = session.idleMs?.takeIf { it >= 0 && it <= MAX_SESSION_IDLE_MS }
                    val startProgression = session.startProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    val endProgression = session.endProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    if (session.bookUrl != lastBook) {
                        if (lastBook != null) writer.endArray().endObject()
                        if (groups + 1 > MAX_SESSION_BOOKS) throw ReadingSessionBackupTooLarge()
                        groups++
                        val book = bookDao.getByUrl(session.bookUrl)
                        writer.beginObject().name("book_id").value(session.bookUrl)
                        book?.title?.takeIf { it.length <= MAX_TEXT_LENGTH }?.let { writer.name("title").value(it) }
                        book?.author?.takeIf { it.length <= MAX_TEXT_LENGTH }?.let { writer.name("author").value(it) }
                        writer.name("sessions").beginArray()
                        lastBook = session.bookUrl
                    }
                    if (count + 1 > MAX_SESSIONS) throw ReadingSessionBackupTooLarge()
                    writer.beginObject()
                        .name("started_at").value(startedAt)
                        .name("ended_at").value(safeEndedAt)
                        .name("duration_ms").value(durationMs)
                    idleMs?.let { writer.name("idle_ms").value(it) }
                    startProgression?.let { writer.name("start_progression").value(it) }
                    endProgression?.let { writer.name("end_progression").value(it) }
                    writer.endObject()
                    writer.flush()
                    count++
                }
                if (lastBook != null) writer.endArray().endObject()
                writer.endArray().endObject()
            }
        }
        return count
    }

    suspend fun restore(
        books: List<BackedUpBookSessions>,
        aliases: Map<String, String> = emptyMap(),
    ): SessionRestoreResult {
        if (books.isEmpty()) return SessionRestoreResult()
        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val importedAt = now()
        val seenInArchive = mutableSetOf<Pair<String, Long>>()
        val candidatesByBook = LinkedHashMap<String, MutableList<ReadingSession>>()
        var alreadyPresent = 0

        for (book in books) {
            currentCoroutineContext().ensureActive()
            val targetUrl = aliases[book.bookId]
                ?: matchBackedUpBookUrl(book.bookId, book.title, book.author, known)
                ?: book.bookId
            for (session in book.sessions) {
                if (!seenInArchive.add(targetUrl to session.startedAt)) {
                    alreadyPresent++
                    continue
                }
                candidatesByBook.getOrPut(targetUrl) { mutableListOf() } += ReadingSession(
                    bookUrl = targetUrl,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    lastCheckpointAt = session.endedAt,
                    durationMs = session.durationMs,
                    startProgression = session.startProgression,
                    endProgression = session.endProgression,
                    idleMs = session.idleMs,
                    uploadedAt = null,
                    legacyEvidenceUnknown = false,
                    importedAt = importedAt,
                )
            }
        }

        var added = 0
        inTransaction {
            val toInsert = mutableListOf<ReadingSession>()
            for ((bookUrl, candidates) in candidatesByBook) {
                val existingStarts = sessionDao.startedAtFor(bookUrl).toHashSet()
                for (candidate in candidates) {
                    if (existingStarts.add(candidate.startedAt)) {
                        toInsert += candidate
                        added++
                    } else {
                        alreadyPresent++
                    }
                }
            }
            if (toInsert.isNotEmpty()) {
                sessionDao.insertAll(toInsert)
            }
        }
        return SessionRestoreResult(added = added, alreadyPresent = alreadyPresent)
    }
}
