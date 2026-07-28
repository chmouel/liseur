package com.chmouel.liseur.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener

class ReaderViewModel(
    private val bookUrl: AbsoluteUrl,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val progressDao: ReadingProgressDao,
    private val library: LocalLibraryRepository,
    private val prefsRepo: ReaderPreferencesRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState

        data class Ready(
            val publication: Publication,
            val navigatorFactory: EpubNavigatorFactory,
            val initialLocator: Locator?,
        ) : UiState

        data class Failure(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    /** Most recent position, used to persist progress and to survive recreation. */
    var lastLocator: Locator? = null
        private set

    init {
        open()
    }

    private fun open() {
        viewModelScope.launch {
            // Make sure saved preferences are loaded before the navigator is
            // created, so the book opens directly with the user's settings.
            prefsRepo.prefs.first()
            val asset = assetRetriever.retrieve(bookUrl).getOrElse {
                _state.value = UiState.Failure(it.message)
                return@launch
            }
            val publication = publicationOpener.open(asset, allowUserInteraction = false)
                .getOrElse {
                    asset.close()
                    _state.value = UiState.Failure(it.message)
                    return@launch
                }
            val initialLocator = progressDao.get(bookUrl.toString())
                ?.let { Locator.fromJSON(JSONObject(it.locatorJson)) }
            lastLocator = initialLocator
            library.markOpened(bookUrl.toString())
            _state.value = UiState.Ready(
                publication = publication,
                navigatorFactory = EpubNavigatorFactory(publication),
                initialLocator = initialLocator,
            )
        }
    }

    fun onLocatorChanged(locator: Locator) {
        lastLocator = locator
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgress(
                    bookUrl = bookUrl.toString(),
                    locatorJson = locator.toJSON().toString(),
                    totalProgression = locator.locations.totalProgression,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setFont(font: ReaderFont) = viewModelScope.launch { prefsRepo.setFont(font) }

    fun setFontSize(size: Double) = viewModelScope.launch { prefsRepo.setFontSize(size) }

    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { prefsRepo.setTheme(theme) }

    fun setLineHeight(value: Double?) = viewModelScope.launch { prefsRepo.setLineHeight(value) }

    fun setPageMargins(value: Double?) = viewModelScope.launch { prefsRepo.setPageMargins(value) }

    fun setBrightness(value: Float?) = viewModelScope.launch { prefsRepo.setBrightness(value) }

    override fun onCleared() {
        (_state.value as? UiState.Ready)?.publication?.close()
    }

    companion object {
        fun factory(bookUrl: AbsoluteUrl): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                ReaderViewModel(
                    bookUrl = bookUrl,
                    assetRetriever = container.assetRetriever,
                    publicationOpener = container.publicationOpener,
                    progressDao = container.database.readingProgressDao(),
                    library = container.libraryRepository,
                    prefsRepo = container.readerPreferences,
                )
            }
        }
    }
}
