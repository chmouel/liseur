package com.chmouel.liseur.data.library

import com.chmouel.liseur.companion.CompanionStore
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.CompanionBackupContents
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.matchBackedUpBookUrl
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class CompanionBackupRepository(
    /** Opens the companion store; restore always needs it. */
    private val store: () -> CompanionStore,
    private val bookDao: BookDao,
    /** Whether a companion store is open or saved; export does not create one just to find it empty. */
    private val storeExists: () -> Boolean,
) {
    /**
     * Writes completed recaps to [file], most recently used first, then the
     * running summaries in what is left, stopping each before the first one
     * that would take the payload past [maxBytes] so a large store never
     * fails the whole backup. Recaps leave room for summaries: they are
     * what the summaries cannot be written again without.
     */
    suspend fun writeContents(file: File, maxBytes: Long) {
        val header = """{"format":1,"application":"liseur","recaps":[""".toByteArray()
        val middle = """],"summaries":[""".toByteArray()
        val footer = "]}".toByteArray()
        require(header.size + middle.size + footer.size <= maxBytes)
        val reserve = minOf(SUMMARY_RESERVE, maxBytes / 16)
        file.outputStream().buffered().use { output ->
            output.write(header)
            var written = (header.size + middle.size + footer.size).toLong()
            if (storeExists()) {
                val books = HashMap<String, Pair<String?, String?>>()
                suspend fun entry(bookUrl: String): JSONObject {
                    val (title, author) = books.getOrPut(bookUrl) {
                        bookDao.getByUrl(bookUrl).let { it?.title to it?.author }
                    }
                    return JSONObject().put("book_id", bookUrl).apply {
                        title?.let { put("title", it) }
                        author?.let { put("author", it) }
                    }
                }
                val store = store()
                var first = true
                store.exportRecaps { row ->
                    currentCoroutineContext().ensureActive()
                    val entry = entry(row.bookUrl)
                        .put("unit_key", row.unitKey).put("unit", row.unit)
                        .put("content_sha", row.contentSha).put("server_id", row.serverId)
                        .put("model", row.model).put("recap_format", row.format)
                        .put("json", row.json).put("saved_at", row.savedAt).put("used_at", row.usedAt)
                    val bytes = (if (first) "" else ",").plus(entry.toString()).toByteArray()
                    if (written + bytes.size > maxBytes - reserve) return@exportRecaps false
                    output.write(bytes)
                    written += bytes.size
                    first = false
                    true
                }
                output.write(middle)
                first = true
                store.exportSummaries { row ->
                    currentCoroutineContext().ensureActive()
                    val entry = entry(row.bookUrl)
                        .put("key", row.key).put("level", row.level).put("lo", row.lo).put("hi", row.hi)
                        .put("content_sha", row.contentSha).put("recap_format", row.recapFormat)
                        .put("summary_format", row.summaryFormat).put("deps", row.deps).put("text", row.text)
                        .put("saved_at", row.savedAt).put("used_at", row.usedAt)
                    val bytes = (if (first) "" else ",").plus(entry.toString()).toByteArray()
                    if (written + bytes.size > maxBytes) return@exportSummaries false
                    output.write(bytes)
                    written += bytes.size
                    first = false
                    true
                }
            } else {
                output.write(middle)
            }
            output.write(footer)
        }
    }

    /** Adds what [contents] holds for books in the library that are not here yet; returns how many recaps were added. */
    suspend fun restore(contents: CompanionBackupContents): Int {
        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val books = contents.recaps.map { Triple(it.recap.bookUrl, it.title, it.author) } +
            contents.summaries.map { Triple(it.summary.bookUrl, it.title, it.author) }
        val targets = books.distinctBy { it.first }.associate { (url, title, author) ->
            url to matchBackedUpBookUrl(url, title, author, known)
        }
        val counts = targets.values.filterNotNull().groupingBy { it }.eachCount()
        fun target(url: String) = targets[url]?.takeIf { counts.getValue(it) == 1 }
        val added = store().restoreRecaps(contents.recaps.mapNotNull { entry ->
            target(entry.recap.bookUrl)?.let { entry.recap.copy(bookUrl = it) }
        })
        if (contents.summaries.isNotEmpty()) {
            store().restoreSummaries(contents.summaries.mapNotNull { entry ->
                target(entry.summary.bookUrl)?.let { entry.summary.copy(bookUrl = it) }
            })
        }
        return added
    }

    private companion object {
        const val SUMMARY_RESERVE = 1024L * 1024
    }
}
