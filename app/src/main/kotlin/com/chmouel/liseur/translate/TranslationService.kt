package com.chmouel.liseur.translate

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
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

    /** Where the text goes under [s], for "Translated by %s."; null for the device. */
    fun destination(s: AppSettings): String?

    /** Whose address or key a reply depends on under [s] (see `ServerConnections`); null for the device. */
    fun owner(s: AppSettings): String?

    /** The model it sends a translation to under [s], as it resolves it; null when it has none. */
    fun model(s: AppSettings): String? = null

    /**
     * Whether a page sentence it translates under [s] goes with the
     * book's text before it. A service that would drop it, or translate
     * it too, says no, so none is gathered and none keys its answers.
     */
    fun usesContext(s: AppSettings): Boolean = false

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

    /**
     * Sentences one after another from [source] into [target], for as long
     * as a page is translated. A service whose answer depends on settings
     * asks with [s], the ones the page's identity was read from, so what
     * it answers is what the identity names.
     */
    fun open(source: String?, target: String, s: AppSettings): TranslationRun = object : TranslationRun {
        override suspend fun translate(sentence: String, context: String?) = translate(sentence, source, target, context)
    }

    /** Its own rows on the Translation page, under the service picker; [onOpenPage] shows its [Page]. */
    @Composable
    fun SettingsRows(onManageServices: () -> Unit, onOpenPage: () -> Unit)

    /** Options most readers never need, in a section of their own that starts closed. */
    @Composable
    fun AdvancedSettings() = Unit

    /** A page of its own opened from its rows, such as its languages; [target] is the language passages go into. */
    @Composable
    fun Page(target: String, onBack: () -> Unit) = Unit

    /** Whether missing languages can be got from here, for a Download button. */
    suspend fun hasDownloads(): Boolean = false

    /**
     * What the Download button opens for [source] into [target]: the
     * system's screen, or a dialog that asks before fetching. [onReady]
     * once they are on the phone, so the caller asks again; [onDismiss]
     * otherwise.
     */
    @Composable
    fun Downloads(source: String?, target: String, onReady: () -> Unit, onDismiss: () -> Unit) = Unit

    /** Whether its translations must be shown with [Credit] beside them. */
    val credited: Boolean get() = false

    /** The mark its terms ask for beside its translations, drawn for a [background]. */
    @Composable
    fun Credit(background: Color, modifier: Modifier) = Unit

    /** What a reader is told and agrees to before it is chosen, or null when choosing it is enough. */
    @get:StringRes
    val consent: Int? get() = null

    /** The label of the button that translates a selection. */
    @get:StringRes
    val action: Int get() = R.string.translation_action

    /** The label of the button that goes on translating the page. */
    @get:StringRes
    val pageAction: Int get() = R.string.translation_page_start
}

/** A run of translations between two languages, closed when the page goes back to the original. */
internal interface TranslationRun {
    /** [sentence] translated, with [context] as the sentence before it. Throws [TranslationError]. */
    suspend fun translate(sentence: String, context: String?): String

    fun close() = Unit
}
