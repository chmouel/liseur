package com.chmouel.liseur.translate

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Translating a selected passage, as the flavor-neutral reader sees it.
 * Both builds offer the device's translator and listed servers; the Play
 * build adds Gemini. [None] is never available and draws nothing.
 */
interface TranslateFeature {
    val isAvailable: Boolean

    /** Whether the chosen service could translate, so the selection bar offers it. */
    val ready: StateFlow<Boolean>

    /** Asks the chosen service again whether it can translate, such as when the reader comes back. */
    suspend fun refresh()

    /** The row on the settings screen that opens [SettingsScreen]. */
    @Composable
    fun SettingsEntry(onClick: () -> Unit)

    /** Translation's own settings screen; [services] opens it on the Services page. */
    @Composable
    fun SettingsScreen(onBack: () -> Unit, services: Boolean = false)

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
     * The bar under a translated page, where read aloud's player sits: the
     * languages and Stop with the reader's [controls] up, and what stopped
     * the run even without them.
     */
    @Composable
    fun PageBar(
        translator: SentenceTranslator,
        state: PageTranslationState,
        theme: ReaderTheme,
        controls: Boolean,
        onRetry: () -> Unit,
        onStop: () -> Unit,
        modifier: Modifier,
    )

    object None : TranslateFeature {
        override val isAvailable = false
        override val ready: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

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

        @Composable
        override fun PageBar(
            translator: SentenceTranslator,
            state: PageTranslationState,
            theme: ReaderTheme,
            controls: Boolean,
            onRetry: () -> Unit,
            onStop: () -> Unit,
            modifier: Modifier,
        ) = Unit
    }
}
