package com.chmouel.liseur.translate

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
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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

/** A saved row and the length of its translation, oldest first, for trimming to the character budget. */
data class SavedLength(val id: Long, val length: Long)

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

    @Query(
        "SELECT rowid AS id, LENGTH(translation) AS length FROM translated_sentences " +
            "ORDER BY used_at, rowid LIMIT :limit",
    )
    suspend fun oldest(limit: Int): List<SavedLength>

    @Query("DELETE FROM translated_sentences WHERE rowid IN (:ids)")
    suspend fun drop(ids: List<Long>)

    @Query(
        "DELETE FROM translated_sentences WHERE rowid IN " +
            "(SELECT rowid FROM translated_sentences ORDER BY used_at, rowid LIMIT :count)",
    )
    suspend fun dropOldest(count: Int)

    @Query("SELECT DISTINCT book_url FROM translated_sentences")
    suspend fun books(): List<String>

    @Query("DELETE FROM translated_sentences WHERE book_url IN (:bookUrls)")
    suspend fun forget(bookUrls: List<String>): Int

    @Query("DELETE FROM translated_sentences")
    suspend fun clear()
}

/** A book left translated, and the languages, so it opens translated again. */
@Entity(tableName = "page_translation_modes")
data class PageTranslationModeRow(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    /** Null when the service works the language out. */
    val source: String?,
    val target: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface PageTranslationModeDao {
    @Query("SELECT * FROM page_translation_modes WHERE book_url = :bookUrl")
    suspend fun find(bookUrl: String): PageTranslationModeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: PageTranslationModeRow)

    @Query("DELETE FROM page_translation_modes WHERE book_url = :bookUrl")
    suspend fun forget(bookUrl: String)

    @Query("SELECT book_url FROM page_translation_modes")
    suspend fun books(): List<String>

    @Query("DELETE FROM page_translation_modes WHERE book_url IN (:bookUrls)")
    suspend fun forget(bookUrls: List<String>): Int
}

/**
 * Kept apart from `liseur.db`: the backup rules name that file only, so
 * a cache that can always be asked for again stays out of backups.
 */
@Database(entities = [TranslatedSentence::class, PageTranslationModeRow::class], version = 2, exportSchema = true)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun sentences(): TranslatedSentenceDao

    abstract fun modes(): PageTranslationModeDao

    companion object {
        const val NAME = "translations.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_translation_modes` (`book_url` TEXT NOT NULL, " +
                        "`source` TEXT, `target` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`book_url`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}

/**
 * The sentences page translation has translated, saved on this phone so
 * a page read again is not asked for again. At most [maxSentences], and
 * [maxCharacters] of translated text, the least recently read going first.
 * Also the books left translated ([modeFor]).
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
    /** Where the books' modes are written, one after the other; it outlives any reader. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class Stats(val sentences: Int, val bytes: Long)

    private val dao = database.sentences()
    private val modes = database.modes()
    private val writes = Mutex()
    private val cleared = AtomicLong()
    private val epochs = ConcurrentHashMap<String, Long>()
    private var puts = 0
    private val resized = MutableStateFlow(0)

    // One queue for every book and every handle: a reader closed after Stop
    // and the same book opened again see its changes in the order they came.
    private val modeChanges = Channel<ModeChange>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (change in modeChanges) {
                try {
                    change.apply()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    change.failed(e)
                }
            }
        }
    }

    /** How many sentences are saved and the room they take, again after each clear. Silent if unreadable. */
    val stats: Flow<Stats> = combine(dao.counted(), resized) { count, _ ->
        Stats(count, files().filter { it.exists() }.sumOf { it.length() })
    }.flowOn(Dispatchers.IO).catch { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "Could not count saved translations: ${e.javaClass.simpleName}")
    }

    /**
     * The book at [bookUrl]'s saved sentences, for one opening of it. The
     * book is registered so a sweep fences it even before it has a row.
     */
    fun forBook(bookUrl: String): PageTranslationCache = BookCache(bookUrl, epochs.computeIfAbsent(bookUrl) { 0L })

    /** Whether the book at [bookUrl] is left translated, for one opening of it; fenced like [forBook]. */
    fun modeFor(bookUrl: String): PageTranslationModes = BookMode(bookUrl, epochs.computeIfAbsent(bookUrl) { 0L })

    /** Keeps within the limits and drops the books gone from the library: once at start. */
    suspend fun tidy() {
        quietly("trim") { writes.withLock { trim() } }
        sweep()
    }

    /**
     * Drops what was saved for books the library no longer has, and
     * fences every open handle on them, saved rows or not. Asked outside
     * any transaction of the caller's, it sees only removals already
     * committed: one rolled back leaves its book, and its sentences, where
     * they were. Whole under the lock, so an older sweep never acts on
     * what a newer one has already settled.
     */
    suspend fun sweep() = quietly("sweep") {
        val emptied = writes.withLock {
            val books = (dao.books() + modes.books() + epochs.keys).distinct()
            if (books.isEmpty()) return@withLock false
            val kept = books.chunked(BATCH).flatMap { present(it) }.toSet()
            val gone = books.filterNot { it in kept }
            gone.forEach { epochs.merge(it, 1L, Long::plus) }
            gone.chunked(BATCH).forEach { modes.forget(it) }
            // Removed books stay fenced, so they are gone again on every later sweep with nothing left to delete.
            val removed = gone.chunked(BATCH).sumOf { dao.forget(it) }
            removed > 0 && dao.count() == 0
        }
        // Settings offer no Clear once nothing is saved, so the room the last book took is given back here.
        if (emptied) compact()
    }

    /**
     * Forgets every saved sentence. Fails when they could not be deleted;
     * the space is then given back as well as it can be. The books left
     * translated stay so: that is the reader's choice, not a cache.
     */
    suspend fun clear() {
        writes.withLock {
            cleared.incrementAndGet()
            dao.clear()
        }
        compact()
    }

    private suspend fun compact() {
        try {
            // Once the rows are gone settings offer no Clear, so a caller leaving does not stop this halfway.
            withContext(NonCancellable + Dispatchers.IO) {
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

    internal suspend fun save(
        bookUrl: String,
        epoch: Long,
        key: String,
        translation: String,
        stamp: Long,
        inLibrary: suspend () -> Boolean = { true },
    ) {
        writes.withLock {
            if (cleared.get() != stamp || (epochs[bookUrl] ?: 0L) != epoch) return
            if (!inLibrary()) return
            dao.put(TranslatedSentence(bookUrl, hash(key), translation, now()))
            // Counting is cheap, so the sentence cap holds after every save; summing the characters waits.
            if (++puts % TRIM_EVERY == 0 || dao.count() > maxSentences) trim()
        }
    }

    // Called holding [writes].
    private suspend fun trim() {
        val over = dao.count() - maxSentences
        if (over > 0) dao.dropOldest(over)
        var excess = dao.characters() - maxCharacters
        while (excess > 0) {
            val oldest = dao.oldest(BATCH)
            if (oldest.isEmpty()) break
            // The exact rows measured: a lookup may reorder them meanwhile.
            val dropped = mutableListOf<Long>()
            var freed = 0L
            for (row in oldest) {
                dropped += row.id
                freed += row.length
                if (freed >= excess) break
            }
            dao.drop(dropped)
            excess -= freed
        }
    }

    private interface ModeChange {
        suspend fun apply()

        fun failed(e: Exception)
    }

    private inner class BookMode(private val bookUrl: String, private val epoch: Long) : PageTranslationModes {
        @Volatile private var found = false

        private suspend fun inLibrary() = found || present(listOf(bookUrl)).isNotEmpty().also { found = it }

        // Through the queue, so a Stop asked just before is already applied.
        override suspend fun saved(): PageTranslationModes.Mode? {
            val answer = CompletableDeferred<PageTranslationModes.Mode?>()
            modeChanges.trySend(
                object : ModeChange {
                    override suspend fun apply() {
                        answer.complete(modes.find(bookUrl)?.let { PageTranslationModes.Mode(it.source, it.target) })
                    }

                    override fun failed(e: Exception) {
                        Log.w(TAG, "Could not read a translated book: ${e.javaClass.simpleName}")
                        answer.complete(null)
                    }
                },
            )
            return answer.await()
        }

        override fun remember(mode: PageTranslationModes.Mode) {
            modeChanges.trySend(
                object : ModeChange {
                    override suspend fun apply() = writes.withLock {
                        if ((epochs[bookUrl] ?: 0L) != epoch || !inLibrary()) return@withLock
                        modes.put(PageTranslationModeRow(bookUrl, mode.source, mode.target, now()))
                    }

                    override fun failed(e: Exception) {
                        Log.w(TAG, "Could not keep a book translated: ${e.javaClass.simpleName}")
                    }
                },
            )
        }

        override fun forget(onFailed: () -> Unit) {
            modeChanges.trySend(
                object : ModeChange {
                    override suspend fun apply() = writes.withLock { modes.forget(bookUrl) }

                    override fun failed(e: Exception) {
                        Log.w(TAG, "Could not stop a book translating: ${e.javaClass.simpleName}")
                        onFailed()
                    }
                },
            )
        }
    }

    private inner class BookCache(private val bookUrl: String, private val epoch: Long) : PageTranslationCache {
        // Made after a sweep already took its book away, nothing would fence it: so the book
        // is looked for once, under the lock, and a later removal is the epoch's to catch.
        @Volatile private var found = false

        private suspend fun inLibrary() = found || present(listOf(bookUrl)).isNotEmpty().also { found = it }

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
                save(bookUrl, epoch, key, value, stamp, ::inLibrary)
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

        // Enough puts between character checks that saving stays cheap, few enough that the overshoot is small.
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
