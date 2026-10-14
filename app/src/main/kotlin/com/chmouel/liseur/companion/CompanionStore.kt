package com.chmouel.liseur.companion

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A chapter's recap, as one server and model wrote it for one version of
 * a book's file. [unit] is the chapter's index and [unitKey] names its
 * boundaries, so a recap is never read against a chapter it was not
 * written for.
 */
@Entity(tableName = "chapter_recap", primaryKeys = ["book_url", "unit_key"], indices = [Index("used_at")])
data class ChapterRecapRow(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "unit_key") val unitKey: String,
    val unit: Int,
    @ColumnInfo(name = "content_sha") val contentSha: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    val model: String,
    val format: Int,
    val json: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "used_at") val usedAt: Long,
)

/** One finished batch of a long chapter, kept so an interruption does not pay for it again. */
@Entity(tableName = "recap_part", primaryKeys = ["book_url", "unit_key", "batch_index"])
data class RecapPartRow(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "unit_key") val unitKey: String,
    @ColumnInfo(name = "batch_index") val batchIndex: Int,
    @ColumnInfo(name = "batch_count") val batchCount: Int,
    @ColumnInfo(name = "content_sha") val contentSha: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    val model: String,
    val format: Int,
    val json: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

/** A chapter the reader finished, waiting to be recapped. */
@Entity(tableName = "recap_queue", primaryKeys = ["book_url", "unit_key"])
data class RecapQueueRow(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "unit_key") val unitKey: String,
    val unit: Int,
    @ColumnInfo(name = "content_sha") val contentSha: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
)

/** The reader's own choice for one book, over the switch in settings. */
@Entity(tableName = "recap_choice")
data class RecapChoiceRow(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    val enabled: Boolean,
)

@Dao
interface CompanionRecapDao {
    @Query("SELECT * FROM chapter_recap WHERE book_url = :bookUrl")
    suspend fun recaps(bookUrl: String): List<ChapterRecapRow>

    @Query("SELECT * FROM chapter_recap WHERE book_url = :bookUrl AND unit_key = :unitKey")
    suspend fun recap(bookUrl: String, unitKey: String): ChapterRecapRow?

    @Query("UPDATE chapter_recap SET used_at = :now WHERE book_url = :bookUrl AND unit_key IN (:keys)")
    suspend fun touch(bookUrl: String, keys: List<String>, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecap(row: ChapterRecapRow)

    @Query("DELETE FROM chapter_recap WHERE book_url = :bookUrl AND (content_sha != :sha OR format != :format)")
    suspend fun forgetOtherRecaps(bookUrl: String, sha: String, format: Int)

    @Query("SELECT * FROM recap_part WHERE book_url = :bookUrl AND unit_key = :unitKey")
    suspend fun parts(bookUrl: String, unitKey: String): List<RecapPartRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPart(row: RecapPartRow)

    @Query("DELETE FROM recap_part WHERE book_url = :bookUrl AND unit_key = :unitKey")
    suspend fun forgetParts(bookUrl: String, unitKey: String)

    @Query(
        "DELETE FROM recap_part WHERE book_url = :bookUrl AND " +
            "(content_sha != :sha OR server_id != :server OR model != :model OR format != :format)",
    )
    suspend fun forgetOtherParts(bookUrl: String, sha: String, server: String, model: String, format: Int)

    @Query("SELECT * FROM recap_queue WHERE book_url = :bookUrl ORDER BY enqueued_at, unit")
    suspend fun queued(bookUrl: String): List<RecapQueueRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(row: RecapQueueRow)

    @Query("DELETE FROM recap_queue WHERE book_url = :bookUrl AND unit_key = :unitKey")
    suspend fun dequeue(bookUrl: String, unitKey: String)

    @Query("DELETE FROM recap_queue WHERE book_url = :bookUrl AND content_sha != :sha")
    suspend fun forgetOtherQueued(bookUrl: String, sha: String)

    @Query("DELETE FROM recap_queue WHERE book_url NOT IN (SELECT book_url FROM recap_choice WHERE enabled)")
    suspend fun clearQueueFollowingSettings()

    @Query("SELECT enabled FROM recap_choice WHERE book_url = :bookUrl")
    fun choice(bookUrl: String): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChoice(row: RecapChoiceRow)

    @Query(
        "SELECT book_url FROM chapter_recap UNION SELECT book_url FROM recap_part " +
            "UNION SELECT book_url FROM recap_queue UNION SELECT book_url FROM recap_choice",
    )
    suspend fun books(): List<String>

    @Query("DELETE FROM chapter_recap WHERE book_url IN (:bookUrls)")
    suspend fun forgetRecaps(bookUrls: List<String>): Int

    @Query("DELETE FROM recap_part WHERE book_url IN (:bookUrls)")
    suspend fun forgetParts(bookUrls: List<String>): Int

    @Query("DELETE FROM recap_queue WHERE book_url IN (:bookUrls)")
    suspend fun forgetQueued(bookUrls: List<String>): Int

    @Query("DELETE FROM recap_choice WHERE book_url IN (:bookUrls)")
    suspend fun forgetChoices(bookUrls: List<String>): Int

    @Query("SELECT COUNT(*) FROM chapter_recap")
    suspend fun recapCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(json)), 0) FROM chapter_recap")
    suspend fun recapCharacters(): Long

    @Query("SELECT book_url, unit_key, LENGTH(json) AS length FROM chapter_recap ORDER BY used_at LIMIT :limit")
    suspend fun oldestRecaps(limit: Int): List<RecapLength>

    @Query("DELETE FROM chapter_recap WHERE book_url = :bookUrl AND unit_key = :unitKey")
    suspend fun forgetRecap(bookUrl: String, unitKey: String)

    @Query("SELECT COUNT(*) FROM recap_part")
    suspend fun partCount(): Int

    @Query(
        "DELETE FROM recap_part WHERE rowid IN " +
            "(SELECT rowid FROM recap_part ORDER BY saved_at LIMIT :count)",
    )
    suspend fun dropOldestParts(count: Int)
}

data class RecapLength(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "unit_key") val unitKey: String,
    val length: Long,
)

/**
 * Kept apart from `liseur.db`: the backup rules name that file only, so
 * recaps, which can always be written again and hold paraphrased book
 * text, are neither backed up nor synced.
 */
@Database(
    entities = [ChapterRecapRow::class, RecapPartRow::class, RecapQueueRow::class, RecapChoiceRow::class],
    version = 2,
    exportSchema = true,
)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun recaps(): CompanionRecapDao

    companion object {
        const val NAME = "companion.db"

        /** Adds the reader's per-book recap choice. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recap_choice` (`book_url` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, PRIMARY KEY(`book_url`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}

/**
 * Who wrote a recap, for which version of the book, in which format. A
 * recap is read under the same book version and format whoever wrote it:
 * lookups use only its names and refs, which are checked against the
 * passages. Batches of an unfinished recap are kept only for the same
 * server and model.
 */
data class RecapIdentity(
    val contentSha: String,
    val serverId: String,
    val model: String,
    val format: Int,
)

/** A chapter as recaps know it: its index and a key naming its boundaries. */
data class RecapUnit(val index: Int, val key: String)

/** A saved recap or batch, with the unit it was written for. */
data class SavedRecap(val unit: RecapUnit, val json: String)

/** One book's recaps, as background recapping uses them. */
interface RecapBook {
    /** What a recap begun now must still hold to be saved. */
    fun ticket(): Long

    suspend fun recaps(identity: RecapIdentity): List<SavedRecap>

    suspend fun hasRecap(identity: RecapIdentity, unit: RecapUnit): Boolean

    suspend fun parts(identity: RecapIdentity, unit: RecapUnit, batchCount: Int): Map<Int, String>

    suspend fun savePart(identity: RecapIdentity, unit: RecapUnit, index: Int, count: Int, json: String, ticket: Long): Boolean

    suspend fun saveRecap(identity: RecapIdentity, unit: RecapUnit, json: String, ticket: Long): Boolean

    suspend fun enqueue(unit: RecapUnit): Boolean

    suspend fun queued(): List<RecapUnit>

    suspend fun dequeue(unit: RecapUnit)
}

/**
 * Chapter recaps saved on this phone, the chapters waiting for one, and
 * the batches of long chapters already done. At most [maxRecaps] recaps
 * and [maxCharacters] of their text, the least recently used going
 * first.
 *
 * Removing a book drops all of it and fences open handles, as a reply
 * asked before the removal must not be saved after it; so does turning
 * recaps off, everywhere or for one book, through [BookRecaps.ticket].
 */
class CompanionStore(
    private val database: CompanionDatabase,
    /** Which of these book URLs the library still holds. */
    private val present: suspend (List<String>) -> List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxRecaps: Int = MAX_RECAPS,
    private val maxCharacters: Long = MAX_CHARACTERS,
    private val maxParts: Int = MAX_PARTS,
) {
    private val dao = database.recaps()
    private val writes = Mutex()
    private val epochs = ConcurrentHashMap<String, Long>()
    private val disabled = AtomicLong()
    private val stops = ConcurrentHashMap<String, Long>()

    /** The book at [bookUrl], whose file hashes to [contentSha], for one opening of it. */
    fun forBook(bookUrl: String, contentSha: String): BookRecaps =
        BookRecaps(bookUrl, contentSha, epochs.computeIfAbsent(bookUrl) { 0L })

    // Both counters only grow, so the sum changes whenever either does.
    private fun ticket(bookUrl: String): Long = disabled.get() + (stops[bookUrl] ?: 0L)

    /**
     * Recaps were turned off in settings: forgets every waiting chapter of
     * the books that follow settings, and no recap begun before is saved.
     */
    suspend fun stopRecaps() {
        writes.withLock {
            disabled.incrementAndGet()
            dao.clearQueueFollowingSettings()
        }
    }

    /** The reader's choice for [bookUrl], or null while it follows settings. */
    fun recapChoice(bookUrl: String): Flow<Boolean?> = dao.choice(bookUrl)

    /** Recaps on or off for [bookUrl] alone; off forgets its waiting chapters and saves no recap begun before. */
    suspend fun setRecapChoice(bookUrl: String, enabled: Boolean) {
        writes.withLock {
            if (!enabled) stops.merge(bookUrl, 1L, Long::plus)
            database.withTransaction {
                dao.putChoice(RecapChoiceRow(bookUrl, enabled))
                if (!enabled) dao.forgetQueued(listOf(bookUrl))
            }
        }
    }

    /** Keeps within the limits and drops the books gone from the library: once at start. */
    suspend fun tidy() {
        quietly("trim") { writes.withLock { trim() } }
        sweep()
    }

    /** Drops what was kept for books the library no longer has, and fences every open handle on them. */
    suspend fun sweep() = quietly("sweep") {
        writes.withLock {
            val books = (dao.books() + epochs.keys).distinct()
            if (books.isEmpty()) return@withLock
            val kept = books.chunked(BATCH).flatMap { present(it) }.toSet()
            val gone = books.filterNot { it in kept }
            gone.forEach { epochs.merge(it, 1L, Long::plus) }
            gone.chunked(BATCH).forEach {
                database.withTransaction {
                    dao.forgetRecaps(it)
                    dao.forgetParts(it)
                    dao.forgetQueued(it)
                    dao.forgetChoices(it)
                }
            }
        }
    }

    // Called holding [writes].
    private suspend fun trim() {
        val over = dao.recapCount() - maxRecaps
        if (over > 0) dao.oldestRecaps(over).forEach { dao.forgetRecap(it.bookUrl, it.unitKey) }
        var excess = dao.recapCharacters() - maxCharacters
        while (excess > 0) {
            val oldest = dao.oldestRecaps(BATCH)
            if (oldest.isEmpty()) break
            for (row in oldest) {
                dao.forgetRecap(row.bookUrl, row.unitKey)
                excess -= row.length
                if (excess <= 0) break
            }
        }
        val parts = dao.partCount() - maxParts
        if (parts > 0) dao.dropOldestParts(parts)
    }

    private suspend fun quietly(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not $what character recaps: ${e.javaClass.simpleName}")
        }
    }

    /**
     * One book's recaps for one opening of it. Reads return only what
     * matches the book version and format asked for, and drop the rest: a
     * recap from another file or format is never used again. One from
     * another server or model is kept and used.
     */
    inner class BookRecaps internal constructor(
        val bookUrl: String,
        val contentSha: String,
        private val epoch: Long,
    ) : RecapBook {
        private fun fenced() = epochs[bookUrl] != epoch

        override fun ticket(): Long = this@CompanionStore.ticket(bookUrl)

        override suspend fun hasRecap(identity: RecapIdentity, unit: RecapUnit): Boolean {
            val row = dao.recap(bookUrl, unit.key) ?: return false
            return row.contentSha == identity.contentSha && row.format == identity.format
        }

        /** The recaps for [identity]'s book version and format, by any server or model, touched as used. */
        override suspend fun recaps(identity: RecapIdentity): List<SavedRecap> {
            require(identity.contentSha == contentSha)
            val rows = writes.withLock {
                if (fenced()) return emptyList()
                dao.forgetOtherRecaps(bookUrl, contentSha, identity.format)
                dao.recaps(bookUrl).also { found ->
                    if (found.isNotEmpty()) dao.touch(bookUrl, found.map { it.unitKey }, now())
                }
            }
            return rows.map { SavedRecap(RecapUnit(it.unit, it.unitKey), it.json) }
        }

        /** The batches of [unit] already done under [identity], by index, when it still has [batchCount]. */
        override suspend fun parts(identity: RecapIdentity, unit: RecapUnit, batchCount: Int): Map<Int, String> {
            require(identity.contentSha == contentSha)
            return writes.withLock {
                if (fenced()) return emptyMap()
                with(identity) { dao.forgetOtherParts(bookUrl, contentSha, serverId, model, format) }
                val rows = dao.parts(bookUrl, unit.key)
                if (rows.any { it.batchCount != batchCount || it.batchIndex !in 0 until batchCount }) {
                    dao.forgetParts(bookUrl, unit.key)
                    emptyMap()
                } else {
                    rows.associate { it.batchIndex to it.json }
                }
            }
        }

        /** Saves one finished batch, unless the book went or recaps were turned off since [ticket]. */
        override suspend fun savePart(
            identity: RecapIdentity,
            unit: RecapUnit,
            index: Int,
            count: Int,
            json: String,
            ticket: Long,
        ): Boolean {
            require(identity.contentSha == contentSha && index in 0 until count)
            return writes.withLock {
                if (fenced() || ticket != ticket()) return false
                dao.putPart(
                    RecapPartRow(
                        bookUrl, unit.key, index, count, contentSha,
                        identity.serverId, identity.model, identity.format, json, now(),
                    ),
                )
                true
            }
        }

        /** Saves [unit]'s recap, and with it drops its batches and its place in the queue. */
        override suspend fun saveRecap(identity: RecapIdentity, unit: RecapUnit, json: String, ticket: Long): Boolean {
            require(identity.contentSha == contentSha)
            return writes.withLock {
                if (fenced() || ticket != ticket()) return false
                val at = now()
                database.withTransaction {
                    dao.putRecap(
                        ChapterRecapRow(
                            bookUrl, unit.key, unit.index, contentSha,
                            identity.serverId, identity.model, identity.format, json, at, at,
                        ),
                    )
                    dao.forgetParts(bookUrl, unit.key)
                    dao.dequeue(bookUrl, unit.key)
                }
                trim()
                true
            }
        }

        /** Queues [unit] to be recapped; false when the book has gone. */
        override suspend fun enqueue(unit: RecapUnit): Boolean = writes.withLock {
            if (fenced()) return false
            dao.enqueue(RecapQueueRow(bookUrl, unit.key, unit.index, contentSha, now()))
            true
        }

        /** The chapters waiting for a recap, oldest first; any queued for another version of the file are dropped. */
        override suspend fun queued(): List<RecapUnit> = writes.withLock {
            if (fenced()) return emptyList()
            dao.forgetOtherQueued(bookUrl, contentSha)
            dao.queued(bookUrl).map { RecapUnit(it.unit, it.unitKey) }
        }

        /** Takes [unit] off the queue without a recap, as when it can no longer be written. */
        override suspend fun dequeue(unit: RecapUnit) = writes.withLock {
            if (!fenced()) dao.dequeue(bookUrl, unit.key)
        }
    }

    companion object {
        const val MAX_RECAPS = 5_000
        const val MAX_CHARACTERS = 20_000_000L
        const val MAX_PARTS = 2_000
        private const val BATCH = 500
        private const val TAG = "CompanionStore"
    }
}
