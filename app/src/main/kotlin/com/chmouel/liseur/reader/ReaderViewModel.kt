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
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.progress.BookPositions
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.ReadingSpeedEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.readium.r2.shared.publication.indexOfFirstWithHref
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

    private val _progress = MutableStateFlow<ReaderProgress?>(null)

    /** Where the reader is in the book, once positions are computed. */
    val progress: StateFlow<ReaderProgress?> = _progress.asStateFlow()

    private val _jumpBack = MutableStateFlow<JumpBack?>(null)

    /** Offer to return to where reading was before the last jump. */
    val jumpBack: StateFlow<JumpBack?> = _jumpBack.asStateFlow()

    private var publication: Publication? = null
    private var bookPositions: BookPositions? = null
    private var speed = ReadingSpeedEstimator()
    private var jumpBackTimer: Job? = null

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
                ?.also { speed = ReadingSpeedEstimator(it.readingSpeed) }
                ?.let { Locator.fromJSON(JSONObject(it.locatorJson)) }
            lastLocator = initialLocator
            library.markOpened(bookUrl.toString())
            this@ReaderViewModel.publication = publication
            _state.value = UiState.Ready(
                publication = publication,
                navigatorFactory = EpubNavigatorFactory(publication),
                initialLocator = initialLocator,
            )
            // Computing positions parses the whole book, so it runs
            // after the reader is on screen.
            bookPositions = BookPositions.of(publication)
            lastLocator?.let { _progress.value = progressAt(it) }
        }
    }

    fun onLocatorChanged(locator: Locator) {
        lastLocator = locator
        val positions = bookPositions
        val totalProgression = locator.locations.totalProgression
        if (positions != null && positions.isUsable && totalProgression != null) {
            speed.record(
                position = totalProgression * positions.totalPositions,
                atMillis = System.currentTimeMillis(),
            )
        }
        _progress.value = progressAt(locator)
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgress(
                    bookUrl = bookUrl.toString(),
                    locatorJson = locator.toJSON().toString(),
                    totalProgression = locator.locations.totalProgression,
                    readingSpeed = speed.speed,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Called before jumping, so the reader can come back in one tap. */
    fun onJump() {
        val from = lastLocator ?: return
        val progress = _progress.value
        // The jump itself is not reading, and neither is finding your
        // way back, so it must not affect the speed estimate.
        speed.forgetLastPosition()
        _jumpBack.value = JumpBack(locator = from, position = progress?.position)
        jumpBackTimer?.cancel()
        jumpBackTimer = viewModelScope.launch {
            delay(JUMP_BACK_TIMEOUT_MS)
            _jumpBack.value = null
        }
    }

    fun dismissJumpBack() {
        jumpBackTimer?.cancel()
        _jumpBack.value = null
    }

    /** The locator for a position on the scrubber, numbered from 1. */
    fun locatorAtPosition(position: Int): Locator? = bookPositions?.locatorAt(position)

    /** The position matching a whole-book progression between 0 and 1. */
    fun positionAtProgression(progression: Float): Int =
        bookPositions?.positionAtProgression(progression) ?: 1

    /** The chapter title shown while dragging the scrubber. */
    fun chapterTitleAtPosition(position: Int): String? =
        bookPositions?.chapterAt(position)?.title

    /** Chapter starts as whole-book progressions, for the scrubber ticks. */
    fun chapterTicks(): List<Float> {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return emptyList()
        val total = positions.totalPositions.toFloat()
        return positions.chapters
            .map { (it.firstPosition - 1) / total }
            .filter { it > 0f }
    }

    private fun progressAt(locator: Locator): ReaderProgress? {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return null
        val publication = publication ?: return null
        val totalProgression = (locator.locations.totalProgression ?: 0.0).toFloat()
        val position = locator.locations.position
            ?: positions.positionAtProgression(totalProgression)
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href)
            ?.let(positions::chapterOfResource)
            ?: positions.chapterAt(position)
        val chapterEnd = chapter?.lastPosition ?: positions.totalPositions
        return ReaderProgress(
            position = position.coerceIn(1, positions.totalPositions),
            totalPositions = positions.totalPositions,
            totalProgression = totalProgression,
            chapterTitle = chapter?.title,
            minutesLeftInChapter = speed.minutesFor((chapterEnd - position).toDouble()),
            minutesLeftInBook = speed.minutesFor((positions.totalPositions - position).toDouble()),
            isSpeedMeasured = speed.isMeasured,
        )
    }

    fun setFont(font: ReaderFont) = viewModelScope.launch { prefsRepo.setFont(font) }

    fun setFontSize(size: Double) = viewModelScope.launch { prefsRepo.setFontSize(size) }

    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { prefsRepo.setTheme(theme) }

    fun setLineHeight(value: Double?) = viewModelScope.launch { prefsRepo.setLineHeight(value) }

    fun setPageMargins(value: Double?) = viewModelScope.launch { prefsRepo.setPageMargins(value) }

    fun setBrightness(value: Float?) = viewModelScope.launch { prefsRepo.setBrightness(value) }

    fun setPageTurnAnimation(enabled: Boolean) =
        viewModelScope.launch { prefsRepo.setPageTurnAnimation(enabled) }

    fun cycleFooterMode() = viewModelScope.launch {
        prefsRepo.setFooterMode(prefs.value.footerMode.next())
    }

    fun setFooterMode(mode: FooterMode) = viewModelScope.launch {
        prefsRepo.setFooterMode(mode)
    }

    override fun onCleared() {
        (_state.value as? UiState.Ready)?.publication?.close()
    }

    /** A place to return to after a jump, and the page it was on. */
    data class JumpBack(val locator: Locator, val position: Int?)

    companion object {
        private const val JUMP_BACK_TIMEOUT_MS = 30_000L

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
