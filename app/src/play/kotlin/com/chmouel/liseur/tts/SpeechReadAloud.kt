package com.chmouel.liseur.tts

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ListeningCheckpoints
import com.chmouel.liseur.readaloud.ReadAloudBookNotice
import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.OpenBookHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Reading aloud, one book at a time, with the voice of the chosen
 * [ReadAloudProvider]: Gemini on the reader's key, or their own Kokoro
 * server.
 *
 * Holds the session in progress and keeps [ReadAloudService] running for
 * it, so playback carries on with the reader gone and the screen off.
 * Changing the provider or a key ends the session; a new server or voice
 * applies from the next one. Main thread only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SpeechReadAloud(
    private val application: Application,
    private val geminiKeys: ApiKeyStore,
    private val kokoroKeys: ApiKeyStore,
    private val settings: AppSettingsRepository,
    private val checkpoints: ListeningCheckpoints,
    private val gemini: GeminiTtsClient = GeminiTtsClient(),
    private val kokoro: KokoroTtsClient = KokoroTtsClient(),
) : ReadAloudFeature {

    private val scope = MainScope()
    private val mutableCurrent = MutableStateFlow<ReadAloudSession?>(null)

    /** The session in progress, for the service. */
    val current: StateFlow<ReadAloudSession?> = mutableCurrent.asStateFlow()

    private var starting: Job? = null

    override val isAvailable = true

    val provider: Flow<ReadAloudProvider> =
        settings.settings.map { ReadAloudProvider.of(it.readAloudProvider) }.distinctUntilChanged()

    /** Gemini needs a key; Kokoro a server and a voice, its key being optional. */
    override val configured: StateFlow<Boolean> =
        combine(settings.settings, geminiKeys.configured) { s, geminiKey ->
            when (ReadAloudProvider.of(s.readAloudProvider)) {
                ReadAloudProvider.GEMINI -> geminiKey
                ReadAloudProvider.KOKORO ->
                    KokoroTts.baseUrl(s.kokoroUrl.orEmpty()) != null && !s.kokoroVoice.isNullOrBlank()
            }
        }.stateIn(scope, SharingStarted.Eagerly, false)

    val geminiKeyConfigured: StateFlow<Boolean> = geminiKeys.configured
    val kokoroKeyConfigured: StateFlow<Boolean> = kokoroKeys.configured

    /** The provider the last session read with, for naming it in a notice. */
    var noticeProvider: ReadAloudProvider = ReadAloudProvider.Default
        private set

    override val session: StateFlow<ReadAloudUi?> =
        mutableCurrent.flatMapLatest { it?.ui ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableNotices = MutableSharedFlow<ReadAloudBookNotice>(extraBufferCapacity = 8)
    override val notices: SharedFlow<ReadAloudBookNotice> = mutableNotices.asSharedFlow()

    override fun start(handle: OpenBookHandle, selection: Locator, reader: Intent) {
        stop()
        starting = scope.launch {
            // Taken here rather than before launching: a start cancelled
            // before it runs never reaches the finally that gives it back.
            if (!handle.acquire()) return@launch
            var held = true
            try {
                val voice = voiceFor(settings.settings.first(), handle.bookId) ?: return@launch
                val session = ReadAloudSession(
                    application = application,
                    handle = handle,
                    reader = reader,
                    voice = voice,
                    checkpoints = checkpoints,
                    onNotice = { mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, it)) },
                    onEnded = { ended -> if (mutableCurrent.value === ended) mutableCurrent.value = null },
                )
                held = false
                mutableCurrent.value = session
                // The reads above suspend, so the reader may have left the
                // foreground and Android may refuse the start.
                try {
                    startService()
                } catch (e: RuntimeException) {
                    session.stop()
                    mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, ReadAloudNotice.Output))
                    return@launch
                }
                session.start(selection)
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (held) handle.release()
            }
        }
    }

    override fun pause() {
        mutableCurrent.value?.pause()
    }

    override fun resume() {
        val session = mutableCurrent.value ?: return
        // The service may have been destroyed under a paused session.
        startService()
        session.resume()
    }

    // Called from the reader, so in the foreground; the service turns
    // foreground itself once the voice plays.
    private fun startService() {
        application.startService(Intent(application, ReadAloudService::class.java))
    }

    override fun stop() {
        starting?.cancel()
        mutableCurrent.value?.stop()
    }

    override fun skipForward() {
        mutableCurrent.value?.skipForward()
    }

    override fun skipBackward() {
        mutableCurrent.value?.skipBackward()
    }

    /** The voice the next session reads with, or null, with the reason told, when there is none. */
    private suspend fun voiceFor(s: AppSettings, bookId: String): SessionVoice? {
        val provider = ReadAloudProvider.of(s.readAloudProvider)
        noticeProvider = provider
        val notSetUp = ReadAloudBookNotice(bookId, ReadAloudNotice.NotSetUp)
        return when (provider) {
            ReadAloudProvider.GEMINI -> {
                val key = geminiKeys.get() ?: return null.also { mutableNotices.tryEmit(notSetUp) }
                val voice = GeminiVoice.of(s.readAloudVoice).id
                SessionVoice(voice, provider.maxConcurrent) { text -> gemini.synthesize(key, text, voice) }
            }
            ReadAloudProvider.KOKORO -> {
                val base = KokoroTts.baseUrl(s.kokoroUrl.orEmpty())
                val voice = s.kokoroVoice?.takeIf { it.isNotBlank() }
                if (base == null || voice == null) return null.also { mutableNotices.tryEmit(notSetUp) }
                val key = kokoroKeys.get()
                val model = KokoroTts.model(s.kokoroModel)
                SessionVoice(voice, provider.maxConcurrent) { text -> kokoro.synthesize(base, key, text, voice, model) }
            }
        }
    }

    /** Switches provider, ending a session read with the other one. */
    suspend fun setProvider(provider: ReadAloudProvider) {
        if (ReadAloudProvider.of(settings.settings.first().readAloudProvider) != provider) stop()
        settings.setReadAloudProvider(provider.id)
    }

    /** Saves the Gemini [key], ending the session read with the old one. */
    suspend fun setGeminiKey(key: String) {
        stop()
        geminiKeys.set(key)
    }

    suspend fun clearGeminiKey() {
        stop()
        geminiKeys.clear()
    }

    suspend fun setGeminiVoice(voice: GeminiVoice) {
        settings.setReadAloudVoice(voice.id)
    }

    val geminiVoice: Flow<GeminiVoice> =
        settings.settings.map { GeminiVoice.of(it.readAloudVoice) }.distinctUntilChanged()

    val kokoroUrl: Flow<String> = settings.settings.map { it.kokoroUrl.orEmpty() }.distinctUntilChanged()

    val kokoroVoice: Flow<String?> = settings.settings.map { it.kokoroVoice }.distinctUntilChanged()

    suspend fun setKokoroUrl(url: String) = settings.setKokoroUrl(url)

    suspend fun setKokoroVoice(voice: String) = settings.setKokoroVoice(voice)

    val kokoroModel: Flow<String> = settings.settings.map { it.kokoroModel.orEmpty() }.distinctUntilChanged()

    suspend fun setKokoroModel(model: String) = settings.setKokoroModel(model)

    /** Saves the Kokoro server's [key], ending the session read with the old one. */
    suspend fun setKokoroKey(key: String) {
        stop()
        kokoroKeys.set(key)
    }

    suspend fun clearKokoroKey() {
        stop()
        kokoroKeys.clear()
    }

    /** The voices the Kokoro server at [url] offers, asked with the saved key. */
    suspend fun kokoroVoices(url: String): Result<List<String>> {
        val base = KokoroTts.baseUrl(url) ?: return Result.failure(IllegalArgumentException("Not a server address"))
        return try {
            Result.success(kokoro.voices(base, kokoroKeys.get()))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun SettingsEntry(onClick: () -> Unit) = ReadAloudSettingsEntry(this, onClick)

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) = ReadAloudSettingsScreen(this, onBack)

    @Composable
    override fun Player(bookId: String, theme: ReaderTheme, modifier: Modifier) =
        ReadAloudPlayer(this, bookId, theme, modifier)

    @Composable
    override fun SelectionButton(onClick: () -> Unit) = ReadAloudSelectionButton(onClick)
}
