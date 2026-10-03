package com.chmouel.liseur.data.library

import android.util.JsonWriter
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.BackedUpBook
import com.chmouel.liseur.domain.BackedUpReadingPosition
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.matchBackedUpBook
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

internal class ReadingPositionBackupTooLarge : IOException("Reading position backup exceeds limit")

class ReadingPositionBackupRepository(
    private val progressDao: ReadingProgressDao,
    private val bookDao: BookDao,
    private val requestBookSync: (String) -> Unit = {},
) {
    suspend fun writeContents(file: File, maxBytes: Long) {
        var written = 0L
        var lastUrl: String? = null
        file.outputStream().use { output ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    if (++written > maxBytes) throw ReadingPositionBackupTooLarge()
                    output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length
                    if (written > maxBytes) throw ReadingPositionBackupTooLarge()
                    output.write(bytes, offset, length)
                }
            }
            JsonWriter(bounded.writer(Charsets.UTF_8)).use { writer ->
                writer.beginObject().name("format").value(1).name("application").value("liseur")
                    .name("positions").beginArray()
                var page = progressDao.firstBackupPage()
                while (page.isNotEmpty()) {
                    for (position in page) {
                        currentCoroutineContext().ensureActive()
                        lastUrl = position.bookUrl
                        // Status-only rows have no saved place to restore.
                        if (runCatching { Locator.fromJSON(JSONObject(position.locatorJson)) }.getOrNull() == null) continue
                        val book = bookDao.getByUrl(position.bookUrl)
                        writer.beginObject().name("book_id").value(position.bookUrl)
                        book?.title?.let { writer.name("title").value(it) }
                        book?.author?.let { writer.name("author").value(it) }
                        writer.name("locator").value(position.locatorJson)
                        position.totalProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }
                            ?.let { writer.name("progression").value(it) }
                        writer.name("read_at").value((position.readAt ?: position.updatedAt).coerceAtLeast(0))
                            .endObject()
                        writer.flush()
                    }
                    page = progressDao.nextBackupPage(checkNotNull(lastUrl))
                }
                writer.endArray().endObject()
            }
        }
    }

    suspend fun restore(positions: List<BackedUpReadingPosition>): Int {
        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val changed = linkedSetOf<String>()
        for (position in positions) {
            currentCoroutineContext().ensureActive()
            val url = matchBackedUpBook(BackedUpBook(position.bookId, position.title, position.author, emptyList()), known)
            progressDao.openBooks.unlessOpen(url) {
                progressDao.restoreBackupPosition(url, position.locatorJson, position.progression,
                    position.readAt, System.currentTimeMillis())
            } ?: throw IOException("Cannot replace the position of an open book")
            changed += url
        }
        changed.forEach(requestBookSync)
        return changed.size
    }
}
