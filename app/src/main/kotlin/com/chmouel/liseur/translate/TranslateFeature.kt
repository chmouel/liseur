package com.chmouel.liseur.translate

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Translating a selected passage, as the flavor-neutral reader sees it.
 * Both builds offer the device's translator and listed servers; the Play
 * build adds Gemini. A service is not offered to readers until explicitly
 * chosen. [None] is never available and draws nothing.
 */
interface TranslateFeature {
    val isAvailable: Boolean

    /** Whether the reader has explicitly chosen a service. */
    val chosen: StateFlow<Boolean>

    /**
     * Whether a chosen service could translate, so the reader offers it.
     * False until a service has been chosen: translation is opt-in, even
     * where the phone's translator would work out of the box.
     */
    val ready: StateFlow<Boolean>

    /** Whether the library offers [SetupSheet] while translation is not [ready]. */
    val offersSetup: Boolean get() = false

    /** The selection bar's label for translating, as the chosen service's terms word it. */
    val action: StateFlow<Int>

    /** Asks the chosen service again whether it can translate, such as when the reader comes back. */
    suspend fun refresh()

    /** The row on the settings screen that opens [SettingsScreen]. */
    @Composable
    fun SettingsEntry(onClick: () -> Unit)

    /** Translation's own settings screen; [services] opens it on the Services page. */
    @Composable
    fun SettingsScreen(onBack: () -> Unit, services: Boolean = false)

    /**
     * Turning translation on in one tap, with the service this phone can
     * use and the app's language already filled in; [onMore] leaves for
     * [SettingsScreen] to choose otherwise.
     */
    @Composable
    fun SetupSheet(onDismiss: () -> Unit, onMore: () -> Unit) = Unit

    /**
     * The sheet that translates [passage], from the language the book
     * [declared] when it declares one. With [onTranslatePage], it offers
     * to go on translating the page from there, in the languages it shows.
     */
    @Composable
    fun Sheet(
        passage: String,
        declared: List<String>,
        onDismiss: () -> Unit,
        onTranslatePage: ((source: String?, target: String) -> Unit)? = null,
    )

    /** The chosen service, ready to translate a page sentence by sentence; null when there is none. */
    suspend fun openPage(source: String?, target: String): SentenceTranslator?

    /**
     * The languages [Sheet] would offer to translate a page of a book that
     * [declared] its languages in, with the app in [app]; null when the
     * reader has to choose or set something up there first.
     */
    suspend fun pageLanguages(declared: List<String>, app: Locale): PageTranslationModes.Mode?

    /**
     * The bar under a translated page, where read aloud's player sits: the
     * languages and Stop with the reader's [controls] up, and what stopped
     * the run even without them. [answeredBy] names what gave the
     * translations on the page, as [PageSwap.by] does.
     */
    @Composable
    fun PageBar(
        translator: SentenceTranslator,
        state: PageTranslationState,
        answeredBy: Set<String>,
        theme: ReaderTheme,
        controls: Boolean,
        onRetry: () -> Unit,
        onStop: () -> Unit,
        modifier: Modifier,
    )

    /**
     * The marks the services in [answeredBy] ask for beside their
     * translations, for the page with the reader's controls down.
     */
    @Composable
    fun PageCredit(answeredBy: Set<String>, theme: ReaderTheme, modifier: Modifier)

    object None : TranslateFeature {
        override val isAvailable = false
        override val chosen: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val ready: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val action: StateFlow<Int> = MutableStateFlow(R.string.translation_action).asStateFlow()

        override suspend fun refresh() = Unit

        @Composable
        override fun SettingsEntry(onClick: () -> Unit) = Unit

        @Composable
        override fun SettingsScreen(onBack: () -> Unit, services: Boolean) = Unit

        @Composable
        override fun Sheet(
            passage: String,
            declared: List<String>,
            onDismiss: () -> Unit,
            onTranslatePage: ((source: String?, target: String) -> Unit)?,
        ) = Unit

        override suspend fun openPage(source: String?, target: String): SentenceTranslator? = null

        override suspend fun pageLanguages(declared: List<String>, app: Locale): PageTranslationModes.Mode? = null

        @Composable
        override fun PageBar(
            translator: SentenceTranslator,
            state: PageTranslationState,
            answeredBy: Set<String>,
            theme: ReaderTheme,
            controls: Boolean,
            onRetry: () -> Unit,
            onStop: () -> Unit,
            modifier: Modifier,
        ) = Unit

        @Composable
        override fun PageCredit(answeredBy: Set<String>, theme: ReaderTheme, modifier: Modifier) = Unit
    }
}
