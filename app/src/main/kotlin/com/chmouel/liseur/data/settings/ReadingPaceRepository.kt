package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.reader.progress.ReadingPace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readingPaceStore: DataStore<Preferences> by preferencesDataStore(
    name = "reading_pace",
)

/**
 * Remembers how fast this reader reads, across every book.
 *
 * Kept apart from the reading preferences because it is not one: nobody
 * chose it, and nothing in the app offers to change it. It is what the
 * app has learned by watching, and its whole value is in outliving the
 * book it was learned from — a new book should open already knowing
 * roughly how long it will take, rather than starting again from a
 * figure out of a textbook.
 */
class ReadingPaceRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.readingPaceStore)


    private object Keys {
        val SPEED = doublePreferencesKey("speed")
        val SAMPLES = intPreferencesKey("samples")
    }

    /** What is known about this reader so far. */
    suspend fun pace(): ReadingPace = store.data
        .map { ReadingPace.of(it[Keys.SPEED], it[Keys.SAMPLES]) }
        .first()

    /**
     * Adds one page's pace to what is known.
     *
     * Read and written in the same edit, because two books can be open
     * across two processes and a read-then-write would have the second
     * one quietly discard the first one's page.
     */
    suspend fun record(sample: Double) {
        store.edit { stored ->
            val next = ReadingPace.of(stored[Keys.SPEED], stored[Keys.SAMPLES]).after(sample)
            if (!next.isKnown) return@edit
            stored[Keys.SPEED] = next.speed
            stored[Keys.SAMPLES] = next.samples
        }
    }
}
