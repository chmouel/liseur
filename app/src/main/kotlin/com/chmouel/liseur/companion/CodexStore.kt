package com.chmouel.liseur.companion

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

@Entity(tableName = "codex_book")
data class CodexBookRow(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    val identity: String,
    val total: Int,
)

@Entity(tableName = "codex_unit", primaryKeys = ["book_url", "unit"])
data class CodexUnitRow(@ColumnInfo(name = "book_url") val bookUrl: String, val unit: Int, val stats: String)

@Entity(tableName = "codex_passage", primaryKeys = ["book_url", "ref"], indices = [Index(value = ["book_url", "unit"])])
data class CodexPassageRow(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val ref: String,
    val unit: Int,
    val lo: Int,
    val resource: Int,
    val text: String,
    val folded: String,
    /** The last 1-based position in [folded] where a match may start; after it lies only the next part's run-on. */
    @ColumnInfo(name = "own_end") val ownEnd: Int,
    val locator: String,
    val end: Int?,
) {
    fun passage(): Passage<Locator> = Passage(
        ref, PassageText.hash(text), resource, text, ChapterRange(lo, unit),
        checkNotNull(Locator.fromJSON(JSONObject(locator))), end, folded,
    )
}

@Dao
interface CodexDao {
    @Query("SELECT * FROM codex_book WHERE book_url = :bookUrl")
    suspend fun book(bookUrl: String): CodexBookRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBook(book: CodexBookRow)

    @Query("SELECT unit FROM codex_unit WHERE book_url = :bookUrl ORDER BY unit")
    suspend fun completed(bookUrl: String): List<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM codex_unit WHERE book_url = :bookUrl AND unit = :unit)")
    suspend fun isCompleted(bookUrl: String, unit: Int): Boolean

    @Query("SELECT * FROM codex_unit WHERE book_url = :bookUrl AND unit < :ceiling AND unit > :after ORDER BY unit LIMIT 50")
    suspend fun units(bookUrl: String, ceiling: Int, after: Int): List<CodexUnitRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUnit(unit: CodexUnitRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPassages(passages: List<CodexPassageRow>)

    @Query("SELECT * FROM codex_passage WHERE book_url = :bookUrl AND unit < :ceiling AND instr(folded, :needle) BETWEEN 1 AND own_end ORDER BY unit, rowid LIMIT 50 OFFSET :offset")
    suspend fun mentions(bookUrl: String, ceiling: Int, needle: String, offset: Int): List<CodexPassageRow>

    @Query("SELECT DISTINCT unit FROM codex_passage WHERE book_url = :bookUrl AND unit < :ceiling AND lo = unit AND instr(folded, :needle) BETWEEN 1 AND own_end ORDER BY unit")
    suspend fun mentionUnits(bookUrl: String, ceiling: Int, needle: String): List<Int>

    // One passage per shared chapter: the first holding both names, else the
    // first holding [needle]. Grouped once, as API 26's SQLite has no window functions.
    @Query(
        "WITH hits AS (SELECT rowid AS id, unit, instr(folded, :other) BETWEEN 1 AND own_end AS paired FROM codex_passage " +
            "WHERE book_url = :bookUrl AND unit < :ceiling AND lo = unit AND instr(folded, :needle) BETWEEN 1 AND own_end), " +
            "picks AS (SELECT unit, coalesce(min(CASE WHEN paired THEN id END), min(id)) AS id FROM hits GROUP BY unit) " +
            "SELECT codex_passage.* FROM codex_passage JOIN picks ON codex_passage.rowid = picks.id " +
            "WHERE picks.unit IN (SELECT unit FROM codex_passage WHERE book_url = :bookUrl AND lo = unit AND instr(folded, :other) BETWEEN 1 AND own_end) " +
            "ORDER BY codex_passage.unit, codex_passage.rowid LIMIT 50 OFFSET :offset",
    )
    suspend fun sharedMentions(bookUrl: String, ceiling: Int, needle: String, other: String, offset: Int): List<CodexPassageRow>

    @Query("DELETE FROM codex_book WHERE book_url IN (:books)")
    suspend fun forgetBooks(books: List<String>)

    @Query("DELETE FROM codex_unit WHERE book_url IN (:books)")
    suspend fun forgetUnits(books: List<String>)

    @Query("DELETE FROM codex_passage WHERE book_url IN (:books)")
    suspend fun forgetPassages(books: List<String>)
}

/** Rebuildable, local evidence. Every completed chapter and its passages commit together. */
class CodexStore(private val database: CompanionDatabase, private val present: suspend (List<String>) -> List<String>) {
    private val dao = database.codex()

    suspend fun prepare(book: CodexBookRow): List<Int> = database.withTransaction {
        check(book.bookUrl in present(listOf(book.bookUrl))) { "Book removed" }
        if (dao.book(book.bookUrl) != book) {
            forget(listOf(book.bookUrl))
            dao.putBook(book)
        }
        dao.completed(book.bookUrl)
    }

    suspend fun save(book: CodexBookRow, unit: Int, stats: Map<String, CodexStats>, passages: List<Passage<Locator>>) = database.withTransaction {
        check(dao.book(book.bookUrl) == book && book.bookUrl in present(listOf(book.bookUrl))) { "Book changed or removed" }
        require(unit in 0 until book.total && passages.all { it.range.hi == unit })
        if (dao.isCompleted(book.bookUrl, unit)) return@withTransaction
        dao.putPassages(CodexIndex.withSplitContinuity(passages).map { run ->
            val it = run.passage
            CodexPassageRow(book.bookUrl, it.ref, unit, it.range.lo, it.resource, it.text, run.folded, run.ownEnd, it.place.toJSON().toString(), it.end)
        })
        dao.putUnit(CodexUnitRow(book.bookUrl, unit, encode(stats)))
    }

    suspend fun entries(book: CodexBookRow, ceiling: Int): List<CodexEntry> = withContext(Dispatchers.Default) {
        database.withTransaction {
            if (dao.book(book.bookUrl) != book) return@withTransaction emptyList()
            val merged = LinkedHashMap<String, CodexEntry>()
            var after = -1
            while (true) {
                coroutineContext.ensureActive()
                val page = dao.units(book.bookUrl, ceiling, after)
                if (page.isEmpty()) break
                for (row in page) CodexIndex.add(merged, row.unit, decode(row.stats))
                after = page.last().unit
            }
            merged.values.filter { it.occurrences >= CodexIndex.MIN_OCCURRENCES }
                .sortedWith(compareByDescending<CodexEntry> { it.occurrences }.thenBy { it.firstUnit }.thenBy { it.name })
        }
    }

    suspend fun graph(book: CodexBookRow, ceiling: Int, entries: List<CodexEntry>): CodexGraph = database.withTransaction {
        if (dao.book(book.bookUrl) != book) return@withTransaction CodexGraph(emptyList(), emptyList(), 1, ceiling)
        val nodes = entries.filter { it.occurrences >= 5 }.take(25).map {
            CodexNode(it.name, it.occurrences, dao.mentionUnits(book.bookUrl, ceiling, needle(it.name)).toSet())
        }
        codexGraph(nodes, ceiling)
    }

    suspend fun mentions(book: CodexBookRow, ceiling: Int, name: String, other: String?, offset: Int): List<Passage<Locator>> = database.withTransaction {
        if (dao.book(book.bookUrl) != book) return@withTransaction emptyList()
        val rows = if (other == null) {
            dao.mentions(book.bookUrl, ceiling, needle(name), offset)
        } else {
            dao.sharedMentions(book.bookUrl, ceiling, needle(name), needle(other), offset)
        }
        rows.map { it.passage() }
    }

    internal suspend fun forget(books: List<String>) {
        dao.forgetPassages(books)
        dao.forgetUnits(books)
        dao.forgetBooks(books)
    }

    companion object {
        const val FORMAT = 1
        private fun needle(name: String) = PassageText.pattern(PassageText.fold(name))
        internal fun encode(stats: Map<String, CodexStats>): String = JSONObject().apply {
            stats.forEach { (name, value) -> put(name, JSONArray().put(value.occurrences).put(value.speechHits)) }
        }.toString()
        internal fun decode(json: String): Map<String, CodexStats> = JSONObject(json).let { obj ->
            obj.keys().asSequence().associateWith { name -> obj.getJSONArray(name).let { CodexStats(it.getInt(0), it.getInt(1)) } }
        }
    }
}
