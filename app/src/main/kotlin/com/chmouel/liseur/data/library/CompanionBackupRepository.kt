package com.chmouel.liseur.data.library

import com.chmouel.liseur.companion.CompanionStore
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.BackedUpCompanionRecap
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
     * Writes completed recaps to [file], most recently used first, stopping
     * before the first one that would take the payload past [maxBytes] so a
     * large store never fails the whole backup.
     */
    suspend fun writeContents(file: File, maxBytes: Long) {
        val header = """{"format":1,"application":"liseur","recaps":[""".toByteArray()
        val footer = "]}".toByteArray()
        require(header.size + footer.size <= maxBytes)
        file.outputStream().buffered().use { output ->
            output.write(header)
            var written = (header.size + footer.size).toLong()
            var first = true
            if (storeExists()) {
                val books = HashMap<String, Pair<String?, String?>>()
                store().exportRecaps { row ->
                    currentCoroutineContext().ensureActive()
                    val (title, author) = books.getOrPut(row.bookUrl) {
                        bookDao.getByUrl(row.bookUrl).let { it?.title to it?.author }
                    }
                    val entry = JSONObject().put("book_id", row.bookUrl)
                    title?.let { entry.put("title", it) }
                    author?.let { entry.put("author", it) }
                    entry.put("unit_key", row.unitKey).put("unit", row.unit)
                        .put("content_sha", row.contentSha).put("server_id", row.serverId)
                        .put("model", row.model).put("recap_format", row.format)
                        .put("json", row.json).put("saved_at", row.savedAt).put("used_at", row.usedAt)
                    val bytes = (if (first) "" else ",").plus(entry.toString()).toByteArray()
                    if (written + bytes.size > maxBytes) return@exportRecaps false
                    output.write(bytes)
                    written += bytes.size
                    first = false
                    true
                }
            }
            output.write(footer)
        }
    }

    suspend fun restore(contents: List<BackedUpCompanionRecap>): Int {
        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val targets = contents.distinctBy { it.recap.bookUrl }.associate { entry ->
            entry.recap.bookUrl to matchBackedUpBookUrl(entry.recap.bookUrl, entry.title, entry.author, known)
        }
        val counts = targets.values.filterNotNull().groupingBy { it }.eachCount()
        return store().restoreRecaps(contents.mapNotNull { entry ->
            val target = targets[entry.recap.bookUrl] ?: return@mapNotNull null
            if (counts.getValue(target) != 1) return@mapNotNull null
            entry.recap.copy(bookUrl = target)
        })
    }
}
