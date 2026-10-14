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
import androidx.room.withTransaction
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
    indices = [Index("used_at"), Index("saved_at")],
)
data class TranslatedSentence(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    /** SHA-256 of the service, model, languages, context and sentence it answers. */
    val key: String,
    val translation: String,
    @ColumnInfo(name = "used_at") val usedAt: Long,
    /**
     * When the translation was saved here, and never changed after:
     * a sentence a peer pulled in keeps 0, so it is never pushed back
     * (ADR-0052), while one translated on this phone keeps the save it
     * was made at, which is what a peer's push watermark cuts at.
     */
    @ColumnInfo(name = "saved_at", defaultValue = "0") val savedAt: Long = 0,
)

/** A saved row and the length of its translation, oldest first, for trimming to the character budget. */
data class SavedLength(val id: Long, val length: Long)

/** A saved sentence a peer is owed: the hashed key, the text, and where the save sits in the push order. */
data class SavedTranslationForPush(
    val rowId: Long,
    val key: String,
    val translation: String,
    val savedAt: Long,
)

/** A sentence translation arriving from a peer, keyed as the local cache is. */
data class PulledTranslation(val key: String, val translation: String)

@Dao
interface TranslatedSentenceDao {
    @Query("SELECT translation FROM translated_sentences WHERE book_url = :bookUrl AND `key` = :key")
    suspend fun find(bookUrl: String, key: String): String?

    @Query("UPDATE translated_sentences SET used_at = :now WHERE book_url = :bookUrl AND `key` = :key")
    suspend fun touch(bookUrl: String, key: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslatedSentence)

    /**
     * Lays a pulled sentence down without disturbing one saved here:
     * a local translation always wins locally (ADR-0052), so the
     * conflict is ignored rather than replaced — which also keeps the
     * row's own `saved_at`, so a sentence this phone translated is
     * still pushed even after a peer's copy of it arrives.
     *
     * @return the new row's rowid, or -1 when a row was already here.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putPulled(row: TranslatedSentence): Long

    /**
     * Follows a newer text the account holds for a sentence a peer
     * pulled in: only rows never saved here (`saved_at = 0`) move, and
     * only when the text really changed — so a row this phone
     * translated keeps its own words, while a row first received from
     * a peer follows the account's later edit instead of freezing at
     * the first copy it happened to pull.
     *
     * @return 1 when the row followed, 0 when it kept its ground.
     */
    @Query(
        "UPDATE translated_sentences SET translation = :translation, used_at = :usedAt " +
            "WHERE book_url = :bookUrl AND `key` = :key AND saved_at = 0 AND translation != :translation",
    )
    suspend fun refreshPulled(bookUrl: String, key: String, translation: String, usedAt: Long): Int

    /**
     * The sentences a peer has not been sent, in push order: by save,
     * then by row within the saves sharing a millisecond — a page's
     * sentences are saved in a loop, so ties are the norm, and a
     * watermark that cut at the time alone would strand every row
     * tied with the last one acknowledged. The pair, not the time, is
     * the watermark.
     */
    @Query(
        "SELECT rowid AS rowId, `key` AS `key`, translation, saved_at AS savedAt FROM translated_sentences " +
            "WHERE book_url = :bookUrl AND saved_at > 0 AND " +
            "(saved_at > :at OR (saved_at = :at AND rowid > :rowId)) " +
            "ORDER BY saved_at, rowid LIMIT :limit",
    )
    suspend fun unsynced(bookUrl: String, at: Long, rowId: Long, limit: Int): List<SavedTranslationForPush>

    /** The book's last save in push order, the watermark that has nothing left to send. */
    @Query(
        "SELECT rowid AS rowId, `key` AS `key`, translation, saved_at AS savedAt FROM translated_sentences " +
            "WHERE book_url = :bookUrl AND saved_at > 0 ORDER BY saved_at DESC, rowid DESC LIMIT 1",
    )
    suspend fun lastSaved(bookUrl: String): SavedTranslationForPush?

    /** The highest save stamp in the table: the floor a new save is stamped above. */
    @Query("SELECT MAX(saved_at) FROM translated_sentences")
    suspend fun maxSavedAt(): Long?

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
 * Where one peer stands with one book's sentence translations (ADR-0052):
 * how far its pushes reached and where its pull stopped. The push
 * watermark is the `(saved_at, rowid)` pair of the last row the server
 * acknowledged — the pair because sentences saved in the same
 * millisecond are the norm, and the time alone cannot tell which of
 * them were sent. Keyed by peer and book, because both belong to that
 * pairing — a different server, or the same book under another
 * account, starts again from nothing.
 *
 * [workId] records which server work that progress was made against.
 * A book whose work is re-resolved — the recovery every pass shares
 * for a name the server disowned — starts again from nothing under
 * the new name: the old watermark and cursor say how far another work
 * went, which is nothing about this one.
 */
@Entity(tableName = "translation_sync_state", primaryKeys = ["peer_id", "book_url"])
data class TranslationSyncState(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "work_id", defaultValue = "") val workId: String = "",
    @ColumnInfo(name = "pushed_saved_at", defaultValue = "0") val pushedSavedAt: Long = 0,
    @ColumnInfo(name = "pushed_rowid", defaultValue = "0") val pushedRowid: Long = 0,
    @ColumnInfo(name = "pull_cursor", defaultValue = "") val pullCursor: String = "",
)

@Dao
interface TranslationSyncStateDao {
    @Query("SELECT * FROM translation_sync_state WHERE peer_id = :peerId AND book_url = :bookUrl")
    suspend fun get(peerId: String, bookUrl: String): TranslationSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslationSyncState)

    @Query("SELECT book_url FROM translation_sync_state")
    suspend fun books(): List<String>

    @Query("SELECT * FROM translation_sync_state WHERE peer_id = :peerId")
    suspend fun forPeer(peerId: String): List<TranslationSyncState>

    @Query("SELECT DISTINCT peer_id FROM translation_sync_state")
    suspend fun peerIds(): List<String>

    @Query("DELETE FROM translation_sync_state WHERE peer_id IN (:peerIds)")
    suspend fun forgetPeers(peerIds: List<String>): Int

    @Query("DELETE FROM translation_sync_state WHERE book_url IN (:bookUrls)")
    suspend fun forget(bookUrls: List<String>): Int

    @Query("DELETE FROM translation_sync_state")
    suspend fun clear()

    /** The highest watermark any peer reached: a save's floor outlives the rows it cut at. */
    @Query("SELECT MAX(pushed_saved_at) FROM translation_sync_state")
    suspend fun maxPushedSavedAt(): Long?
}

/**
 * A rekey whose copy this store has committed but whose account
 * transaction may not have: the copy must survive a collection that
 * cannot tell the two apart (see [SavedTranslations.forgetPeersBeyond]).
 */
@Entity(tableName = "translation_rekey_moves", primaryKeys = ["from_key", "to_key"])
data class TranslationRekeyMove(
    @ColumnInfo(name = "from_key") val fromKey: String,
    @ColumnInfo(name = "to_key") val toKey: String,
)

@Dao
interface TranslationRekeyMoveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslationRekeyMove)

    @Query("SELECT to_key FROM translation_rekey_moves")
    suspend fun toKeys(): List<String>

    @Query("DELETE FROM translation_rekey_moves WHERE from_key = :key OR to_key = :key")
    suspend fun forget(key: String)
}

/**
 * A clear of the account's translations that this device owes a peer
 * (ADR-0050): one work's, or with an empty [workId] every work's. It is
 * state rather than a queue — one row per clear still owed, the same
 * request however often it is sent, gone once the server answered it.
 * The work id is taken when the reader asks, because removing a book
 * takes its work alias with it. [generation] goes up each time the
 * same clear is asked for again, so an answer to an earlier request
 * settles only that one, not a later one it may have raced.
 */
@Entity(tableName = "translation_account_clears", primaryKeys = ["peer_id", "work_id"])
data class TranslationAccountClear(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "generation", defaultValue = "0") val generation: Long = 0,
) {
    val everyWork: Boolean get() = workId.isEmpty()

    companion object {
        fun everyWork(peerId: String) = TranslationAccountClear(peerId, "")
    }
}

@Dao
interface TranslationAccountClearDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<TranslationAccountClear>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: TranslationAccountClear): Long

    @Query(
        "UPDATE translation_account_clears SET generation = generation + 1 " +
            "WHERE peer_id = :peerId AND work_id = :workId",
    )
    suspend fun bump(peerId: String, workId: String): Int

    /** Every work's clear first: it answers for the others too. */
    @Query("SELECT * FROM translation_account_clears WHERE peer_id = :peerId ORDER BY work_id")
    suspend fun forPeer(peerId: String): List<TranslationAccountClear>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM translation_account_clears " +
            "WHERE peer_id = :peerId AND (work_id = '' OR work_id = :workId))",
    )
    suspend fun owed(peerId: String, workId: String): Boolean

    @Query(
        "DELETE FROM translation_account_clears " +
            "WHERE peer_id = :peerId AND work_id = :workId AND generation = :generation",
    )
    suspend fun settle(peerId: String, workId: String, generation: Long)

    @Query("SELECT DISTINCT peer_id FROM translation_account_clears")
    suspend fun peerIds(): List<String>

    @Query("DELETE FROM translation_account_clears WHERE peer_id IN (:peerIds)")
    suspend fun forgetPeers(peerIds: List<String>): Int
}

/**
 * Kept apart from `liseur.db`: the backup rules name that file only, so
 * a cache that can always be asked for again stays out of backups.
 */
@Database(
    entities = [
        TranslatedSentence::class, PageTranslationModeRow::class, TranslationSyncState::class,
        TranslationRekeyMove::class, TranslationAccountClear::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun sentences(): TranslatedSentenceDao

    abstract fun modes(): PageTranslationModeDao

    abstract fun translationSync(): TranslationSyncStateDao

    abstract fun rekeyMoves(): TranslationRekeyMoveDao

    abstract fun accountClears(): TranslationAccountClearDao

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

        /**
         * Version 3 adds the cross-device sentence cache's bookkeeping
         * (ADR-0052). Sentences saved before the upgrade keep the last
         * time they were read as their save, so they are shared once
         * rather than never; sentences pulled from a peer keep 0, which
         * is what keeps them from being pushed back.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE translated_sentences ADD COLUMN saved_at INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("UPDATE translated_sentences SET saved_at = used_at")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translated_sentences_saved_at` " +
                        "ON translated_sentences (`saved_at`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `translation_sync_state` (`peer_id` TEXT NOT NULL, " +
                        "`book_url` TEXT NOT NULL, `work_id` TEXT NOT NULL DEFAULT '', " +
                        "`pushed_saved_at` INTEGER NOT NULL DEFAULT 0, " +
                        "`pushed_rowid` INTEGER NOT NULL DEFAULT 0, " +
                        "`pull_cursor` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`peer_id`, `book_url`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `translation_rekey_moves` (`from_key` TEXT NOT NULL, " +
                        "`to_key` TEXT NOT NULL, PRIMARY KEY(`from_key`, `to_key`))",
                )
            }
        }

        /** Version 4 adds the clears owed to the account (ADR-0050). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `translation_account_clears` (`peer_id` TEXT NOT NULL, " +
                        "`work_id` TEXT NOT NULL, `generation` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`peer_id`, `work_id`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    private val sync = database.translationSync()
    private val moves = database.rekeyMoves()
    private val owedClears = database.accountClears()
    private val writes = Mutex()
    private val cleared = AtomicLong()
    private val epochs = ConcurrentHashMap<String, Long>()

    /** The highest save stamp handed out, under [writes]; read from the table on first use. */
    private var lastSaveAt: Long? = null
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
     *
     * A book's peer bookkeeping goes with its sentences, for the same
     * reason the sentences do: a book that comes back starts again from
     * nothing, and a watermark or a cursor naming rows that no longer
     * exist would only mislead.
     */
    suspend fun sweep() = quietly("sweep") {
        val emptied = writes.withLock {
            val books = (dao.books() + modes.books() + sync.books() + epochs.keys).distinct()
            if (books.isEmpty()) return@withLock false
            val kept = books.chunked(BATCH).flatMap { present(it) }.toSet()
            val gone = books.filterNot { it in kept }
            gone.forEach { epochs.merge(it, 1L, Long::plus) }
            gone.chunked(BATCH).forEach {
                modes.forget(it)
                sync.forget(it)
            }
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
     *
     * The peer bookkeeping goes too, both halves of it: the pull cursors
     * so the next pull re-fetches what the account holds (a local Clear
     * was never the account's to clear, ADR-0052), and the push
     * watermarks because there are no rows behind them to keep place
     * for — whatever is saved next starts from the beginning.
     *
     * [accountClears] are the account clears the reader asked for along
     * with this one (ADR-0050), recorded in the same transaction: a
     * pull that ran between the two would bring back what was just
     * cleared.
     */
    suspend fun clear(accountClears: List<TranslationAccountClear> = emptyList()) {
        writes.withLock {
            cleared.incrementAndGet()
            // One transaction, so an interruption cannot leave an empty
            // cache behind the bookkeeping of the cache it emptied: the
            // next pull would resume past rows that are no longer here
            // and never bring them back. Advance the fence after the
            // transaction even when cancellation is delivered as it
            // returns: a ticket sampled while it ran cannot land later.
            try {
                database.withTransaction {
                    dao.clear()
                    sync.clear()
                    owe(accountClears)
                }
            } finally {
                cleared.incrementAndGet()
            }
        }
        compact()
    }

    /**
     * Forgets one book's saved sentences and where every peer stood
     * with it, for a book that stays. [accountClears] are recorded with
     * it, for the reason [clear] gives.
     *
     * What is in flight is fenced the way [clear] fences it, every
     * book's at once, rather than by the book's epoch: the reader that
     * asked keeps its cache and mode handles, and they must go on
     * saving what it translates next.
     */
    suspend fun clearBook(bookUrl: String, accountClears: List<TranslationAccountClear> = emptyList()) {
        writes.withLock {
            cleared.incrementAndGet()
            try {
                database.withTransaction {
                    dao.forget(listOf(bookUrl))
                    sync.forget(listOf(bookUrl))
                    owe(accountClears)
                }
            } finally {
                // Invalidate tickets sampled after the first fence advance
                // even if cancellation is delivered as the transaction returns.
                cleared.incrementAndGet()
            }
        }
        resized.value++
    }

    /**
     * Records clears owed to the account, leaving this device's
     * sentences where they are. Those of [bookUrl] are marked sent to
     * each peer cleared, in the same transaction: the sync that sends
     * the clear would otherwise push them straight back.
     * A global fence drops saves and pulls started before this
     * transaction, so a late translation is not pushed after the clear.
     */
    suspend fun requestAccountClears(clears: List<TranslationAccountClear>, bookUrl: String? = null) {
        if (clears.isEmpty()) return
        writes.withLock {
            cleared.incrementAndGet()
            try {
                database.withTransaction {
                    owe(clears)
                    if (bookUrl == null) return@withTransaction
                    val last = dao.lastSaved(bookUrl) ?: return@withTransaction
                    for (clear in clears.filterNot { it.everyWork }) {
                        val state = sync.get(clear.peerId, bookUrl)
                            ?.takeIf { it.workId == clear.workId }
                            ?: TranslationSyncState(clear.peerId, bookUrl, clear.workId)
                        if (last.savedAt > state.pushedSavedAt ||
                            (last.savedAt == state.pushedSavedAt && last.rowId > state.pushedRowid)
                        ) {
                            sync.put(state.copy(pushedSavedAt = last.savedAt, pushedRowid = last.rowId))
                        }
                    }
                }
            } finally {
                cleared.incrementAndGet()
            }
        }
    }

    // Called holding [writes], inside a transaction.
    private suspend fun owe(clears: List<TranslationAccountClear>) {
        for (clear in clears) {
            if (owedClears.bump(clear.peerId, clear.workId) == 0) {
                owedClears.insert(TranslationAccountClear(clear.peerId, clear.workId))
            }
        }
    }

    /** The clears owed to a peer, every work's first. */
    suspend fun owedClears(peerId: String): List<TranslationAccountClear> = owedClears.forPeer(peerId)

    /** Whether a clear owed to the peer still covers this work, its own or every work's. */
    suspend fun clearOwed(peerId: String, workId: String): Boolean = owedClears.owed(peerId, workId)

    /** Drops a clear the server has answered, unless it was asked for again since. */
    suspend fun settleClear(clear: TranslationAccountClear) {
        writes.withLock { owedClears.settle(clear.peerId, clear.workId, clear.generation) }
    }

    // -- Cross-device exchange (ADR-0052) ----------------------------------

    /**
     * A pull's right to land, captured before the request leaves.
     *
     * The same fencing a save answers to: a page that comes back after
     * a Clear, or after its book left the library, is dropped rather
     * than written — and its cursor with it, so nothing the fence
     * refused is stepped over for good.
     */
    class PullTicket internal constructor(internal val clearedAt: Long, internal val epochAt: Long)

    /** Registers the book and captures the fence a pull will answer to. */
    fun pullTicket(bookUrl: String): PullTicket =
        PullTicket(cleared.get(), epochs.computeIfAbsent(bookUrl) { 0L })

    /** Where a peer stands with a book: its push watermark and pull cursor, or nothing yet. */
    suspend fun syncState(peerId: String, bookUrl: String): TranslationSyncState? = sync.get(peerId, bookUrl)

    /**
     * Where a peer stands with a book *under this work name*, starting
     * over when the name changed. A book re-resolved after the server
     * disowned its work id is a different work to the account, whatever
     * it is on this phone: the old watermark says how far pushes to a
     * work the server no longer holds reached, and the old cursor where
     * a pull of that work stopped — neither is a word about the new one,
     * so both go and the exchange begins again from nothing.
     */
    suspend fun ensureWork(peerId: String, bookUrl: String, workId: String): TranslationSyncState = writes.withLock {
        val state = sync.get(peerId, bookUrl)
        if (state != null && state.workId == workId) {
            state
        } else {
            TranslationSyncState(peerId, bookUrl, workId).also { sync.put(it) }
        }
    }

    /** The sentences a peer has not been sent, oldest save first. */
    suspend fun unsynced(bookUrl: String, watermark: TranslationSyncState, limit: Int): List<SavedTranslationForPush> =
        dao.unsynced(bookUrl, watermark.pushedSavedAt, watermark.pushedRowid, limit)

    /**
     * Lays one pulled page down and moves the peer's cursor, together
     * or not at all — the same rule the position sync's cursor lives
     * by, here a transaction rather than a hope. A local translation
     * always wins locally, so a sentence already here keeps its own
     * words and its own `saved_at`; a sentence first received from a
     * peer follows the account's newer text instead of freezing at the
     * first copy it happened to pull. What lands counts against the
     * same caps a save does — a phone that only receives must fit the
     * cache too — and a row the trim evicts is gone until a cursor
     * restart re-reads it, which is the same bargain every cached
     * sentence already makes.
     *
     * @return how many sentences landed or changed.
     */
    suspend fun landPulled(
        peerId: String,
        bookUrl: String,
        ticket: PullTicket,
        rows: List<PulledTranslation>,
        cursor: String,
    ): Int = writes.withLock {
        if (cleared.get() != ticket.clearedAt || (epochs[bookUrl] ?: 0L) != ticket.epochAt) return 0
        if (rows.isNotEmpty() && present(listOf(bookUrl)).isEmpty()) return 0
        val at = now()
        var landed = 0
        database.withTransaction {
            for (row in rows) {
                // A pulled sentence has never been saved here, so it is never
                // pushed back; its `used_at` is its arrival, and the local
                // trim treats it like any other sentence.
                if (dao.putPulled(TranslatedSentence(bookUrl, row.key, row.translation, at)) > 0) {
                    landed++
                } else {
                    landed += dao.refreshPulled(bookUrl, row.key, row.translation, at)
                }
            }
            val state = sync.get(peerId, bookUrl) ?: TranslationSyncState(peerId, bookUrl)
            sync.put(state.copy(pullCursor = cursor))
        }
        // Both caps, not only the sentence count: a page of long
        // translations, or a refresh that lengthened them, can spend
        // the characters while the count stays put.
        trim()
        landed
    }

    /** Drops a peer's pull cursor for a book, so the next pull starts over. */
    suspend fun restartPull(peerId: String, bookUrl: String) {
        writes.withLock {
            sync.get(peerId, bookUrl)?.let { sync.put(it.copy(pullCursor = "")) }
        }
    }

    /**
     * Records how far a peer's pushes reached, after the server
     * acknowledged them — never before, so an interrupted push is
     * simply repeated. The watermark is the pair of the last row
     * acknowledged; it only moves forward.
     */
    suspend fun recordPushed(peerId: String, bookUrl: String, watermark: SavedTranslationForPush) {
        writes.withLock {
            val state = sync.get(peerId, bookUrl) ?: TranslationSyncState(peerId, bookUrl)
            if (watermark.savedAt > state.pushedSavedAt ||
                (watermark.savedAt == state.pushedSavedAt && watermark.rowId > state.pushedRowid)
            ) {
                sync.put(
                    state.copy(pushedSavedAt = watermark.savedAt, pushedRowid = watermark.rowId),
                )
            }
        }
    }

    /**
     * Forgets a peer's bookkeeping when its account goes. The sentences
     * stay — they are this phone's cache, and were never the server's to
     * take away — but a watermark and a cursor are that server's own
     * numbers, of no use to the next one. So are the clears still owed
     * to it: the next account never held those translations.
     */
    suspend fun forgetPeer(peerId: String) {
        writes.withLock {
            sync.forgetPeers(listOf(peerId))
            owedClears.forgetPeers(listOf(peerId))
            moves.forget(peerId)
        }
    }

    /**
     * Forgets the bookkeeping of every peer the connected account is
     * not, asked where a translation pass begins. A pass runs for as
     * long as the network takes, and a disconnect landing between its
     * ownership check and a write leaves behind a row naming a peer
     * nobody answers to any more — a watermark and a cursor that are
     * that server's own numbers, of no use to the next one — and
     * nothing else collects it: a sweep asks what became of books, not
     * of accounts. The sentences stay, as they do when one account
     * goes: they are this phone's cache.
     *
     * Which peers survive is asked under the store's lock, from the
     * account connected *now* rather than the one the pass began for,
     * so a pass running on after its account was renamed cannot take
     * the new name's progress for dead. A rekey whose copy this store
     * has committed but whose account transaction has not landed is
     * indistinguishable from that, so its target is protected by the
     * move the copy recorded, until the key is carried again or goes.
     */
    suspend fun forgetPeersBeyond(live: suspend () -> Set<String>) = quietly("forget peers of") {
        writes.withLock {
            val keep = live() + moves.toKeys()
            val gone = sync.peerIds().filterNot { it in keep }
            gone.chunked(BATCH).forEach { sync.forgetPeers(it) }
            val owedGone = owedClears.peerIds().filterNot { it in keep }
            owedGone.chunked(BATCH).forEach { owedClears.forgetPeers(it) }
        }
    }

    /**
     * Carries a peer's bookkeeping to its account's new key, when a
     * rekey renames it: the same progress under the name the account
     * answers to now. A book with rows under both keys keeps the one
     * being carried, which is the one the account just used.
     *
     * The rows under the old key are kept, not deleted: this store
     * commits on its own, outside the main database's transaction, and
     * a move that deleted first would leave the account with no
     * progress at all if that transaction rolled back. The copies are
     * written over harmlessly if the move runs again, and the rows
     * under a key nothing answers to any more go when the account
     * disconnects ([forgetPeer]).
     */
    suspend fun rekeyPeer(from: String, to: String) {
        writes.withLock {
            database.withTransaction {
                // The move is recorded with the copy, in the same
                // transaction: a collection that runs while the account
                // transaction is still open cannot tell a copy that
                // will be rolled back from one that will not, and must
                // not delete either. A key renamed again retires the
                // protection it carried, incoming and outgoing: the
                // earlier attempt either landed, and its target is a
                // key nobody answers to now, or it rolled back, and
                // its copy is one nobody ever answers to.
                moves.forget(from)
                moves.put(TranslationRekeyMove(from, to))
                for (row in sync.forPeer(from)) {
                    sync.put(row.copy(peerId = to))
                }
                owedClears.put(owedClears.forPeer(from).map { it.copy(peerId = to) })
            }
        }
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
            // A save is stamped never below the last one, clock be damned:
            // the stamp is a push watermark's coordinate, and a save that
            // landed below a watermark a peer already acknowledged would
            // be stranded behind it for good. The floor is remembered in
            // memory and read from the table on the first save of a run —
            // from the sentences, and from the watermarks, which outlive
            // the sentences an eviction can take — so a restart costs one
            // query and a stepped-back clock costs nothing.
            if (lastSaveAt == null) {
                lastSaveAt = maxOf(dao.maxSavedAt() ?: 0L, sync.maxPushedSavedAt() ?: 0L)
            }
            val at = maxOf(now(), (lastSaveAt ?: 0L) + 1)
            lastSaveAt = at
            dao.put(TranslatedSentence(bookUrl, hash(key), translation, at, at))
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
