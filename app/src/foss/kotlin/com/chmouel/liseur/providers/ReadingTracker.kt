package com.chmouel.liseur.providers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.db.LiseurDatabase
import org.json.JSONObject

/** The F-Droid build reports reading to no tracker: everything here does nothing. */
@Suppress("UNUSED_PARAMETER")
internal class ReadingTracker(context: Context, database: LiseurDatabase) {
    fun resume() = Unit

    fun bookChanged(bookUrl: String) = Unit

    suspend fun forgettingBooks(bookUrls: List<String>) = Unit

    fun forgotBooks(bookUrls: List<String>, committed: Boolean) = Unit

    suspend fun keeps(bookUrl: String) = false

    suspend fun backup(into: JSONObject) = Unit

    fun prepareRestore(payload: JSONObject): suspend () -> Unit = {}

    @Composable
    fun SettingsEntry(onOpen: () -> Unit) = Unit

    @Composable
    fun Rows() = Unit

    @Composable
    fun reviewPublicNote(bookUrl: String): String? = null

    @Composable
    fun BookDetailsSection(bookUrl: String, modifier: Modifier, onOpenSettings: () -> Unit = {}) = Unit

    @Composable
    fun rememberReaderPushAction(
        bookUrl: String,
        savePlace: suspend () -> Boolean,
        requestEdition: ((bookUrl: String, push: Boolean) -> Unit)? = null,
    ): (() -> Unit)? = null

    @Composable
    fun ReaderEditionPicker(
        ask: String?,
        pushes: Boolean,
        onDone: () -> Unit,
        savePlace: suspend () -> Boolean,
    ) = Unit
}
