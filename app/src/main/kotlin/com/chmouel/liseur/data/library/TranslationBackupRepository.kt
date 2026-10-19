package com.chmouel.liseur.data.library

import android.util.JsonWriter
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.BackedUpTranslation
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.matchBackedUpBookUrl
import com.chmouel.liseur.translate.SavedTranslations
import com.chmouel.liseur.translate.KeptTranslation
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TranslationBackupResult(val books: Int = 0, val skipped: Int = 0)

class TranslationBackupRepository(
    private val store: () -> SavedTranslations,
    private val bookDao: BookDao,
    private val storeExists: () -> Boolean = { true },
) {
    suspend fun writeContents(file: File, maxBytes: Long): TranslationBackupResult {
        require(maxBytes >= EMPTY.length)
        if (!storeExists()) {
            file.writeText(EMPTY)
            return TranslationBackupResult()
        }
        val saved = store()
        val records = saved.keptRecords()
        var skipped = records.count { it.state != "done" && it.state != "restored" }
        var count = 0
        var written = EMPTY.toByteArray().size.toLong()
        file.outputStream().buffered().use { output ->
            output.write(HEADER.toByteArray())
            for (record in records.filter { it.state == "done" || it.state == "restored" }) {
                currentCoroutineContext().ensureActive()
                val exported = saved.withKeptExport(record.bookUrl) { current ->
                    val book = bookDao.getByUrl(current.bookUrl)
                    // Count with the same streaming encoder before writing: never emit a partial book.
                    val counter = Utf8Counter()
                    writeBook(JsonWriter(OutputStreamWriter(counter, Charsets.UTF_8)),
                        current, book?.title, book?.author, saved)
                    val bytes = counter.bytes + if (count == 0) 0 else 1
                    if (written + bytes > maxBytes) false else {
                        if (count > 0) output.write(','.code)
                        val writer = output.writer(Charsets.UTF_8)
                        writeBook(JsonWriter(writer), current, book?.title, book?.author, saved)
                        writer.flush()
                        written += bytes
                        count++
                        true
                    }
                }
                if (exported != true) skipped++
            }
            output.write("]}".toByteArray())
        }
        return TranslationBackupResult(count, skipped)
    }

    suspend fun restore(contents: List<BackedUpTranslation>): TranslationBackupResult {
        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val targets = contents.associate { entry ->
            entry.record.bookUrl to matchBackedUpBookUrl(entry.record.bookUrl, entry.title, entry.author, known)
        }
        val counts = targets.values.filterNotNull().groupingBy { it }.eachCount()
        var restored = 0
        for (entry in contents) {
            currentCoroutineContext().ensureActive()
            val target = targets[entry.record.bookUrl]?.takeIf { counts[it] == 1 } ?: continue
            store().restoreKept(entry.record.copy(bookUrl = target), entry.rows, entry.failures)
            restored++
        }
        return TranslationBackupResult(restored, contents.size - restored)
    }

    private suspend fun writeBook(
        writer: JsonWriter, record: KeptTranslation, title: String?, author: String?, store: SavedTranslations,
    ) {
        writer.beginObject()
        writer.name("book_id").value(record.bookUrl)
        writer.name("title").value(title)
        writer.name("author").value(author)
        writer.name("source").value(record.source)
        writer.name("target").value(record.target)
        writer.name("identity").value(record.identity)
        writer.name("total").value(record.total.toLong())
        writer.name("failed").value(record.failed.toLong())
        writer.name("rows").beginArray()
        store.visitExportRows(record.bookUrl) { row ->
            writer.beginObject().name("key").value(row.key).name("translation").value(row.translation)
                .name("peer").value(row.savedAt == 0L).name("used_at").value(row.usedAt).endObject()
        }
        writer.endArray().name("failures").beginArray()
        store.visitExportFailures(record.bookUrl) { writer.value(it) }
        writer.endArray().endObject()
        writer.flush()
    }

    private class Utf8Counter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) {
            bytes++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes += length
        }
    }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val HEADER = """{"format":1,"application":"liseur","books":["""
        private const val EMPTY = "$HEADER]}"
    }
}
