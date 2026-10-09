package com.chmouel.liseur.translate

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.Flow

/** Whether a service translates one pair of languages as things stand. */
enum class PairState {
    Ready,

    /** On this phone, once its languages are downloaded. */
    NeedsDownload,
    Unsupported,
}

/**
 * One way to translate a passage: the device's own translator, Gemini, or
 * a listed OpenAI-compatible server. Nothing is sent anywhere until
 * [translate] is called.
 */
internal interface TranslationService {
    /** As stored in `translation_provider`, shared with read aloud's ids. */
    val id: String

    @get:StringRes
    val label: Int

    @get:StringRes
    val summary: Int
    val icon: ImageVector

    /** Whether it can translate at all: a system translator, or a key, server and model. */
    val configured: Flow<Boolean>

    /** Whether it works out a passage's language, so a book without one needs no source picked. */
    val detectsLanguage: Boolean

    /** Asks again what [configured] and [targets] depend on, as after the system's settings. */
    suspend fun refresh() = Unit

    /** Where the text goes, for "Translated by %s."; null for the device. */
    suspend fun destination(): String?

    /** Whose address or key a reply depends on (see `ServerConnections`); null for the device. */
    suspend fun owner(): String?

    /**
     * The languages it translates [source] into, with their state; null
     * when it takes any language. [source] is null for a detected one.
     */
    suspend fun targets(source: String?): Map<String, PairState>?

    /** The languages it translates from; null when it takes any. */
    suspend fun sources(): Set<String>?

    /**
     * [passage] in [target]; [context] is the text before it, sent along
     * when the service can use it and never translated. Throws
     * [TranslationError]; cancelling the caller cancels the request.
     */
    suspend fun translate(passage: String, source: String?, target: String, context: String? = null): String

    /** Sentences one after another from [source] into [target], for as long as a page is translated. */
    fun open(source: String?, target: String): TranslationRun = object : TranslationRun {
        override suspend fun translate(sentence: String, context: String?) = translate(sentence, source, target, context)
    }

    /** Its own rows on the Translation page, under the service picker. */
    @Composable
    fun SettingsRows(onManageServices: () -> Unit)
}

/** A run of translations between two languages, closed when the page goes back to the original. */
internal interface TranslationRun {
    /** [sentence] translated, with [context] as the sentence before it. Throws [TranslationError]. */
    suspend fun translate(sentence: String, context: String?): String

    fun close() = Unit
}
