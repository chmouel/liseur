package com.chmouel.liseur.translate

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A sentence page translation translated, kept for the book it was read in. */
@Entity(
    tableName = "translated_sentences",
    primaryKeys = ["book_url", "key"],
    indices = [Index("used_at")],
)
data class TranslatedSentence(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    /** SHA-256 of the service, model, languages, context and sentence it answers. */
    val key: String,
    val translation: String,
    @ColumnInfo(name = "used_at") val usedAt: Long,
)

@Dao
interface TranslatedSentenceDao {
    @Query("SELECT translation FROM translated_sentences WHERE book_url = :bookUrl AND `key` = :key")
    suspend fun find(bookUrl: String, key: String): String?

    @Query("UPDATE translated_sentences SET used_at = :now WHERE book_url = :bookUrl AND `key` = :key")
    suspend fun touch(bookUrl: String, key: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslatedSentence)

    @Query("SELECT COUNT(*) FROM translated_sentences")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM translated_sentences")
    fun counted(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(translation)), 0) FROM translated_sentences")
    suspend fun characters(): Long

    @Query("SELECT LENGTH(translation) FROM translated_sentences ORDER BY used_at, rowid LIMIT :limit")
    suspend fun oldestLengths(limit: Int): List<Long>

    @Query(
        "DELETE FROM translated_sentences WHERE rowid IN " +
            "(SELECT rowid FROM translated_sentences ORDER BY used_at, rowid LIMIT :count)",
    )
    suspend fun dropOldest(count: Int)

    @Query("SELECT DISTINCT book_url FROM translated_sentences")
    suspend fun books(): List<String>

    @Query("DELETE FROM translated_sentences WHERE book_url IN (:bookUrls)")
    suspend fun forget(bookUrls: List<String>)

    @Query("DELETE FROM translated_sentences")
    suspend fun clear()
}

/**
 * Kept apart from `liseur.db`: the backup rules name that file only, so
 * a cache that can always be asked for again stays out of backups.
 */
@Database(entities = [TranslatedSentence::class], version = 1, exportSchema = true)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun sentences(): TranslatedSentenceDao

    companion object {
        const val NAME = "translations.db"
    }
}

/**
 * The sentences page translation has translated, saved on this phone so
 * a page read again is not asked for again. At most [maxSentences], and
 * [maxCharacters] of translated text, the least recently read going first.
 *
 * A clear, and the removal of a book, are fenced: a reply asked before
 * either is not saved after it. Reads take no lock; every write goes
 * through [writes].
 */
class SavedTranslations(
    private val database: TranslationCacheDatabase,
    /** Which of these book URLs the library still holds. */
    private val present: suspend (List<String>) -> List<String>,
    /** The files the database takes up, to say how much room it uses. */
    private val files: () -> List<File> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    private val maxSentences: Int = MAX_SENTENCES,
    private val maxCharacters: Long = MAX_CHARACTERS,
) {
    data class Stats(val sentences: Int, val bytes: Long)

    private val dao = database.sentences()
    private val writes = Mutex()
    private val cleared = AtomicLong()
    private val epochs = ConcurrentHashMap<String, Long>()
    private var puts = 0
    private val resized = MutableStateFlow(0)

    /** How many sentences are saved and the room they take, again after each clear. Silent if unreadable. */
    val stats: Flow<Stats> = combine(dao.counted(), resized) { count, _ ->
        Stats(count, files().filter { it.exists() }.sumOf { it.length() })
    }.flowOn(Dispatchers.IO).catch { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "Could not count saved translations: ${e.javaClass.simpleName}")
    }

    /** The book at [bookUrl]'s saved sentences, for one opening of it. */
    fun forBook(bookUrl: String): PageTranslationCache = BookCache(bookUrl, epochs[bookUrl] ?: 0L)

    /** Keeps within the limits and drops the books gone from the library: once at start. */
    suspend fun tidy() {
        quietly("trim") { writes.withLock { trim() } }
        sweep()
    }

    /**
     * Drops what was saved for books the library no longer has. Asked
     * outside any transaction of the caller's, it sees only removals
     * already committed: one rolled back leaves its book, and its
     * sentences, where they were.
     */
    suspend fun sweep() = quietly("sweep") {
        val books = dao.books()
        if (books.isEmpty()) return@quietly
        val kept = books.chunked(BATCH).flatMap { present(it) }.toSet()
        val gone = books.filterNot { it in kept }
        if (gone.isEmpty()) return@quietly
        writes.withLock {
            gone.forEach { epochs.merge(it, 1L, Long::plus) }
            gone.chunked(BATCH).forEach { dao.forget(it) }
        }
    }

    /**
     * Forgets every saved sentence. Fails when they could not be deleted;
     * the space is then given back as well as it can be.
     */
    suspend fun clear() {
        writes.withLock {
            cleared.incrementAndGet()
            dao.clear()
        }
        try {
            withContext(Dispatchers.IO) {
                // Outside any transaction: VACUUM refuses to run in one.
                val db = database.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                db.execSQL("VACUUM")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not compact saved translations: ${e.javaClass.simpleName}")
        }
        resized.value++
    }

    // Upkeep runs unattended on the application scope: a store it cannot reach is left for next time.
    private suspend fun quietly(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not $what saved translations: ${e.javaClass.simpleName}")
        }
    }

    internal suspend fun lookup(bookUrl: String, key: String): PageTranslationCache.Lookup {
        val stamp = cleared.get()
        val hashed = hash(key)
        val found = dao.find(bookUrl, hashed)
        if (found != null) dao.touch(bookUrl, hashed, now())
        return PageTranslationCache.Lookup(found, stamp)
    }

    internal suspend fun save(bookUrl: String, epoch: Long, key: String, translation: String, stamp: Long) {
        writes.withLock {
            if (cleared.get() != stamp || (epochs[bookUrl] ?: 0L) != epoch) return
            dao.put(TranslatedSentence(bookUrl, hash(key), translation, now()))
            if (++puts % TRIM_EVERY == 0) trim()
        }
    }

    // Called holding [writes].
    private suspend fun trim() {
        val over = dao.count() - maxSentences
        if (over > 0) dao.dropOldest(over)
        var excess = dao.characters() - maxCharacters
        while (excess > 0) {
            val lengths = dao.oldestLengths(BATCH)
            if (lengths.isEmpty()) break
            var dropped = 0
            var freed = 0L
            for (length in lengths) {
                dropped++
                freed += length
                if (freed >= excess) break
            }
            dao.dropOldest(dropped)
            excess -= freed
        }
    }

    private inner class BookCache(private val bookUrl: String, private val epoch: Long) : PageTranslationCache {
        // A saved sentence that cannot be read or written is one asked again: never a failed page.
        override suspend fun get(key: String): PageTranslationCache.Lookup = try {
            lookup(bookUrl, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a saved translation: ${e.javaClass.simpleName}")
            PageTranslationCache.Lookup(null, MISSED)
        }

        override suspend fun put(key: String, value: String, stamp: Long) {
            if (stamp == MISSED) return
            try {
                save(bookUrl, epoch, key, value, stamp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save a translation: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val MAX_SENTENCES = 50_000

        /** About 20 MB of Latin text, 60 MB at most for scripts that take three bytes a character. */
        const val MAX_CHARACTERS = 20_000_000L

        // Enough puts between trims that saving stays one insert, few enough that the overshoot is small.
        private const val TRIM_EVERY = 100

        // Below SQLite's 999-variable limit on older Android versions.
        private const val BATCH = 500

        // The stamp of a lookup that failed: nothing is saved under it.
        private const val MISSED = -1L

        private const val TAG = "SavedTranslations"

        private fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
