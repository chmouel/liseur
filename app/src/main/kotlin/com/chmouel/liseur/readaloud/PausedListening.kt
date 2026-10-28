package com.chmouel.liseur.readaloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first

/**
 * A book left paused while read aloud, kept on this device only, so that
 * closing the app puts the paused player back on reopening.
 *
 * [revision] and [positionJson] are the book's saved place as the pause
 * left it: a place saved since then means the reader has moved on.
 */
data class PausedReadAloud(
    val bookId: String,
    val heardJson: String,
    val voice: String,
    val language: String,
    val revision: Long,
    val positionJson: String,
)

/** A book's saved place: its revision and locator. */
data class SavedPlace(val revision: Long, val locatorJson: String)

interface PausedReadAloudStore {
    suspend fun read(): PausedReadAloud?
    suspend fun write(record: PausedReadAloud?)
}

/**
 * Keeps the one paused book. Every write, clear and check runs in the
 * order of the position writes, through [queue], so a baseline is always
 * the place the pause itself saved and a check sees every move made
 * before it.
 *
 * @param queue runs an action once the position writes queued before it
 *   have committed; false when it can no longer run anything.
 * @param saved the place stored for a book now.
 */
class PausedListening(
    private val store: PausedReadAloudStore,
    private val queue: (suspend () -> Unit) -> Boolean,
    private val saved: suspend (String) -> SavedPlace?,
) {
    /** [bookId] paused at [heard], whose place was just queued. */
    fun paused(bookId: String, heardJson: String, voice: String, language: String) {
        inOrder {
            val place = saved(bookId)
            store.write(place?.let { PausedReadAloud(bookId, heardJson, voice, language, it.revision, it.locatorJson) })
        }
    }

    /** Forgets [bookId]'s paused place, or any book's when null. */
    fun forget(bookId: String? = null) {
        inOrder {
            val record = store.read() ?: return@inOrder
            if (bookId == null || record.bookId == bookId) store.write(null)
        }
    }

    /**
     * The reader saved the page showing the heard sentence as [locatorJson]:
     * the same place, measured again, so the record moves its baseline to it.
     */
    fun captured(bookId: String, locatorJson: String) {
        inOrder {
            val record = store.read()?.takeIf { it.bookId == bookId } ?: return@inOrder
            val place = saved(bookId)
            when {
                place == null -> store.write(null)
                place.revision == record.revision && place.locatorJson == record.positionJson -> Unit
                place.locatorJson == locatorJson ->
                    store.write(record.copy(revision = place.revision, positionJson = place.locatorJson))
                else -> store.write(null)
            }
        }
    }

    /** [bookId]'s paused place while nothing has moved it since; any other record of it is forgotten. */
    suspend fun toRestore(bookId: String): PausedReadAloud? {
        val answer = CompletableDeferred<PausedReadAloud?>()
        val queued = queue {
            answer.complete(attempt { current(bookId) })
        }
        return if (queued) answer.await() else null
    }

    private fun inOrder(action: suspend () -> Unit) {
        queue { attempt { action() } }
    }

    // Runs inside the position writer's queue, which an escaping failure would stop.
    private suspend fun <T> attempt(action: suspend () -> T): T? = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Whether [record] still stands, checked behind every write queued so far. */
    suspend fun stillStands(record: PausedReadAloud): Boolean = toRestore(record.bookId) == record

    private suspend fun current(bookId: String): PausedReadAloud? {
        val record = store.read()?.takeIf { it.bookId == bookId } ?: return null
        val place = saved(bookId)
        if (place != null && place.revision == record.revision && place.locatorJson == record.positionJson) return record
        store.write(null)
        return null
    }
}

private val Context.pausedReadAloudStore: DataStore<Preferences> by preferencesDataStore(
    name = "read_aloud_paused",
)

class DataStorePausedReadAloudStore(private val context: Context) : PausedReadAloudStore {
    private object Keys {
        val BOOK = stringPreferencesKey("book")
        val HEARD = stringPreferencesKey("heard")
        val VOICE = stringPreferencesKey("voice")
        val LANGUAGE = stringPreferencesKey("language")
        val REVISION = longPreferencesKey("revision")
        val POSITION = stringPreferencesKey("position")
    }

    override suspend fun read(): PausedReadAloud? {
        val p = context.pausedReadAloudStore.data.first()
        return PausedReadAloud(
            bookId = p[Keys.BOOK] ?: return null,
            heardJson = p[Keys.HEARD] ?: return null,
            voice = p[Keys.VOICE] ?: return null,
            language = p[Keys.LANGUAGE] ?: return null,
            revision = p[Keys.REVISION] ?: return null,
            positionJson = p[Keys.POSITION] ?: return null,
        )
    }

    override suspend fun write(record: PausedReadAloud?) {
        context.pausedReadAloudStore.edit { p ->
            p.clear()
            if (record == null) return@edit
            p[Keys.BOOK] = record.bookId
            p[Keys.HEARD] = record.heardJson
            p[Keys.VOICE] = record.voice
            p[Keys.LANGUAGE] = record.language
            p[Keys.REVISION] = record.revision
            p[Keys.POSITION] = record.positionJson
        }
    }
}
